"""Synthetic contract regressions; actual saved-model parity is recorded separately."""
from copy import deepcopy
import math
import numpy as np
import pytest
from fastapi.testclient import TestClient
from wattly_ml.artifacts import save_bundle, load_bundle
from wattly_ml.backtest import predict
from wattly_ml.data import KnownPrices, as_of, digest
from wattly_ml.features import features
from wattly_ml.price_only import PRICE_ONLY_POLICY, completed_price_history
from wattly_ml.serving.app import create_app
from wattly_ml.timebase import instant, floor, targets, STEP
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload

def bundle():
    b=synthetic_bundle(7)
    b['manifest'].update(productionEligible=False,inputPolicy=PRICE_ONLY_POLICY,
        expectedDemandPolicy='ALWAYS_MISSING_AS_TRAINED',researchRecipe='PRICE_183D')
    return b

class InputSensitiveModel:
    def predict(self,x):
        latest,demand,available=x[0][0],x[0][17],x[0][18]
        return [latest+(0 if math.isnan(demand) else demand)+available*1000]

def test_completed_price_recipe_ignores_current_price_solar_and_demand_but_uses_completed_price():
    payload=request_payload();origin=instant(payload['asOf']);boundary=floor(origin)
    past=as_of(payload['priceHistory'],origin)
    demand=[{'targetPeriod':t.isoformat(),'publishedAt':(origin-STEP).isoformat(),
             'availableAt':origin.isoformat(),'demandMw':99999,'source':'SYNTHETIC'} for t in targets(origin)]
    past=KnownPrices(past,demand);past.solar_values={boundary:99999}
    b=bundle();b['models']=[InputSensitiveModel()]*24
    expected=past[boundary-STEP]['usep']
    assert predict(b,past,origin)==[expected]*24
    past[boundary]['usep']=999999
    assert predict(b,past,origin)==[expected]*24
    past[boundary-STEP]['usep']=expected+5
    assert predict(b,past,origin)==[expected+5]*24
    clean=completed_price_history(past,origin)
    assert not hasattr(clean,'demand_forecasts') and not hasattr(clean,'solar_values')

def configure(tmp_path,monkeypatch):
    b=bundle();save_bundle(b,tmp_path/'models','TEST_ONLY_PRICE')
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path))
    monkeypatch.setenv('WATTLY_ALLOW_EXPERIMENTAL_AI','true')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MODEL_VERSION','TEST_ONLY_PRICE')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MANIFEST_SHA256',digest(tmp_path/'models/TEST_ONLY_PRICE/manifest.json'))
    monkeypatch.setenv('WATTLY_SOLAR_SNAPSHOT',str(tmp_path/'does-not-exist.json'))
    return b

def test_real_bundle_http_serves_without_any_solar_file_and_labels_the_recipe(tmp_path,monkeypatch):
    configure(tmp_path,monkeypatch)
    with TestClient(create_app()) as client:
        assert client.get('/ready').json()['servingMode']=='EXPERIMENTAL'
        response=client.post('/forecast',json=request_payload())
        assert response.status_code==200
        data=response.json()
        assert len(data['points'])==24 and data['inputEvidence'] is None
        assert data['manifest']['productionEligible'] is False
        assert {'EXPERIMENTAL_AI','PRICE_CALENDAR_HOLIDAY_ONLY','COMPLETED_PERIOD_PRICE_INPUTS','EXPECTED_DEMAND_OMITTED_BY_RECIPE'}<=set(data['qualityFlags'])
        assert not any('SOLAR' in flag for flag in data['qualityFlags'])
    assert not (tmp_path/'current.json').exists()

def test_missing_completed_period_fails_closed_instead_of_using_current_price(tmp_path,monkeypatch):
    configure(tmp_path,monkeypatch);payload=request_payload();latest=floor(instant(payload['asOf']))-STEP
    payload['priceHistory']=[r for r in payload['priceHistory'] if instant(r['periodStart'])!=latest]
    with TestClient(create_app()) as client:
        assert client.post('/forecast',json=payload).status_code==503

@pytest.mark.parametrize('change',[{'productionEligible':True},{'featureSchemaVersion':1},
    {'inputPolicy':'unknown'},{'expectedDemandPolicy':'USE_LIVE_DEMAND'}])
def test_changed_price_only_contract_is_not_loadable(tmp_path,change):
    b=bundle();b['manifest'].update(change)
    save_bundle(b,tmp_path,'TEST_ONLY_BAD')
    with pytest.raises(ValueError,match='price-only input contract'):load_bundle(tmp_path/'TEST_ONLY_BAD')
