import json
from pathlib import Path
import sqlite3
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from wattly_ml.timebase import instant, floor, targets, DAY, STEP
from wattly_ml.data import as_of, truth, quality, read_source, snapshot, prepare, load_dataset, weather_for, digest
from wattly_ml.baselines import baselines
from wattly_ml.spikes import DEFAULT_CONFIG, reference
from wattly_ml.features import features
from wattly_ml.backtest import backtest, scores
from wattly_ml.serving.app import create_app

FIXTURES = Path(__file__).parents[1]/"fixtures"


def row(period, value, available=None):
    return {"periodStart":period.isoformat(), "sourceUpdatedAt":(period+timedelta(seconds=60)).isoformat(),
            "availableAt":(available or period+timedelta(seconds=120)).isoformat(),"usep":value,
            "priceStatus":"PROVISIONAL","source":"SYNTHETIC","revisionId":str(period),"timeMapping":"EMC_PERIOD"}


def history(days=10):
    start=instant("2026-01-01T00:00:00+08:00")
    return [row(start+i*STEP,100+(i%48)) for i in range(days*48)]


def test_golden_baselines_midnight_negative_prices_and_exact_boundary():
    gold=json.loads((FIXTURES/"baseline-golden.json").read_text())
    start=instant(gold["start"]); origin=instant(gold["asOf"])
    rows=[row(start+i*STEP,gold["firstPrice"]+i*gold["increment"]) for i in range(gold["count"])]
    result=baselines(as_of(rows,origin),origin)
    for name in ("B1","B2","B3"): assert result[name]==gold[name]
    assert targets(origin)[0]==instant(gold["firstTarget"])
    assert targets(origin)[-1]==instant(gold["lastTarget"])
    assert targets(floor(origin))==targets(origin)
    with_current=as_of(rows+[row(floor(origin),1000)],origin)
    assert baselines(with_current,origin)['B1'][0]==pytest.approx(2855/6)
    missing=as_of(rows,origin); del missing[floor(origin)-3*STEP]
    assert baselines(missing,origin)["B1"] is None
    with pytest.raises(ValueError): instant("2026-01-01T00:00:00")


def test_delayed_publications_revisions_truth_and_no_row_shift():
    origin=instant("2026-01-02T00:00:00Z"); period=origin-STEP
    original=row(period,-2); late={**original,"usep":500,"availableAt":(origin+STEP).isoformat()}
    assert as_of([original,late],origin)[period]["usep"]==-2
    assert as_of([original,late],origin+STEP)[period]["usep"]==500
    with pytest.raises(ValueError): as_of([original,{**original,"usep":99}],origin)
    assert truth([original,late],"PROVISIONAL")[period]==500
    assert truth([original,late],"PROVISIONAL",origin)[period]==-2
    final={**original,"priceStatus":"FINAL","sourceUpdatedAt":None,"availableAt":None}
    assert as_of([final],origin)=={}
    assert as_of([final],origin,True)[period]["usep"]==-2


def test_emc_actual_headers_final_vintages_and_gaps():
    rows,errors=read_source(FIXTURES/"emc-synthetic.csv","emc")
    assert not errors and len(rows)==2
    assert rows[0]["periodStart"]=="2026-09-19T16:00:00+00:00"
    assert rows[1]["periodStart"]=="2026-09-20T15:30:00+00:00"
    assert rows[0]["availableAt"] is None and rows[0]["usep"]==-12.5
    assert quality(rows)["missingHalfHours"]==46


