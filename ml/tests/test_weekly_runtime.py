from copy import deepcopy
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread
from urllib.parse import parse_qs

import pytest
from wattly_ml import weekly_runtime as runtime
from wattly_ml.data import digest,load_dataset
from wattly_ml.timebase import STEP,instant
from test_price_only_weekly import prepared


@contextmanager
def audit_server():
    records=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def answer(self,status,value=None,**headers):
            self.send_response(status)
            for k,v in headers.items():self.send_header(k.replace('_','-'),v)
            self.send_header('Content-Type','application/json');self.end_headers()
            if value is not None:self.wfile.write(json.dumps(value).encode())
        def do_GET(self):
            assert self.path=='/api/v1/csrf'
            self.answer(200,{'headerName':'X-XSRF-TOKEN','token':'after' if 'session=admin' in self.headers.get('Cookie','') else 'before'},Set_Cookie='xsrf=fixture; Path=/')
        def do_POST(self):
            body=self.rfile.read(int(self.headers['Content-Length']))
            assert 'xsrf=fixture' in self.headers.get('Cookie','')
            if self.path=='/login':
                assert parse_qs(body.decode())=={'username':['fixture-admin'],'password':['fixture-password']}
                assert self.headers['X-XSRF-TOKEN']=='before'
                self.answer(302,Set_Cookie='session=admin; Path=/',Location='/households.html')
            else:
                assert self.path=='/api/v1/admin/forecast-training-attempts'
                assert 'session=admin' in self.headers.get('Cookie','') and self.headers['X-XSRF-TOKEN']=='after'
                records.append(json.loads(body));self.answer(200,{'saved':True})
    http=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=Thread(target=http.serve_forever,daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{http.server_port}',records
    finally:http.shutdown();http.server_close();thread.join()


@pytest.fixture
def configured(prepared,tmp_path):
    p=prepared;collector=tmp_path/'collector';collector.mkdir();db=collector/'collector.sqlite3'
    conn=sqlite3.connect(db)
    conn.executescript('PRAGMA journal_mode=WAL; CREATE TABLE responses(id INTEGER,received_at TEXT); '
       'CREATE TABLE events(id TEXT,source_updated TEXT,first_seen TEXT,last_seen TEXT,price_response_id INTEGER,'
       'payload_json TEXT,weather_response_id INTEGER,weather_json TEXT);')
    start=instant('2026-01-01T00:00:00+08:00')
    for i in range(20*48):
        period=start+i*STEP;published=period+STEP/30;received=period+STEP/15
        conn.execute('INSERT INTO responses VALUES (?,?)',(i,received.isoformat()))
        conn.execute('INSERT INTO events VALUES (?,?,?,?,?,?,?,?)',(str(i),published.isoformat(),received.isoformat(),received.isoformat(),i,
            json.dumps({'updated':published.timestamp(),'usep':100.+i%48,'demand':6000}),None,None))
    conn.commit()
    cfg=dict(schemaVersion=1,runtime=str(tmp_path/'weekly'),collectorDatabase=str(db),
        champion=str(p['champion']),championManifestSha256=p['pin'],
        protectedStart='2026-07-29T00:00:00+08:00',protectedEnd='2026-09-29T00:00:00+08:00',
        mappingEvidence='EMC_RT_20260929_24_PERIODS',validationDays=7,maturityDays=1)
    path=tmp_path/'weekly.json';path.write_text(json.dumps(cfg))
    yield {**p,'config':path,'cfg':cfg,'connection':conn,'db':db,'now':instant('2026-01-21T03:00:00+08:00')}
    conn.close()


def successful_training(monkeypatch,*,better=True):
    calls=[]
    def fit(*args,**kwargs):
        calls.append((args,kwargs))
        rows,manifest=load_dataset(args[0]);assert len(rows)==960 and manifest['quality']['publicationVintages']
        assert kwargs['version']=='weekly-price-only-2026-W04'
        return {'commonPairs':48,'candidateMaeNoWorseOnCommonPairs':better,'adoptionEligible':False}
    monkeypatch.setattr(runtime,'train',fit)
    return calls


def test_fresh_wal_backup_audit_identity_and_one_attempt_per_week(configured,monkeypatch):
    p=configured;fits=successful_training(monkeypatch);records=[]
    def send(path,value):records.append(deepcopy(value));return {'saved':True}
    sources=[p['db'],Path(str(p['db'])+'-wal'),p['champion']/'manifest.json',p['champion']/'models.joblib']
    hashes={s:digest(s) for s in sources}
    result=runtime.run_week(p['config'],p['now'],sender=send)
    assert result['state']=='SUCCESS' and not result['auditPending']
    assert [r['state'] for r in records]==['RUNNING','SUCCESS']
    assert records[-1]['modelVersion']=='weekly-price-only-2026-W04'
    assert records[-1]['promotionDecision']=='NOT_ATTEMPTED_SHADOW_ONLY'
    assert records[-1]['trainingEnd']=='2026-01-13T00:00:00+08:00'
    assert records[-1]['validationEnd']=='2026-01-20T00:00:00+08:00'
    assert len(records[-1]['datasetDigest'])==64
    assert hashes=={s:digest(s) for s in sources}
    assert p['connection'].execute('select count(*) from events').fetchone()[0]==960
    assert runtime.run_week(p['config'],p['now']+STEP,sender=send)['state']=='SKIPPED_ALREADY_ATTEMPTED'
    assert len(fits)==1 and len(records)==2


def test_real_trainer_reaches_database_compatible_audit(configured):
    p=configured;records=[]
    def send(path,value):records.append(deepcopy(value));return {'saved':True}
    result=runtime.run_week(p['config'],p['now'],sender=send)
    assert result['state'] in ('SUCCESS','REJECTED') and result['commonPairs']==312*24
    terminal=records[-1]
    assert terminal['scores']['candidateVersion']==terminal['modelVersion']
    assert terminal['scores']['adoptionEligible'] is False
    root=Path(p['cfg']['runtime'])/'attempts'/result['attemptId']
    assert (root/'fit/candidates'/terminal['modelVersion']/'models.joblib').exists()
    assert not (Path(p['cfg']['runtime'])/'current.json').exists()


def test_audit_outage_blocks_fitting_and_explicit_sync_does_not_refit(configured,monkeypatch):
    p=configured;fits=successful_training(monkeypatch)
    def offline(*args):raise OSError('fixture unavailable')
    result=runtime.run_week(p['config'],p['now'],sender=offline)
    assert result['state']=='FAILED' and result['auditPending'] and not fits
    terminal=[]
    def send(path,value):terminal.append(deepcopy(value));return {'saved':True}
    assert runtime.sync(p['config'],p['now'],sender=send)=={'synced':1,'refitted':False}
    assert terminal[0]['state']=='FAILED' and not fits
    assert runtime.run_week(p['config'],p['now'],sender=send)['state']=='SKIPPED_ALREADY_ATTEMPTED'


def test_terminal_audit_retry_preserves_exact_payload(configured,monkeypatch):
    p=configured;fits=successful_training(monkeypatch);records=[]
    def once(path,value):
        records.append(deepcopy(value))
        if len(records)>1:raise OSError('terminal response lost')
        return {'saved':True}
    result=runtime.run_week(p['config'],p['now'],sender=once)
    assert result['state']=='SUCCESS' and result['auditPending']
    recovered=[]
    def send(path,value):recovered.append(deepcopy(value));return {'saved':True}
    assert runtime.sync(p['config'],p['now'],sender=send)['synced']==1
    assert recovered==[records[-1]] and len(fits)==1
    assert runtime.sync(p['config'],p['now'],sender=send)['synced']==0


def test_concurrent_worker_does_not_create_an_attempt(configured):
    p=configured;root=Path(p['cfg']['runtime']);root.mkdir()
    with (root/'weekly.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        with pytest.raises(BlockingIOError):runtime.run_week(p['config'],p['now'],sender=lambda *a:{'saved':True})
    assert not (root/'attempts').exists()


def test_abandoned_running_record_without_pending_marker_is_recovered(configured):
    p=configured;directory=Path(p['cfg']['runtime'])/'attempts/abandoned';directory.mkdir(parents=True)
    runtime.atomic_json(directory/'audit.json',dict(state='RUNNING',trainingDate=(p['now']-4*STEP).isoformat(),
        attemptId='abandoned',modelVersion='fixture',promotionDecision='NOT_ATTEMPTED_SHADOW_ONLY'))
    calls=[]
    def send(path,value):calls.append(value);return {'saved':True}
    assert runtime.sync(p['config'],p['now'],sender=send)['synced']==1
    assert calls[0]['state']=='FAILED' and 'Abandoned' in calls[0]['reason']


def test_configuration_change_during_fit_fails_instead_of_success(configured,monkeypatch):
    p=configured
    def changed(*args,**kwargs):
        p['config'].write_text(p['config'].read_text()+'\n')
        return {'commonPairs':48,'candidateMaeNoWorseOnCommonPairs':True}
    monkeypatch.setattr(runtime,'train',changed)
    records=[]
    def send(path,value):records.append(deepcopy(value));return {'saved':True}
    assert runtime.run_week(p['config'],p['now'],sender=send)['state']=='FAILED'
    assert records[-1]['state']=='FAILED'


def test_worse_candidate_is_rejected_without_adoption(configured,monkeypatch):
    successful_training(monkeypatch,better=False)
    result=runtime.run_week(configured['config'],configured['now'],sender=lambda *a:{'saved':True})
    assert result['state']=='REJECTED' and result['promotionDecision']=='NOT_ATTEMPTED_SHADOW_ONLY'


@pytest.mark.parametrize('field,value',[('schemaVersion',True),('validationDays',True),('validationDays',29),('maturityDays',0),('championManifestSha256','bad')])
def test_invalid_config_is_rejected_before_runtime_writes(configured,field,value):
    p=configured;cfg=p['cfg'].copy();cfg[field]=value;p['config'].write_text(json.dumps(cfg))
    with pytest.raises(ValueError):runtime.run_week(p['config'],p['now'],sender=lambda *a:{'saved':True})
    assert not Path(cfg['runtime']).exists()


def test_runtime_may_not_overlap_collector_or_serving_directory(configured):
    p=configured;cfg=p['cfg'].copy();cfg['runtime']=str(p['champion'].parents[1]/'weekly')
    p['config'].write_text(json.dumps(cfg))
    with pytest.raises(ValueError,match='overlaps'):runtime.config(p['config'])


def test_runtime_may_not_use_another_live_home_or_existing_model_storage(configured,tmp_path,monkeypatch):
    p=configured;root=Path(p['cfg']['runtime'])
    monkeypatch.setenv('WATTLY_ML_HOME',str(root.parent))
    with pytest.raises(ValueError,match='configured serving'):runtime.config(p['config'])
    monkeypatch.delenv('WATTLY_ML_HOME')
    (root/'models').mkdir(parents=True)
    with pytest.raises(ValueError,match='serving/database'):runtime.config(p['config'])


def test_render_verifies_disabled_units_and_cannot_install(configured,tmp_path,monkeypatch):
    p=configured;env=tmp_path/'private.env';env.write_text('WATTLY_RUN_API_URL=http://127.0.0.1:1\n');env.chmod(0o600)
    stage=tmp_path/'staged-units';repo=Path(__file__).resolve().parents[2]
    assert runtime.render(p['config'],env,stage,sys.executable,repo)=={'rendered':True,'installed':False,'enabled':False}
    checked=subprocess.run(['systemd-analyze','--user','verify',str(stage/'wattly-price-only-weekly.service'),str(stage/'wattly-price-only-weekly.timer')],capture_output=True,text=True)
    assert checked.returncode==0,checked.stderr
    text=(stage/'wattly-price-only-weekly.service').read_text()
    assert 'ProtectSystem=strict' in text and 'CPUQuota=100%' in text and '47min' in text
    assert 'Asia/Singapore' in (stage/'wattly-price-only-weekly.timer').read_text()
    monkeypatch.setenv('XDG_CONFIG_HOME',str(tmp_path/'config'))
    with pytest.raises(ValueError,match='staging directory'):runtime.render(p['config'],env,tmp_path/'config/systemd/user',sys.executable,repo)
    env.chmod(0o644)
    with pytest.raises(ValueError,match='mode 600'):runtime.render(p['config'],env,tmp_path/'other-units',sys.executable,repo)


def test_new_weekly_payload_uses_real_shared_login_and_rotated_csrf(configured,monkeypatch):
    successful_training(monkeypatch)
    with audit_server() as (base,records):
        monkeypatch.setenv('WATTLY_RUN_API_URL',base);monkeypatch.setenv('WATTLY_RUN_AUTH_MODE','session')
        monkeypatch.setenv('WATTLY_ADMIN_USER','fixture-admin');monkeypatch.setenv('WATTLY_ADMIN_PASSWORD','fixture-password')
        result=runtime.run_week(configured['config'],configured['now'])
    assert result['state']=='SUCCESS' and not result['auditPending']
    assert [r['state'] for r in records]==['RUNNING','SUCCESS']
    assert records[-1]['scores']['adoptionEligible'] is False


def test_scheduled_wrapper_passes_explicit_clock_without_training(configured,tmp_path):
    p=configured;capture=tmp_path/'arguments';fake=tmp_path/'fake-python'
    fake.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$CAPTURE_ARGS"\n');fake.chmod(0o755)
    private=tmp_path/'private.env';private.write_text(f'WATTLY_PYTHON={fake}\nWATTLY_RUN_API_URL=http://127.0.0.1:1\nWATTLY_ADMIN_USER=fixture\nWATTLY_ADMIN_PASSWORD=fixture\n');private.chmod(0o600)
    wrapper=Path(__file__).resolve().parents[2]/'scripts/forecast-price-only-scheduled.sh'
    result=subprocess.run(['bash',str(wrapper),str(private),str(p['config'])],env={**os.environ,'CAPTURE_ARGS':str(capture)},capture_output=True,text=True,timeout=5)
    assert result.returncode==0,result.stderr
    args=capture.read_text().splitlines()
    assert args[:3]==['-m','wattly_ml.weekly_runtime','run']
    assert instant(args[args.index('--as-of')+1])
    assert not Path(p['cfg']['runtime']).exists()
