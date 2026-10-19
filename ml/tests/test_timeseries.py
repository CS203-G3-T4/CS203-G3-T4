"""Synthetic checks for recursive timestamp alignment and training availability."""
import json
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean

import joblib
import numpy as np
import pytest
from sklearn.dummy import DummyRegressor
from sklearn.pipeline import Pipeline

from wattly_ml import timeseries
from wattly_ml.artifacts import versions
from wattly_ml.backtest import History, backtest
from wattly_ml.data import digest, write_json
from wattly_ml.spikes import DEFAULT_CONFIG
from wattly_ml.timebase import DAY, SGT, STEP, instant
from test_corrections import dataset
from test_pipeline import history, row


class Increment:
    def predict(self, x):
        return [x[0][0]+1]


def test_recursive_forecast_bridges_unpublished_interval_and_preserves_negatives():
    origin = instant('2026-01-02T00:00:00+08:00')
    rows = [row(origin-STEP*n, -9-n) for n in range(1,7)]
    past = History(rows).at(origin)
    values = timeseries.forecast(Increment(), past, origin)
    assert values == list(range(-8,16))  # 25 recursive steps, but only 24 future target intervals returned.
    current = {**row(origin,-9), 'sourceUpdatedAt':origin.isoformat(), 'availableAt':origin.isoformat()}
    assert timeseries.forecast(Increment(),History(rows+[current]).at(origin),origin) == values
    missing = [r for r in rows if instant(r['periodStart']) != origin-3*STEP]
    assert timeseries.forecast(Increment(),History(missing).at(origin),origin) is None
    assert timeseries.forecast(Increment(),{},origin) is None


def test_ar_examples_ignore_future_truth_revisions_and_fit_scaler_on_training_only():
    rows = history(8); start = instant(rows[48]['periodStart']); cutoff = start+4*DAY
    xs, ys, stamps, excluded = timeseries.training_examples(rows,start,cutoff)
    assert len(xs) == len(ys) == len(stamps) == 4*48
    assert all(instant(t)+STEP <= cutoff for t in stamps)
    for n, stamp in enumerate(stamps):
        past = History(rows).at(instant(stamp))
        assert xs[n].tolist() == [past[instant(stamp)-i*STEP]['usep'] for i in range(1,7)]
    changed = [{**r,'usep':99999999} if instant(r['periodStart']) >= cutoff else r for r in rows]
    changed.append({**rows[50], 'revisionId':'late', 'usep':-99999999,
        'sourceUpdatedAt':(cutoff+STEP).isoformat(), 'availableAt':(cutoff+STEP).isoformat()})
    xx, yy, tt, ee = timeseries.training_examples(changed,start,cutoff)
    np.testing.assert_array_equal(xs,xx); np.testing.assert_array_equal(ys,yy)
    assert stamps == tt and excluded == ee
    models, metadata = timeseries.fit(rows,start,cutoff)
    for model in models.values():
        np.testing.assert_allclose(model[0].mean_,xs.mean(axis=0))
    assert metadata['trainingTargetPeriods'] == len(ys) and not metadata['productionEligible']
    origin = cutoff
    assert timeseries.forecast(models['AR6_RIDGE_10'],History(rows).at(origin),origin) == timeseries.forecast(
        models['AR6_RIDGE_10'],History(changed).at(origin),origin)


