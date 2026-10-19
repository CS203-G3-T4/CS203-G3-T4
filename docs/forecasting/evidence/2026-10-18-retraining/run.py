"""Refit the active schema2 pipeline without tuning or changing serving pointers."""
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
import json
import os

import numpy as np
from sklearn.base import clone
from threadpoolctl import threadpool_limits

from wattly_ml.artifacts import load_bundle, save_bundle
from wattly_ml.backtest import History, backtest, predict, scores
from wattly_ml.cli import report
from wattly_ml.data import DatasetRows, digest, load_dataset, write_json
from wattly_ml.operations import TrainingAttempt
from wattly_ml.timebase import DAY, SGT, floor, instant
from wattly_ml.train import train, training_examples

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'models-and-evaluation-v1'
PREVIOUS = Path('.local/acceptance-2026-10-18/runtime/models/price-calendar-holiday-demand-v2-20261018').resolve()
EVALUATION_VERSION = 'schema2-collector-evaluation-20261018'
LATEST_VERSION = 'schema2-collector-latest-20261018'
PROTECTED = [
    ('original-protected-test', instant('2026-07-29T00:00:00+08:00'), instant('2026-09-23T00:00:00+08:00')),
    ('presentation-demo', instant('2026-09-28T00:00:00+08:00'), instant('2026-09-29T00:00:00+08:00')),
]


def outside(row, intervals):
    return not any(a <= instant(row['periodStart']) < b for _, a, b in intervals)


