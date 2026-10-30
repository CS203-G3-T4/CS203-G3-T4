from copy import deepcopy
from datetime import datetime, timedelta
import json
import math

import numpy as np
import pytest
from fastapi.testclient import TestClient
from sklearn.dummy import DummyRegressor
from wattly_ml.artifacts import load_bundle, save_bundle
from wattly_ml.data import KnownPrices, digest
from wattly_ml.features import feature_names, features
from wattly_ml.serving.app import create_app
from wattly_ml.solar import BASE_URL, SOLAR_POLICY, label_time, read_solar_snapshot
from wattly_ml.timebase import DAY, STEP, SGT, floor, instant, targets
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload


def solar_value(t):
    return float(100+t.astimezone(SGT).hour*2+t.minute//30)


def snapshot(as_of):
    local=as_of.astimezone(SGT); today=local.replace(hour=0,minute=0,second=0,microsecond=0)
    responses=[]
    for value,start,count,updated in ((12,today-12*timedelta(hours=1),72,floor(as_of)+timedelta(minutes=1)),
                                      (14,today-7*DAY,336,today-STEP)):
        periods=[start+i*STEP for i in range(count)]
        panel={'lastupdate':updated.astimezone(SGT).strftime('%d %b %Y %H:%M'),
               'currentdate':floor(as_of).astimezone(SGT).strftime('%d %b %Y %H:%M')+'-'+(floor(as_of)+STEP).astimezone(SGT).strftime('%H:%M'),
               'labels':[t.strftime('%d %b %H:%M')+'-'+(t+STEP).strftime('%H:%M') for t in periods],
               'datasets':[{'label':'Demand','data':['6000']*count},
                           {'label':'Solar','data':[str(6000+solar_value(t)) for t in periods]}]}
        responses.append({'value':value,'url':BASE_URL+str(value)+'&fromDate=&toDate=',
                          'receivedAt':(as_of-timedelta(seconds=1)).isoformat(),
                          'body':{'success':True,'data':{'data':[panel]}}})
    return {'schemaVersion':1,'responses':responses}


def write_snapshot(tmp_path,data):
    p=tmp_path/'solar.json';p.write_text(json.dumps(data));return p


def test_stacked_solar_is_subtracted_and_future_forecasts_are_not_used(tmp_path):
    now=instant('2026-01-10T04:02:00+08:00');s=snapshot(now)
    p=write_snapshot(tmp_path,s);values,e=read_solar_snapshot(p,now)
    latest=floor(now)-STEP
    assert values[latest]==solar_value(latest) and e['requiredPeriods']==7
    assert latest-DAY in values and floor(now) not in values
    panel=s['responses'][0]['body']['data']['data'][0]
    for i,label in enumerate(panel['labels']):
        if label_time(label,now.astimezone(SGT))>=floor(now):
            panel['datasets'][1]['data'][i]='999999999'
    assert read_solar_snapshot(write_snapshot(tmp_path,s),now)[0]==values


@pytest.mark.parametrize('kind',['future_receipt','old_receipt','old_update','missing_yesterday','negative_solar','duplicate_label','wrong_source'])
def test_unusable_solar_inputs_fail_closed(tmp_path,kind):
    now=instant('2026-01-10T04:02:00+08:00');s=snapshot(now)
    item=s['responses'][0];panel=item['body']['data']['data'][0]
    if kind=='future_receipt':item['receivedAt']=(now+STEP).isoformat()
    if kind=='old_receipt':item['receivedAt']=(now-DAY).isoformat()
    if kind=='old_update':panel['lastupdate']=(now-3*STEP).astimezone(SGT).strftime('%d %b %Y %H:%M')
    if kind=='negative_solar':panel['datasets'][1]['data']=['1']*72
    if kind=='duplicate_label':panel['labels'][1]=panel['labels'][0]
    if kind=='wrong_source':item['url']='https://example.com/solar'
    if kind=='missing_yesterday':
        daily=s['responses'][1]['body']['data']['data'][0]
        wanted=floor(now)-STEP-DAY
        idx=next(i for i,x in enumerate(daily['labels']) if label_time(x,now.astimezone(SGT))==wanted)
        daily['datasets'][1]['data'][idx]='-'
    with pytest.raises(ValueError):read_solar_snapshot(write_snapshot(tmp_path,s),now)


def test_solar_recipe_uses_completed_prices_and_keeps_demand_missing(tmp_path):
    payload=request_payload();origin=instant(payload['asOf']);boundary=floor(origin)
    past=KnownPrices({instant(r['periodStart']):r for r in payload['priceHistory']})
    past.solar_values={boundary-STEP-i*STEP:float(i*2+10) for i in range(6)}
    past.solar_values[boundary-STEP-DAY]=3.
    expected=features(past,origin,targets(origin)[0],schema_version=3)
    assert len(expected)==len(feature_names(3))==30
    assert expected[0]==expected[1]==past[boundary-STEP]['usep']
    assert math.isnan(expected[17]) and expected[18]==0
    np.testing.assert_allclose(expected[19:],[10,12,14,20,15,np.std([10,12,14,16,18,20]),-2,-4,-10,7,6])
    past[boundary]['usep']=999999
    past.demand_forecasts=[{'targetPeriod':targets(origin)[0].isoformat(),'publishedAt':origin.isoformat(),
                           'availableAt':origin.isoformat(),'demandMw':999999}]
    np.testing.assert_allclose(features(past,origin,targets(origin)[0],schema_version=3),expected,equal_nan=True)
    del past.solar_values[boundary-2*STEP]
    with pytest.raises(ValueError):features(past,origin,targets(origin)[0],schema_version=3)


def test_year_boundary_labels_are_resolved_without_shifting_periods(tmp_path):
    now=instant('2027-01-01T04:02:00+08:00')
    values,_=read_solar_snapshot(write_snapshot(tmp_path,snapshot(now)),now)
    assert instant('2026-12-31T03:30:00+08:00') in values
    assert max(values)==instant('2027-01-01T03:30:00+08:00')


def test_old_dispatch_forecasts_never_turn_into_observed_history(tmp_path):
    original=instant('2026-01-10T04:29:00+08:00')
    now=instant('2026-01-10T05:02:00+08:00')
    data=snapshot(original)
    for item in data['responses']:
        item['receivedAt']=(now-timedelta(seconds=1)).isoformat()
    panel=data['responses'][0]['body']['data']['data'][0]
    panel['lastupdate']=original.astimezone(SGT).strftime('%d %b %Y %H:%M')
    with pytest.raises(ValueError,match='Required recent/yesterday'):
        read_solar_snapshot(write_snapshot(tmp_path,data),now)


def test_solar_http_serving_requires_opt_in_and_fresh_complete_inputs(tmp_path,monkeypatch):
    payload=request_payload();origin=instant(payload['asOf']);b=synthetic_bundle(9)
    b['models']=[DummyRegressor(strategy='constant',constant=9).fit([[0]*30],[9])]*24
    b['manifest'].update(featureSchemaVersion=3,featureOrder=feature_names(3),inputPolicy=SOLAR_POLICY,
                         productionEligible=False,exploratory=True)
    save_bundle(b,tmp_path/'models','solar-test')
    model_path=tmp_path/'models/solar-test'
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path));monkeypatch.setenv('WATTLY_ALLOW_EXPERIMENTAL_AI','true')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MODEL_VERSION','solar-test')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MANIFEST_SHA256',digest(model_path/'manifest.json'))
    solar_path=write_snapshot(tmp_path,snapshot(origin));monkeypatch.setenv('WATTLY_SOLAR_SNAPSHOT',str(solar_path))
    with TestClient(create_app()) as client:
        response=client.post('/forecast',json=payload)
        assert response.status_code==200
        data=response.json();assert len(data['points'])==24 and data['modelVersion']=='solar-test'
        assert data['manifest']['productionEligible'] is False
        assert data['inputEvidence']['source']=='EMC_PUBLIC_PROVISIONAL_SOLAR'
        assert 'COMPLETED_PERIOD_PRICE_INPUTS' in data['qualityFlags']
        raw=snapshot(origin)
        panel=raw['responses'][0]['body']['data']['data'][0]
        required={instant(r['periodStart']) for r in data['inputEvidence']['periods']}
        unused=next(i for i,label in enumerate(panel['labels'])
                    if label_time(label,origin.astimezone(SGT)) not in required
                    and label_time(label,origin.astimezone(SGT))+STEP<=floor(origin))
        panel['datasets'][1]['data'][unused]='99999999'
        write_snapshot(tmp_path,raw)
        unchanged=client.post('/forecast',json=payload)
        assert unchanged.status_code==200
        assert unchanged.json()['points']==data['points']
        assert unchanged.json()['inputEvidence']['periods']==data['inputEvidence']['periods']
        wanted=floor(origin)-STEP
        required_idx=next(i for i,label in enumerate(panel['labels']) if label_time(label,origin.astimezone(SGT))==wanted)
        panel['datasets'][1]['data'][required_idx]='-'
        write_snapshot(tmp_path,raw)
        rejected=client.post('/forecast',json=payload)
        assert rejected.status_code==503 and rejected.json()['detail']['code']=='SOLAR_INPUT_UNAVAILABLE'
        solar_path.unlink()
        response=client.post('/forecast',json=payload)
        assert response.status_code==503 and response.json()['detail']['code']=='SOLAR_INPUT_UNAVAILABLE'
    monkeypatch.delenv('WATTLY_ALLOW_EXPERIMENTAL_AI')
    with TestClient(create_app()) as client:
        assert client.post('/forecast',json=payload).status_code==503
    assert not (tmp_path/'current.json').exists()
    m=json.loads((model_path/'manifest.json').read_text());m['productionEligible']=True
    (model_path/'manifest.json').write_text(json.dumps(m))
    with pytest.raises(ValueError):load_bundle(model_path)