def test_backup_includes_wal_and_never_changes_collector(tmp_path):
    source_dir=tmp_path/"collector"; source_dir.mkdir(); source=source_dir/"source.sqlite3"
    connection=sqlite3.connect(source)
    connection.executescript('PRAGMA journal_mode=WAL; CREATE TABLE responses(id INTEGER,received_at TEXT); '
      'CREATE TABLE events(id TEXT,source_updated TEXT,first_seen TEXT,last_seen TEXT,price_response_id INTEGER,'
      'payload_json TEXT,weather_response_id INTEGER,weather_json TEXT);')
    stamp="2026-01-01T00:01:00+08:00"
    payload=json.dumps({"updated":instant(stamp).timestamp(),"usep":-4,"demand":6000})
    connection.execute('INSERT INTO events VALUES (?,?,?,?,?,?,?,?)',('external-hash',stamp,stamp,stamp,1,payload,None,None))
    connection.commit()
    before=digest(source); count=connection.execute('select count(*) from events').fetchone()
    snap=snapshot(source,tmp_path/"snapshots","sqlite")
    assert digest(source)==before and connection.execute('select count(*) from events').fetchone()==count
    assert json.loads((snap/"manifest.json").read_text())["quality"]["rowCount"]==1
    result=prepare([snap],tmp_path/"dataset")
    rows,manifest=load_dataset(tmp_path/"dataset")
    assert rows[0]["externalId"]=="external-hash" and not result["quality"]["mappingVerified"]
    with pytest.raises(ValueError): prepare([snap],tmp_path/"dataset")
    connection.close()


def test_weather_never_uses_future_revision_retrieval_or_expired_validity():
    origin=instant("2026-01-01T00:00:00Z")
    w={"issuedAt":origin.isoformat(),"updatedAt":origin.isoformat(),"availableAt":origin.isoformat(),
       "validStart":origin.isoformat(),"validEnd":(origin+4*STEP).isoformat()}
    assert weather_for([w],origin,origin+STEP)==w
    assert weather_for([w],origin,origin+4*STEP) is None
    assert weather_for([{**w,"availableAt":(origin+STEP).isoformat()}],origin,origin+STEP) is None
    assert weather_for([{**w,"updatedAt":(origin+STEP).isoformat()}],origin,origin+STEP) is None


def test_spike_zero_mad_insufficient_history_and_negative_reference():
    origin=instant("2026-02-01T00:00:00Z"); target=origin+STEP
    past={target-n*DAY:{"usep":-5} for n in range(1,29)}
    ref=reference(past,target,origin)
    assert ref["typical"]==-5 and ref["threshold"]==-2
    assert not reference({},target,origin)["available"]
    assert not reference(dict(list(past.items())[:13]),target,origin)["available"]
    for invalid in ({}, {**DEFAULT_CONFIG,"minimumSamples":True}, {**DEFAULT_CONFIG,"minimumSamples":29},
                    {**DEFAULT_CONFIG,"k":float('nan')}, {**DEFAULT_CONFIG,"spreadFloor":0}):
        with pytest.raises(ValueError): reference(past,target,origin,invalid)