def reference(path, rows, manifest):
    """Constant synthetic HGB stand-ins; never used as accuracy evidence."""
    path.mkdir(); (path/'final-fit').mkdir()
    start = instant(rows[48]['periodStart']); boundary = start+4*DAY
    model = DummyRegressor(strategy='constant',constant=120).fit([[0]],[120])
    models = {m:[model]*24 for m in ('CURRENT','HGB_MAE','HGB_SMALL','HGB_REGULARISED','MEDIAN')}
    folds=[]
    for number, n in enumerate((2,3),1):
        cutoff = start+n*DAY; end = cutoff+DAY
        bundle={'models':models['CURRENT'], 'manifest':{'version':'SYNTHETIC ONLY', 'usableFrom':cutoff.isoformat(),
            'spikeConfig':DEFAULT_CONFIG}}
        base=backtest(rows,cutoff,end,bundle=bundle,truth_cutoff=end)
        records=[r for r in base if r['method']!='AI']
        for name in models:
            records.extend({**r,'method':name,'priceRegime':'NON_TAIL'} for r in base if r['method']=='AI')
        known = History(rows)
        for r in base:
            if r['method'] == 'AI':
                past = known.at(instant(r['asOf']))
                value = past[max(past)]['usep'] if r['exclusionReason'] is None else None
                records.append({**r,'method':'PERSISTENCE','predictedUsep':value,'priceRegime':'NON_TAIL'})
        for r in records:r['priceRegime']='NON_TAIL'
        folder=path/f'fold-{number}';folder.mkdir()
        (folder/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
        folds.append({'cutoff':cutoff.isoformat(),'end':end.isoformat()})
    write_json(path/'selection.json',{'fixture':'SYNTHETIC ONLY'})
    joblib.dump({'models':models},path/'final-fit/candidates.joblib')
    tomorrow=datetime.now(timezone.utc).astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)+DAY
    write_json(path/'prospective.json',{'start':tomorrow.isoformat(),'end':(tomorrow+7*DAY).isoformat(),
        'selectionSha256':digest(path/'selection.json'),
        'modelSha256':digest(path/'final-fit/candidates.joblib')})
    write_json(path/'final-fit/manifest.json',{'trainingCutoff':boundary.isoformat(), 'dependencies':versions(),
        'prospectiveSha256':digest(path/'prospective.json'),'highPriceTailThreshold':200})
    write_json(path/'summary.json',{'status':'COMPLETE','protocol':{'datasetDigest':manifest['sha256'],
        'trainingStart':start.isoformat(),'selectionEnd':boundary.isoformat(),'folds':folds}})
    return path


def test_ar_study_reuses_hgb_freezes_selection_before_diagnostics_and_recalculates_pairs(tmp_path,monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE','EMC_RT_20260929_24_PERIODS')
    rows=history(8); data=dataset(tmp_path/'data',rows)
    manifest=json.loads((data/'manifest.json').read_text()); prior=reference(tmp_path/'reference',rows,manifest)
    hashes={str(p):digest(p) for p in prior.rglob('*') if p.is_file()}
    output=tmp_path/'study'; fits=[]; original=Pipeline.fit
    boundary=instant('2026-01-06T00:00:00+08:00')
    def capture(self,x,y,**kwargs):
        fits.append(len(y)); return original(self,x,y,**kwargs)
    monkeypatch.setattr(Pipeline,'fit',capture)
    original_records=timeseries.hgb_records
    def diagnostic_guard(*args,**kwargs):
        assert (output/'selection.json').exists() and (output/'final-fit/candidates.joblib').exists()
        fit_count=len(fits)
        result=original_records(*args,**kwargs)
        assert len(fits)==fit_count  # Saved HGB comparison never fits.
        return result
    monkeypatch.setattr(timeseries,'hgb_records',diagnostic_guard)
    result=timeseries.study(data,prior,output)
    assert result['referenceUnchanged'] and len(fits)==9  # Two folds + final fit, three AR candidates each.
    assert instant(result['finalFit']['trainingCutoff'])==boundary
    assert all(digest(Path(p))==checksum for p,checksum in hashes.items())
    assert result['selection']['validation']['commonPairs']==1152
    saved=[json.loads(line) for line in (output/'validation/predictions.jsonl').read_text().splitlines()]
    for name in timeseries.BENCHMARKS+list(timeseries.SPECS_AR):
        valid=[r for r in saved if r['method']==name and r['exclusionReason'] is None]
        assert len(valid)==1152
        assert result['selection']['validation']['table'][name]['mae']==pytest.approx(mean(
            abs(r['predictedUsep']-r['actual']) for r in valid))
    assert not (output/'current.json').exists()
    assert result['diagnostics'] and all(d['purpose']=='ALREADY_SEEN_DIAGNOSTIC' for d in result['diagnostics'])
    with pytest.raises(ValueError,match='immutable'):
        timeseries.study(data,prior,output)
    (prior/'selection.json').write_text('{}')
    with pytest.raises(ValueError,match='unchanged'):
        timeseries.study(data,prior,tmp_path/'tampered')
    assert not (tmp_path/'tampered').exists()
