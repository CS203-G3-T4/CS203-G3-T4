"""One frozen solar/demand ablation using original, receipt-stamped LIVE inputs.

No collector, live model, trial, timer, or approval pointer is modified here.
Repeated forecasts in a half-hour are not independent training examples.
"""
from collections import Counter
from datetime import timedelta
import hashlib
import json
import math
from pathlib import Path
from statistics import mean

from .artifacts import load_bundle, save_bundle
from .backtest import fresh, predict
from .baselines import baselines
from .data import KnownPrices, digest
from .features import feature_names, features, holiday_calendar, holiday_digest
from .solar import SOLAR_POLICY, SOLAR_DEMAND_POLICY
from .spike_grading import event_metrics, metrics, runs
from .spikes import DEFAULT_CONFIG, reference
from .timebase import DAY, STEP, floor, instant, targets

PARAMETERS = dict(max_iter=40, max_leaf_nodes=15, l2_regularization=1.,
                  random_state=42, early_stopping=False)
METHODS = ('SOLAR_DEMAND', 'SOLAR_REFIT', 'SOLAR_FROZEN', 'B1', 'B2', 'B3')


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def protocol_check(protocol):
    start, cutoff, test_start, end = [instant(protocol[k]) for k in
                                    ('trainStart', 'trainCutoff', 'testStart', 'testEnd')]
    if (not start < cutoff < test_start < end or any(t != floor(t) for t in (start, cutoff, test_start, end))
            or test_start-cutoff < DAY or end-test_start != 14*DAY
            or protocol['parameters'] != PARAMETERS or protocol['spikeConfig'] != DEFAULT_CONFIG
            or protocol['truthPolicy'] != 'PROVISIONAL'
            or protocol['minimumTrainingOrigins'] < 48 or protocol['minimumTestOrigins'] < 48):
        raise ValueError('Invalid frozen demand experiment protocol')
    return start, cutoff, test_start, end


def _mapped(row, mapping_evidence=None):
    return row.get('timeMapping') == 'EMC_PERIOD' or (
        mapping_evidence == 'EMC_RT_20260929_24_PERIODS' and row.get('source') == 'NEMS_SN_SG'
        and row.get('timeMapping') == 'UNVERIFIED_FLOOR'
        and floor(instant(row['sourceUpdatedAt'])) == instant(row['periodStart']))


def snapshot_history(issue, mapping_evidence=None):
    """Reconstruct only the inputs actually saved with this issue, never later repairs."""
    from .serving.app import ForecastRequest
    origin = instant(issue['asOf'])
    snapshot = issue['inputs']
    raw = snapshot['marketInputs']
    if not isinstance(raw, dict) or not raw.get('expectedDemand'):
        raise ValueError('Saved issue has no original expected-demand inputs')
    verified = raw.get('mappingVerified') is True or all(_mapped(row, mapping_evidence) for row in raw['priceHistory'])
    if not verified:
        raise ValueError('Price period mapping lacks the recorded evidence')
    price_keys = ('periodStart', 'sourceUpdatedAt', 'availableAt', 'usep')
    request = ForecastRequest.model_validate(dict(schemaVersion=2, requestId=issue['id'], asOf=origin,
        intervalMinutes=30, horizonCount=24, mappingVerified=verified,
        priceHistory=[{k:row[k] for k in price_keys} for row in raw['priceHistory']],
        expectedDemand=raw['expectedDemand']))
    past = KnownPrices({r.periodStart: r.model_dump() for r in request.priceHistory},
                       [r.model_dump() for r in request.expectedDemand])
    if not fresh(past, origin):
        raise ValueError('Stale price history')
    solar = snapshot['solarInputs']
    if solar['source'] != 'EMC_PUBLIC_PROVISIONAL_SOLAR' or len(solar['snapshotSha256']) != 64:
        raise ValueError('Missing original solar provenance')
    past.solar_values = {}
    for row in solar['periods']:
        period, received = instant(row['periodStart']), instant(row['availableAt'])
        value = float(row['solarMw'])
        if (period != floor(period) or period+STEP > floor(origin) or received > origin
                or origin-received > timedelta(minutes=10) or not math.isfinite(value) or value < 0
                or period in past.solar_values):
            raise ValueError('Invalid or future solar input')
        past.solar_values[period] = value
    # Demand is mandatory at every target, rather than imputed missing for this candidate.
    for target in targets(origin):
        features(past, origin, target, schema_version=4)
    if any(v is None for v in baselines(past, origin).values()):
        raise ValueError('Incomplete baseline comparison history')
    return past


