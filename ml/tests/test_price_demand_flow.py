"""Actual read-only PostgreSQL and actual Java F4, using clearly synthetic inputs."""
from contextlib import contextmanager
from copy import deepcopy
from decimal import Decimal
import json,os,shutil,uuid
from pathlib import Path

import pytest
from wattly_ml import price_demand_export as exporter
from wattly_ml.price_demand_export import load_export
from wattly_ml.price_demand_savings import replay
from wattly_ml.price_demand_training import METHODS
from wattly_ml.timebase import STEP, instant, targets
from test_price_demand_training import prepare


@pytest.fixture
def postgres(monkeypatch,tmp_path):
    import psycopg
    from psycopg import sql
    from psycopg.conninfo import make_conninfo
    url=os.environ.get('WATTLY_WEEKLY_TEST_DATABASE_URL')
    if not url:pytest.skip('Set disposable WATTLY_WEEKLY_TEST_DATABASE_URL')
    schema='demand_flow_'+uuid.uuid4().hex
    with psycopg.connect(url,autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
        conn.execute(sql.SQL('CREATE TABLE {}.forecast_run (id text,as_of timestamptz,mode text,model_version text,stale boolean,status text,input_snapshot jsonb,is_demo boolean)').format(sql.Identifier(schema)))
        conn.execute(sql.SQL('CREATE TABLE {}.market_price_revision (id bigint,source text,period_start timestamptz,published_at timestamptz,available_at timestamptz,usep numeric,price_status text,time_mapping text)').format(sql.Identifier(schema)))
        monkeypatch.setenv('DEMAND_FIXTURE_DATABASE',make_conninfo(url,options='-c search_path='+schema))
        try:yield conn,schema
        finally:conn.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(schema)))


def seed(postgres,tmp_path):
    from psycopg import sql
    from psycopg.types.json import Jsonb
    conn,schema=postgres;issues,prices,p,frozen=prepare(tmp_path)
    selected=[deepcopy(issues[0]),deepcopy(issues[0]),deepcopy(issues[48])]
    selected[1].update(id='SYNTHETIC_DUPLICATE',asOf=(instant(selected[0]['asOf'])+STEP/10).isoformat())
    for row in selected:
        snapshot={**row['inputs'],'_syntheticTestOnly':True,'householdPrivate':'NOT_EXPORTED'}
        conn.execute(sql.SQL('INSERT INTO {}.forecast_run VALUES (%s,%s,%s,%s,%s,%s,%s,%s)').format(sql.Identifier(schema)),
            (row['id'],row['asOf'],'LIVE',p['frozenModelVersion'],False,'AVAILABLE',Jsonb(snapshot),False))
    for n,row in enumerate(prices):
        conn.execute(sql.SQL('INSERT INTO {}.market_price_revision VALUES (%s,%s,%s,%s,%s,%s,%s,%s)').format(sql.Identifier(schema)),
            (n,'NEMS_SN_SG',row['periodStart'],row['sourceUpdatedAt'],row['availableAt'],row['usep'],'PROVISIONAL','EMC_PERIOD'))
    return p,frozen


def test_export_preserves_original_receipts_deduplicates_and_never_exports_households(postgres,tmp_path):
    p,_=seed(postgres,tmp_path);output=tmp_path/'dataset'
    result=exporter.export(p,'train',p['trainCutoff'],output,'DEMAND_FIXTURE_DATABASE')
    issues,prices=load_export(output,p,'train')
    assert len(issues)==1 and result['sourceRows']==2
    assert result['sourceExclusions']=={'REPEATED_HALF_HOUR':1}
    assert result['actualCompleteOrigins']==1 and not result['sufficientSupport']
    assert result['readOnly'] and result['transactionIsolation']=='REPEATABLE_READ'
    assert all(instant(r['availableAt'])<=instant(p['trainCutoff']) for r in prices)
    raw=(output/'issues.jsonl').read_text()
    assert 'NOT_EXPORTED' not in raw and 'householdPrivate' not in raw
    assert instant(issues[0]['inputs']['expectedDemand'][0]['availableAt'])==instant(issues[0]['asOf'])
    with pytest.raises(FileExistsError):exporter.export(p,'train',p['trainCutoff'],output,'DEMAND_FIXTURE_DATABASE')
    (output/'issues.jsonl').chmod(0o600);(output/'issues.jsonl').write_text(raw+'\n')
    with pytest.raises(ValueError,match='hash changed'):load_export(output,p,'train')


