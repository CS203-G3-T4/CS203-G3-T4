"""Immutable acquisition, lossless canonical revisions, explicit as-of selection."""
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import time
from collections import Counter
from datetime import datetime, timezone

from .timebase import DAY, SGT, STEP, floor, instant


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path: Path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False, default=str) + "\n")


def snapshot(source: Path, root: Path, kind: str, source_url: str | None = None) -> Path:
    source, root = source.resolve(strict=True), root.resolve()
    if root == source.parent or source.parent in root.parents:
        raise ValueError("Snapshot destination must be outside the source data directory")
    root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".acquiring-", dir=root))
    try:
        dest = work / ("collector.sqlite3" if kind == "sqlite" else "source.csv")
        if kind == "sqlite":
            deadline = time.monotonic() + 15
            def progress(*_):
                if time.monotonic() > deadline:
                    raise TimeoutError("Collector snapshot exceeded 15 seconds")
            with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True, timeout=2) as src:
                with sqlite3.connect(dest) as dst:
                    src.backup(dst, pages=128, progress=progress, sleep=0.05)
        else:
            # CSVs may still be growing. Reject a changing source and ask for a retry.
            before = source.stat()
            shutil.copyfile(source, dest)
            after = source.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError("CSV changed during snapshot; retry or use SQLite backup")
        checksum = digest(dest)
        rows, errors = read_source(dest, kind)
        manifest = {"schemaVersion": 1, "kind": kind, "source": str(source), "sourceUrl": source_url,
                    "acquiredAt": datetime.now(timezone.utc).isoformat(), "sha256": checksum,
                    "file": dest.name, "priceStatus": "FINAL" if kind == "emc" else "PROVISIONAL",
                    "timeMapping": "EMC_PERIOD" if kind == "emc" else "UNVERIFIED_FLOOR",
                    "quality": quality(rows, errors)}
        write_json(work / "manifest.json", manifest)
        final = root / (kind + "-" + checksum[:20])
        if final.exists():
            if digest(final / dest.name) != checksum:
                raise ValueError("Existing immutable snapshot checksum mismatch")
            shutil.rmtree(work)
            return final
        for file in work.iterdir():
            file.chmod(0o444)
        work.rename(final)
        return final
    except BaseException:
        shutil.rmtree(work, ignore_errors=True)
        raise


def finite(value) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("Non-finite value")
    return number


def validate(row: dict) -> dict:
    row = dict(row)
    period = instant(row["periodStart"])
    if period != floor(period) or row.get("intervalMinutes", 30) != 30:
        raise ValueError("Market periods must be aligned half-hours")
    row["periodStart"] = period.isoformat()
    for field in ("sourceUpdatedAt", "availableAt"):
        if row.get(field) is not None:
            row[field] = instant(row[field]).isoformat()
    if (row.get("sourceUpdatedAt") is None) != (row.get("availableAt") is None):
        raise ValueError("Publication and availability must both exist, or both be unknown")
    if row.get("priceStatus") not in ("FINAL", "PROVISIONAL"):
        raise ValueError("Unknown price status")
    row["usep"] = finite(row["usep"])
    if row.get("demand") is not None:
        row["demand"] = finite(row["demand"])
        if row["demand"] < 0:
            raise ValueError("Negative demand")
    for field in ("source", "revisionId", "timeMapping"):
        if not isinstance(row.get(field), str) or not row[field]:
            raise ValueError("Missing " + field)
    return row