def main():
    if OUT.exists():
        raise ValueError('Immutable output exists; choose another run directory')
    rows, dataset = load_dataset(ROOT / 'dataset')
    audit = json.loads((ROOT / 'data-audit-before-training.json').read_text())
    assert audit['sqliteQuickCheck'] == 'ok' and not audit['parsingFailures']
    assert not audit['availabilityBeforePublication'] and dataset['quality']['publicationVintages']
    assert not dataset['quality']['duplicateRows'] and dataset['provisionalOffsetMinutes'] == 0
    acquired = max(instant(s['acquiredAt']) for s in dataset['sources'])
    assert all(instant(r['sourceUpdatedAt']) <= instant(r['availableAt']) <= acquired for r in rows)
    start = instant(audit['trainingStart'])
    test_start, test_end = instant(audit['testStart']), instant(audit['testEnd'])
    validation_start = test_start - 7 * DAY
    latest_cutoff = floor(acquired)
    protected_latest = PROTECTED + [('new-holdout', test_start, test_end)]
    old = load_bundle(PREVIOUS)
    assert instant(old['manifest']['trainingCutoff']) < validation_start
    reference_hashes = {str(p): digest(p) for p in PREVIOUS.iterdir() if p.is_file()}
    operational = DatasetRows([r for r in rows if outside(r, PROTECTED)])
    development = DatasetRows([r for r in operational if all(instant(r[k]) < test_start
        for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))])
    latest_rows = DatasetRows([r for r in rows if outside(r, protected_latest)
        and all(instant(r[k]) <= latest_cutoff for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))])
    assert all(outside(r, protected_latest) for r in latest_rows)
    OUT.mkdir()
    protocol = {
        'createdAt': datetime.now(timezone.utc).isoformat(), 'datasetDigest': dataset['sha256'],
        'featureSchemaVersion': 2, 'trainingStart': start.isoformat(),
        'trainingCutoff': validation_start.isoformat(), 'validationStart': validation_start.isoformat(),
        'validationEnd': test_start.isoformat(), 'testStart': test_start.isoformat(), 'testEnd': test_end.isoformat(),
        'latestFitCutoff': latest_cutoff.isoformat(), 'previousModel': str(PREVIOUS),
        'protectedIntervals': [{'purpose': name, 'start': a.isoformat(), 'end': b.isoformat()}
            for name, a, b in protected_latest],
        'selection': 'Fixed existing active HGB settings; no hyperparameter search; baseline selected on earlier seven-day validation only',
        'model': '24 HistGradientBoostingRegressor pipelines; squared_error, max_iter40, max_leaf_nodes15, l2_regularization1, seed42',
        'features': 'Shared schema2 prices/calendar/holidays/demand availability; no historical origin-known demand available',
        'truth': 'PROVISIONAL; actual source publication and collection availability respected at origins and split boundaries',
        'interpretation': 'Historical reconstruction, not evidence of an artifact actually served at historical origins',
        'latestFitRule': 'Refit on all eligible current data excluding every protected interval, including the new holdout; earlier scores do not evaluate this fit',
        'previousComparison': 'Previous twelve-month final-price research model versus short-history provisional-price retrain; cannot attribute differences solely to extra data',
        'researchOnly': True, 'productionEligible': False, 'promotion': 'Not attempted',
    }
    write_json(OUT / 'protocol.json', protocol)
    args = SimpleNamespace(output=OUT, dataset=ROOT / 'dataset', version=EVALUATION_VERSION,
        start=start, cutoff=validation_start, end=test_start)
    attempt = TrainingAttempt(args)
    try:
        attempt.start()
        attempt.update(datasetDigest=dataset['sha256'])
        bundle, validation_records = train(development, dataset, start, validation_start, test_start,
            policy='PROVISIONAL', exploratory=True)
        bundle['manifest'].update(researchOnly=True, protectedIntervals=protocol['protectedIntervals'],
            protocolSha256=digest(OUT / 'protocol.json'))
        assert bundle['manifest']['featureSchemaVersion'] == 2 and not bundle['manifest']['productionEligible']
        selected_baseline = bundle['manifest']['baselineRanking'][0]
        saved = save_bundle(bundle, OUT / 'models', EVALUATION_VERSION)
        bundle = load_bundle(OUT / 'models' / EVALUATION_VERSION)
        for r in validation_records:
            if r['method'] == 'AI':
                r['modelVersion'] = EVALUATION_VERSION
        previous_validation = backtest(operational, validation_start, test_start, policy='PROVISIONAL',
            bundle=old, truth_cutoff=test_start)
        validation_records.extend({**r, 'method': 'PREVIOUS_AI'} for r in previous_validation if r['method'] == 'AI')
        validation = scores(validation_records, selected_baseline)
        report(OUT / 'validation', validation_records, validation)
        print(json.dumps({'phase': 'validation-complete', 'commonPairs': validation['commonPairs'],
            'selectedBaseline': selected_baseline, 'mae': {m: v['mae'] for m, v in validation['table'].items()}}), flush=True)

        test_records = backtest(operational, test_start, test_end, policy='PROVISIONAL', bundle=bundle, truth_cutoff=test_end)
        previous_test = backtest(operational, test_start, test_end, policy='PROVISIONAL', bundle=old, truth_cutoff=test_end)
        test_records.extend({**r, 'method': 'PREVIOUS_AI'} for r in previous_test if r['method'] == 'AI')
        test = scores(test_records, selected_baseline)
        assert validation['commonPairs'] > 0 and test['commonPairs'] > 0
        report(OUT / 'held-out-test', test_records, test)
        print(json.dumps({'phase': 'test-complete', 'commonPairs': test['commonPairs'],
            'mae': {m: v['mae'] for m, v in test['table'].items()}}), flush=True)

        xs, ys, excluded = training_examples(latest_rows, start, latest_cutoff,
            policy='PROVISIONAL', feature_schema=2)
        assert len(ys[0]) >= 48
        with threadpool_limits(limits=1):
            models = [clone(model).fit(np.asarray(x), y) for model, x, y in zip(bundle['models'], xs, ys)]
        latest_manifest = deepcopy(bundle['manifest'])
        latest_manifest.update(trainingDate=datetime.now(timezone.utc).isoformat(), trainingCutoff=latest_cutoff.isoformat(),
            usableFrom=latest_cutoff.isoformat(), trainingOrigins=len(ys[0]), excludedTrainingOrigins=excluded,
            protectedIntervals=protocol['protectedIntervals'], expectedDemandCoverage=float(np.mean([x[-1] for x in xs[0]])),
            holidayCoverage=float(np.mean([np.isfinite(x[-3]) for x in xs[0]])),
            validation={'table': {'AI': {'mae': None}}, 'commonPairs': 0, 'skill': None,
                'reason': 'This latest fit has not been independently evaluated'},
            earlierEvaluationVersion=EVALUATION_VERSION, baselineValidationSha256=digest(OUT / 'validation/summary.json'))
        latest = save_bundle({'manifest': latest_manifest, 'models': models}, OUT / 'models', LATEST_VERSION)
        loaded_latest = load_bundle(OUT / 'models' / LATEST_VERSION)
        predictions = predict(loaded_latest, History(operational).at(acquired), acquired)
        assert len(predictions) == 24 and np.all(np.isfinite(predictions))
        assert all(digest(Path(p)) == h for p, h in reference_hashes.items())
        assert load_dataset(ROOT / 'dataset')[1]['sha256'] == dataset['sha256']
        result = {'status': 'COMPLETE', 'protocol': protocol, 'quality': audit,
            'validation': validation, 'heldOutTest': test, 'evaluationManifest': saved, 'latestFitManifest': latest,
            'referenceFileDigests': reference_hashes, 'referenceUnchanged': True,
            'latestForecastSmoke': {'origin': acquired.isoformat(), 'finitePredictions': 24},
            'limitations': ['Short gappy provisional history and one test day do not establish production eligibility.',
                'Overlapping forecast pairs are not independent events; no genuine-spike labels exist.',
                'No origin-known expected-demand vintages; missing remains missing.',
                'Original protected test, presentation day and new holdout are excluded from latest fitting.',
                'Latest-fit weights differ from evaluation-fit weights and have no independent accuracy score.',
                'Previous AI used twelve-month final-price history; this comparison is not an isolated extra-data experiment.']}
        write_json(OUT / 'summary.json', result)
        attempt.update(scores={'validation': validation, 'test': test}, latestFitVersion=LATEST_VERSION)
        attempt.finish('SUCCESS', reason='Research retrain completed; production eligibility requirements remain unmet', decision='RETAINED')
        print(json.dumps({'status': 'COMPLETE', 'output': str(OUT), 'evaluationOrigins': saved['trainingOrigins'],
            'latestFitOrigins': latest['trainingOrigins'], 'productionEligible': latest['productionEligible']}), flush=True)
    except BaseException as exc:
        attempt.finish('FAILED', str(exc), decision='RETAINED')
        write_json(OUT / 'failure.json', {'type': type(exc).__name__, 'reason': str(exc)})
        raise
    finally:
        attempt.close()


if __name__ == '__main__':
    main()
