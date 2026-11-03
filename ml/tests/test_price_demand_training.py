"""Synthetic training/HTTP plumbing, never real accuracy or savings evidence."""
from copy import deepcopy
import json
import math
from pathlib import Path
import shutil
import sys

import pytest
from fastapi.testclient import TestClient
from sklearn.tree import DecisionTreeRegressor

from wattly_ml.artifacts import current_bundle, digest, load_bundle, promote, save_bundle
from wattly_ml.backtest import predict
from wattly_ml.baselines import baselines
from wattly_ml.demand_candidate import PARAMETERS, identity
from wattly_ml.features import features
from wattly_ml.price_demand import PRICE_DEMAND_POLICY, DEMAND_POLICY, RESEARCH_RECIPE
from wattly_ml.price_demand_readiness import snapshot_inputs
from wattly_ml.price_demand_training import fit, evaluate, protocol_check, main
from wattly_ml.price_only import PRICE_ONLY_POLICY
from wattly_ml.serving.app import create_app
from wattly_ml.timebase import DAY, STEP, floor, instant, targets
from test_acceptance import synthetic_bundle
from test_price_demand_readiness import issue as input_issue, ORIGIN as INPUT_ORIGIN

ORIGIN=instant('2026-11-09T00:02:00+08:00')
EVIDENCE='EMC_RT_20260929_24_PERIODS'

def issue(origin,number):
    row=input_issue();offset=origin-INPUT_ORIGIN
    row.update(id='SYNTHETIC_'+str(number),asOf=origin.isoformat(),syntheticTestOnly=True,
        modelVersion='synthetic-price-only')
    for value in row['inputs']['priceHistory']:
        for k in ('periodStart','sourceUpdatedAt','availableAt'):
            value[k]=(instant(value[k])+offset).isoformat()
    for value in row['inputs']['expectedDemand']:
        for k in ('targetPeriod','publishedAt','availableAt'):
            value[k]=(instant(value[k])+offset).isoformat()
    return row

def prepare(tmp):
    bundle=synthetic_bundle(100)
    bundle['manifest'].update(productionEligible=False,inputPolicy=PRICE_ONLY_POLICY,
        expectedDemandPolicy='ALWAYS_MISSING_AS_TRAINED',researchRecipe='PRICE_183D',
        trainingDate=(ORIGIN-2*DAY).isoformat(),usableFrom=(ORIGIN-DAY).isoformat())
    save_bundle(bundle,tmp/'frozen','synthetic-price-only')
    frozen=tmp/'frozen/synthetic-price-only';start=floor(ORIGIN)
    protocol=dict(id='SYNTHETIC_ONLY_PRICE_DEMAND',status='REGISTERED',kind=RESEARCH_RECIPE,syntheticTestOnly=True,
        registeredAt=(start-DAY).isoformat(),trainStart=start.isoformat(),trainCutoff=(start+3*DAY).isoformat(),
        testStart=(start+4*DAY).isoformat(),testEnd=(start+18*DAY).isoformat(),
        minimumTrainingOrigins=48,minimumTestOrigins=48,parameters=PARAMETERS.copy(),
        spikeConfig=dict(minimumSamples=14,k=3.,spreadFloor=1.),truthPolicy='PROVISIONAL',
        periodMappingEvidence=EVIDENCE,frozenModelVersion='synthetic-price-only',
        frozenModelManifestSha256=digest(frozen/'manifest.json'))
    issues=[issue(ORIGIN+n*STEP,n) for n in range(48)]
    issues += [issue(ORIGIN+4*DAY+n*STEP,n+48) for n in range(48)]
    truth={}
    for row in issues:
        for t in targets(instant(row['asOf'])):
            truth[t]=dict(periodStart=t.isoformat(),sourceUpdatedAt=(t+STEP).isoformat(),
                availableAt=(t+STEP).isoformat(),priceStatus='PROVISIONAL',timeMapping='EMC_PERIOD',
                source='SYNTHETIC_TEST',usep=100+t.hour*2+t.minute/30)
    return issues,list(truth.values()),protocol,frozen

