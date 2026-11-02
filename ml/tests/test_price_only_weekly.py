from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys

import numpy as np
import pytest
from fastapi.testclient import TestClient
from sklearn.dummy import DummyRegressor
from sklearn.impute import SimpleImputer
from sklearn.pipeline import make_pipeline

from wattly_ml import price_only_weekly as weekly
from wattly_ml.artifacts import load_bundle, save_bundle
from wattly_ml.backtest import History, predict
from wattly_ml.data import DatasetRows, digest, quality, write_json
from wattly_ml.features import feature_names, features, holiday_calendar, holiday_digest
from wattly_ml.price_only import PRICE_ONLY_POLICY, completed_price_history
from wattly_ml.serving.app import create_app
from wattly_ml.spikes import DEFAULT_CONFIG
from wattly_ml.timebase import DAY, STEP, instant, targets
from test_pipeline import history, row


@pytest.fixture
def prepared(tmp_path):
    rows=history(13)
    data=tmp_path/'dataset';data.mkdir()
    (data/'prices.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    write_json(data/'manifest.json',{'sha256':digest(data/'prices.jsonl'),'quality':quality(rows)})
    template=make_pipeline(SimpleImputer(strategy='median',keep_empty_features=True),DummyRegressor(strategy='mean'))
    template.fit([[1.]*19,[2.]*19],[123.,125.])
    manifest=dict(schemaVersion=1,featureSchemaVersion=2,featureOrder=feature_names(2),
        inputPolicy=PRICE_ONLY_POLICY,expectedDemandPolicy='ALWAYS_MISSING_AS_TRAINED',
        holidayCalendarVersion=holiday_calendar()['version'],holidayCalendarSha256=holiday_digest(),
        trainingCutoff='2025-12-01T00:00:00+08:00',trainingDate='2025-12-01T00:00:00+08:00',
        usableFrom='2025-12-01T00:00:00+08:00',baselineRanking=['B3','B2','B1'],
        spikeConfig=DEFAULT_CONFIG.copy(),researchRecipe='PRICE_183D',productionEligible=False,
        exploratory=True,retrospective=True)
    manifest=save_bundle({'manifest':manifest,'models':[deepcopy(template) for _ in range(24)]},tmp_path/'serving/models','frozen')
    champion=tmp_path/'serving/models/frozen'
    (tmp_path/'serving/current.json').write_text('sentinel: untouched live pointer')
    return dict(rows=rows,data=data,champion=champion,pin=digest(champion/'manifest.json'),
        output=tmp_path/'result',cutoff=instant('2026-01-12T00:00:00+08:00'),
        end=instant('2026-01-12T13:00:00+08:00'),as_of=instant('2026-01-13T00:00:00+08:00'))


def run(p,**overrides):
    values={**p,**overrides}
    return weekly.run(*(values[k] for k in ('data','champion','pin','output','cutoff','end','as_of')),
                      protected=values.get('protected'))


def test_fit_save_load_paired_report_and_actual_http_inference(prepared):
    p=prepared
    paths=[p['data']/'prices.jsonl',p['champion']/'models.joblib',p['champion']/'manifest.json',p['champion'].parents[1]/'current.json']
    before={path:digest(path) for path in paths}
    summary=run(p)
    assert summary['commonPairs']==48
    assert set(summary['table'])=={'AI','CURRENT','B1','B2','B3'}
    assert summary['trainingOrigins']==11*48-24-1
    assert summary['trainingCoverage']<1
    assert summary['declaredSynthetic'] and not summary['adoptionEligible']
    assert not summary['savingsOrHarmEvaluated'] and not summary['prospectiveValidation']
    assert summary['promotionDecision']=='NOT_ATTEMPTED_SHADOW_ONLY'
    candidate=load_bundle(p['output']/'candidates/weekly-price-only-candidate')
    assert candidate['manifest']['usableFrom']==p['as_of'].isoformat()
    assert candidate['manifest']['productionEligible'] is False
    assert candidate['models'][0].get_params()['dummyregressor__strategy']=='mean'
    assert candidate['models'][0].named_steps['dummyregressor'].constant_[0,0]!=124.
    assert load_bundle(p['champion'])['models'][0].named_steps['dummyregressor'].constant_[0,0]==124.
    actual_rows=[r for r in p['rows'] if max(instant(r['periodStart']),instant(r['availableAt']))<=p['as_of']]
    request=dict(schemaVersion=2,requestId='synthetic-weekly-contract',asOf=p['as_of'].isoformat(),
        intervalMinutes=30,horizonCount=24,mappingVerified=True,
        priceHistory=[{k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')} for r in actual_rows])
    with TestClient(create_app(loader=lambda:candidate)) as client:
        response=client.post('/forecast',json=request)
    assert response.status_code==200
    assert len(response.json()['points'])==24
    assert 'EXPECTED_DEMAND_OMITTED_BY_RECIPE' in response.json()['qualityFlags']
    direct=predict(candidate,History(p['rows']).at(p['as_of']),p['as_of'])
    assert [r['predictedUsep'] for r in response.json()['points']]==direct
    assert before=={path:digest(path) for path in paths}
    records=[json.loads(line) for line in (p['output']/'predictions.jsonl').read_text().splitlines()]
    pairs={}
    for m in summary['table']:
        valid=[r for r in records if r['method']==m and r['exclusionReason'] is None]
        pairs[m]={(r['asOf'],r['horizon'],r['targetPeriod'],r['actual']) for r in valid}
        errors=[r['predictedUsep']-r['actual'] for r in valid]
        assert summary['table'][m]['mae']==pytest.approx(np.mean(np.abs(errors)))
        assert summary['table'][m]['rmse']==pytest.approx(np.sqrt(np.mean(np.square(errors))))
    assert all(v==pairs['AI'] for v in pairs.values())
    attempt=json.loads((p['output']/'attempt.json').read_text())
    assert attempt['state']=='COMPLETE' and attempt['sourceHashesVerified']


def test_training_features_match_completed_serving_and_never_accept_auxiliaries():
    rows=history(10);origin=instant('2026-01-09T00:00:00+08:00')
    rows.append({**row(origin,999999.),'sourceUpdatedAt':origin.isoformat(),'availableAt':origin.isoformat()})
    xs,ys,_=weekly.training_matrix(rows,origin,origin+26*STEP)
    past=completed_price_history(History(rows).at(origin),origin)
    assert xs[0][0][0]!=999999.
    assert len(xs[0][0])==19 and np.isnan(xs[0][0][-2]) and xs[0][0][-1]==0
    for h,target in enumerate(targets(origin)):
        np.testing.assert_allclose(xs[h][0],features(past,origin,target,schema_version=2),equal_nan=True)
    assert len(ys[0])==2
    demand=dict(targetPeriod=targets(origin)[0].isoformat(),publishedAt=(origin-STEP).isoformat(),
                availableAt=origin.isoformat(),demandMw=999999.,source='SYNTHETIC')
    with_demand=DatasetRows(rows,[demand])
    extra,_,_=weekly.training_matrix(with_demand,origin,origin+26*STEP)
    for a,b in zip(xs,extra):np.testing.assert_allclose(a,b,equal_nan=True)


def test_no_future_targets_or_late_revisions_enter_training():
    rows=history(10);cutoff=instant('2026-01-09T00:00:00+08:00');start=cutoff-DAY
    xs,ys,excluded=weekly.training_matrix(rows,start,cutoff)
    changed=[{**r,'usep':999999.} if instant(r['periodStart'])>=cutoff else r for r in rows]
    changed.append({**rows[350],'usep':-999999.,'sourceUpdatedAt':(cutoff+STEP).isoformat(),'availableAt':(cutoff+STEP).isoformat()})
    x2,y2,e2=weekly.training_matrix(changed,start,cutoff)
    assert excluded['PURGED_SPLIT_BOUNDARY']==24 and e2==excluded
    for a,b in zip(xs,x2):np.testing.assert_allclose(a,b,equal_nan=True)
    assert ys==y2


def test_comparison_uses_identical_pairs_and_reports_own_coverage():
    records=[]
    for method in ('AI','CURRENT','B1','B2','B3'):
        for h in (1,2):
            excluded=method=='B3' and h==2
            records.append(dict(asOf='2026-01-12T00:00:00Z',horizon=h,targetPeriod=f'2026-01-12T0{h}:00:00Z',
                method=method,actual=100.,predictedUsep=None if excluded else (102. if h==1 else 9999.),
                actualSpike=False,spikeFlag=None if excluded else False,
                exclusionReason='INSUFFICIENT_HISTORY' if excluded else None))
    result=weekly.comparison(records)
    assert result['commonPairs']==1
    assert result['table']['AI']['mae']==2 and result['table']['AI']['eligiblePairs']==2
    assert result['table']['B3']['eligiblePairs']==1
    records[-2]['targetPeriod']='2026-01-15T00:00:00Z'
    with pytest.raises(ValueError,match='target identities'):weekly.comparison(records)


@pytest.mark.parametrize('change,match',[
    ({'pin':'0'*64},'pin mismatch'),
    ({'as_of':'2026-01-11T00:00:00Z'},'matured'),
    ({'end':'2026-01-12T00:00:00+08:00'},'ordered half-hours'),
    ({'cutoff':'2026-01-12T00:01:00+08:00'},'ordered half-hours'),
    ({'as_of':'2026-01-13T00:00:00'},'timezone'),
    ({'protected':('2026-01-12T00:00:00+08:00','2026-01-13T00:00:00+08:00')},'protected interval'),
])
def test_bad_boundaries_and_pins_write_nothing(prepared,change,match):
    with pytest.raises(ValueError,match=match):run(prepared,**change)
    assert not prepared['output'].exists()


@pytest.mark.parametrize('where',['dataset','champion','serving'])
def test_output_cannot_touch_source_or_serving_runtime(prepared,monkeypatch,where):
    p=prepared;monkeypatch.setenv('WATTLY_ML_HOME',str(p['champion'].parents[1]))
    destination={'dataset':p['data']/'result','champion':p['champion']/'result','serving':p['champion'].parents[1]/'new-run'}[where]
    with pytest.raises(ValueError,match='separate|serving runtime'):run(p,output=destination)
    assert not destination.exists()


def test_existing_run_is_never_overwritten(prepared):
    p=prepared;p['output'].mkdir();sentinel=p['output']/'attempt.json';sentinel.write_text('old evidence')
    with pytest.raises(FileExistsError):run(p)
    assert sentinel.read_text()=='old evidence'


@pytest.mark.parametrize('kind',['unverified','unreceived','final','received_before_publication'])
def test_unverified_or_reconstructed_input_vintages_fail_closed(prepared,kind):
    p=prepared;rows=deepcopy(p['rows'])
    rows[0].update({'timeMapping':'UNVERIFIED_FLOOR'} if kind=='unverified' else
                   {'availableAt':None,'sourceUpdatedAt':None} if kind=='unreceived' else
                   {'availableAt':rows[0]['periodStart']} if kind=='received_before_publication' else {'priceStatus':'FINAL'})
    (p['data']/'prices.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    write_json(p['data']/'manifest.json',{'sha256':digest(p['data']/'prices.jsonl'),'quality':quality(rows)})
    with pytest.raises(ValueError,match='receipt-stamped'):run(p)
    assert not p['output'].exists()


def test_fit_failure_is_audited_and_never_promoted(prepared,monkeypatch):
    def fail(*args):raise RuntimeError('synthetic fit failure')
    monkeypatch.setattr(weekly,'fit_candidate',fail)
    with pytest.raises(RuntimeError,match='synthetic fit'):run(prepared)
    audit=json.loads((prepared['output']/'attempt.json').read_text())
    assert audit['state']=='FAILED' and not audit['resultsValid']
    assert audit['promotionDecision']=='NOT_ATTEMPTED_SHADOW_ONLY'
    assert not (prepared['output']/'current.json').exists()


def test_input_changed_during_attempt_cannot_be_reported_valid(prepared,monkeypatch):
    original=weekly.fit_candidate
    def change(*args):
        bundle=original(*args)
        path=prepared['data']/'prices.jsonl';path.write_text(path.read_text()+'\n')
        return bundle
    monkeypatch.setattr(weekly,'fit_candidate',change)
    with pytest.raises(ValueError,match='Input changed'):run(prepared)
    assert json.loads((prepared['output']/'attempt.json').read_text())['state']=='FAILED'
    assert not (prepared['output']/'comparison.json').exists()


def test_input_changed_while_loading_cannot_become_the_recorded_source(prepared,monkeypatch):
    original=weekly.load_dataset
    def change(path):
        result=original(path)
        source=Path(path)/'prices.jsonl';source.write_text(source.read_text()+'\n')
        return result
    monkeypatch.setattr(weekly,'load_dataset',change)
    with pytest.raises(ValueError,match='changed while loading'):run(prepared)
    assert not prepared['output'].exists()


def test_cli_runs_one_synthetic_rehearsal_and_refuses_second_write(prepared):
    p=prepared
    command=[sys.executable,'-m','wattly_ml.price_only_weekly','--dataset',str(p['data']),
        '--champion',str(p['champion']),'--champion-manifest-sha256',p['pin'],'--output',str(p['output']),
        '--training-cutoff',p['cutoff'].isoformat(),'--validation-end',p['end'].isoformat(),'--as-of',p['as_of'].isoformat()]
    first=subprocess.run(command,capture_output=True,text=True,timeout=90)
    assert first.returncode==0,first.stderr
    assert json.loads(first.stdout)['adoptionEligible'] is False
    before=digest(p['output']/'attempt.json')
    second=subprocess.run(command,capture_output=True,text=True,timeout=90)
    assert second.returncode!=0 and digest(p['output']/'attempt.json')==before


def test_recorded_mapping_evidence_cannot_authorize_other_sources_or_offsets():
    p=instant('2026-01-01T00:00:00+08:00')
    record={**row(p,100.),'source':'NEMS_SN_SG','timeMapping':'UNVERIFIED_FLOOR'}
    assert not weekly.mapped(record,None)
    assert weekly.mapped(record,weekly.MAPPING_EVIDENCE)
    assert not weekly.mapped({**record,'source':'OTHER'},weekly.MAPPING_EVIDENCE)
    assert not weekly.mapped({**record,'sourceUpdatedAt':(p+STEP).isoformat()},weekly.MAPPING_EVIDENCE)
    assert record['timeMapping']=='UNVERIFIED_FLOOR'


def test_champion_trained_in_validation_cannot_be_a_fair_comparator(prepared):
    p=prepared;path=p['champion']/'manifest.json';manifest=json.loads(path.read_text())
    manifest['trainingCutoff']=(p['cutoff']+STEP).isoformat();write_json(path,manifest)
    with pytest.raises(ValueError,match='learned from the evaluation'):run(p,pin=digest(path))
    assert not p['output'].exists()


def test_insufficient_training_is_recorded_without_a_candidate(prepared):
    p=prepared
    with pytest.raises(ValueError,match='48 complete training origins'):
        run(p,protected=('2025-07-01T00:00:00+08:00',p['cutoff'].isoformat()))
    assert json.loads((p['output']/'attempt.json').read_text())['state']=='FAILED'
    assert not (p['output']/'candidates').exists()


@pytest.mark.parametrize('candidate,no_worse',[(100.,True),(120.,False)])
def test_better_and_worse_candidates_both_remain_review_only(candidate,no_worse):
    prices={'AI':candidate,'CURRENT':108.,'B1':105.,'B2':103.,'B3':102.}
    records=[dict(asOf='2026-01-12T00:00:00Z',targetPeriod='2026-01-12T00:30:00Z',
        horizon=1,method=method,predictedUsep=value,actual=100.,actualSpike=False,
        spikeFlag=False,exclusionReason=None) for method,value in prices.items()]
    result=weekly.comparison(records)
    assert result['candidateMaeNoWorseOnCommonPairs'] is no_worse
    assert result['adoptionEligible'] is False and result['promotionDecision']=='NOT_ATTEMPTED_SHADOW_ONLY'