def with_today(now):
    data=snapshot(now)
    local=now.astimezone(SGT);today=local.replace(hour=0,minute=0,second=0,microsecond=0)
    # Real forward panel rolls to today's noon, excluding recent completed periods.
    forward=data['responses'][0]['body']['data']['data'][0]
    start=today.replace(hour=12)
    forward['labels']=[(start+i*STEP).strftime('%d %b %H:%M')+'-'+(start+(i+1)*STEP).strftime('%H:%M') for i in range(72)]
    forward['datasets'][1]['data']=[str(6000+solar_value(start+i*STEP)) for i in range(72)]
    item=deepcopy(data['responses'][0]);item['value']=10;item['url']=BASE_URL+'10&fromDate=&toDate='
    panel=item['body']['data']['data'][0]
    panel['labels']=[(today+i*STEP).strftime('%d %b %H:%M')+'-'+(today+(i+1)*STEP).strftime('%H:%M') for i in range(48)]
    panel['datasets'][0]['data']=['6000']*48
    panel['datasets'][1]['data']=[str(6000+solar_value(today+i*STEP)) for i in range(48)]
    data['responses'].append(item)
    return data


@pytest.mark.parametrize('text',['2026-10-30T12:03:00+08:00','2026-10-30T23:33:00+08:00','2027-01-01T00:03:00+08:00'])
def test_today_panel_repairs_forward_rollover_without_using_future_solar(tmp_path,text):
    now=instant(text);data=with_today(now)
    values,evidence=read_solar_snapshot(write_snapshot(tmp_path,data),now)
    latest=floor(now)-STEP
    assert values[latest]==solar_value(latest) and len(evidence['periods'])==7
    assert all(instant(p['periodStart'])+STEP<=floor(now) for p in evidence['periods'])
    assert floor(now) not in values
    # No contribution from future dispatch targets even when artificially extreme.
    p=data['responses'][-1]['body']['data']['data'][0]
    for i,label in enumerate(p['labels']):
        if label_time(label,now.astimezone(SGT))>=floor(now):p['datasets'][1]['data'][i]='99999999'
    assert read_solar_snapshot(write_snapshot(tmp_path,data),now)[0]==values


