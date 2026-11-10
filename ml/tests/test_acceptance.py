"""Synthetic regression checks, never used as measured forecasting evidence."""
from copy import deepcopy
import json
import math
from pathlib import Path

from fastapi.testclient import TestClient
import pytest
from sklearn.dummy import DummyRegressor

from wattly_ml import cli
from wattly_ml.artifacts import load_bundle, promote, save_bundle
from wattly_ml.data import DatasetRows, KnownPrices, as_of, validate_demand
from wattly_ml.features import ACTIVE_FEATURES, features, holiday_calendar, holiday_digest
from wattly_ml.serving.app import create_app, ForecastRequest
from wattly_ml.spikes import DEFAULT_CONFIG
from wattly_ml.timebase import DAY, STEP, instant, targets
from test_pipeline import history, request_payload


def synthetic_bundle(value=1):
    origin=instant(request_payload()['asOf'])
    estimator=DummyRegressor(strategy='constant',constant=value).fit([[0]*len(ACTIVE_FEATURES)],[value])
    return {'models':[estimator]*24,'manifest':{'schemaVersion':1,'featureSchemaVersion':2,'featureOrder':ACTIVE_FEATURES,
        'holidayCalendarVersion':holiday_calendar()['version'],'holidayCalendarSha256':holiday_digest(),
        'syntheticTestOnly':True,'productionEligible':True,'exploratory':False,'retrospective':False,
        'trainingDate':(origin-3*DAY).isoformat(),'trainingCutoff':(origin-3*DAY).isoformat(),
        'usableFrom':(origin-2*DAY).isoformat(),'validationEnd':(origin-2*DAY).isoformat(),
        'baselineRanking':['B1','B2','B3'],'spikeConfig':DEFAULT_CONFIG,
        'validation':{'table':{'AI':{'mae':1/value}},'commonPairs':10000,'skill':.5}}}


def test_target_demand_is_known_at_origin_and_never_extrapolates_observed_demand():
    rows=history(); origin=instant(rows[-1]['availableAt']); target=targets(origin)[0]
    for row in rows: row['demand']=6000  # Current observed demand is not a forecast for future hours.
    past=as_of(rows,origin)
    assert math.isnan(features(past,origin,target,schema_version=2)[-2])
    first={'targetPeriod':target.isoformat(),'publishedAt':(origin-STEP).isoformat(),
           'availableAt':origin.isoformat(),'demandMw':7000,'source':'SYNTHETIC_TEST_FORECAST'}
    late={**first,'availableAt':(origin+STEP).isoformat(),'demandMw':99999}
    past=as_of(DatasetRows(rows,[validate_demand(first),validate_demand(late)]),origin)
    assert features(past,origin,target,schema_version=2)[-2:]==[7000,1]
    assert math.isnan(features(past,origin,target+STEP,schema_version=2)[-2])
    payload=request_payload(); payload.update(schemaVersion=2,expectedDemand=[late])
    with pytest.raises(ValueError): ForecastRequest.model_validate(payload)
    payload['expectedDemand']=[first,{**first,'demandMw':10}]
    with pytest.raises(ValueError): ForecastRequest.model_validate(payload)


def test_public_holidays_use_versioned_announced_dates_including_substitutes():
    origin=instant('2026-07-01T00:00:00+08:00')
    assert features({},origin,instant('2026-08-10T12:00:00+08:00'),schema_version=2)[-3]==1
    assert features({},origin,instant('2026-08-11T12:00:00+08:00'),schema_version=2)[-3]==0
    assert math.isnan(features({},instant('2025-06-01T00:00:00Z'),instant('2026-01-01T00:00:00Z'),schema_version=2)[-3])
    with pytest.raises(ValueError): features({},origin,origin,schema_version=99)


def test_regular_evening_peak_uses_its_own_four_week_reference():
    from wattly_ml.spikes import reference
    evening=instant('2026-03-01T19:00:00+08:00'); morning=evening-20*STEP
    past={evening-n*DAY:{'usep':200} for n in range(1,29)}
    past.update({morning-n*DAY:{'usep':50} for n in range(1,29)})
    ref=reference(past,evening,evening)
    assert ref['typical']==200 and 200<=ref['threshold']<600
    assert reference(past,morning,morning)['typical']==50


