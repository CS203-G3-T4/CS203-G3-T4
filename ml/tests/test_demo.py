import json
from pathlib import Path

import pytest

from wattly_ml import demo
from wattly_ml.artifacts import load_bundle, promote
from wattly_ml.data import digest, quality, write_json
from wattly_ml.timebase import instant, targets, STEP
from test_pipeline import history


def test_demo_uses_only_earlier_data_and_keeps_production_guards(tmp_path, monkeypatch):
    rows = history(7)
    dataset = tmp_path/'dataset'; dataset.mkdir()
    prices = dataset/'prices.jsonl'
    prices.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    write_json(dataset/'manifest.json',{
        'sha256':digest(prices),'quality':quality(rows),'provisionalOffsetMinutes':0,
        'sources':[{'acquiredAt':rows[-1]['availableAt']}]})
    actual_train = demo.train
    def observed_train(earlier, manifest, start, cutoff, end, **kwargs):
        assert all(max(instant(r[k]) for k in ('periodStart','sourceUpdatedAt','availableAt')) < end for r in earlier)
        return actual_train(earlier,manifest,start,cutoff,end,**kwargs,iterations=2)
    monkeypatch.setattr(demo,'train',observed_train)
    output = tmp_path/'experiment'
    result = demo.experiment(dataset,output)
    assert result['protocol']['testStart']=='2026-01-06T00:00:00+08:00'
    assert result['protocol']['testEnd']=='2026-01-07T00:00:00+08:00'
    assert result['protocol']['comparisonMethods']==['AI','B1','B2']
    assert result['test']['commonPairs']==576
    assert result['allMethods']['commonPairs']==0
    assert result['exclusions']['AI']=={'PURGED_SPLIT_BOUNDARY':576}
    assert not result['actionable'] and not result['productionApproved']
    public = json.loads((output/'public'/'forecast-demo.json').read_text())
    assert len(public['actuals'])==48 and len(public['origins'])==24
    for origin in public['origins']:
        assert [instant(p['targetPeriod']) for p in origin['points']]==targets(instant(origin['asOf']))
        assert instant(origin['points'][-1]['targetPeriod'])+STEP<=instant(result['protocol']['testEnd'])
        assert all(p['B3'] is None for p in origin['points'])
    bundle = load_bundle(output/'models'/result['modelVersion'])
    assert bundle['manifest']['exploratory'] and not bundle['manifest']['productionEligible']
    assert instant(bundle['manifest']['trainingDate'])>instant(result['protocol']['testEnd'])
    with pytest.raises(ValueError): promote(output,result['modelVersion'])
    assert not (output/'current.json').exists()
    frozen = (output/'test'/'summary.json').read_bytes()
    with pytest.raises(ValueError,match='immutable'): demo.experiment(dataset,output)
    assert (output/'test'/'summary.json').read_bytes()==frozen


def test_future_prices_and_late_revisions_cannot_change_training_inputs_or_labels(monkeypatch):
    import numpy as np
    from sklearn.pipeline import Pipeline
    from wattly_ml.train import train
    from wattly_ml.timebase import DAY

    rows = history(7)
    start = instant(rows[48]['periodStart'])
    cutoff, end = start+2*DAY, start+3*DAY
    fits = []
    original_fit = Pipeline.fit
    def observed_fit(self, x, y, **kwargs):
        fits.append((np.array(x, copy=True), np.array(y, copy=True)))
        return original_fit(self, x, y, **kwargs)
    monkeypatch.setattr(Pipeline, 'fit', observed_fit)
    manifest = {'sha256': 'synthetic', 'quality': quality(rows)}
    train(rows, manifest, start, cutoff, end, policy='PROVISIONAL', exploratory=True, iterations=2)
    # Change validation/test prices, plus an old period revised only after fitting.
    changed = [{**r, 'usep': 999999} if instant(r['periodStart']) >= cutoff else r for r in rows]
    changed.append({**rows[70], 'usep': -999999, 'revisionId': 'late-revision',
                    'availableAt': (cutoff+STEP).isoformat()})
    train(changed, manifest, start, cutoff, end, policy='PROVISIONAL', exploratory=True, iterations=2)
    assert len(fits) == 48
    for before, after in zip(fits[:24], fits[24:]):
        for original, altered in zip(before, after):
            np.testing.assert_allclose(original, altered, rtol=0, atol=0, equal_nan=True)