def collector_row(event, offset_minutes=0):
    payload = json.loads(event["payload_json"])
    published = instant(event["source_updated"])
    if published.timestamp() != payload["updated"]:
        raise ValueError("Payload and source timestamp disagree")
    seen = instant(event["first_seen"])
    received = instant(event["received_at"]) if event.get("received_at") else seen
    row = {"periodStart": (floor(published) + STEP * (offset_minutes // 30)).isoformat(),
           "sourceUpdatedAt": published.isoformat(), "availableAt": max(seen, received).isoformat(),
           "usep": payload["usep"], "demand": payload.get("demand"), "vcp": payload.get("vcp"),
           "priceStatus": "PROVISIONAL", "source": "NEMS_SN_SG", "revisionId": event["id"],
           "externalId": event["id"], "timeMapping": "UNVERIFIED_FLOOR" if offset_minutes==0 else f"UNVERIFIED_OFFSET_{offset_minutes}",
           "provenance": event.get("provenance", "collector-sqlite"), "payload": payload,
           "lastSeenAt": event.get("last_seen"), "weather": None}
    if event.get("weather_json"):
        weather = json.loads(event["weather_json"])
        row["weather"] = {"issuedAt": weather["timestamp"], "updatedAt": weather["update_timestamp"],
                          "availableAt": event.get("weather_received_at"),
                          "validStart": weather["valid_period"]["start"],
                          "validEnd": weather["valid_period"]["end"], "payload": weather}
        for field in ("issuedAt","updatedAt","validStart","validEnd","availableAt"):
            if row["weather"][field] is not None:
                row["weather"][field] = instant(row["weather"][field]).isoformat()
        w = row["weather"]
        if instant(w["validStart"])>=instant(w["validEnd"]):
            raise ValueError("Invalid weather validity range")
        w["matchedAtPublication"] = (max(instant(w["issuedAt"]),instant(w["updatedAt"]))<=published
                                    and instant(w["validStart"])<=published<instant(w["validEnd"]))
    return validate(row)


def emc_row(row, index):
    # Exact header aliases documented from EMC's public download, never fuzzy matching.
    date_value = row.get("DATE", row.get("Date", ""))
    parsed = None
    for fmt in ("%d-%b-%Y", "%d/%m/%Y", "%Y-%m-%d", "%d %b %Y"):
        try:
            parsed = datetime.strptime(date_value, fmt).replace(tzinfo=SGT)
            break
        except ValueError:
            continue
    if parsed is None:
        raise ValueError("Unrecognized EMC Date: " + date_value)
    period = int(row.get("PERIOD", row.get("Period", "")))
    if not 1 <= period <= 48:
        raise ValueError("EMC Period must be 1..48")
    price_key = next((k for k in ("USEP ($/MWh)", "USEP", "USEP ($/MWH)") if k in row), None)
    if price_key is None:
        raise ValueError("Expected Date,Period,USEP ($/MWh); received " + repr(list(row)))
    value = finite(row[price_key])
    start = (parsed + STEP * (period - 1)).isoformat()
    return validate({"periodStart": start, "sourceUpdatedAt": None, "availableAt": None,
                     "usep": value, "demand": None, "priceStatus": "FINAL", "source": "EMC",
                     "revisionId": hashlib.sha256(f"{start}|{value}".encode()).hexdigest(),
                     "externalId": str(index), "timeMapping": "EMC_PERIOD",
                     "provenance": "retrospective-final-csv", "weather": None})


def read_source(path: Path, kind: str, offset_minutes=0):
    if offset_minutes % 30 or abs(offset_minutes) > 60:
        raise ValueError("Provisional mapping offset must be -60,-30,0,30,60 minutes")
    rows, errors = [], []
    if kind == "sqlite":
        with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2) as conn:
            conn.row_factory = sqlite3.Row
            source = [dict(r) for r in conn.execute("""
                SELECT e.*, p.received_at, w.received_at AS weather_received_at
                FROM events e LEFT JOIN responses p ON p.id=e.price_response_id
                LEFT JOIN responses w ON w.id=e.weather_response_id ORDER BY e.source_updated,e.id
                """)]
    else:
        with path.open(newline="", encoding="utf-8-sig") as stream:
            source = list(csv.DictReader(stream))
    for index, original in enumerate(source, 1):
        try:
            if kind == "emc":
                row = emc_row(original, index)
            else:
                if kind == "combined":
                    original = {"id": original["record_id"], "source_updated": original["source_updated_sgt"],
                                "first_seen": original["first_collected_sgt"],
                                "last_seen": original["last_seen_sgt"], "payload_json": original["price_payload_json"],
                                "weather_json": original.get("weather_item_json"),
                                "provenance": "combined-csv-reduced-retrieval-provenance"}
                row = collector_row(original, offset_minutes)
            rows.append(row)
        except (ValueError, KeyError, TypeError, OverflowError) as exc:
            errors.append({"row": index, "reason": str(exc)})
    return rows, errors


def quality(rows, errors=()):
    periods = sorted(set(instant(r["periodStart"]) for r in rows))
    keys = Counter((r["periodStart"],r.get("sourceUpdatedAt"),r["source"],r["usep"],
                    r.get("demand"),r.get("vcp"),r["priceStatus"],r["timeMapping"]) for r in rows)
    missing = sum(int((b-a)/STEP)-1 for a,b in zip(periods, periods[1:]))
    stale = sum(bool(r.get("availableAt") and r.get("sourceUpdatedAt") and
                     (instant(r["availableAt"])-instant(r["sourceUpdatedAt"])).total_seconds() > 2400) for r in rows)
    return {"rowCount": len(rows), "periodCount": len(periods), "missingHalfHours": missing,
            "duplicateRows": sum(v-1 for v in keys.values()), "revisionRows": len(keys)-len(periods),
            "staleObservations": stale, "parsingFailures": list(errors),
            "from": periods[0].isoformat() if periods else None, "to": periods[-1].isoformat() if periods else None,
            "mappingVerified": bool(rows) and all(r["timeMapping"] == "EMC_PERIOD" for r in rows),
            "publicationVintages": bool(rows) and all(r.get("availableAt") for r in rows)}


def prepare(snapshots: list[Path], destination: Path, offset_minutes=0):
    if destination.exists():
        raise ValueError("Dataset directory already exists; datasets are immutable")
    rows, errors, manifests = [], [], []
    for directory in snapshots:
        m = json.loads((directory / "manifest.json").read_text())
        if digest(directory / m["file"]) != m["sha256"]:
            raise ValueError("Snapshot checksum mismatch")
        batch, failures = read_source(directory / m["file"], m["kind"], offset_minutes)
        for row in batch:
            row["provenance"] += "@" + m["sha256"]
        rows.extend(batch)
        errors.extend({**f, "snapshot": directory.name} for f in failures)
        manifests.append(m)
    rows.sort(key=lambda r: (r["periodStart"], r.get("sourceUpdatedAt") or "", r.get("availableAt") or "", r["revisionId"]))
    # Retain duplicates/provenance in exports; as-of selection handles semantic duplicates.
    destination.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".prepare-", dir=destination.parent))
    try:
        data = work / "prices.jsonl"
        with data.open("w") as out:
            for row in rows:
                out.write(json.dumps(row, allow_nan=False) + "\n")
        manifest = {"schemaVersion": 1, "sha256": digest(data), "sources": manifests,
                    "provisionalOffsetMinutes": offset_minutes, "quality": quality(rows, errors)}
        write_json(work / "manifest.json", manifest)
        for file in work.iterdir():
            file.chmod(0o444)
        work.rename(destination)
        return manifest
    except BaseException:
        shutil.rmtree(work, ignore_errors=True)
        raise