@pytest.fixture(scope='module')
def trained(tmp_path_factory):
    tmp=tmp_path_factory.mktemp('price-demand-contract')
    issues,prices,protocol,frozen=prepare(tmp)
    fit_issues=[r if instant(r['asOf'])<instant(protocol['trainCutoff']) else {**r,'inputs':'DO_NOT_READ_HOLDOUT'} for r in issues]
    future={**prices[-1],'usep':'DO_NOT_READ_FUTURE_OUTCOME'}
    result=fit(fit_issues,[*prices,future],protocol,protocol['trainCutoff'],tmp/'registry',frozen)
    experiment=Path(result['experiment'])
    summary=evaluate(issues,prices,protocol,protocol['testEnd'],experiment,frozen)
    return tmp,issues,prices,protocol,frozen,result,summary

def test_full_synthetic_fit_load_evaluate_uses_exact_common_pairs_and_real_baseline_inputs(trained):
    tmp,issues,prices,protocol,frozen,result,summary=trained
    experiment=Path(result['experiment'])
    demand=load_bundle(experiment/'models/price-demand-v1')
    control=load_bundle(experiment/'models/price-refit-v1')
    assert len(demand['models'])==len(control['models'])==24
    assert demand['manifest']['inputPolicy']==PRICE_DEMAND_POLICY
    assert control['manifest']['inputPolicy']==PRICE_ONLY_POLICY
    assert demand['manifest']['trainingInputsSha256']==control['manifest']['trainingInputsSha256']
    assert result['trainingOrigins']==48 and result['holdoutInspected'] is False
    assert summary['commonOrigins']==48 and summary['commonPairs']==48*24
    assert summary['syntheticTestOnly'] and not summary['automaticPromotion']
    assert summary['savingsValidation']=='PENDING_REAL_F4_REPLAY'
    records=[json.loads(line) for line in (experiment/'evaluation/predictions.jsonl').read_text().splitlines()]
    methods={r['method'] for r in records}
    pairs=[{(r['asOf'],r['targetPeriod'],r['horizon']) for r in records if r['method']==m} for m in methods]
    assert len(methods)==6 and all(p==pairs[0] for p in pairs)
    assert len(records)==48*24*6 and all(summary['table'][m]['all']['pairs']==48*24 for m in methods)
    first=next(row for row in issues if instant(row['asOf'])>=instant(protocol['testStart']))
    past,_=snapshot_inputs(first,EVIDENCE)
    b1=next(r for r in records if r['method']=='B1')
    assert b1['predictedUsep']==baselines(past.as_issued_prices,instant(first['asOf']))['B1'][0]
    assert b1['predictedUsep']>100000  # Deliberately extreme current quote was retained for baseline B1.
    assert not (tmp/'current.json').exists()

def test_fit_and_evaluation_are_claimed_once_and_new_attempt_cannot_overwrite(trained):
    tmp,issues,prices,p,frozen,result,_=trained;experiment=Path(result['experiment'])
    before=(experiment/'fit-attempt.json').read_bytes()
    with pytest.raises(ValueError,match='claimed'):fit(issues,prices,p,p['trainCutoff'],tmp/'registry',frozen)
    with pytest.raises(FileExistsError):evaluate(issues,prices,p,p['testEnd'],experiment,frozen)
    assert (experiment/'fit-attempt.json').read_bytes()==before

def test_future_or_missing_truth_does_not_complete_support(tmp_path):
    issues,prices,p,frozen=prepare(tmp_path)
    with pytest.raises(ValueError,match='Insufficient complete training'):
        fit(issues,[],p,p['trainCutoff'],tmp_path/'registry',frozen)
    for row in prices:row['availableAt']=p['testEnd']
    with pytest.raises(ValueError,match='Insufficient complete training'):
        fit(issues,prices,p,p['trainCutoff'],tmp_path/'registry',frozen)
    assert not (tmp_path/'registry').exists()

def test_early_late_fit_early_score_and_draft_protocol_fail_before_outputs(tmp_path):
    issues,prices,p,frozen=prepare(tmp_path)
    for at in (p['trainStart'],p['testStart']):
        with pytest.raises(ValueError,match='Fit only'):fit(issues,prices,p,at,tmp_path/'registry',frozen)
    with pytest.raises(ValueError,match='Untouched holdout'):evaluate([],[],p,p['testStart'],tmp_path/'missing',frozen)
    p['status']='DRAFT'
    with pytest.raises(ValueError,match='registered'):fit(issues,prices,p,p['trainCutoff'],tmp_path/'registry',frozen)
    assert not (tmp_path/'registry').exists()

@pytest.mark.parametrize('change',[{'minimumTrainingOrigins':47},{'parameters':{}},
    {'registeredAt':'2026-11-10T00:00:00+08:00'},{'syntheticTestOnly':'yes'},
    {'frozenModelManifestSha256':'no-pin'}])