def test_export_timing_gate_precedes_connection_and_file_creation(tmp_path,monkeypatch):
    _,_,p,_=prepare(tmp_path)
    def forbidden(*a,**k):raise AssertionError('Database must not be opened')
    monkeypatch.setattr(exporter,'postgres_connection',forbidden)
    with pytest.raises(ValueError,match='outside'):exporter.export(p,'test',p['testStart'],tmp_path/'no-output','MISSING')
    assert not (tmp_path/'no-output').exists()


def test_export_connection_failure_never_exposes_credentials(tmp_path,monkeypatch):
    _,_,p,_=prepare(tmp_path)
    @contextmanager
    def broken(*a,**k):raise RuntimeError('postgres://secret-user:secret-password@example');yield
    monkeypatch.setattr(exporter,'postgres_connection',broken)
    with pytest.raises(ValueError) as error:exporter.export(p,'train',p['trainCutoff'],tmp_path/'failed','FIXTURE')
    assert 'secret' not in str(error.value)
    assert 'secret' not in (tmp_path/'failed/failed.json').read_text()


def test_export_repeatable_read_excludes_concurrent_later_revision(postgres,tmp_path,monkeypatch):
    from psycopg import sql
    p,_=seed(postgres,tmp_path);conn,schema=postgres;original=exporter.postgres_connection
    @contextmanager
    def wrapped(variable):
        with original(variable) as connection:
            class Wrapper:
                def execute(self,*a,**k):return connection.execute(*a,**k)
                def cursor(self,**kwargs):
                    if kwargs.get('name')=='demand_received_truth':
                        t=targets(instant('2026-11-09T00:02:00+08:00'))[0]
                        conn.execute(sql.SQL('INSERT INTO {}.market_price_revision VALUES (%s,%s,%s,%s,%s,%s,%s,%s)').format(sql.Identifier(schema)),
                            (99999,'NEMS_SN_SG',t,t+STEP,t+STEP,999999,'PROVISIONAL','EMC_PERIOD'))
                    return connection.cursor(**kwargs)
            yield Wrapper()
    monkeypatch.setattr(exporter,'postgres_connection',wrapped)
    output=tmp_path/'snapshot';exporter.export(p,'train',p['trainCutoff'],output,'DEMAND_FIXTURE_DATABASE')
    _,prices=load_export(output,p,'train');assert not any(r['usep']==999999 for r in prices)