def test_promotion_reload_rollback_and_corrupt_schema_keep_public_service_safe(tmp_path,monkeypatch):
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path))
    a=synthetic_bundle(1); b=synthetic_bundle(2)
    save_bundle(a,tmp_path/'models','TEST_ONLY_A'); save_bundle(b,tmp_path/'models','TEST_ONLY_B')
    promote(tmp_path,'TEST_ONLY_A')
    with TestClient(create_app()) as client:
        assert client.post('/forecast',json=request_payload()).json()['modelVersion']=='TEST_ONLY_A'
        promote(tmp_path,'TEST_ONLY_B',champion_score={'version':'TEST_ONLY_A','validationEnd':b['manifest']['validationEnd'],'mae':1})
        assert client.get('/ready').json()['modelVersion']=='TEST_ONLY_B'
        assert client.post('/forecast',json=request_payload()).json()['points'][0]['predictedUsep']==2
        promote(tmp_path,'TEST_ONLY_A',rollback=True)
        assert client.post('/forecast',json=request_payload()).json()['modelVersion']=='TEST_ONLY_A'
        manifest=tmp_path/'models/TEST_ONLY_A/manifest.json'
        changed=json.loads(manifest.read_text()); changed['featureSchemaVersion']=99
        manifest.write_text(json.dumps(changed))
        with pytest.raises(ValueError): load_bundle(manifest.parent)
        # A changed manifest must invalidate the pointer and fall back through Spring.
        pointer=json.loads((tmp_path/'current.json').read_text());pointer['manifestSha256']='corrupt'
        (tmp_path/'current.json').write_text(json.dumps(pointer))
        assert client.get('/ready').status_code==503
        assert client.post('/forecast',json=request_payload()).status_code==503


def test_every_failed_weekly_attempt_leaves_a_durable_record_before_training(tmp_path,monkeypatch):
    from wattly_ml import operations
    calls=[]
    monkeypatch.setattr(operations,'post_admin',lambda path,value:calls.append(deepcopy(value)) or {'saved':True})
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path/'runtime'))
    status=cli.main(['weekly',str(tmp_path/'missing-dataset'),'--start','2025-01-01T00:00:00Z',
        '--cutoff','2026-01-01T00:00:00Z','--end','2026-02-01T00:00:00Z','--version','TEST_ONLY_FAILED',
        '--output',str(tmp_path/'report')])
    assert status==2 and calls[0]['state']=='RUNNING' and calls[-1]['state']=='FAILED'
    assert calls[-1]['promotionDecision']=='RETAINED'
    saved=json.loads(next((tmp_path/'runtime/training-attempts').glob('*.json')).read_text())
    assert saved==calls[-1] and saved['modelVersion']=='TEST_ONLY_FAILED'


def test_scheduled_data_acquisition_failures_are_recorded(tmp_path,monkeypatch):
    from wattly_ml import operations, scheduled
    calls=[]
    monkeypatch.setattr(operations,'post_admin',lambda path,value:calls.append(deepcopy(value)) or {'saved':True})
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path/'runtime'))
    monkeypatch.setenv('WATTLY_RUN_API_URL','http://localhost:1')
    monkeypatch.setenv('COLLECTOR_DATA_DIR',str(tmp_path/'absent-collector'))
    assert scheduled.main()==2
    assert calls[0]['state']=='RUNNING' and calls[-1]['state']=='FAILED'
    assert 'DATA_ACQUISITION' in [v['promotionDecision'] for v in calls]


def test_audit_spool_recovers_abandoned_attempt_and_syncs_exact_final_state(tmp_path,monkeypatch):
    from argparse import Namespace
    from datetime import datetime,timezone,timedelta
    from wattly_ml import operations
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path))
    args=Namespace(output=tmp_path/'report',version='TEST_ONLY_RECOVERY',start=instant('2025-01-01T00:00:00Z'),
        cutoff=instant('2026-01-01T00:00:00Z'),end=instant('2026-02-01T00:00:00Z'),dataset=tmp_path/'dataset')
    monkeypatch.setattr(operations,'post_admin',lambda *a:False)
    attempt=operations.TrainingAttempt(args)
    attempt.finish('FAILED','API unavailable')
    assert attempt.path.with_suffix('.pending').exists()
    final=json.loads(attempt.path.read_text())
    calls=[]
    monkeypatch.setenv('WATTLY_RUN_API_URL','http://localhost:1')
    monkeypatch.setattr(operations,'post_admin',lambda path,value:calls.append(deepcopy(value)) or {'saved':True})
    assert operations.sync_runs(tmp_path)=={'synced':1}
    assert calls==[final] and not attempt.path.with_suffix('.pending').exists()
    attempt.value.update(state='RUNNING',trainingDate=(datetime.now(timezone.utc)-timedelta(hours=2)).isoformat())
    attempt.save()
    operations.sync_runs(tmp_path)
    assert calls[-1]['state']=='FAILED' and calls[-1]['promotionDecision']=='RETAINED'
    assert 'Abandoned' in calls[-1]['reason']