def select_issues(issues, protocol, as_of):
    protocol_check(protocol)
    selected, excluded, seen = {}, Counter(), set()
    for issue in sorted(issues, key=lambda r: (instant(r['asOf']), r['id'])):
        if issue['id'] in seen:
            raise ValueError('Duplicate saved forecast issue')
        seen.add(issue['id'])
        origin = instant(issue['asOf'])
        if origin > as_of or origin < instant(protocol['trainStart']):
            excluded['OUTSIDE_AVAILABLE_COHORT'] += 1
            continue
        if (issue['mode'] != 'LIVE' or issue['modelVersion'] != protocol['frozenModelVersion']
                or issue.get('stale') or issue.get('status') != 'AVAILABLE'):
            excluded['NOT_FRESH_FROZEN_LIVE_AI'] += 1
            continue
        try:
            past = snapshot_history(issue, protocol.get('periodMappingEvidence'))
        except (ValueError, KeyError, TypeError, IndexError) as exc:
            excluded['INVALID_INPUTS:'+str(exc).split('\n')[0][:100]] += 1
            continue
        slot = floor(origin)
        if slot in selected:
            excluded['REPEATED_HALF_HOUR'] += 1
            continue
        selected[slot] = (issue, past)
    return list(selected.values()), dict(excluded)


def known_truth(prices, cutoff, mapping_evidence=None):
    """Latest provisional vintage actually received by the fixed scoring cutoff."""
    selected = {}
    for row in prices:
        if row['priceStatus'] != 'PROVISIONAL' or not _mapped(row, mapping_evidence):
            continue
        if not row.get('availableAt') or not row.get('sourceUpdatedAt'):
            continue
        period, published, received = (instant(row[k]) for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))
        value = float(row['usep'])
        if period != floor(period) or published > received or not math.isfinite(value):
            raise ValueError('Invalid actual-price revision')
        if max(period+STEP, published, received) > cutoff:
            continue
        rank = (published, received)
        if period in selected and selected[period][0] == rank and selected[period][1] != value:
            raise ValueError('Conflicting actual-price vintage')
        if period not in selected or rank > selected[period][0]:
            selected[period] = rank, value
    return {t: v for t, (_, v) in selected.items()}


def complete_rows(selected, actuals, start, end):
    rows, excluded = [], Counter()
    for issue, past in selected:
        origin = instant(issue['asOf'])
        if not start <= origin < end:
            continue
        ts = targets(origin)
        if ts[-1]+STEP > end:
            excluded['PURGED_SPLIT_BOUNDARY'] += 1
        elif not all(t in actuals for t in ts):
            excluded['MISSING_RECEIVED_ACTUALS'] += 1
        else:
            rows.append((issue, past, [actuals[t] for t in ts]))
    return rows, dict(excluded)


def readiness(issues, prices, protocol, as_of):
    as_of = instant(as_of)
    start, cutoff, test_start, end = protocol_check(protocol)
    selected, excluded = select_issues(issues, protocol, as_of)
    evidence = protocol.get('periodMappingEvidence')
    train_rows, train_excluded = complete_rows(selected, known_truth(prices, min(cutoff, as_of), evidence), start, cutoff)
    test_rows, test_excluded = complete_rows(selected, known_truth(prices, min(end, as_of), evidence), test_start, end)
    ready_fit = cutoff <= as_of < test_start and len(train_rows) >= protocol['minimumTrainingOrigins']
    return dict(protocolId=protocol['id'], protocolSha256=identity(protocol), asOf=as_of.isoformat(),
        savedIssues=len(issues), eligibleHalfHourOrigins=len(selected), completeTrainingOrigins=len(train_rows),
        completeTestOrigins=len(test_rows), minimumTrainingOrigins=protocol['minimumTrainingOrigins'],
        minimumTestOrigins=protocol['minimumTestOrigins'], readyToFit=ready_fit,
        readyToEvaluate=as_of >= end and len(test_rows) >= protocol['minimumTestOrigins'],
        fitWindowClosed=as_of >= test_start, trainingExclusions=train_excluded, testExclusions=test_excluded,
        inputExclusions=excluded, trainCutoff=cutoff.isoformat(), testStart=test_start.isoformat(), testEnd=end.isoformat(),
        accuracyResult=None, automaticPromotion=False,
        status='READY_TO_FIT_ONCE' if ready_fit else 'COLLECTING' if as_of < cutoff else 'CHECK_SUPPORT_OR_FIT_WINDOW')


