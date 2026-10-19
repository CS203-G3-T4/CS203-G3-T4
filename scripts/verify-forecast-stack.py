"""Bounded acceptance proof in a new PostgreSQL cluster; never connects to the live DB.

Set JAVA_HOME, WATTLY_PYTHON, WATTLY_PG_BIN, NODE_PATH, PLAYWRIGHT_BROWSERS_PATH.
Run with the dedicated Python. All subprocesses/cluster created here are stopped on exit.
"""
import base64
import argparse
from datetime import datetime, timezone
import functools
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from urllib.request import Request, urlopen

ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ.get('WATTLY_STACK_PROOF',str(ROOT/'.local/acceptance-2026-10-18/stack-v1'))).resolve()
PYTHON=os.environ['WATTLY_PYTHON']
PG=Path(os.environ['WATTLY_PG_BIN'])
ENV={**os.environ,'PYTHONPATH':str(ROOT/'ml/src'),'PYTHONDONTWRITEBYTECODE':'1',
     'OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','MKL_NUM_THREADS':'1'}
ENV['LD_LIBRARY_PATH']=str(PG.parents[2]/'x86_64-linux-gnu')+(':'+ENV['LD_LIBRARY_PATH'] if ENV.get('LD_LIBRARY_PATH') else '')


def run(args,log,timeout=1200,check=True,env=None):
    with (OUT/log).open('w') as out:
        process=subprocess.Popen([str(a) for a in args],cwd=ROOT,env=env or ENV,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            code=process.wait(timeout=timeout)
        except BaseException:
            os.killpg(process.pid,signal.SIGTERM)
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: os.killpg(process.pid,signal.SIGKILL); process.wait()
            raise
        if check and code:
            raise RuntimeError(f'{args[0]} exited{code}; inspect {OUT/log}')
        return code


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); return sock.getsockname()[1]