def test_inflight_request_keeps_one_model_identity_during_concurrent_reload(monkeypatch):
    from wattly_ml import timebase
    first=synthetic_bundle(1);first['manifest']['version']='TEST_ONLY_OLD'
    second=synthetic_bundle(2);second['manifest']['version']='TEST_ONLY_NEW'
    app=create_app(loader=lambda:first)
    payload=request_payload()
    with TestClient(app) as client:
        def reload_after_manifest_read(value):
            if value==first['manifest']['usableFrom']:
                app.state.bundle=second
            return instant(value)
        monkeypatch.setattr(timebase,'instant',reload_after_manifest_read)
        response=client.post('/forecast',json=payload)
        assert response.status_code==200
        assert response.json()['modelVersion']=='TEST_ONLY_OLD'
        assert response.json()['points'][0]['predictedUsep']==1
        assert app.state.bundle['manifest']['version']=='TEST_ONLY_NEW'


def test_savings_replay_sends_only_saved_forecast_spikes_never_future_truth(tmp_path,monkeypatch):
    from wattly_ml import household_replay
    origin=instant('2026-08-01T00:00:00+08:00')
    stamp=origin.isoformat()
    rows=[{'asOf':stamp,'targetPeriod':target.isoformat(),'horizon':horizon,'method':method,
           'predictedUsep':100,'actual':500,'spikeFlag':horizon==1,'actualSpike':True,
           'truthPolicy':'FINAL','exclusionReason':None}
          for method in ('AI','B1') for horizon,target in enumerate(targets(origin),1)]
    predictions=tmp_path/'predictions.jsonl'
    predictions.write_text(''.join(json.dumps(row)+'\n' for row in rows))
    validation=tmp_path/'validation.json'
    validation.write_text(json.dumps({'datasetDigest':'synthetic-test','end':(origin-DAY).isoformat()}))
    model=tmp_path/'manifest.json'
    model.write_text(json.dumps({'datasetDigest':'synthetic-test','baselineRanking':['B1'],
        'usableFrom':(origin-DAY).isoformat(),'version':'SYNTHETIC_TEST_ONLY'}))
    calls=[]
    def replay(path,payload):
        calls.append(payload)
        return {'household':{'id':1},'exclusions':{},'fixedRateNotShifted':False,'cases':[]}
    monkeypatch.setattr(household_replay,'post_admin',replay)
    monkeypatch.setattr(household_replay,'report',lambda *args:None)
    household_replay.study(predictions,validation,model,[1],tmp_path/'report')
    assert len(calls)==1
    for method in ('ai','baseline'):
        assert [p['spike'] for p in calls[0][method]]==[True]+[False]*23
    assert all(p['spike'] is False for p in calls[0]['actual'])


def test_fitted_demand_aware_model_consumes_the_correct_target_vintage_over_http():
    # Synthetic plumbing proof only: never a real accuracy or savings claim.
    import numpy as np
    from sklearn.impute import SimpleImputer
    from sklearn.pipeline import make_pipeline
    from sklearn.tree import DecisionTreeRegressor
    payload=request_payload();origin=instant(payload['asOf']);payload['schemaVersion']=2
    past=KnownPrices({instant(r['periodStart']):r for r in payload['priceHistory']})
    def demands(mw):
        return [{'targetPeriod':t.isoformat(),'publishedAt':(origin-STEP).isoformat(),
                 'availableAt':origin.isoformat(),'demandMw':mw,'source':'SYNTHETIC_TEST_FORECAST'} for t in targets(origin)]
    x=[];y=[]
    for mw,price in [(6000,100),(7000,200)]:
        past.demand_forecasts=demands(mw)
        x.append(features(past,origin,targets(origin)[0],schema_version=2));y.append(price)
    model=make_pipeline(SimpleImputer(keep_empty_features=True),DecisionTreeRegressor(random_state=42)).fit(np.asarray(x),y)
    bundle=synthetic_bundle();bundle['models']=[model]*24;bundle['manifest']['version']='SYNTHETIC_DEMAND_HTTP_TEST'
    with TestClient(create_app(lambda:bundle)) as client:
        for mw,expected in [(6000,100),(7000,200)]:
            payload['expectedDemand']=demands(mw)
            response=client.post('/forecast',json=payload)
            assert response.status_code==200
            assert all(p['predictedUsep']==expected for p in response.json()['points'])
            assert 'EXPECTED_DEMAND_PARTIAL_OR_MISSING' not in response.json()['qualityFlags']
        payload['expectedDemand'][0]['availableAt']=(origin+STEP).isoformat()
        assert client.post('/forecast',json=payload).status_code==422
