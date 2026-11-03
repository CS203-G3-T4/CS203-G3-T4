"""Scheduler-independent weekly entry point. No automatic model adoption."""
import argparse
from datetime import datetime,timezone
import importlib.metadata
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile

from .artifacts import load_bundle
from .data import digest
from .operations import audit_url,check_admin_access
from .timebase import instant
from .weekly_runtime import config,run_week,sync


def required(name):
    value=os.environ.get(name)
    if not value:raise ValueError('Set '+name)
    return value


def credentials():
    # Support mounted secrets and platform-injected variables; no shell sourcing.
    for name in ('WATTLY_ADMIN_USER','WATTLY_ADMIN_PASSWORD','WATTLY_TRAINING_DATABASE_URL'):
        file=os.environ.get(name+'_FILE')
        if file:
            if os.environ.get(name):raise ValueError('Choose a variable or secret file for '+name)
            data=Path(file).read_text()
            if len(data)>16384:raise ValueError('Oversized secret file')
            os.environ[name]=data.rstrip('\r\n')
    required('WATTLY_ADMIN_USER');required('WATTLY_ADMIN_PASSWORD')
    if os.environ.get('WATTLY_RUN_AUTH_MODE','session')!='session':
        raise ValueError('Portable training requires shared session authentication')
    os.environ['WATTLY_RUN_AUTH_MODE']='session'
    audit_url(required('WATTLY_RUN_API_URL'))


def settings():
    root=Path(required('WATTLY_WEEKLY_HOME')).expanduser()
    champion=Path(required('WATTLY_WEEKLY_CHAMPION')).expanduser()
    if not root.is_absolute() or not champion.is_absolute():raise ValueError('Use absolute deployment paths')
    kind=os.environ.get('WATTLY_WEEKLY_SOURCE','postgres')
    if kind=='postgres':source=dict(kind=kind,dsnEnvironment='WATTLY_TRAINING_DATABASE_URL',name='NEMS_SN_SG')
    elif kind=='sqlite':source=dict(kind=kind,path=required('WATTLY_WEEKLY_COLLECTOR'))
    else:raise ValueError('Source must be postgres or sqlite')
    value=dict(schemaVersion=2,runtime=str(root),champion=str(champion),
        championManifestSha256=required('WATTLY_WEEKLY_CHAMPION_SHA256'),source=source,
        protectedStart='2026-07-29T00:00:00+08:00',protectedEnd='2026-09-29T00:00:00+08:00',
        mappingEvidence='EMC_RT_20260929_24_PERIODS',validationDays=7,maturityDays=1)
    raw=json.dumps(value,sort_keys=True,allow_nan=False).encode()
    # This contains paths and an environment VARIABLE NAME, never the DSN/password.
    import hashlib
    name=hashlib.sha256(raw).hexdigest()+'.json'
    # Validate in a temporary location before writing to persistent runtime.
    with tempfile.TemporaryDirectory() as temporary:
        probe=Path(temporary)/name;probe.write_bytes(raw);cfg=config(probe)
    root=cfg['runtime'];directory=root/'configuration';directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    path=directory/name
    try:
        with path.open('xb') as stream:stream.write(raw)
        path.chmod(0o600)
    except FileExistsError:
        if path.read_bytes()!=raw:raise ValueError('Stored weekly configuration changed')
    return path,config(path)


