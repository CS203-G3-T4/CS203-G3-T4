from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest
from wattly_ml import portable_weekly as portable
from wattly_ml import weekly_runtime as runtime
from wattly_ml import weekly_sources as sources
from wattly_ml.data import digest,load_dataset
from test_weekly_runtime import prepared,configured,audit_server,successful_training


@pytest.fixture
def deployment(configured,monkeypatch):
    p=configured
    for key in list(os.environ):
        if key.startswith(('WATTLY_WEEKLY_','WATTLY_ADMIN_','WATTLY_TRAINING_')):monkeypatch.delenv(key)
    values=dict(WATTLY_WEEKLY_HOME=p['cfg']['runtime'],WATTLY_WEEKLY_CHAMPION=str(p['champion']),
        WATTLY_WEEKLY_CHAMPION_SHA256=p['pin'],WATTLY_WEEKLY_SOURCE='sqlite',
        WATTLY_WEEKLY_COLLECTOR=str(p['db']),WATTLY_WEEKLY_STORAGE_READY='true',
        WATTLY_WEEKLY_SINGLE_WORKER='true',WATTLY_ADMIN_USER='fixture-admin',
        WATTLY_ADMIN_PASSWORD='fixture-password',WATTLY_RUN_API_URL='http://127.0.0.1:1')
    for key,value in values.items():monkeypatch.setenv(key,value)
    return p


def test_portable_readiness_and_identical_legacy_training_pairs(deployment,monkeypatch):
    p=deployment;path,cfg=portable.settings()
    before={s:digest(s) for s in (p['db'],p['champion']/'manifest.json',p['champion']/'models.joblib')}
    portable.credentials();check=portable.check(path,cfg)
    assert check['ready'] and check['exclusiveProcessLockVerified'] and not check['trained']
    records=[]
    def send(endpoint,value):records.append(deepcopy(value));return {'saved':True}
    result=runtime.run_week(path,p['now'],sender=send)
    assert result['state'] in ('SUCCESS','REJECTED') and result['commonPairs']==312*24
    assert [r['state'] for r in records]==['RUNNING',result['state']]
    assert records[-1]['scores']['adoptionEligible'] is False
    assert runtime.run_week(path,p['now'],sender=send)['state']=='SKIPPED_ALREADY_ATTEMPTED'
    assert all(digest(s)==value for s,value in before.items())


def test_default_disabled_and_reserved_score_gate_create_no_attempt(deployment,monkeypatch):
    p=deployment;path,cfg=portable.settings()
    assert portable.execute('run',path,cfg,p['now'])=={'state':'SKIPPED_DISABLED','refitted':False}
    monkeypatch.setenv('WATTLY_WEEKLY_ENABLED','true')
    monkeypatch.setenv('WATTLY_WEEKLY_NOT_BEFORE','2026-11-08T20:00:00+08:00')
    assert portable.execute('run',path,cfg,p['now'])=={'state':'SKIPPED_NOT_BEFORE','refitted':False}
    assert not (cfg['runtime']/'attempts').exists()


@pytest.mark.parametrize('name',['WATTLY_WEEKLY_STORAGE_READY','WATTLY_WEEKLY_SINGLE_WORKER'])
def test_portable_preflight_requires_deployment_storage_and_single_worker(deployment,monkeypatch,name):
    path,cfg=portable.settings();monkeypatch.delenv(name)
    with pytest.raises(ValueError):portable.check(path,cfg)
    assert not (cfg['runtime']/'attempts').exists()


def test_relative_and_overlapping_paths_refused_before_writing(deployment,monkeypatch):
    p=deployment;monkeypatch.setenv('WATTLY_WEEKLY_HOME','relative')
    with pytest.raises(ValueError,match='absolute'):portable.settings()
    monkeypatch.setenv('WATTLY_WEEKLY_HOME',str(p['champion'].parent/'weekly'))
    with pytest.raises(ValueError,match='overlaps'):portable.settings()
    assert not (p['champion'].parent/'weekly').exists()


def test_portable_configuration_is_pinned_and_contains_no_credentials(deployment,monkeypatch):
    monkeypatch.setenv('WATTLY_WEEKLY_SOURCE','postgres')
    monkeypatch.setenv('WATTLY_TRAINING_DATABASE_URL','postgresql://secret-user:secret-pass@private/db')
    path,cfg=portable.settings();assert portable.settings()[0]==path
    assert 'secret-user' not in path.read_text() and 'secret-pass' not in path.read_text()
    assert cfg['source']['dsnEnvironment']=='WATTLY_TRAINING_DATABASE_URL'
    path.write_text('{}')
    with pytest.raises(ValueError,match='Stored weekly configuration changed'):portable.settings()


