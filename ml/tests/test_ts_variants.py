"""Synthetic software evidence, never market accuracy evidence."""
import json
from pathlib import Path
from statistics import mean

import joblib
import numpy as np
import pytest
from sklearn.pipeline import Pipeline

from wattly_ml import timeseries, ts_variants
from wattly_ml.backtest import History
from wattly_ml.data import digest, write_json
from wattly_ml.timebase import DAY, STEP, instant
from test_corrections import dataset
from test_pipeline import history
from test_timeseries import reference


class Fixed:
    def __init__(self,value):self.value=value
    def predict(self,x):return [self.value]


def noisy_history(days):
    # Huber's estimated residual scale degenerates on perfectly exact sawtooth labels.
    # Fixed synthetic noise exercises the actual solver; it is never accuracy evidence.
    rng = np.random.default_rng(7415)
    return [{**r,'usep':r['usep']+float(noise)}
            for r,noise in zip(history(days),rng.normal(0,3,days*48))]


def test_variants_use_exact_daily_anchors_and_delta_labels_are_shift_invariant():
    rows=history(9);start=instant(rows[48]['periodStart']);cutoff=start+4*DAY
    for spec in ts_variants.SPECS.values():
        xs,ys,stamps,_=ts_variants.examples(rows,start,cutoff,spec)
        assert len(xs)==(24 if spec['direct'] else 1)
        for stamp in stamps:
            assert instant(stamp)+(25*STEP if spec['direct'] else STEP)<=cutoff
        changed=[{**r,'usep':99999999} if instant(r['periodStart'])>=cutoff else r for r in rows]
        changed.append({**rows[50],'revisionId':'late','usep':-99999999,
            'sourceUpdatedAt':(cutoff+STEP).isoformat(),'availableAt':(cutoff+STEP).isoformat()})
        xx,yy,tt,ee=ts_variants.examples(changed,start,cutoff,spec)
        assert stamps==tt
        for a,b in zip(xs+ys,xx+yy):np.testing.assert_array_equal(a,b)
        if spec['delta']:
            shifted=[{**r,'usep':r['usep']+700} for r in rows]
            sx,sy,_,_=ts_variants.examples(shifted,start,cutoff,spec)
            for a,b in zip(xs+ys,sx+sy):np.testing.assert_array_equal(a,b)
        if spec['seasonal']:
            reduced=[r for r in rows if instant(r['periodStart'])!=start-DAY+STEP]
            _,less,_,_=ts_variants.examples(reduced,start,cutoff,spec)
            assert len(less[0])<len(ys[0])
    spec=ts_variants.SPECS['AR6_DAILY_RIDGE']
    xs,_,stamps,_=ts_variants.examples(rows,start,cutoff,spec)
    labels={instant(r['periodStart']):r['usep'] for r in rows}
    assert xs[0][0,-1]==labels[instant(stamps[0])-DAY]


def test_direct_forecasts_never_recurse_and_missing_seasonal_history_stays_unavailable():
    rows=history(9);origin=instant(rows[6*48]['periodStart']);past=History(rows).at(origin)
    anchor=past[max(past)]['usep']
    spec={**ts_variants.SPECS['DIRECT6_DAILY_DELTA_HUBER']}
    assert ts_variants.forecast([Fixed(h) for h in range(1,25)],past,origin,spec)==[anchor+h for h in range(1,25)]
    recursive={**ts_variants.SPECS['AR6_DAILY_DELTA_HUBER']}
    assert ts_variants.forecast(Fixed(1),past,origin,recursive)==[anchor+h for h in range(2,26)]
    missing=dict(past);del missing[origin+STEP-DAY]
    assert ts_variants.forecast([Fixed(1)]*24,missing,origin,spec) is None
    assert ts_variants.forecast(Fixed(1),missing,origin,recursive) is None
    with pytest.raises(ValueError,match='Non-finite'):
        ts_variants.forecast([Fixed(float('nan'))]*24,past,origin,spec)


def test_variant_fit_scaling_and_missing_data_counts(tmp_path):
    rows=noisy_history(8);start=instant(rows[48]['periodStart']);cutoff=start+4*DAY
    models,metadata=ts_variants.fit(rows,start,cutoff)
    for name,spec in ts_variants.SPECS.items():
        xs,ys,_,_=ts_variants.examples(rows,start,cutoff,spec)
        fitted=models[name] if spec['direct'] else [models[name]]
        assert len(fitted)==len(xs)==metadata['trainingSamplesByMethod'][name]['fittedEstimators']
        for x,y,model in zip(xs,ys,fitted):
            np.testing.assert_allclose(model[0].mean_,x.mean(axis=0))
            assert len(y)==metadata['trainingSamplesByMethod'][name]['perEstimator'][0]
    assert not metadata['productionEligible']