def fit(issues, prices, protocol, as_of, output):
    """Fit one candidate plus one matched control. Never inspect holdout labels."""
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingRegressor
    from sklearn.impute import SimpleImputer
    from sklearn.pipeline import make_pipeline
    from threadpoolctl import threadpool_limits
    as_of, output = instant(as_of), Path(output)
    start, cutoff, test_start, _ = protocol_check(protocol)
    if output.exists():
        raise ValueError('Experiment versions are immutable; no retries into an existing version')
    if not cutoff <= as_of < test_start:
        raise ValueError('Fit only after training cutoff and before untouched testing starts')
    selected, _ = select_issues(issues, protocol, min(as_of, cutoff))
    rows, _ = complete_rows(selected, known_truth(prices, cutoff, protocol.get('periodMappingEvidence')), start, cutoff)
    if len(rows) < protocol['minimumTrainingOrigins']:
        raise ValueError('Insufficient complete training origins with received actuals')
    # Everything below is training-only; the experiment digest excludes held-out prices.
    training_digest = identity([dict(issue=issue, actual=y) for issue, _, y in rows])
    bundles = {}
    with threadpool_limits(limits=1):
        for name, schema, policy in (('SOLAR_DEMAND', 4, SOLAR_DEMAND_POLICY), ('SOLAR_REFIT', 3, SOLAR_POLICY)):
            x = np.asarray([[features(past, instant(issue['asOf']), t, schema_version=schema)
                             for t in targets(instant(issue['asOf']))] for issue, past, _ in rows])
            y = np.asarray([values for _, _, values in rows])
            models = []
            for h in range(24):
                model = make_pipeline(SimpleImputer(strategy='median', add_indicator=True, keep_empty_features=True),
                                      HistGradientBoostingRegressor(**PARAMETERS))
                models.append(model.fit(x[:, h, :], y[:, h]))
            manifest = dict(schemaVersion=1, featureSchemaVersion=schema, featureOrder=feature_names(schema),
                inputPolicy=policy, holidayCalendarVersion=holiday_calendar()['version'], holidayCalendarSha256=holiday_digest(),
                productionEligible=False, exploratory=True, retrospective=False, trainingDate=as_of.isoformat(),
                trainingCutoff=cutoff.isoformat(), usableFrom=test_start.isoformat(), validationEnd=test_start.isoformat(),
                trainingOrigins=len(rows), trainingInputsSha256=training_digest, parameters=PARAMETERS,
                expectedDemandCoverage=1. if schema == 4 else 0., baselineRanking=['B3', 'B2', 'B1'],
                baselineRankingSource='Fixed existing ranking; all three baselines reported', spikeConfig=DEFAULT_CONFIG.copy(),
                experimentProtocolSha256=identity(protocol), experimentProtocolId=protocol['id'])
            bundles[name] = dict(manifest=manifest, models=models)
    output.mkdir(parents=True)
    for name, bundle in bundles.items():
        save_bundle(bundle, output/'models', name.lower()+'-v1')
    (output/'protocol.json').write_text(json.dumps(protocol, indent=2)+'\n')
    return dict(trainingOrigins=len(rows), trainingInputsSha256=training_digest,
                protocolSha256=identity(protocol), candidate='solar_demand-v1', control='solar_refit-v1',
                productionEligible=False, holdoutInspected=False)


def _error(rows):
    errors = [r['predictedUsep']-r['actual'] for r in rows]
    return dict(pairs=len(errors), mae=mean(abs(x) for x in errors) if errors else None,
                rmse=math.sqrt(mean(x*x for x in errors)) if errors else None)


def _false_issues(rows):
    grouped = {}
    for row in rows:
        grouped.setdefault(instant(row['issueSlot']), []).append(row)
    fully_assessed = {slot: rs for slot, rs in grouped.items()
                      if len(rs) == 24 and all(r['actualSpike'] is not None for r in rs)}
    false_slots = {slot for slot, rs in fully_assessed.items()
                   if any(r['spikeFlag'] for r in rs) and not any(r['spikeFlag'] and r['actualSpike'] for r in rs)}
    return dict(assessedIssueHalfHours=len(fully_assessed), falseIssueHalfHours=len(false_slots),
        falseIssueEpisodes=len(runs(false_slots)), falseIssueExposureMinutes=len(false_slots)*30,
        meaning='Scheduled issue slots containing at least one warning and no correctly warned target; mixed true/false issues are reported in pair-level FP. This is not delivered app-notification count.')