def test_mounted_secrets_shared_session_and_transport_rules(deployment,monkeypatch,tmp_path):
    password=tmp_path/'private-secret';password.write_text('mounted-fixture-password\n')
    monkeypatch.delenv('WATTLY_ADMIN_PASSWORD');monkeypatch.setenv('WATTLY_ADMIN_PASSWORD_FILE',str(password))
    portable.credentials();assert os.environ['WATTLY_ADMIN_PASSWORD']=='mounted-fixture-password'
    with pytest.raises(ValueError,match='Choose a variable or secret file'):portable.credentials()
    monkeypatch.delenv('WATTLY_ADMIN_PASSWORD_FILE');monkeypatch.setenv('WATTLY_RUN_AUTH_MODE','local-basic')
    with pytest.raises(ValueError,match='session'):portable.credentials()
    monkeypatch.setenv('WATTLY_RUN_AUTH_MODE','session');monkeypatch.setenv('WATTLY_RUN_API_URL','http://untrusted.example')
    with pytest.raises(ValueError,match='HTTPS'):portable.credentials()


def test_model_pin_failure_stops_before_attempt(deployment):
    path,cfg=portable.settings();cfg['championManifestSha256']='0'*64
    with pytest.raises(ValueError,match='pin mismatch'):portable.check(path,cfg)
    assert not (cfg['runtime']/'attempts').exists()


def test_filesystem_without_working_locks_fails_preflight(deployment,monkeypatch):
    path,cfg=portable.settings()
    class Unlocked:returncode=0
    monkeypatch.setattr(portable.subprocess,'run',lambda *a,**k:Unlocked())
    with pytest.raises(ValueError,match='exclusive process locks'):portable.check(path,cfg)


def test_portable_command_uses_real_shared_session_and_retains_one_weekly_attempt(deployment,monkeypatch):
    p=deployment
    with audit_server() as (base,records):
        monkeypatch.setenv('WATTLY_RUN_API_URL',base);monkeypatch.setenv('WATTLY_WEEKLY_ENABLED','true')
        command=[sys.executable,'-m','wattly_ml.portable_weekly','run','--as-of',p['now'].isoformat()]
        env={**os.environ,'PYTHONPATH':str(Path(__file__).resolve().parents[1]/'src')}
        first=subprocess.run(command,env=env,capture_output=True,text=True,timeout=75)
        second=subprocess.run(command,env=env,capture_output=True,text=True,timeout=30)
    assert first.returncode==0,first.stderr
    assert second.returncode==0,second.stderr
    assert json.loads(first.stdout)['commonPairs']==312*24
    assert json.loads(second.stdout)['state']=='SKIPPED_ALREADY_ATTEMPTED'
    assert len(records)==2 and records[0]['state']=='RUNNING'
    assert 'fixture-password' not in first.stdout+first.stderr


def test_database_error_never_enters_persisted_audit(deployment,monkeypatch):
    p=deployment;monkeypatch.setenv('WATTLY_WEEKLY_SOURCE','postgres');path,cfg=portable.settings()
    def unavailable(*_):raise OSError('postgresql://admin:DO_NOT_LEAK@private/db')
    monkeypatch.setattr(sources,'postgres_connection',unavailable);records=[]
    def send(endpoint,value):records.append(deepcopy(value));return {'saved':True}
    result=runtime.run_week(path,p['now'],sender=send)
    assert result['state']=='FAILED' and len(records)==2
    assert 'DO_NOT_LEAK' not in json.dumps(records)
    assert 'PostgreSQL acquisition failed' in records[-1]['reason']


def test_audit_recovery_does_not_depend_on_model_or_source_readiness(deployment,monkeypatch):
    p=deployment;path,cfg=portable.settings()
    def offline(*_):raise OSError('fixture audit outage')
    failed=runtime.run_week(path,p['now'],sender=offline)
    assert failed['state']=='FAILED' and failed['auditPending']
    def unavailable(*_):raise AssertionError('Audit recovery must not load the model/source')
    monkeypatch.setattr(portable,'check',unavailable)
    with audit_server() as (base,records):
        monkeypatch.setenv('WATTLY_RUN_API_URL',base)
        assert portable.execute('sync',path,cfg,p['now'])=={'synced':1,'refitted':False}
    assert len(records)==1 and records[0]['state']=='FAILED'


def test_invalid_v2_source_and_legacy_unit_adapter_are_rejected(deployment):
    path,cfg=portable.settings()
    with pytest.raises(ValueError,match='portable_weekly'):runtime.filesystem_guard(path)
    with pytest.raises(ValueError,match='portable scheduler'):runtime.render(path,Path('unused'),Path('unused'),sys.executable,Path('unused'))
    raw=json.loads(path.read_text());raw['source']['unexpected']='value';path.write_text(json.dumps(raw))
    with pytest.raises(ValueError,match='source configuration'):runtime.config(path)


def test_hard_timeout_does_not_spawn_another_candidate(monkeypatch):
    calls=[]
    class Child:
        def wait(self,timeout=None):
            calls.append(('wait',timeout))
            if timeout==45*60:raise subprocess.TimeoutExpired('fixture',timeout)
            return 2
        def terminate(self):calls.append('terminate')
        def poll(self):return 2
    monkeypatch.setattr(portable.subprocess,'Popen',lambda *a,**k:Child())
    monkeypatch.setattr(sys,'argv',['portable','run','--as-of','2026-01-21T03:00:00+08:00'])
    assert portable.main()==124
    assert calls==[('wait',2700),'terminate',('wait',30)]
