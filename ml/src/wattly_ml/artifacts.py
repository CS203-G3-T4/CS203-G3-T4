"""Only trusted local joblib files. Hashes detect corruption, not hostile authors."""
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import tempfile

from .data import digest, write_json
from .features import FEATURES


def versions():
    return {"python":platform.python_version(), **{p:importlib.metadata.version(p)
            for p in ("numpy","scipy","scikit-learn","joblib")}}


def save_bundle(bundle, root: Path, version: str):
    import joblib
    if not version or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_." for c in version) or version in (".",".."):
        raise ValueError("Unsafe model version")
    root.mkdir(parents=True,exist_ok=True)
    destination = root/version
    if destination.exists():
        raise ValueError("Model versions are immutable")
    work = Path(tempfile.mkdtemp(prefix=".model-",dir=root))
    try:
        joblib.dump(bundle["models"],work/"models.joblib",compress=3)
        manifest = {**bundle["manifest"],"version":version,"dependencies":versions(),
                    "modelSha256":digest(work/"models.joblib")}
        write_json(work/"manifest.json",manifest)
        work.rename(destination)
        return manifest
    except BaseException:
        shutil.rmtree(work,ignore_errors=True)
        raise


def load_bundle(path: Path):
    import joblib
    manifest = json.loads((path/"manifest.json").read_text())
    if (manifest["schemaVersion"]!=1 or manifest["featureOrder"]!=FEATURES or
        manifest["dependencies"]!=versions() or digest(path/"models.joblib")!=manifest["modelSha256"]):
        raise ValueError("Corrupt or incompatible model bundle")
    models = joblib.load(path/"models.joblib")
    if len(models)!=24:
        raise ValueError("Bundle must contain 24 horizon models")
    return {"manifest":manifest,"models":models}


def current_bundle(home: Path):
    pointer = json.loads((home/"current.json").read_text())
    path = (home/"models"/pointer["version"]).resolve()
    if path.parent != (home/"models").resolve() or digest(path/"manifest.json")!=pointer["manifestSha256"]:
        raise ValueError("Invalid approved bundle pointer")
    bundle = load_bundle(path)
    if not bundle["manifest"]["productionEligible"]:
        raise ValueError("Exploratory bundle cannot serve as approved AI")
    return bundle


def promote(home: Path, version: str, *, rollback=False, champion_score=None):
    import fcntl
    home.mkdir(parents=True,exist_ok=True)
    with (home/".promotion.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        return _promote_locked(home,version,rollback=rollback,champion_score=champion_score)


def _promote_locked(home: Path, version: str, *, rollback=False, champion_score=None):
    path = (home/"models"/version).resolve()
    if path.parent != (home/"models").resolve():
        raise ValueError("Model must be in the trusted model directory")
    bundle = load_bundle(path)
    m = bundle["manifest"]
    score = m["validation"]["table"]["AI"]["mae"]
    if not m["productionEligible"] or not m["baselineRanking"] or score is None or m["validation"]["commonPairs"]<48*24*7:
        raise ValueError("Promotion blocked: insufficient validated data/common coverage")
    if not rollback and (m["validation"]["skill"] is None or m["validation"]["skill"]<=0):
        raise ValueError("Candidate does not improve validation-selected baseline")
    previous = json.loads((home/"current.json").read_text()) if (home/"current.json").exists() else None
    if rollback:
        if not previous or previous.get("previousVersion")!=version:
            raise ValueError("Rollback is limited to the previous approved bundle")
    elif previous:
        if not champion_score or champion_score["version"]!=previous["version"] or champion_score["validationEnd"]!=m["validationEnd"]:
            raise ValueError("Evaluate current champion on the identical new validation window before promotion")
        if score>=champion_score["mae"]:
            raise ValueError("Candidate does not improve current champion on paired validation")
    pointer = {"version":version,"manifestSha256":digest(path/"manifest.json"),
               "previousVersion":previous["version"] if previous else None,
               "promotedAt":datetime.now(timezone.utc).isoformat()}
    # One file carries both identity and checksum; failed validation never touches it.
    with tempfile.NamedTemporaryFile(mode="w",dir=home,prefix=".current-",delete=False) as out:
        json.dump(pointer,out); out.flush(); os.fsync(out.fileno()); name=out.name
    os.replace(name,home/"current.json")
    return pointer