def test_today_source_cannot_hide_stale_or_future_receipts(tmp_path):
    now=instant('2026-10-30T12:03:00+08:00')
    for key,value in [('receivedAt',(now+STEP).isoformat()),('receivedAt',(now-DAY).isoformat()),('url','https://example.com')]:
        data=with_today(now);data['responses'][-1][key]=value
        with pytest.raises(ValueError):read_solar_snapshot(write_snapshot(tmp_path,data),now)


@pytest.mark.parametrize('bad_value',['999999','-','NaN','1'])
@pytest.mark.parametrize('panel_value',[12,14])
def test_required_overlaps_cannot_replace_or_hide_bad_values(tmp_path,bad_value,panel_value):
    now=instant('2026-01-10T13:02:00+08:00');data=snapshot(now)
    # Yesterday's 12:30 period is required and appears in both source panels.
    # Corrupt either the first or last panel processed: neither may hide the other.
    panel=next(r for r in data['responses'] if r['value']==panel_value)['body']['data']['data'][0]
    wanted=floor(now)-STEP-DAY
    idx=next(i for i,label in enumerate(panel['labels']) if label_time(label,now.astimezone(SGT))==wanted)
    panel['datasets'][1]['data'][idx]=bad_value
    with pytest.raises(ValueError,match='EMC solar value'):
        read_solar_snapshot(write_snapshot(tmp_path,data),now)


@pytest.mark.parametrize('bad_value',['999999','-','NaN'])
def test_unused_historical_disagreements_cannot_veto_or_enter_recipe(tmp_path,bad_value):
    now=instant('2026-01-10T04:02:00+08:00');data=snapshot(now)
    expected,evidence=read_solar_snapshot(write_snapshot(tmp_path,data),now)
    panel=data['responses'][0]['body']['data']['data'][0]
    unused=label_time(panel['labels'][0],now.astimezone(SGT))
    assert unused not in expected
    panel['datasets'][1]['data'][0]=bad_value
    values,actual=read_solar_snapshot(write_snapshot(tmp_path,data),now)
    assert values==expected and actual['periods']==evidence['periods']
    assert set(values)=={floor(now)-STEP-i*STEP for i in range(6)}|{floor(now)-STEP-DAY}


def test_equal_overlapping_solar_values_are_accepted(tmp_path):
    now=instant('2026-01-10T04:02:00+08:00')
    values,_=read_solar_snapshot(write_snapshot(tmp_path,snapshot(now)),now)
    assert values[floor(now)-STEP]==solar_value(floor(now)-STEP)