def test_solver_failure_keeps_candidate_context_without_substitution(monkeypatch):
    def fail_fit(*args,**kwargs):
        raise ValueError('synthetic solver failure')
    monkeypatch.setattr(ts_variants.HuberRegressor,'fit',fail_fit)
    rows=noisy_history(8);start=instant(rows[48]['periodStart'])
    with pytest.raises(ValueError,match='AR6_HUBER, estimator 1: fit failed: synthetic solver failure'):
        ts_variants.fit(rows,start,start+4*DAY)


def test_improvement_study_preserves_references_freezes_before_diagnostics_and_confirmation_never_fits(tmp_path,monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE','EMC_RT_20260929_24_PERIODS')
    rows=noisy_history(8);data=dataset(tmp_path/'data',rows)
    hgb=reference(tmp_path/'hgb',rows,json.loads((data/'manifest.json').read_text()))
    old=tmp_path/'old-ar';timeseries.study(data,hgb,old)
    hashes={str(p):digest(p) for folder in (hgb,old) for p in folder.rglob('*') if p.is_file()}
    output=tmp_path/'improved';original=timeseries.records;boundary=instant('2026-01-06T00:00:00+08:00')
    def guard(rows,base,models,metadata,**kwargs):
        if metadata.get('candidateSpecs') and any(instant(r['asOf'])>=boundary for r in base):
            assert (output/'selection.json').exists() and (output/'prospective.json').exists()
        return original(rows,base,models,metadata,**kwargs)
    monkeypatch.setattr(timeseries,'records',guard)
    result=timeseries.study(data,old,output,improvements=True)
    monkeypatch.setattr(timeseries,'records',original)
    assert result['referenceUnchanged'] and not result['prospective']['productionEligible']
    assert all(digest(Path(p))==checksum for p,checksum in hashes.items())
    assert instant(result['prospective']['start'])>=instant(json.loads((hgb/'prospective.json').read_text())['end'])
    selected=result['selection']['selectedMethod'];assert selected in ts_variants.SPECS
    raw=[json.loads(line) for line in (output/'validation/predictions.jsonl').read_text().splitlines()]
    methods=result['selection']['validation']['comparisonMethods']
    common=set.intersection(*[{(r['asOf'],r['horizon']) for r in raw if r['method']==m and r['exclusionReason'] is None} for m in methods])
    for m in methods:
        valid=[r for r in raw if r['method']==m and (r['asOf'],r['horizon']) in common]
        assert result['selection']['validation']['table'][m]['mae']==pytest.approx(mean(abs(r['predictedUsep']-r['actual']) for r in valid))
    assert not (output/'current.json').exists()
    with pytest.raises(ValueError,match='immutable'):timeseries.study(data,old,output,improvements=True)
    with pytest.raises(ValueError,match='pending'):timeseries.confirm(data,output,tmp_path/'early')
    assert not (tmp_path/'early').exists()
    # An elapsed synthetic confirmation window, with no refitting of candidates or frozen references.
    start=instant(rows[0]['periodStart']);plan=json.loads((output/'prospective.json').read_text())
    plan.update(frozenAt=(start+8*DAY).isoformat(),start=(start+9*DAY).isoformat(),end=(start+16*DAY).isoformat())
    write_json(output/'prospective.json',plan)
    final=json.loads((output/'final-fit/manifest.json').read_text());final['prospectiveSha256']=digest(output/'prospective.json')
    write_json(output/'final-fit/manifest.json',final)
    def fail_fit(*args,**kwargs):raise AssertionError('Confirmation cannot fit')
    monkeypatch.setattr(Pipeline,'fit',fail_fit)
    confirmed=timeseries.confirm(dataset(tmp_path/'future',history(20)),output,tmp_path/'confirmed')
    assert confirmed['status']=='COMPLETE' and confirmed['commonOriginDays']==7 and not confirmed['productionEligible']
    (output/'prospective.json').write_text('{}')
    with pytest.raises((ValueError,KeyError)):timeseries.confirm(tmp_path/'future',output,tmp_path/'tampered')