def check(path,cfg):
    import fcntl
    root=cfg['runtime']
    if os.environ.get('WATTLY_WEEKLY_STORAGE_READY')!='true':
        raise ValueError('Provision persistent POSIX storage and set WATTLY_WEEKLY_STORAGE_READY=true')
    if os.environ.get('WATTLY_WEEKLY_SINGLE_WORKER')!='true':
        raise ValueError('Use one worker/shared runtime and set WATTLY_WEEKLY_SINGLE_WORKER=true')
    if digest(cfg['champion']/'manifest.json')!=cfg['championManifestSha256']:
        raise ValueError('Champion pin mismatch')
    bundle=load_bundle(cfg['champion'])  # Trusted, operator-provisioned model only.
    if bundle['manifest'].get('researchRecipe')!='PRICE_183D':raise ValueError('Require the price-only champion')
    if digest(cfg['champion']/'manifest.json')!=cfg['championManifestSha256']:
        raise ValueError('Champion changed during readiness check')
    with (root/'weekly.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        # A second process must be excluded by this filesystem's advisory lock.
        code='import fcntl,sys\nf=open(sys.argv[1],"a")\ntry: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\nexcept BlockingIOError: sys.exit(73)\n'
        test=subprocess.run([sys.executable,'-c',code,str(root/'weekly.lock')],capture_output=True,timeout=10)
        if test.returncode!=73:raise ValueError('Storage does not enforce exclusive process locks')
    with tempfile.NamedTemporaryFile(dir=root) as stream:
        stream.write(b'READINESS_ONLY');stream.flush();os.fsync(stream.fileno())
    if cfg['source']['kind']=='postgres':
        from .weekly_sources import postgres_connection
        with postgres_connection(cfg['source']['dsnEnvironment']) as conn:
            if conn.execute('SHOW transaction_read_only').fetchone()[0]!='on':raise ValueError('Database must be read-only')
            conn.execute("SET LOCAL statement_timeout='10s'")
            conn.execute('SELECT id,source,period_start,published_at,available_at,usep,demand,vcp,price_status,time_mapping FROM market_price_revision LIMIT 0')
    else:
        import sqlite3
        with sqlite3.connect(cfg['source']['path'].as_uri()+'?mode=ro',uri=True,timeout=2) as conn:
            conn.execute('SELECT id,payload_json,source_updated,first_seen FROM events LIMIT 0')
    return dict(ready=True,source=cfg['source']['kind'],persistentStorageConfirmed=True,
        singleWorkerConfirmed=True,exclusiveProcessLockVerified=True,modelVersion=bundle['manifest']['version'],
        modelDependencyVersionsVerified=True,trained=False,automaticAdoption=False)


def execute(action,path,cfg,now):
    if action=='check':return check(path,cfg)
    if action=='check-admin':return {**check(path,cfg),**check_admin_access()}
    # Reconcile the exact saved audit even when training data/model storage is
    # unavailable. sync acquires the existing runtime lock and never fits.
    if action=='sync':return sync(path,now)
    if action=='run':
        if os.environ.get('WATTLY_WEEKLY_ENABLED')!='true':
            return dict(state='SKIPPED_DISABLED',refitted=False)
        not_before=os.environ.get('WATTLY_WEEKLY_NOT_BEFORE')
        if not_before and now<instant(not_before):return dict(state='SKIPPED_NOT_BEFORE',refitted=False)
    check(path,cfg)
    return run_week(path,now)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('check','check-admin','run','sync','_worker'))
    parser.add_argument('--as-of',type=instant)
    parser.add_argument('--worker-action',choices=('run','sync'),help=argparse.SUPPRESS)
    args=parser.parse_args()
    now=args.as_of or datetime.now(timezone.utc)  # Scheduling boundary; core receives explicit time.
    try:
        if args.action in ('run','sync'):
            # The same one-shot works with cron, Railway and a container job.
            # A hard bound still applies when no external timeout/systemd exists.
            child=subprocess.Popen([sys.executable,'-m',__spec__.name,'_worker',
                '--worker-action',args.action,'--as-of',now.isoformat()])
            def terminate(*_):child.terminate()
            previous=signal.signal(signal.SIGTERM,terminate)
            try:
                try:return child.wait(timeout=45*60)
                except subprocess.TimeoutExpired:
                    child.terminate()
                    try:child.wait(timeout=30)
                    except subprocess.TimeoutExpired:child.kill();child.wait()
                    print(json.dumps({'state':'FAILED_TIMEOUT','refittedAgain':False}));return 124
            finally:
                signal.signal(signal.SIGTERM,previous)
                if child.poll() is None:child.terminate();child.wait(timeout=30)
        credentials();path,cfg=settings()
        result=execute(args.worker_action if args.action=='_worker' else args.action,path,cfg,now)
        print(json.dumps(result,allow_nan=False))
        return 2 if result.get('state')=='FAILED' or result.get('auditPending') else 0
    except Exception as exc:
        # Never log raw network/connection errors, environment or a traceback.
        reason=str(exc) if isinstance(exc,ValueError) else type(exc).__name__
        print(json.dumps({'ready':False,'error':reason}),file=sys.stderr);return 2


if __name__=='__main__':raise SystemExit(main())
