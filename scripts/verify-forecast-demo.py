"""Independently audit the frozen presentation; never write into its experiment.

Run with the demo Python: verify-forecast-demo.py DATASET EXPERIMENT OUTPUT.json
Re-fits the unchanged parameters on earlier data only to verify reproducibility.
"""
import json
import math
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from statistics import mean
from unittest.mock import patch

import numpy as np
from sklearn.pipeline import Pipeline

from wattly_ml.artifacts import load_bundle
from wattly_ml.backtest import History, predict
from wattly_ml.baselines import baselines
from wattly_ml.data import digest, load_dataset
from wattly_ml.features import features
from wattly_ml.timebase import DAY, STEP, instant
from wattly_ml.train import train


def audit(dataset, experiment):
    files = [p for root in (dataset, experiment) for p in root.rglob('*') if p.is_file()]
    before = {str(p): digest(p) for p in files}
    rows, manifest = load_dataset(dataset)
    public = json.loads((experiment/'public/forecast-demo.json').read_text())
    protocol = json.loads((experiment/'protocol.json').read_text())
    saved = json.loads((experiment/'test/summary.json').read_text())
    assert public['protocol'] == saved['protocol'] == protocol
    assert public['test'] == saved['test'] and public['validation'] == saved['validation']
    assert protocol['horizonCount'] == 24 and protocol['intervalMinutes'] == 30
    start, cutoff, test_start, test_end = [instant(protocol[k]) for k in
        ('trainingStart', 'trainingCutoff', 'testStart', 'testEnd')]
    assert cutoff == instant(protocol['validationStart'])
    assert test_start == instant(protocol['validationEnd'])
    assert start < cutoff < test_start < test_end
    bundle = load_bundle(experiment/'models'/public['modelVersion'])
    model = bundle['manifest']
    assert model['exploratory'] and not model['productionEligible']
    assert not (experiment/'current.json').exists()
    assert instant(model['trainingCutoff']) == cutoff
    assert instant(model['usableFrom']) == test_start
    assert not model['retrospective']
    assert model['parameters'] == {'max_iter': 40, 'max_leaf_nodes': 15, 'l2_regularization': 1}

    # Independent vintage selection: no call to production as_of() or truth().
    def known(at, labels=False):
        selected = {}
        for r in sorted(rows, key=lambda r: (instant(r['sourceUpdatedAt']), instant(r['availableAt']))):
            period = instant(r['periodStart'])
            if (period <= at and instant(r['sourceUpdatedAt']) <= at and instant(r['availableAt']) <= at
                    and (not labels or period+STEP <= at and r['priceStatus'] == 'PROVISIONAL')):
                selected[period] = r
        return selected

    def target_times(origin):
        return [origin + h*STEP for h in range(1, 25)]

    # Audit exactly what each pipeline receives before fitting its imputer/model.
    xs, ys = [[] for _ in range(24)], [[] for _ in range(24)]
    labels = known(cutoff, labels=True)
    origin = start
    training_purged = 0
    while origin < cutoff:
        targets = target_times(origin)
        past = known(origin)
        if targets[-1]+STEP > cutoff or not all(t in labels for t in targets):
            training_purged += 1
        elif len(past) >= 6 and origin-max(instant(r['sourceUpdatedAt']) for r in past.values()) <= timedelta(minutes=40):
            assert past == History(rows).at(origin)
            for h, target in enumerate(targets):
                xs[h].append(features(past, origin, target))
                ys[h].append(labels[target]['usep'])
        origin += STEP
    fit_calls = 0
    real_fit = Pipeline.fit

    def checked_fit(self, x, y, **kwargs):
        nonlocal fit_calls
        np.testing.assert_allclose(x, xs[fit_calls], equal_nan=True, rtol=0, atol=0)
        np.testing.assert_allclose(y, ys[fit_calls], rtol=0, atol=0)
        fit_calls += 1
        return real_fit(self, x, y, **kwargs)

    earlier = [r for r in rows if all(instant(r[k]) < test_start for k in
               ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    with patch.object(Pipeline, 'fit', checked_fit):
        rebuilt, _ = train(earlier, manifest, start, cutoff, test_start, policy='PROVISIONAL', exploratory=True)
    assert fit_calls == 24 and len(ys[0]) == model['trainingOrigins']
    assert training_purged == model['excludedTrainingOrigins']

    split_results = {}
    for name, lower, upper in (('validation', cutoff, test_start), ('test', test_start, test_end)):
        records = [json.loads(line) for line in (experiment/name/'predictions.jsonl').read_text().splitlines()]
        keys = [(r['method'], r['asOf'], r['horizon'], r['targetPeriod']) for r in records]
        assert len(keys) == len(set(keys)), 'Duplicate forecast pairs'
        labels = known(upper, labels=True)
        eligible, excluded = {}, {}
        for method in ('AI', 'B1', 'B2', 'B3'):
            subset = [r for r in records if r['method'] == method]
            eligible[method] = {(r['asOf'], r['horizon'], r['targetPeriod']): r for r in subset if r['exclusionReason'] is None}
            excluded[method] = dict(Counter(r['exclusionReason'] for r in subset if r['exclusionReason']))
        # Reproduce predictions with independently selected input vintages.
        for stamp in sorted({r['asOf'] for r in records}):
            origin = instant(stamp)
            assert lower <= origin < upper and origin.minute in (0, 30) and origin.second == 0
            past = known(origin)
            assert past == History(rows).at(origin)
            values = baselines(past, origin)
            purged = target_times(origin)[-1]+STEP > upper
            if not purged:
                values['AI'] = predict(bundle, past, origin)
                np.testing.assert_allclose(values['AI'], predict(rebuilt, past, origin), rtol=0, atol=1e-9)
            for r in (r for r in records if r['asOf'] == stamp):
                target = target_times(origin)[r['horizon']-1]
                assert instant(r['targetPeriod']) == target and target > origin
                assert r['actual'] == (labels[target]['usep'] if target in labels else None)
                reason = ('PURGED_SPLIT_BOUNDARY' if purged else
                          'STALE_OR_MISSING_INPUT' if not past or origin-max(instant(v['sourceUpdatedAt']) for v in past.values()) > timedelta(minutes=40) else
                          'INSUFFICIENT_HISTORY' if values[r['method']] is None else
                          'MISSING_TRUTH' if target not in labels else None)
                assert r['exclusionReason'] == reason
                if reason is None:
                    assert math.isclose(r['predictedUsep'], values[r['method']][r['horizon']-1], abs_tol=1e-9, rel_tol=0)
                else:
                    assert r['predictedUsep'] is None
                reference_count = sum(target-n*DAY < origin and target-n*DAY in past for n in range(1, 29))
                if reference_count < model['spikeConfig']['minimumSamples']:
                    assert r['threshold'] is None and r['spikeFlag'] is None and r['actualSpike'] is None
        methods = (['AI']+[m for m in ('B1', 'B2', 'B3') if eligible[m]]) if name == 'validation' else protocol['comparisonMethods']
        if name == 'validation':
            assert methods == protocol['comparisonMethods'], 'Method set must be selected on validation'
        common = set.intersection(*(set(eligible[m]) for m in methods))
        mae = {m: mean(abs(eligible[m][k]['predictedUsep']-eligible[m][k]['actual']) for k in common) for m in methods}
        for key in common:
            assert len({eligible[m][key]['actual'] for m in methods}) == 1
        metrics = public[name]
        assert len(common) == metrics['commonPairs']
        for method in methods:
            assert math.isclose(mae[method], metrics['table'][method]['mae'], rel_tol=0, abs_tol=1e-9)
        if name == 'validation':
            assert min(methods[1:], key=mae.get) == protocol['validationSelectedBaseline']
        else:
            assert excluded == public['exclusions']
            exported = {(o['asOf'], p['horizon'], p['targetPeriod']): p for o in public['origins'] for p in o['points']}
            assert set(exported) == common
            for key, point in exported.items():
                assert point['actual'] == eligible['AI'][key]['actual']
                for method in methods:
                    assert point[method] == eligible[method][key]['predictedUsep']
        split_results[name] = {'mae': mae, 'commonPairs': len(common), 'eligibleOrigins': len({k[0] for k in common}),
                              'distinctTargets': len({k[2] for k in common}), 'exclusions': excluded,
                              'allMethodCommonPairs': len(set.intersection(*(set(v) for v in eligible.values())))}
    assert {str(p): digest(p) for p in files} == before, 'Frozen inputs or artifacts changed'
    return {'checkedAt': datetime.now(timezone.utc).isoformat(), 'modelVersion': public['modelVersion'],
            'datasetDigest': manifest['sha256'], 'trainingOriginsPerHorizon': len(ys[0]),
            'excludedTrainingOrigins': training_purged, 'verifiedPipelineFits': fit_calls,
            'splits': split_results, 'frozenFilesUnchanged': True, 'frozenFileDigests': before,
            'checks': ['Independent paired MAE and truth-vintage selection', 'Publication and availability <= origin for every input',
                       'Training labels known by training cutoff; complete targets within split',
                       'All 24 fit inputs/labels verified; unchanged-parameter refit reproduces saved predictions',
                       'Methods and baseline selected using validation only', 'Public chart values equal saved predictions',
                       'Missing spike history leaves thresholds and flags unset'],
            'limitation': 'Retrospective experiment, one overlapping test day; not proof of a historical deployed artifact or general accuracy.'}


if __name__ == '__main__':
    dataset, experiment, output = map(Path, sys.argv[1:])
    result = audit(dataset, experiment)
    with output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'frozenFileDigests'}, indent=2))