def evaluate(issues, prices, protocol, as_of, experiment, frozen_path):
    """One end-of-test report, identical complete targets for all six methods."""
    start, cutoff, test_start, end = protocol_check(protocol)
    if instant(as_of) < end:
        raise ValueError('Untouched holdout is not finished; no interim accuracy peeking')
    experiment = Path(experiment)
    if json.loads((experiment/'protocol.json').read_text()) != protocol:
        raise ValueError('Experiment protocol changed after fitting')
    bundles = {name: load_bundle(experiment/'models'/(name.lower()+'-v1'))
               for name in ('SOLAR_DEMAND', 'SOLAR_REFIT')}
    frozen = load_bundle(Path(frozen_path))
    if (frozen['manifest']['modelSha256'] != protocol['frozenModelSha256']
            or frozen['manifest']['version'] != protocol['frozenModelVersion']):
        raise ValueError('Frozen model changed')
    for bundle in bundles.values():
        m = bundle['manifest']
        if (m['experimentProtocolSha256'] != identity(protocol) or instant(m['trainingDate']) >= test_start
                or instant(m['trainingCutoff']) != cutoff or instant(m['usableFrom']) != test_start):
            raise ValueError('Candidate is not the pre-holdout frozen fit')
    bundles['SOLAR_FROZEN'] = frozen
    selected, input_excluded = select_issues(issues, protocol, instant(as_of))
    eligible, outcome_excluded = complete_rows(selected, known_truth(prices, end, protocol.get('periodMappingEvidence')), test_start, end)
    if len(eligible) < protocol['minimumTestOrigins']:
        raise ValueError('Insufficient complete common holdout origins')
    records = []
    for issue, past, actuals in eligible:
        origin = instant(issue['asOf'])
        forecasts = {**baselines(past, origin), **{name: predict(b, past, origin) for name, b in bundles.items()}}
        for h, (target, actual) in enumerate(zip(targets(origin), actuals), 1):
            ref = reference(past, target, origin, protocol['spikeConfig'])
            threshold = ref['threshold'] if ref['available'] else None
            for method in METHODS:
                value = forecasts[method][h-1]
                if not math.isfinite(value):
                    raise ValueError('Non-finite candidate prediction; do not drop failed origins')
                records.append(dict(asOf=origin.isoformat(), issueSlot=floor(origin).isoformat(), targetPeriod=target.isoformat(),
                    horizon=h, method=method, predictedUsep=value, actual=actual, truthPolicy='PROVISIONAL',
                    threshold=threshold, actualSpike=actual > threshold if threshold is not None else None,
                    spikeFlag=value > threshold if threshold is not None else None, exclusionReason=None))
    table = {}
    for method in METHODS:
        own = [r for r in records if r['method'] == method]
        labelled = [r for r in own if r['threshold'] is not None]
        table[method] = dict(all=_error(own), ordinary=_error([r for r in labelled if not r['actualSpike']]),
            spike=_error([r for r in labelled if r['actualSpike']]), classification=metrics(labelled),
            falseIssues=_false_issues(own),
            perHorizon={str(h): _error([r for r in own if r['horizon'] == h]) for h in range(1, 25)},
            testWeeks={str(w+1): _error([r for r in own if test_start+w*7*DAY <= instant(r['asOf']) < test_start+(w+1)*7*DAY]) for w in range(2)},
            fixedLeadEvents={str(h): event_metrics([r for r in labelled if r['horizon'] == h]) for h in (1, 6, 12, 24)})
    return records, dict(protocolSha256=identity(protocol), commonOrigins=len(eligible), commonPairs=len(eligible)*24,
        table=table, inputExclusions=input_excluded, outcomeExclusions=outcome_excluded,
        priceUnits='SGD_PER_MWH', savingsValidation='PENDING_REAL_F4_REPLAY', automaticPromotion=False,
        genuineSpikeValidation=False, decision='REVIEW_ALL_PRICE_SPIKE_AND_SAVINGS_RESULTS_BEFORE_ADOPTION',
        caveats=['Observed provisional truth frozen at testEnd; missing receipt vintages excluded for every method',
                 'Solar-only refit isolates new-data retraining from the additional demand feature',
                 'Overlapping horizons are dependent; event counts at different leads must not be added',
                 'Two weeks cannot establish consistent performance in every season or enough independent spike events'])