def evaluation(tmp):
    _,_,p,_=prepare(tmp);experiment=tmp/'experiment';experiment.mkdir();directory=experiment/'evaluation';directory.mkdir()
    (experiment/'protocol.json').write_text(json.dumps(p));(experiment/'evaluation-attempt.json').write_text('{"state":"SUCCESS"}')
    from wattly_ml.demand_candidate import identity
    origins=[instant(p['testStart'])+STEP/15,instant(p['testStart'])+STEP+STEP/15]
    rows=[]
    for origin in origins:
        for method in METHODS:
            for h,t in enumerate(targets(origin),1):
                predicted=10 if method=='PRICE_DEMAND' and h==1 else 1000 if method=='PRICE_DEMAND' else 100
                actual=2000 if t.hour==16 and t.minute in (30,) or t.hour==17 and t.minute==0 else 100
                rows.append(dict(asOf=origin.isoformat(),horizon=h,targetPeriod=t.isoformat(),method=method,
                    predictedUsep=predicted,actual=actual,threshold=None,actualSpike=None,spikeFlag=None,truthPolicy='PROVISIONAL'))
    # Use a common actual for every overlapping target across origins.
    (directory/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    (directory/'summary.json').write_text(json.dumps(dict(protocolSha256=identity(p),syntheticTestOnly=True,commonOrigins=2,commonPairs=48)))
    app=dict(powerKw='1',runMinutes=30,earliestStart='00:30',mustFinishBy='12:00',usualStart='01:30',runsPerWeek=7,enabled=True)
    profile=dict(syntheticTestOnly=True,minimumSavingSgd='0.05',maxActive=3,
        households=[dict(id=1,exposedToWholesalePrice=True,appliances=[dict(app,id=n) for n in range(1,5)]),
                    dict(id=2,exposedToWholesalePrice=False,appliances=[dict(app,id=9)])])
    profiles=tmp/'profiles.json';profiles.write_text(json.dumps(profile))
    from wattly_ml.data import digest
    p['savingsProfileSha256']=digest(profiles)
    (experiment/'protocol.json').write_text(json.dumps(p))
    (directory/'summary.json').write_text(json.dumps(dict(protocolSha256=identity(p),syntheticTestOnly=True,commonOrigins=2,commonPairs=48)))
    return directory,profiles,p


def java_tools():
    root=os.environ.get('JAVA_HOME_21_X64') or '/usr/lib/jvm/java-25-openjdk-amd64'
    compiler=Path(root)/'bin/javac';runtime=Path(root)/'bin/java'
    return (str(runtime),str(compiler)) if compiler.exists() else (shutil.which('java'),shutil.which('javac'))


def test_actual_f4_replay_deduplicates_runs_retains_losses_and_uses_same_cases(tmp_path):
    directory,profiles,p=evaluation(tmp_path);java,javac=java_tools()
    output=tmp_path/'savings';result=replay(directory,profiles,Path(__file__).parents[2],output,p['testEnd'],java,javac)
    assert result['commonCases']==4 and result['fixedRateHouseholdsNotShifted']==1
    assert all(v['cases']==4 for v in result['methods'].values())
    assert result['methods']['PRICE_DEMAND']['moves']==4  # Three at first refresh, one at next.
    assert result['methods']['PRICE_DEMAND']['harmfulMoves']==4
    assert Decimal(result['methods']['PRICE_DEMAND']['savingsSgd'])<0
    assert result['methods']['B3']['savingsSgd']=='0'
    assert not result['measuredHouseholdBills'] and not result['automaticPromotion']
    decisions=[json.loads(line) for line in (output/'decisions.jsonl').read_text().splitlines()]
    for method in METHODS:
        own=[r for r in decisions if r['method']==method]
        assert len({(r['householdId'],r['applianceId'],r['usualStart']) for r in own})==4
    with pytest.raises(FileExistsError):replay(directory,profiles,Path(__file__).parents[2],output,p['testEnd'],java,javac)


def test_actual_prices_change_savings_but_not_chosen_plans(tmp_path):
    directory,profiles,p=evaluation(tmp_path);java,javac=java_tools();root=Path(__file__).parents[2]
    replay(directory,profiles,root,tmp_path/'one',p['testEnd'],java,javac)
    path=directory/'predictions.jsonl';rows=[json.loads(line) for line in path.read_text().splitlines()]
    for r in rows:r['actual']=1
    path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    replay(directory,profiles,root,tmp_path/'two',p['testEnd'],java,javac)
    selected=lambda folder:[(r['method'],r['applianceId'],r['chosenStart']) for line in (folder/'decisions.jsonl').read_text().splitlines() if (r:=json.loads(line))]
    assert selected(tmp_path/'one')==selected(tmp_path/'two')
    assert json.loads((tmp_path/'two/summary.json').read_text())['methods']['PRICE_DEMAND']['harmfulMoves']==0


def test_incomplete_pairs_and_early_savings_are_rejected(tmp_path):
    directory,profiles,p=evaluation(tmp_path);root=Path(__file__).parents[2]
    with pytest.raises(ValueError,match='before'):replay(directory,profiles,root,tmp_path/'early',p['testStart'])
    path=directory/'predictions.jsonl';lines=path.read_text().splitlines();path.write_text('\n'.join(lines[:-1]))
    with pytest.raises(ValueError,match='same complete pairs'):replay(directory,profiles,root,tmp_path/'bad',p['testEnd'])
    assert not (tmp_path/'bad').exists()


def test_posthoc_profile_or_policy_changes_are_rejected(tmp_path):
    directory,profiles,p=evaluation(tmp_path)
    profile=json.loads(profiles.read_text());profile['minimumSavingSgd']='0.01'
    profiles.write_text(json.dumps(profile))
    with pytest.raises(ValueError,match='pinned'):
        replay(directory,profiles,Path(__file__).parents[2],tmp_path/'bad',p['testEnd'])
    assert not (tmp_path/'bad').exists()


def test_invalid_first_capture_is_excluded_without_repairing_or_losing_later_input(postgres,tmp_path):
    from psycopg import sql
    from psycopg.types.json import Jsonb
    p,_=seed(postgres,tmp_path);conn,schema=postgres
    conn.execute(sql.SQL('INSERT INTO {}.forecast_run VALUES (%s,%s,%s,%s,%s,%s,%s,%s)').format(sql.Identifier(schema)),
        ('INVALID_FIRST',instant(p['trainStart'])+STEP/30,'LIVE',p['frozenModelVersion'],False,'AVAILABLE',
         Jsonb({'_syntheticTestOnly':True,'priceHistory':None,'expectedDemand':[]}),False))
    result=exporter.export(p,'train',p['trainCutoff'],tmp_path/'dataset','DEMAND_FIXTURE_DATABASE')
    assert result['eligibleHalfHourOrigins']==1 and result['sourceExclusions']['INVALID_ORIGINAL_INPUTS']==1