def get_json(url):
    with urlopen(url,timeout=5) as response: return json.load(response)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-only',action='store_true',help='Skip suites already run; still rebuild and verify the disposable application')
    options=parser.parse_args()
    if OUT.exists(): raise ValueError('Choose a new WATTLY_STACK_PROOF directory')
    OUT.mkdir(parents=True)
    temporary=Path(tempfile.mkdtemp(prefix='wattly-acceptance-'))
    (OUT/'temporary-cluster-location.txt').write_text(str(temporary)+'\n')
    pgdata=temporary/'postgres'; pgport=port(); appport=port(); feedport=port()
    pg_started=False; spring=None; feed=None
    try:
        shared=PG.parents[3]/'share/postgresql'/PG.parent.name
        init=[PG/'initdb','-D',pgdata,'-U','wattly_test','--auth=trust','--no-locale','-E','UTF8']
        if shared.exists(): init+=['-L',shared]
        run(init,'initdb.log',timeout=30)
        run([PG/'pg_ctl','-D',pgdata,'-l',OUT/'postgres.log','-o',f'-p {pgport} -h 127.0.0.1 -k {temporary}','-w','start'],'postgres-start.log',timeout=30)
        pg_started=True
        ENV.update(WATTLY_TEST_DB_URL=f'jdbc:postgresql://127.0.0.1:{pgport}/postgres',WATTLY_TEST_DB_USER='wattly_test',
            WATTLY_TEST_DB_PASSWORD='',WATTLY_PYTHON_SMOKE_EXECUTABLE=PYTHON,WATTLY_UI_SMOKE='true',WATTLY_BROWSER_SMOKE='true')
        if not options.application_only:
            run([ROOT/'mvnw','-q','test'],'java-tests.log')
            run([PYTHON,'-m','pytest','ml/tests','-q','-p','no:cacheprovider','--junitxml',OUT/'python-tests.xml'],'python-tests.log')
            run(['node','--test',*sorted((ROOT/'src/test/js').glob('*.test.js'))],'frontend-tests.log')
        else:
            run([ROOT/'mvnw','-q','test'],'java-tests.log')
            run([PYTHON,'-m','pytest','ml/tests/test_acceptance.py','-q','-p','no:cacheprovider'],'python-focused-tests.log')
        run([ROOT/'mvnw','-q','-DskipTests','package'],'package.log')
        run([PYTHON,'-m','build','ml','--wheel','--no-isolation'],'wheel.log')
        # Main's default must keep working without Python, admin credentials or price history.
        default_port=port(); default_base=f'http://127.0.0.1:{default_port}'
        default_env={**ENV,'DB_URL':f'jdbc:postgresql://127.0.0.1:{pgport}/postgres?currentSchema=main_default',
            'DB_USER':'wattly_test','DB_PASSWORD':'','USEP_POLLING_ENABLED':'false','NEA_DAILY_POLLING_ENABLED':'false',
            'FORECAST_IMPORT_FILE':'','FORECAST_MODE':'LIVE','FORECAST_PYTHON_URL':'http://127.0.0.1:1',
            'WATTLY_ADMIN_USER':'','WATTLY_ADMIN_PASSWORD':''}
        default_env.pop('RECOMMENDATION_FORECAST_SOURCE',None)
        with (OUT/'default-spring.log').open('w') as log:
            spring=subprocess.Popen([str(Path(ENV['JAVA_HOME'])/'bin/java'),'-jar',str(ROOT/'target/energy-market-service-0.0.1-SNAPSHOT.jar'),
                '--server.address=127.0.0.1',f'--server.port={default_port}','--spring.flyway.default-schema=main_default'],
                cwd=ROOT,env=default_env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            deadline=time.monotonic()+60
            while time.monotonic()<deadline:
                if spring.poll() is not None: raise RuntimeError('Default Spring startup failed; see default-spring.log')
                try:
                    if get_json(default_base+'/actuator/health')['status']=='UP': break
                except OSError: pass
                time.sleep(.25)
            else: raise RuntimeError('Default app did not become healthy')
            with urlopen(Request(default_base+'/api/v1/households/1/recommendations/generate',data=b'',method='POST'),timeout=10) as response:
                demo=json.load(response)
            assert demo['demoForecast'] is True and demo['forecastSource']=='DEMO_FORECAST', 'Main recommendation default regressed'
            assert get_json(default_base+'/api/v1/households/3/budget')['rateSource']=='FIXED_PLAN'
            assert get_json(default_base+'/api/v1/forecast/latest')['available'] is False
            for path in ('dashboard.html','recommendations.html','spending.html','appliances.html','settings.html','forecast.html'):
                with urlopen(default_base+'/'+path,timeout=5) as response: assert response.status==200
            (OUT/'default-startup-proof.json').write_text(json.dumps({'healthyWithoutPythonOrHistory':True,
                'mainDemoDefaultPreserved':True,'fixedRateBudgetPreserved':True,'existingPagesAvailable':True,
                'freshDatabaseForecastHonestlyUnavailable':True},indent=2)+'\n')
            os.killpg(spring.pid,signal.SIGTERM); spring.wait(timeout=15); spring=None
        sys.path.insert(0,str(ROOT/'ml/src'))
        from wattly_ml.data import snapshot, prepare
        source=Path(os.environ.get('COLLECTOR_DATA_DIR','/home/bryan/cs203/data'))/'collector.sqlite3'
        snap=snapshot(source,OUT/'snapshots','sqlite')
        prepare([snap],OUT/'collector-dataset')
        with sqlite3.connect((snap/'collector.sqlite3').as_uri()+'?mode=ro',uri=True) as db:
            raw=db.execute('SELECT payload_json FROM events ORDER BY source_updated DESC LIMIT1'.replace('LIMIT1','LIMIT 1')).fetchone()[0].encode()
        class Feed(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path=='/price':
                    self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
                else: self.send_response(503);self.end_headers()
            def log_message(self,*args): pass
        feed=ThreadingHTTPServer(('127.0.0.1',feedport),Feed)
        threading.Thread(target=feed.serve_forever,daemon=True).start()
        password=secrets.token_urlsafe(24)
        ENV.update(DB_URL=f'jdbc:postgresql://127.0.0.1:{pgport}/postgres?currentSchema=resident_proof',DB_USER='wattly_test',DB_PASSWORD='',
            USEP_FEED_URL=f'http://127.0.0.1:{feedport}/price',NEA_FEED_URL=f'http://127.0.0.1:{feedport}/weather-unavailable',
            USEP_POLLING_ENABLED='true',NEA_DAILY_POLLING_ENABLED='false',FORECAST_MODE='LIVE',
            FORECAST_IMPORT_FILE=str(OUT/'collector-dataset/prices.jsonl'),FORECAST_PERIOD_MAPPING_EVIDENCE='EMC_RT_20260929_24_PERIODS',
            FORECAST_PYTHON_URL='http://127.0.0.1:1',WATTLY_ADMIN_USER='local-proof-admin',WATTLY_ADMIN_PASSWORD=password,WATTLY_FORECAST_LOCAL_ADMIN_ENABLED='true',
            RECOMMENDATION_FORECAST_SOURCE='f3',
            WATTLY_RUN_API_URL=f'http://127.0.0.1:{appport}',WATTLY_BROWSER_PROOF_SOURCE='ISOLATED_APP_WITH_ARCHIVED_REAL_COLLECTOR_PRICES')
        private=OUT/'private-runtime.env'
        with private.open('w') as stream:
            for name in ['DB_URL','DB_USER','DB_PASSWORD','WATTLY_RUN_API_URL','WATTLY_ADMIN_USER','WATTLY_ADMIN_PASSWORD']:
                stream.write('export '+name+'='+shlex.quote(ENV[name])+'\n')
        private.chmod(0o600)
        springlog=(OUT/'spring.log').open('w')
        spring=subprocess.Popen([str(Path(ENV['JAVA_HOME'])/'bin/java'),'-jar',str(ROOT/'target/energy-market-service-0.0.1-SNAPSHOT.jar'),
            '--server.address=127.0.0.1',f'--server.port={appport}','--spring.flyway.default-schema=resident_proof'],cwd=ROOT,env=ENV,stdout=springlog,stderr=subprocess.STDOUT,start_new_session=True)
        base=ENV['WATTLY_RUN_API_URL']
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            if spring.poll() is not None: raise RuntimeError('Spring exited; see spring.log')
            try:
                if get_json(base+'/api/v1/forecast/latest').get('run'): break
            except OSError: pass
            time.sleep(.25)
        else: raise RuntimeError('No saved real-data forecast or explicit stale/unavailable run within90seconds')
        from wattly_ml.operations import post_admin
        os.environ.update({k:ENV[k] for k in ['WATTLY_RUN_API_URL','WATTLY_ADMIN_USER','WATTLY_ADMIN_PASSWORD']})
        studies=ROOT/'.local/acceptance-2026-10-18/reports'
        ranking=json.loads((studies/'baselines-three-months-v2/baseline-validation.json').read_text())
        post_admin('forecast-baseline-validation',ranking)
        # Re-run the existing local command to persist the newly validated baseline order.
        refresh={**ENV,'FORECAST_IMPORT_FILE':'','USEP_POLLING_ENABLED':'false'}
        run([Path(ENV['JAVA_HOME'])/'bin/java','-jar',ROOT/'target/energy-market-service-0.0.1-SNAPSHOT.jar',
            '--spring.main.web-application-type=none','--spring.flyway.default-schema=resident_proof',
            '--forecast.demo.run-once=true'],'refresh.log',env=refresh,timeout=60)
        run(['node',ROOT/'scripts/forecast-pages-browser.cjs',base,OUT/'browser'],'browser.log',timeout=90)
        run(['node',ROOT/'scripts/verify-main-sync-browser.cjs',base,OUT/'main-browser'],'main-browser.log',timeout=90)
        profiles=get_json(base+'/api/v1/households')
        households=[p['id'] for p in profiles if not p.get('simulated',False)]
        (OUT/'frozen-household-cohort.json').write_text(json.dumps(profiles,indent=2))
        if not households: raise RuntimeError('No configured F2 household scenarios')
        model=ROOT/'.local/acceptance-2026-10-18/runtime/models/price-calendar-holiday-demand-v2-20261018/manifest.json'
        run([PYTHON,'-m','wattly_ml.cli','savings-study',studies/'unseen-weeks-v1/predictions.jsonl',
            '--validation',studies/'validation-v1/summary.json','--model-manifest',model,'--households',*map(str,households),
            '--output',studies/('household-savings-'+OUT.name)],'savings.log',timeout=600)
        # Real operational scheduled attempt: short/gappy provisional history must fail safely and be audited.
        scheduled={**ENV,'WATTLY_ML_HOME':str(OUT/'weekly-runtime'),'COLLECTOR_DATA_DIR':str(source.parent),
            'WATTLY_PROTECTED_START':'2026-07-29T00:00:00+08:00','WATTLY_PROTECTED_END':'2026-09-29T00:00:00+08:00'}
        code=run([PYTHON,'-m','wattly_ml.scheduled'],'weekly-attempt.log',env=scheduled,timeout=120,check=False)
        if code!=2: raise RuntimeError('Short-data scheduled candidate should retain the champion and fail safely')
        weekly_env=OUT/'private-weekly.env'
        weekly_values={k:v for k,v in scheduled.items() if k.startswith('WATTLY_') or k=='COLLECTOR_DATA_DIR'}
        weekly_env.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in weekly_values.items()))
        weekly_env.chmod(0o600)
        run(['bash',ROOT/'scripts/install-forecast-timer.sh','render',weekly_env,OUT/'systemd-units'],'timer-units.log',timeout=15)
        unit='wattly-acceptance-'+secrets.token_hex(6)
        try:
            run(['systemd-run','--user','--unit='+unit,'--on-active=2s','--timer-property=AccuracySec=1s',
                '--property=TimeoutStartSec=47min','--property=CPUQuota=100%', '--property=MemoryMax=2G',
                '--property=NoNewPrivileges=true','--property=PrivateTmp=true','--property=ProtectSystem=strict',
                '--property=ReadWritePaths='+scheduled['WATTLY_ML_HOME'], '--working-directory='+str(ROOT),
                '/usr/bin/bash',ROOT/'scripts/forecast-weekly-scheduled.sh',weekly_env],'timer-start.log',timeout=15)
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                state=subprocess.check_output(['systemctl','--user','show',unit+'.service','-p','ActiveState','-p','ExecMainStatus','-p','Result'],text=True)
                if 'ActiveState=failed' in state or 'ActiveState=inactive' in state and 'Result=exit-code' in state: break
                time.sleep(.5)
            (OUT/'timer-result.txt').write_text(state)
            if 'ExecMainStatus=2' not in state: raise RuntimeError('Native timer did not run the expected audited short-data rejection: '+state)
        finally:
            run(['systemctl','--user','stop',unit+'.timer',unit+'.service'],'timer-stop.log',timeout=15,check=False)
            run(['systemctl','--user','reset-failed',unit+'.service'],'timer-reset.log',timeout=15,check=False)
        run([PG/'psql','-h','127.0.0.1','-p',str(pgport),'-U','wattly_test','-d','postgres','-X','-A','-t','-c',
            "SELECT outcome,promotion_decision,reason FROM resident_proof.model_run WHERE attempt_id IS NOT NULL"],'database-attempts.txt',timeout=10)
        (OUT/'proof.json').write_text(json.dumps({'status':'PASS','isolatedPostgres':True,'postgresVersion':PG.parent.name,
            'realSpringPythonHttp':True,'realBrowserPages':True,'weeklyShortDataAttemptExit':code,
            'nativeTimerFired':True,'fullSuitesInThisInvocation':not options.application_only,
            'mainDashboardRecommendationsBudgetPages':True,
            'defaultStartupWithoutPythonOrHistory':True,'mainRecommendationDefaultPreserved':True,
            'configuredHouseholdScenarios':households,'collectorModified':False,'liveDatabaseUsed':False,
            'archivedCollectorFeed':'Original payload/timestamps preserved; stale inputs remain unavailable, never made fresh for the proof'},indent=2))
        print(str(OUT/'proof.json'))
    finally:
        if spring is not None and spring.poll() is None:
            os.killpg(spring.pid,signal.SIGTERM)
            try: spring.wait(timeout=15)
            except subprocess.TimeoutExpired: os.killpg(spring.pid,signal.SIGKILL); spring.wait()
        if feed: feed.shutdown();feed.server_close()
        if pg_started: run([PG/'pg_ctl','-D',pgdata,'-m','fast','-w','stop'],'postgres-stop.log',timeout=30,check=False)


if __name__=='__main__': main()
