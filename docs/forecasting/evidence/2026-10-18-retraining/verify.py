"""Independently recompute saved prediction errors and check immutable bundles."""
from collections import Counter
import csv
import json
from pathlib import Path
from statistics import mean

import numpy as np

from wattly_ml.artifacts import load_bundle
from wattly_ml.backtest import History, predict
from wattly_ml.data import DatasetRows, digest, load_dataset, truth, write_json
from wattly_ml.timebase import STEP, instant, targets

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'models-and-evaluation-v1'


def main():
    result = json.loads((OUT / 'summary.json').read_text())
    assert result['status'] == 'COMPLETE'
    rows, manifest = load_dataset(ROOT / 'dataset')
    assert manifest['sha256'] == result['protocol']['datasetDigest']
    assert all(digest(Path(p)) == h for p, h in result['referenceFileDigests'].items())
    verified = {}
    horizon_rows = []
    for split, start_key, end_key in [('validation', 'validationStart', 'validationEnd'),
                                     ('held-out-test', 'testStart', 'testEnd')]:
        start, end = (instant(result['protocol'][k]) for k in (start_key, end_key))
        actuals = truth(rows, 'PROVISIONAL', end)
        records = [json.loads(line) for line in (OUT / split / 'predictions.jsonl').read_text().splitlines()]
        methods = sorted({r['method'] for r in records})
        valid = {m: {} for m in methods}
        for row in records:
            if row['exclusionReason'] is not None:
                continue
            origin, target = instant(row['asOf']), instant(row['targetPeriod'])
            key = (row['asOf'], row['horizon'])
            assert key not in valid[row['method']]
            assert start <= origin < end and target + STEP <= end
            assert targets(origin)[row['horizon']-1] == target
            assert row['actual'] == actuals[target] and np.isfinite(row['predictedUsep'])
            valid[row['method']][key] = row
        common = set.intersection(*(set(v) for v in valid.values()))
        saved = result['validation' if split == 'validation' else 'heldOutTest']
        assert len(common) == saved['commonPairs'] > 0
        mae = {}
        for method in methods:
            eligible = valid[method]
            error = mean(abs(eligible[k]['predictedUsep']-eligible[k]['actual']) for k in common)
            assert abs(error-saved['table'][method]['mae']) < 1e-10
            assert len(eligible) == saved['table'][method]['eligiblePairs']
            mae[method] = error
            for h in range(1, 25):
                keys = [k for k in common if k[1] == h]
                h_mae = mean(abs(eligible[k]['predictedUsep']-eligible[k]['actual']) for k in keys) if keys else None
                assert h_mae == saved['table'][method]['perHorizon'][str(h)]
                horizon_rows.append([split, method, h, len(keys), h_mae])
        # This secondary comparison deliberately excludes B3 and has separate coverage.
        broad_methods = ['AI', 'PREVIOUS_AI', 'B1', 'B2']
        broad = set.intersection(*(set(valid[m]) for m in broad_methods))
        verified[split] = {'commonPairs': len(common),
            'distinctOrigins': len({k[0] for k in common}),
            'distinctTargets': len({valid['AI'][k]['targetPeriod'] for k in common}),
            'mae': mae, 'eligiblePairs': {m: len(v) for m, v in valid.items()},
            'exclusions': {m: dict(Counter(r['exclusionReason'] for r in records
                if r['method'] == m and r['exclusionReason'] is not None)) for m in methods},
            'broaderComparisonWithoutB3': {'methods': broad_methods, 'commonPairs': len(broad),
                'distinctOrigins': len({k[0] for k in broad}),
                'distinctTargets': len({valid['AI'][k]['targetPeriod'] for k in broad}),
                'mae': {m: mean(abs(valid[m][k]['predictedUsep']-valid[m][k]['actual']) for k in broad)
                    if broad else None for m in broad_methods},
                'limitation': 'Different coverage; cannot compare these errors with the all-method B3 scoreboard'}}
    checks = {}
    acquired = max(instant(s['acquiredAt']) for s in manifest['sources'])
    intervals = [(instant(p['start']), instant(p['end'])) for p in result['protocol']['protectedIntervals']
        if p['purpose'] != 'new-holdout']
    past = History(DatasetRows([r for r in rows if not any(a <= instant(r['periodStart']) < b for a,b in intervals)])).at(acquired)
    for key in ('evaluationManifest', 'latestFitManifest'):
        metadata = result[key]
        loaded = load_bundle(OUT / 'models' / metadata['version'])
        assert loaded['manifest'] == metadata
        assert loaded['manifest']['researchOnly'] and not loaded['manifest']['productionEligible']
        values = predict(loaded, past, acquired)
        assert len(values) == 24 and np.all(np.isfinite(values))
        checks[key] = {'version': metadata['version'], 'modelSha256': metadata['modelSha256'],
            'trainingOrigins': metadata['trainingOrigins'], 'finiteForecasts': len(values)}
    assert not (ROOT / 'runtime/current.json').exists()
    attempts = [json.loads(p.read_text()) for p in (ROOT / 'runtime/training-attempts').glob('*.json')]
    assert len(attempts) == 1 and attempts[0]['state'] == 'SUCCESS' and attempts[0]['promotionDecision'] == 'RETAINED'
    assert attempts[0]['datasetDigest'] == manifest['sha256']
    proof = {'status': 'PASS', 'datasetDigest': manifest['sha256'], 'scores': verified, 'models': checks,
        'independentSavedPredictionChecks': True, 'canonicalTruthMatches': True, 'targetAlignmentAndSplitPurging': True,
        'referenceUnchanged': True, 'trainingAttemptRecorded': True, 'productionPromotionAttempted': False,
        'latestFitHasIndependentAccuracyScore': False}
    write_json(OUT / 'verification.json', proof)
    with (OUT / 'horizons.csv').open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['split', 'method', 'horizon', 'common_pairs', 'mae_sgd_mwh'])
        writer.writerows(horizon_rows)
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
