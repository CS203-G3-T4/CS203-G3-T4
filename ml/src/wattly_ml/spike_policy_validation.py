"""Offline reproduction of SAME_SLOT_K3_RELEVANT_WARNING_V1.

The grading core is preserved from the frozen 1 November evaluator. This module
reads supplied JSON only: no database, environment, model fitting or live writes.
Source authenticity and genuine human labels cannot be established by hashes.
"""
from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from statistics import median
import argparse
import hashlib
import json
import math

STEP = timedelta(minutes=30)
DAY = timedelta(days=1)
POLICY_ID = "SAME_SLOT_K3_RELEVANT_WARNING_V1"
MAX_INPUT_BYTES = 256 * 1024 * 1024

def dt(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
    if not isinstance(result, datetime) or result.tzinfo is None or result.utcoffset() is None:
        raise ValueError("Timezone-aware timestamps required")
    return result.astimezone(timezone.utc)

def floor(t):
    return datetime.fromtimestamp(int(t.timestamp()) // 1800 * 1800, timezone.utc)

def reference(rows, target, at):
    latest = {}
    for r in rows:
        t, pub, available = (dt(r['period_start']), dt(r['published_at']), dt(r['available_at']))
        if t >= at or pub > at or available > at:
            continue
        order = (pub, available, r['id'])
        if t not in latest or order > latest[t][0]:
            latest[t] = (order, Decimal(str(r['usep'])))
    values = [latest[t][1] for d in range(1, 29) if (t := (target - DAY * d)) < at and t in latest]
    if len(values) < 14:
        return {'available': False, 'samples': len(values), 'threshold': None}
    typical = median(values)
    spread = max(Decimal(1), Decimal('1.4826') * median((abs(x - typical) for x in values)))
    return {'available': True, 'samples': len(values), 'typical': typical, 'spread': spread, 'threshold': typical + 3 * spread}

def candidate(row):
    if not row['prediction'].get('aiWarning'):
        return (False, 'NO_SPIKE_AVOIDANCE_PLAN')
    if Decimal(str(row['prediction']['AI'])) < Decimal('.10'):
        return (False, 'ESTIMATE_BELOW_010')
    if dt(row['ai_start']) <= dt(row['issued_at']) or dt(row['usual_start']) <= dt(row['issued_at']):
        return (False, 'RUN_NOT_FUTURE')
    interval = row['prediction'].get('aiRange') or {}
    if not interval.get('available') or interval.get('completedDays', 0) < 20:
        return (False, 'CALIBRATION_UNAVAILABLE')
    if interval.get('lowerSgd') is None or Decimal(str(interval['lowerSgd'])) <= 0:
        return (False, 'LOWER_BOUND_NOT_POSITIVE')
    return (True, 'ELIGIBLE')

def confusion(rows):
    c = Counter()
    for r in rows:
        actual = r['actual'] > r['threshold']
        pred = r['prediction'] > r['threshold']
        c['tp' if actual and pred else 'fp' if pred else 'fn' if actual else 'tn'] += 1
    tp, fp, fn, tn = [c[k] for k in ['tp', 'fp', 'fn', 'tn']]
    return {**{k: c[k] for k in ['tp', 'fp', 'fn', 'tn']}, 'pairs': len(rows), 'distinctTargets': len({r['target_period'] for r in rows}), 'precision': tp / (tp + fp) if tp + fp else None, 'recall': tp / (tp + fn) if tp + fn else None, 'interpretation': 'Arithmetic on statistical proxy labels; no independent human labels or population-confidence claim'}

def groups(periods):
    out = []
    for t in sorted(set(periods)):
        if not out or t != out[-1][-1] + STEP:
            out.append([])
        out[-1].append(t)
    return out

def episodes(plans):
    grouped = {}
    for row in plans:
        grouped.setdefault(row['household_id'], []).append(row)
    result = []
    for household, rows in grouped.items():
        for row in sorted(rows, key=lambda r: (dt(r['usual_start']), r['id'])):
            begin = dt(row['usual_start'])
            end = begin + timedelta(minutes=row['run_minutes'])
            if result and result[-1]['household'] == household and (begin <= result[-1]['end']):
                result[-1]['plans'].append(row)
                result[-1]['end'] = max(end, result[-1]['end'])
            else:
                result.append({'household': household, 'start': begin, 'end': end, 'plans': [row]})
    return result

def grade_episodes(plans, lookup):
    scored = []
    pending = 0
    false_periods = set()
    affected = set()
    harmful = Decimal(0)
    net = Decimal(0)
    for event in episodes(plans):
        truths = []
        savings = []
        valid = True
        periods = set()
        for row in event['plans']:
            if row.get('outcome') is None:
                valid = False
                break
            begin = dt(row['usual_start'])
            end = begin + timedelta(minutes=row['run_minutes'])
            t = floor(begin)
            while t < end:
                point = lookup.get((row['run_id'], t))
                if point is None or point['actual'] is None or point['threshold'] is None:
                    valid = False
                    break
                truths.append(Decimal(str(point['actual'])) > Decimal(str(point['threshold'])))
                periods.add(t)
                t += STEP
            if not valid:
                break
            savings.append(Decimal(str(row['outcome']['AI']['savingSgd'])))
        if not valid:
            pending += 1
            continue
        spike = any(truths)
        saving = sum(savings, Decimal(0))
        affected.update(periods)
        if not spike:
            false_periods.update(((event['household'], t) for t in periods))
        for value in savings:
            if value < 0:
                harmful -= value
        net += saving
        scored.append({'actualSpike': spike, 'savingPositive': saving > 0, 'harmful': any((v < 0 for v in savings))})
    warnings = len(scored)
    true = sum((e['actualSpike'] for e in scored))
    false = warnings - true
    return {'warningEpisodes': len(episodes(plans)), 'completedEpisodes': warnings, 'pendingEpisodes': pending, 'trueStatisticalSpikeEpisodes': true, 'falseStatisticalSpikeEpisodes': false, 'distinctAffectedMarketPeriods': len(affected), 'spikeWarningPrecision': true / warnings if warnings else None, 'falseShareOfWarnings': false / warnings if warnings else None, 'jointUsefulEpisodes': sum((e['actualSpike'] and e['savingPositive'] for e in scored)), 'nonSavingEpisodes': sum((not e['savingPositive'] for e in scored)), 'episodesWithHarmfulMoves': sum((e['harmful'] for e in scored)), 'unweightedPlanSavingsSgd': str(net), 'unweightedHarmfulPlanLossSgd': str(harmful), 'falseAffectedHouseholdRunHalfHours': len(false_periods), 'counting': 'Counterfactual initial household episodes from frozen first plans; not delivered notifications; false duration counts household/affected-run periods, not time a real notice remained visible'}

def _analyse(payload, protocol, mode):
    window = protocol['preliminary'] if mode == 'preliminary' else protocol['prospective']
    begin, end = (dt(window['issueStart']), dt(window['issueEndExclusive']))
    cutoff = dt(payload['snapshotAt'])
    raw = []
    lookup = {}
    exclusions = Counter()
    for r in payload['pairs']:
        origin, target = (dt(r['as_of']), dt(r['target_period']))
        assert target == floor(origin) + STEP * r['horizon'], 'Stored target alignment mismatch'
        assert r['model_version'] == protocol['modelVersion'] and dt(r['captured_at']) >= origin
        lookup[r['run_id'], target] = r
        if target + STEP > cutoff or r['actual'] is None:
            exclusions['PENDING_TRUTH'] += 1
            continue
        if mode == 'preliminary' and target >= end:
            exclusions['TARGET_OUTSIDE_PRELIMINARY_WINDOW'] += 1
            continue
        if r['threshold'] is None:
            exclusions['MISSING_REFERENCE'] += 1
            continue
        if r['evaluated_at'] is None or dt(r['evaluated_at']) > cutoff:
            exclusions['FUTURE_EVALUATION'] += 1
            continue
        raw.append(r)
    methods = {}
    for method, key in [('AI', 'ai'), ('B3', 'b3')]:
        allrows = [dict(r, prediction=r[key]) for r in raw]
        leads = {}
        for h in [1, 6, 12, 24]:
            one = {}
            for r in sorted((r for r in allrows if r['horizon'] == h), key=lambda r: (dt(r['captured_at']), dt(r['as_of']), r['run_id'])):
                one.setdefault(dt(r['target_period']), r)
            chosen = list(one.values())
            metric = confusion(chosen)
            bytarget = {dt(r['target_period']): r for r in chosen}
            events = groups((t for t, r in bytarget.items() if r['actual'] > r['threshold']))
            complete = [event for event in events if event[0] - STEP in bytarget and event[-1] + STEP in bytarget]
            warned = sum((any((bytarget[t]['prediction'] > bytarget[t]['threshold'] and dt(bytarget[t]['as_of']) < event[0] for t in event)) for event in complete))
            false = groups((t for t, r in bytarget.items() if r['prediction'] > r['threshold'] and r['actual'] <= r['threshold']))
            leads[str(h)] = {**metric, 'observedPositiveRuns': len(events), 'fullyBracketedEvents': len(complete), 'warnedBeforeOnset': warned, 'missedBeforeOnset': len(complete) - warned, 'falseTargetEpisodes': len(false), 'falseTargetHalfHours': sum(map(len, false)), 'leadLabel': 'Saved horizon index; actual minutes depend on issue time within its half-hour'}
        methods[method] = {'dependentRunTargetPairs': confusion(allrows), 'fixedLeadFirstIssueTargets': leads}
    rows = payload['opportunities']
    selected = []
    gates = Counter()
    for row in rows:
        ok, reason = candidate(row)
        gates[reason] += 1
        if ok:
            selected.append(row)
    control = [r for r in rows if r['prediction'].get('aiWarning')]
    actuals = {}
    for r in sorted(payload['history'], key=lambda x: (dt(x['available_at']), dt(x['published_at']), x['id'])):
        t = dt(r['period_start'])
        if begin <= t < end and t + STEP <= cutoff and (r['price_status'] == 'PROVISIONAL') and (r['source'] == 'NEMS_SN_SG'):
            actuals.setdefault(t, r)
    classifications = Counter()
    parity = 0
    from wattly_ml.spikes import reference as implementation_reference
    for t, r in actuals.items():
        asof = dt(r['available_at'])
        ref = reference(payload['history'], t, asof)
        if not ref['available']:
            classifications['UNAVAILABLE'] += 1
            continue
        history = {}
        for old in sorted(payload['history'], key=lambda x: (dt(x['published_at']), dt(x['available_at']), x['id'])):
            if dt(old['period_start']) < asof and dt(old['published_at']) <= asof and (dt(old['available_at']) <= asof):
                history[dt(old['period_start'])] = {'usep': float(old['usep'])}
        check = implementation_reference(history, t, asof)
        assert check['sampleCount'] == ref['samples'] and abs(check['threshold'] - float(ref['threshold'])) < 1e-08
        parity += 1
        value = Decimal(str(r['usep']))
        state = 'SPIKE' if value > ref['threshold'] else 'EXPENSIVE' if value - ref['typical'] > ref['spread'] else 'CHEAP' if value - ref['typical'] < -ref['spread'] else 'NORMAL'
        classifications[state] += 1
    candidate_grade = grade_episodes(selected, lookup)
    control_grade = grade_episodes(control, lookup)
    events = methods['AI']['fixedLeadFirstIssueTargets']['6']['fullyBracketedEvents']
    supported = candidate_grade['completedEpisodes'] >= 20 and candidate_grade['distinctAffectedMarketPeriods'] >= 20 and (events >= 20)
    result = {'schemaVersion': 1, 'policyId': protocol['policyId'], 'mode': mode, 'snapshotAt': payload['snapshotAt'], 'window': window, 'readOnly': payload['readOnly'] == 'on', 'freshIndependentConfirmation': False if mode == 'preliminary' else True, 'freshnessLimit': protocol['preliminary']['limitation'] if mode == 'preliminary' else 'Reserved before outcomes; human-meaningful truth still requires independent labels.', 'priceForecasts': methods, 'excludedPairs': dict(exclusions), 'storedPlans': len(rows), 'candidateGateCounts': dict(gates), 'controlWarningPlanCount': len(control), 'candidateWarningPlanCount': len(selected), 'controlEpisodes': control_grade, 'candidateEpisodes': candidate_grade, 'observedRule': {'assessedTargets': len(actuals), 'classifications': dict(classifications), 'independentDecimalParityChecks': parity, 'genuinePrecision': None, 'genuineRecall': None}, 'supportAdequate': supported, 'twoFalseInTenTarget': 'UNDETERMINED' if not supported else 'OBSERVED_MET' if candidate_grade['falseShareOfWarnings'] <= 0.2 else 'OBSERVED_NOT_MET', 'teamApproved': False, 'productionEligible': False, 'automaticPromotion': False, 'liveBehaviorChanged': False, 'humanLabelsAvailable': False, 'conclusion': 'This batch cannot establish genuine-spike quality or guaranteed personal-warning reliability.'}
    return result


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _number(value, field, optional=False):
    if value is None and optional:
        return
    _require(type(value) in (int, float) and math.isfinite(value), field + " must be a finite JSON number")


def _validate(snapshot, protocol, mode, as_of):
    _require(isinstance(snapshot, dict) and isinstance(protocol, dict), "JSON objects required")
    _require(mode in ("preliminary", "prospective"), "Unknown scoring mode")
    _require(protocol.get("schemaVersion") == 1 and protocol.get("policyId") == POLICY_ID, "Unsupported fixed policy")
    _require(isinstance(protocol.get("modelVersion"), str) and protocol["modelVersion"], "Pinned modelVersion required")
    _require(isinstance(protocol.get("trialId"), str) and protocol["trialId"], "Pinned trialId required")
    expected = {"minimumEstimatedSavingSgd": .10, "requireSavedIssueTimeRange": True,
                "minimumEarlierCompletedDays": 20, "minimumRangeLowerSgdExclusive": 0,
                "sameFrozenFirstPlans": True, "reranking": False, "warningFlag": "aiWarning"}
    _require(protocol.get("candidate") == expected, "Candidate differs from the fixed warning policy")
    expected_target = {"maximumFalseWarningEpisodeFraction": .2, "minimumCompletedWarningEpisodes": 20,
                       "minimumDistinctAffectedMarketPeriods": 20, "minimumFullyBracketedStatisticalEvents": 20}
    _require(all(protocol.get("primaryTarget", {}).get(k) == v for k, v in expected_target.items()), "Fixed support/target changed")
    fixed = dt(protocol["fixedAt"])
    forward = protocol["prospective"]
    _require(dt(forward["issueEndExclusive"]) - dt(forward["issueStart"]) == 7 * DAY, "Exactly seven prospective days required")
    _require(dt(forward["scoreAfter"]) == dt(forward["issueEndExclusive"]) + timedelta(hours=13), "Outcome maturity window changed")
    _require(fixed <= dt(forward["issueStart"]) and forward.get("scoreCount") == 1
             and forward.get("automaticExtension") is False, "Prospective protocol must be fixed before its single cohort")
    window = protocol[mode]
    begin, end, snapshot_at = dt(window["issueStart"]), dt(window["issueEndExclusive"]), dt(snapshot["snapshotAt"])
    _require(begin == floor(begin) and end == floor(end) and begin < end, "Ordered half-hour cohort boundaries required")
    _require(snapshot_at >= fixed and snapshot_at <= dt(as_of), "Snapshot cannot postdate the declared scoring time")
    if mode == "prospective":
        _require(snapshot_at >= dt(forward["scoreAfter"]), "Prospective outcomes have not matured")
    else:
        _require(end <= fixed and isinstance(window.get("limitation"), str) and window["limitation"], "Preliminary exposure limitations required")
    _require(snapshot.get("readOnly") == "on", "Require the source export's read-only declaration")
    for key in ("pairs", "opportunities", "history", "trials"):
        _require(isinstance(snapshot.get(key), list), "Snapshot requires list: " + key)
    matching = [t for t in snapshot["trials"] if t.get("id") == protocol["trialId"]]
    _require(len(matching) == 1 and matching[0].get("model_version") == protocol["modelVersion"], "Trial/model identity mismatch")
    pair_keys, runs = set(), {}
    for row in snapshot["pairs"]:
        _require(isinstance(row, dict), "Price rows must be objects")
        origin, target, captured = dt(row["as_of"]), dt(row["target_period"]), dt(row["captured_at"])
        _require(type(row["horizon"]) is int and 1 <= row["horizon"] <= 24, "Invalid horizon")
        _require(isinstance(row["run_id"], str) and row["run_id"], "Run identifier required")
        _require(begin <= origin < end and origin <= captured <= snapshot_at, "Forecast outside cohort or known receipt time")
        _require(target == floor(origin) + STEP * row["horizon"], "Stored target alignment mismatch")
        _require(row["model_version"] == protocol["modelVersion"], "Changed model in cohort")
        identity = (row["run_id"], row["horizon"])
        _require(identity not in pair_keys, "Duplicate run/horizon")
        pair_keys.add(identity)
        run_identity = (origin, captured, row["model_version"])
        _require(row["run_id"] not in runs or runs[row["run_id"]] == run_identity, "Inconsistent run metadata")
        runs[row["run_id"]] = run_identity
        for name in ("ai", "b3", "threshold", "actual"):
            _number(row.get(name), name, name in ("threshold", "actual"))
        if row["actual"] is not None:
            _require(row.get("evaluated_at") is not None and target + STEP <= snapshot_at
                     and dt(row["evaluated_at"]) <= snapshot_at, "Future or unfinished truth supplied")
    histories, revision_ids = {}, set()
    for row in snapshot["history"]:
        _require(isinstance(row, dict) and type(row["id"]) is int and row["id"] not in revision_ids, "Duplicate/invalid history revision")
        revision_ids.add(row["id"])
        target, published, available = dt(row["period_start"]), dt(row["published_at"]), dt(row["available_at"])
        _require(target == floor(target) and max(target, published, available) <= snapshot_at, "Unavailable history revision supplied")
        _require(row["price_status"] in ("FINAL", "PROVISIONAL") and isinstance(row["source"], str), "History provenance required")
        _number(row["usep"], "history usep")
        key = target, published, available
        _require(key not in histories or histories[key] == row["usep"], "Conflicting equally available prices")
        histories[key] = row["usep"]
    plans, first_plans = set(), set()
    for row in snapshot["opportunities"]:
        _require(isinstance(row, dict) and type(row["id"]) is int and row["id"] not in plans, "Duplicate/invalid plan")
        plans.add(row["id"])
        _require(type(row["household_id"]) is int and type(row["appliance_id"]) is int, "Plan identifiers required")
        origin = dt(row["issued_at"])
        _require(row["trial_id"] == protocol["trialId"] and row["run_id"] in runs
                 and origin == runs[row["run_id"]][0], "Plan differs from its frozen trial/run")
        usual, chosen, baseline = [dt(row[k]) for k in ("usual_start", "ai_start", "b3_start")]
        key = row["household_id"], row["appliance_id"], usual
        _require(key not in first_plans, "Repeated first-plan opportunity")
        first_plans.add(key)
        _require(type(row["run_minutes"]) is int and 1 <= row["run_minutes"] <= 1440, "Invalid run duration")
        prediction = row["prediction"]
        _require(isinstance(prediction, dict) and type(prediction.get("aiWarning")) is bool, "Saved AI warning flag required")
        _number(prediction.get("AI"), "AI saving")
        interval = prediction.get("aiRange")
        _require(isinstance(interval, dict) and type(interval.get("available")) is bool, "Saved range declaration required")
        _require(type(interval.get("completedDays")) is int and interval["completedDays"] >= 0, "Completed calibration-day count required")
        if interval["available"]:
            _number(interval.get("lowerSgd"), "range lower bound")
            _number(interval.get("upperSgd"), "range upper bound")
            _require(interval["lowerSgd"] <= interval["upperSgd"], "Reversed savings range")
        if row["outcome"] is not None:
            _require(row.get("evaluated_at") is not None and dt(row["evaluated_at"]) <= snapshot_at
                     and max(usual, chosen, baseline) + timedelta(minutes=row["run_minutes"]) <= snapshot_at,
                     "Future or unfinished plan outcome")
            outcome = row["outcome"]["AI"]
            for key in ("savingSgd", "usualCostSgd", "chosenCostSgd"):
                _number(outcome[key], "outcome " + key)
            error = Decimal(str(outcome["usualCostSgd"])) - Decimal(str(outcome["chosenCostSgd"])) - Decimal(str(outcome["savingSgd"]))
            _require(abs(error) <= Decimal("0.00000001"), "Inconsistent saved cost arithmetic")


def evaluate(snapshot, protocol, mode, as_of):
    """Grade supplied first-plan evidence without consulting a clock or runtime.

    Issue-time ranges and source-export declarations remain trusted input, not
    newly calibrated confidence or independently authenticated provenance.
    """
    try:
        _validate(snapshot, protocol, mode, as_of)
        result = _analyse(snapshot, protocol, mode)
    except (KeyError, TypeError, OverflowError) as error:
        raise ValueError("Malformed required snapshot/protocol field") from error
    result.update(inputContractVersion=1, synthetic=bool(snapshot.get("synthetic", False)),
                  inputAuthenticityVerified=False, runtimeGuardsExecuted=False,
                  gradingAsOf=dt(as_of).isoformat())
    return result


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def _load(path):
    path = Path(path)
    _require(path.stat().st_size <= MAX_INPUT_BYTES, "Input exceeds 256 MiB limit")
    with path.open(encoding="utf-8") as stream:
        return json.load(stream, object_pairs_hook=_unique_object,
                         parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Non-finite JSON value")))


def _digest(path):
    _require(path.stat().st_size <= MAX_INPUT_BYTES, "Input exceeds 256 MiB limit")
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            result.update(block)
    return result.hexdigest()


def evaluate_files(snapshot_path, protocol_path, output_path, mode, as_of):
    """Write one new report. Never overwrite an input or an existing result."""
    output = Path(output_path)
    if output.exists():
        raise FileExistsError("Output already exists; preserve the original report")
    snapshot_path, protocol_path = Path(snapshot_path), Path(protocol_path)
    hashes = {"snapshotSha256": _digest(snapshot_path),
              "protocolSha256": _digest(protocol_path)}
    result = evaluate(_load(snapshot_path), _load(protocol_path), mode, as_of)
    _require(hashes == {"snapshotSha256": _digest(snapshot_path),
                        "protocolSha256": _digest(protocol_path)}, "Input changed during grading")
    result.update(hashes)
    encoded = json.dumps(result, indent=2, allow_nan=False) + "\n"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        stream.write(encoded)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("preliminary", "prospective"), required=True)
    parser.add_argument("--as-of", required=True, help="Declared aware scoring timestamp; no system clock is used")
    args = parser.parse_args(argv)
    try:
        result = evaluate_files(args.snapshot, args.protocol, args.output, args.mode, args.as_of)
    except (ValueError, OSError, AssertionError) as error:
        parser.exit(2, "Validation refused: " + str(error) + "\n")
    print(json.dumps({key: result[key] for key in ("policyId", "mode", "synthetic", "supportAdequate", "twoFalseInTenTarget", "productionEligible")}))


if __name__ == "__main__":
    main()