def load_dataset(directory: Path):
    manifest = json.loads((directory / "manifest.json").read_text())
    if digest(directory / "prices.jsonl") != manifest["sha256"]:
        raise ValueError("Dataset checksum mismatch")
    rows = [validate(json.loads(line)) for line in (directory / "prices.jsonl").read_text().splitlines()]
    return rows, manifest


def as_of(rows, origin, retrospective=False):
    selected = {}
    for row in rows:
        period = instant(row["periodStart"])
        published = instant(row["sourceUpdatedAt"]) if row.get("sourceUpdatedAt") else None
        available = instant(row["availableAt"]) if row.get("availableAt") else None
        if published is None or available is None:
            if not retrospective or row["priceStatus"] != "FINAL":
                continue
            published = available = period + STEP  # Explicit retrospective assumption, never persisted as fact.
        if max(period, published, available) > origin:
            continue
        rank = (published, available)
        prior = selected.get(period)
        if prior and rank == prior[0] and row["usep"] != prior[1]["usep"]:
            raise ValueError("Conflicting revisions with identical publication/availability")
        if prior is None or rank > prior[0]:
            selected[period] = (rank, row)
    return {p: r for p, (_, r) in selected.items()}


def truth(rows, policy="FINAL", cutoff=None, retrospective=False):
    eligible = [r for r in rows if r["priceStatus"] == policy]
    if cutoff is not None:
        eligible = [r for r in eligible if instant(r["periodStart"])+STEP<=cutoff and (
            max(instant(r["availableAt"]),instant(r["sourceUpdatedAt"]))<=cutoff if r.get("availableAt") else retrospective)]
    if policy == "FINAL":
        result = {}
        for r in eligible:
            p = instant(r["periodStart"])
            if p in result and result[p] != r["usep"]:
                raise ValueError("Conflicting final truths; choose one frozen vintage")
            result[p] = r["usep"]
        return result
    return {p: r["usep"] for p, r in as_of(eligible, datetime.max.replace(tzinfo=timezone.utc)).items()}


def weather_for(weather, origin, target):
    valid = [w for w in weather if w.get("availableAt") and
             max(instant(w[k]) for k in ("issuedAt", "updatedAt", "availableAt")) <= origin and
             instant(w["validStart"]) <= target < instant(w["validEnd"])]
    return max(valid, key=lambda w: (instant(w["updatedAt"]),instant(w["availableAt"])), default=None)