def test_protocol_rejects_changed_recipe_and_registration(tmp_path,change):
    _,_,p,_=prepare(tmp_path);p.update(change)
    with pytest.raises(ValueError):protocol_check(p)

def test_real_protocol_cannot_reuse_short_synthetic_support_or_reserved_period(tmp_path):
    _,_,p,_=prepare(tmp_path);p['syntheticTestOnly']=False
    with pytest.raises(ValueError):protocol_check(p)
    p.update(minimumTrainingOrigins=480,minimumTestOrigins=480,
        trainCutoff=(floor(ORIGIN)+14*DAY).isoformat(),testStart=(floor(ORIGIN)+15*DAY).isoformat(),
        testEnd=(floor(ORIGIN)+29*DAY).isoformat())
    protocol_check(p)
    p['trainStart']='2026-11-01T00:00:00+08:00';p['registeredAt']='2026-10-31T00:00:00+08:00'
    with pytest.raises(ValueError):protocol_check(p)

def test_failed_fit_is_retained_and_cannot_be_automatically_retried(tmp_path,monkeypatch):
    from sklearn.ensemble import HistGradientBoostingRegressor
    issues,prices,p,frozen=prepare(tmp_path)
    def broken(*args,**kwargs):raise RuntimeError('synthetic failure')
    monkeypatch.setattr(HistGradientBoostingRegressor,'fit',broken)
    with pytest.raises(RuntimeError):fit(issues,prices,p,p['trainCutoff'],tmp_path/'registry',frozen)
    experiment=tmp_path/'registry'/identity(p)
    assert json.loads((experiment/'fit-attempt.json').read_text())['state']=='FAILED'
    with pytest.raises(ValueError,match='claimed'):fit(issues,prices,p,p['trainCutoff'],tmp_path/'registry',frozen)

def test_postfit_artifact_changes_and_frozen_model_changes_are_detected(trained,tmp_path):
    _,issues,prices,p,frozen,result,_=trained
    copy=tmp_path/'copy';shutil.copytree(result['experiment'],copy)
    manifest=copy/'models/price-demand-v1/manifest.json'
    data=json.loads(manifest.read_text());data['trainingOrigins']=999
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError,match='manifest changed'):evaluate(issues,prices,p,p['testEnd'],copy,frozen)
    changed=deepcopy(p);changed['frozenModelManifestSha256']='0'*64
    with pytest.raises(ValueError,match='Frozen model manifest changed'):
        fit(issues,prices,changed,changed['trainCutoff'],tmp_path/'other',frozen)

def candidate_bundle():
    b=synthetic_bundle(100)
    b['manifest'].update(productionEligible=False,inputPolicy=PRICE_DEMAND_POLICY,
        expectedDemandPolicy=DEMAND_POLICY,researchRecipe=RESEARCH_RECIPE,
        trainingDate=(ORIGIN-2*DAY).isoformat(),usableFrom=(ORIGIN-DAY).isoformat())
    row=issue(ORIGIN,1);past,_=snapshot_inputs(row,EVIDENCE)
    low=features(past,ORIGIN,targets(ORIGIN)[0],schema_version=2);high=low.copy();high[17]=9000
    tree=DecisionTreeRegressor(random_state=42).fit([low,high],[100,300])
    b['models']=[tree]*24
    return b,row