def test_backtest_and_serving_use_the_artifacts_spike_rule():
    from sklearn.dummy import DummyRegressor
    model=DummyRegressor(strategy="constant",constant=1000).fit([[0]],[1000])
    rows=history(); origin=instant(rows[8*48]["periodStart"])
    config={"minimumSamples":7,"k":2.0,"spreadFloor":2.0}
    manifest={"version":"SYNTHETIC_CONFIG_TEST","usableFrom":origin.isoformat(),"trainingDate":origin.isoformat(),
              "baselineRanking":["B1","B2","B3"],"retrospective":False,"spikeConfig":config}
    bundle={"manifest":manifest,"models":[model]*24}
    records=backtest(rows,origin,origin+DAY,bundle=bundle)
    expected=[r for r in records if instant(r['asOf'])==origin and r['method']=='AI']
    past=as_of(rows,origin)
    assert not reference(past,targets(origin)[0],origin)['available']  # Default minimum is 14 days.
    assert expected[0]['threshold']==105
    payload=request_payload(); payload['asOf']=origin.isoformat()
    payload['priceHistory']=[{k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')}
                             for r in past.values()]
    with TestClient(create_app(lambda:bundle)) as client:
        response=client.post('/forecast',json=payload)
        assert response.status_code==200,response.text
        for actual,offline in zip(response.json()['points'],expected):
            assert actual['assessmentAvailable']
            assert actual['spikeThreshold']==offline['threshold']
            assert actual['spikeFlag']==offline['spikeFlag']


def test_shared_features_ignore_future_and_backtest_purges_split():
    rows=history(); origin=instant(rows[8*48]["periodStart"])
    a=features(as_of(rows,origin),origin,origin+STEP)
    rows[-1]["usep"]=99999999
    assert a==features(as_of(rows,origin),origin,origin+STEP)
    records=backtest(rows,origin,origin+DAY)
    assert all(r["exclusionReason"]=="PURGED_SPLIT_BOUNDARY" for r in records if instant(r["asOf"])>=origin+DAY-24*STEP)
    result=scores(records)
    assert result["commonPairs"]>0
    assert result['commonOriginDays']==1
    assert result["table"]["B1"]["spikes"]["precision"] is None


def request_payload():
    rows=history(); origin=instant(rows[-1]["availableAt"])
    return {"schemaVersion":1,"requestId":"synthetic","asOf":origin.isoformat(),"intervalMinutes":30,
            "horizonCount":24,"mappingVerified":True,
            "priceHistory":[{k:r[k] for k in ("periodStart","sourceUpdatedAt","availableAt","usep")} for r in rows]}


def test_absent_corrupt_model_health_readiness_and_invalid_requests(tmp_path):
    def broken(): raise ValueError("bad checksum")
    with TestClient(create_app(broken)) as client:
        assert client.get('/health').status_code==200
        assert client.get('/ready').status_code==503
        assert client.post('/forecast',json=request_payload()).status_code==503
        payload=request_payload(); payload['priceHistory'][-1]['availableAt']='2099-01-01T00:00:00Z'
        assert client.post('/forecast',json=payload).status_code==422
        payload=request_payload(); payload['priceHistory'].reverse()
        assert client.post('/forecast',json=payload).status_code==422
        payload=request_payload(); payload['asOf']='2026-01-01T00:00:00'
        assert client.post('/forecast',json=payload).status_code==422
        payload=request_payload(); payload['priceHistory'][0]['usep']='NaN'
        assert client.post('/forecast',json=payload).status_code==422
        assert client.post('/forecast',content=b' '*512001).status_code==413


def test_tiny_training_artifact_serving_and_promotion_guards(tmp_path,monkeypatch):
    from wattly_ml.train import train
    from wattly_ml.artifacts import save_bundle,load_bundle,promote
    rows=history(11); start=instant(rows[7*48]["periodStart"])
    cutoff=start+2*DAY; end=cutoff+DAY
    for r in rows:
        if instant(r['periodStart'])>=cutoff:
            r['usep']=999999  # Validation-only regime must not enter learned preprocessing.
    from sklearn.impute import SimpleImputer
    fit_sizes=[]
    original_fit=SimpleImputer.fit
    def observe_fit(self,x,y=None):
        fit_sizes.append(len(x))
        return original_fit(self,x,y)
    monkeypatch.setattr(SimpleImputer,'fit',observe_fit)
    manifest={"sha256":"synthetic","quality":quality(rows)}
    bundle,records=train(rows,manifest,start,cutoff,end,policy="PROVISIONAL",exploratory=True,iterations=2)
    assert len(bundle['models'])==24
    assert bundle['manifest']['excludedTrainingOrigins']>=24
    assert fit_sizes==[bundle['manifest']['trainingOrigins']]*24
    imputer=bundle['models'][0].steps[0][1]
    assert max(imputer.statistics_)<1000  # Train-only feature statistics, not target IDs or timestamps.
    save_bundle(bundle,tmp_path/'models','fixture')
    loaded=load_bundle(tmp_path/'models'/'fixture')
    manifest_path=tmp_path/'models'/'fixture'/'manifest.json'
    original_manifest=manifest_path.read_text()
    incompatible=json.loads(original_manifest); incompatible['dependencies']['scikit-learn']='incompatible'
    manifest_path.write_text(json.dumps(incompatible))
    with pytest.raises(ValueError): load_bundle(tmp_path/'models'/'fixture')
    invalid_rule=json.loads(original_manifest); invalid_rule['spikeConfig']['spreadFloor']=0
    manifest_path.write_text(json.dumps(invalid_rule))
    with pytest.raises(ValueError,match="Invalid spike configuration"): load_bundle(tmp_path/'models'/'fixture')
    manifest_path.write_text(original_manifest)
    # A deliberately labelled fixture simulates a model that existed before the replay day.
    loaded['manifest']['trainingDate']=cutoff.isoformat()
    with pytest.raises(ValueError): promote(tmp_path,'fixture')
    with TestClient(create_app(lambda:loaded)) as client:
        payload=request_payload(); payload['asOf']=end.isoformat()
        response=client.post('/forecast',json=payload)
        assert response.status_code==200,response.text
        result=response.json(); assert len(result['points'])==24
        assert instant(result['points'][0]['targetPeriod'])==targets(end)[0]
        payload['asOf']=(end-DAY).isoformat(); payload['priceHistory']=payload['priceHistory'][:7*48]
        assert client.post('/forecast',json=payload).status_code==503
    (tmp_path/'models'/'fixture'/'models.joblib').write_bytes(b'corrupt')
    with pytest.raises(ValueError): load_bundle(tmp_path/'models'/'fixture')

    # Test promotion mechanics with synthetic validation metadata, never a real performance claim.
    bundle['manifest'].update(productionEligible=True,exploratory=False,baselineRanking=['B1','B2','B3'],trainingDate=cutoff.isoformat())
    bundle['manifest']['validation']={'table':{'AI':{'mae':1.0}},'commonPairs':10000,'skill':0.1}
    save_bundle(bundle,tmp_path/'models','approved-fixture')
    promote(tmp_path,'approved-fixture')
    before=(tmp_path/'current.json').read_bytes()
    with pytest.raises(ValueError): promote(tmp_path,'fixture')
    assert (tmp_path/'current.json').read_bytes()==before
    bundle['manifest']['validation']['table']['AI']['mae']=0.5
    save_bundle(bundle,tmp_path/'models','better-fixture')
    promote(tmp_path,'better-fixture',champion_score={'version':'approved-fixture','validationEnd':end.isoformat(),'mae':1.0})
    promote(tmp_path,'approved-fixture',rollback=True)
    assert json.loads((tmp_path/'current.json').read_text())['version']=='approved-fixture'
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path))
    with TestClient(create_app()) as client:
        assert client.get('/ready').status_code==200
        payload=request_payload(); payload['asOf']=end.isoformat()
        assert len(client.post('/forecast',json=payload).json()['points'])==24
    # A rejected weekly candidate still leaves its per-origin report and no active pointer.
    from wattly_ml import cli
    import wattly_ml.train as training
    bundle['manifest']['productionEligible']=False
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path/'weekly'))
    monkeypatch.setattr(cli,'load_dataset',lambda _: (rows,manifest))
    monkeypatch.setattr(training,'train',lambda *a,**kw: (bundle,records))
    status=cli.main(['weekly',str(tmp_path/'dataset'),'--start',start.isoformat(),'--cutoff',cutoff.isoformat(),
                     '--end',end.isoformat(),'--truth-policy','PROVISIONAL','--exploratory','--version','rejected',
                     '--output',str(tmp_path/'weekly-report')])
    assert status==2 and (tmp_path/'weekly-report'/'predictions.jsonl').exists()
    assert not (tmp_path/'weekly'/'current.json').exists()


def test_decision_harness_keeps_losses_and_fixed_rate():
    from wattly_ml.decisions import compare
    case={"caseId":"fixture","householdId":1,"actual":[100,200],"ai":[200,100],"baseline":[100,200],
          "feasiblePlansKwh":[[1,0],[0,1]],"usualPlanKwh":[1,0],"exposedToWholesalePrice":True}
    assert compare([case])['aiSavingsSgd']==pytest.approx(-.1)
    assert compare([{**case,"exposedToWholesalePrice":False}])['aiSavingsSgd']==0
