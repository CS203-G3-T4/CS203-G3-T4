"""Offline, no-solar input preflight; never trains, scores, serves or promotes.

Use original issue snapshots, not demand values joined onto historical prices
after the fact. The matched control keeps the current completed-price recipe.
"""
from collections import Counter
import argparse
import json
from pathlib import Path

from .backtest import fresh
from .baselines import baselines
from .data import KnownPrices
from .demand_candidate import _mapped
from .features import features
from .price_only import completed_price_history
from .timebase import STEP, floor, instant, targets


def snapshot_inputs(issue, mapping_evidence):
    """Return candidate/control histories with identical completed price inputs."""
    from .serving.app import ForecastRequest
    origin = instant(issue['asOf'])
    snapshot = issue['inputs']
    # Java schema2 stores the market object directly; solar-era snapshots wrap it.
    raw = snapshot.get('marketInputs', snapshot) if isinstance(snapshot, dict) else None
    if not isinstance(raw, dict) or not raw.get('expectedDemand'):
        raise ValueError('MISSING_ORIGINAL_DEMAND')
    rows = raw.get('priceHistory')
    if not isinstance(rows, list) or not rows:
        raise ValueError('MISSING_ORIGINAL_PRICES')
    if not all(_mapped(row, mapping_evidence) for row in rows):
        raise ValueError('UNVERIFIED_PRICE_MAPPING')
    request = ForecastRequest.model_validate(dict(schemaVersion=2, requestId=issue['id'],
        asOf=origin, intervalMinutes=30, horizonCount=24, mappingVerified=True,
        priceHistory=[{k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')} for r in rows],
        expectedDemand=raw['expectedDemand']))
    if any(r.sourceUpdatedAt > r.availableAt for r in request.priceHistory):
        raise ValueError('PRICE_RECEIPT_BEFORE_PUBLICATION')
    history = KnownPrices({r.periodStart:r.model_dump() for r in request.priceHistory})
    if not fresh(history, origin):
        raise ValueError('STALE_PRICE_HISTORY')
    control = completed_price_history(history, origin)
    candidate = KnownPrices(control, [r.model_dump() for r in request.expectedDemand])
    for target in targets(origin):
        values = features(candidate, origin, target, schema_version=2)
        if values[-1] != 1:
            raise ValueError('MISSING_TARGET_DEMAND')
    if any(value is None for value in baselines(control, origin).values()):
        raise ValueError('INCOMPLETE_BASELINE_HISTORY')
    return candidate, control


def readiness(issues, *, start, end_exclusive, as_of, model_version, mapping_evidence):
    """Input support only. Outcome maturity by elapsed time is not actual receipt."""
    start, end, as_of = map(instant, (start, end_exclusive, as_of))
    if not start < end <= as_of or not model_version:
        raise ValueError('Require a closed, already elapsed input-inspection window and model version')
    selected, excluded, seen = {}, Counter(), set()
    for issue in sorted(issues, key=lambda r:(instant(r['asOf']),r['id'])):
        if issue['id'] in seen:
            raise ValueError('Duplicate saved forecast issue')
        seen.add(issue['id'])
        origin = instant(issue['asOf'])
        if not start <= origin < end:
            excluded['OUTSIDE_INSPECTION_WINDOW'] += 1
            continue
        if (issue.get('mode') != 'LIVE' or issue.get('isDemo', False)
                or issue.get('modelVersion') != model_version
                or issue.get('stale') is not False or issue.get('status') != 'AVAILABLE'):
            excluded['NOT_FRESH_ORIGINAL_MODEL_ISSUE'] += 1
            continue
        try:
            snapshot_inputs(issue, mapping_evidence)
        except (ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
            # Aggregate reasons only; never emit request contents or household data.
            reason = str(exc).split('\n')[0]
            if reason not in {'MISSING_ORIGINAL_DEMAND','MISSING_ORIGINAL_PRICES','UNVERIFIED_PRICE_MAPPING',
                    'PRICE_RECEIPT_BEFORE_PUBLICATION','STALE_PRICE_HISTORY','MISSING_TARGET_DEMAND',
                    'INCOMPLETE_BASELINE_HISTORY','Latest completed price unavailable'}:
                reason = 'INVALID_SNAPSHOT_CONTRACT'
            excluded[reason] += 1
            continue
        slot = floor(origin)
        if slot in selected:
            excluded['REPEATED_HALF_HOUR'] += 1
            continue
        selected[slot] = origin
    origins = sorted(selected.values())
    return dict(kind='PRICE_PLUS_AS_ISSUED_DEMAND_INPUT_PREFLIGHT_V1',
        inspectionStart=start.isoformat(), inspectionEndExclusive=end.isoformat(), asOf=as_of.isoformat(),
        modelVersion=model_version, savedIssues=len(issues), eligibleHalfHourOrigins=len(origins),
        firstEligibleIssue=origins[0].isoformat() if origins else None,
        lastEligibleIssue=origins[-1].isoformat() if origins else None,
        excluded=dict(sorted(excluded.items())),
        elapsedMatureOriginsBeforeReceiptCheck=sum(targets(t)[-1]+STEP <= as_of for t in origins),
        actualReceiptValidatedOrigins=None, readyToFit=False, realCandidateFitted=False,
        reason='INPUT_PREFLIGHT_ONLY_REQUIRES_REGISTERED_PROTOCOL_AND_RECEIVED_OUTCOMES',
        solarRequired=False, newProtocolRegistered=False, accuracyResult=None,
        productionEligible=False, automaticPromotion=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--issues', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    for name in ('start','end-exclusive','as-of','model-version','mapping-evidence'):
        parser.add_argument('--'+name, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists; preserve earlier evidence')
    issues = [json.loads(line) for line in args.issues.read_text().splitlines() if line.strip()]
    result = readiness(issues, start=args.start, end_exclusive=args.end_exclusive,
        as_of=args.as_of, model_version=args.model_version, mapping_evidence=args.mapping_evidence)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2, allow_nan=False)
        stream.write('\n')
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