def configure(tmp,monkeypatch):
    b,row=candidate_bundle();save_bundle(b,tmp/'models','SYNTHETIC_PRICE_DEMAND')
    model=tmp/'models/SYNTHETIC_PRICE_DEMAND'
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp));monkeypatch.setenv('WATTLY_ALLOW_EXPERIMENTAL_AI','true')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MODEL_VERSION',model.name)
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MANIFEST_SHA256',digest(model/'manifest.json'))
    monkeypatch.setenv('WATTLY_SOLAR_SNAPSHOT',str(tmp/'missing-solar-file'))
    payload=dict(schemaVersion=2,requestId=row['id'],asOf=row['asOf'],intervalMinutes=30,horizonCount=24,
        mappingVerified=True,expectedDemand=row['inputs']['expectedDemand'],priceHistory=[
            {k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')} for r in row['inputs']['priceHistory']])
    return b,payload

def test_pinned_http_loads_no_solar_bundle_and_uses_the_same_demand_sensitive_prediction_path(tmp_path,monkeypatch):
    _,payload=configure(tmp_path,monkeypatch)
    with TestClient(create_app()) as client:
        assert client.get('/ready').json()['servingMode']=='EXPERIMENTAL'
        first=client.post('/forecast',json=payload);assert first.status_code==200
        data=first.json();assert data['points'][0]['predictedUsep']==100
        assert data['inputEvidence'] is None and not any('SOLAR' in f for f in data['qualityFlags'])
        assert {'COMPLETED_PERIOD_PRICE_INPUTS','AS_ISSUED_EXPECTED_DEMAND','EXPERIMENTAL_AI'}<=set(data['qualityFlags'])
        payload['expectedDemand'][0]['demandMw']=9000
        second=client.post('/forecast',json=payload)
        assert second.status_code==200 and second.json()['points'][0]['predictedUsep']==300
        payload['priceHistory'][-1]['usep']=12345678
        assert client.post('/forecast',json=payload).json()['points'][0]['predictedUsep']==300
        payload['expectedDemand'].pop()
        assert client.post('/forecast',json=payload).status_code==503
    assert not (tmp_path/'current.json').exists()

def test_approved_mode_cannot_serve_demand_candidate_even_via_test_loader():
    b,row=candidate_bundle()
    with TestClient(create_app(loader=lambda:b)) as client:
        payload=dict(schemaVersion=2,requestId=row['id'],asOf=row['asOf'],intervalMinutes=30,horizonCount=24,
            mappingVerified=True,expectedDemand=row['inputs']['expectedDemand'],priceHistory=[
                {k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')} for r in row['inputs']['priceHistory']])
        assert client.post('/forecast',json=payload).status_code==503

@pytest.mark.parametrize('change',[{'productionEligible':True},{'featureSchemaVersion':1},
    {'inputPolicy':'unknown'},{'expectedDemandPolicy':'ALWAYS_MISSING_AS_TRAINED'},{'researchRecipe':'unknown'}])
def test_invalid_candidate_manifest_is_not_loadable(tmp_path,change):
    b,_=candidate_bundle();b['manifest'].update(change)
    save_bundle(b,tmp_path,'SYNTHETIC_BAD')
    with pytest.raises(ValueError,match='price-demand input contract'):load_bundle(tmp_path/'SYNTHETIC_BAD')

def test_cli_checks_deadline_before_opening_heldout_exports(tmp_path,monkeypatch):
    _,_,p,frozen=prepare(tmp_path);path=tmp_path/'protocol.json';path.write_text(json.dumps(p))
    monkeypatch.setattr(sys,'argv',['trainer','evaluate','--issues',str(tmp_path/'not-read-issues'),
        '--prices',str(tmp_path/'not-read-outcomes'),'--protocol',str(path),'--as-of',p['testStart'],
        '--experiment',str(tmp_path/'not-read-experiment'),'--frozen-model',str(frozen)])
    with pytest.raises(SystemExit) as error:main()
    assert error.value.code==2


@pytest.mark.parametrize('failure',['missing','future','conflict','negative','missing_completed'])
def test_shared_prediction_recipe_rejects_invalid_demand_without_imputing(failure):
    b,row=candidate_bundle();past,_=snapshot_inputs(row,EVIDENCE)
    if failure=='missing':past.demand_forecasts=[]
    if failure=='future':past.demand_forecasts[0]['availableAt']=(ORIGIN+STEP).isoformat()
    if failure=='conflict':past.demand_forecasts.append({**past.demand_forecasts[0],'demandMw':1})
    if failure=='negative':past.demand_forecasts[0]['demandMw']=-1
    if failure=='missing_completed':past.pop(floor(ORIGIN)-STEP)
    with pytest.raises(ValueError):predict(b,past,ORIGIN)


def test_candidate_cannot_be_promoted_or_loaded_as_approved(tmp_path):
    b,_=candidate_bundle();save_bundle(b,tmp_path/'models','SYNTHETIC_PRICE_DEMAND')
    path=tmp_path/'models/SYNTHETIC_PRICE_DEMAND'
    (tmp_path/'current.json').write_text(json.dumps(dict(version=path.name,manifestSha256=digest(path/'manifest.json'))))
    before=(tmp_path/'current.json').read_bytes()
    with pytest.raises(ValueError,match='Exploratory'):current_bundle(tmp_path)
    with pytest.raises(ValueError):promote(tmp_path,path.name)
    assert (tmp_path/'current.json').read_bytes()==before
