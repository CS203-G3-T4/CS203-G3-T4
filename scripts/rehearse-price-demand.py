#!/usr/bin/env python3
"""Synthetic checkout-only rehearsal; starts its own PostgreSQL, Spring and Python.

Never connects to the live app/DB or an upstream collector. All retained evidence
is under a new private directory. Requires Java21+, Maven and PostgreSQL17 tools.
"""
import argparse
from contextlib import contextmanager
from http.cookiejar import CookieJar
import json,os,socket,subprocess,sys,tempfile,time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request,build_opener,HTTPCookieProcessor,urlopen

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--private-root',type=Path,required=True);p.add_argument('--pg-bin',type=Path,required=True)
p.add_argument('--java',default='java');p.add_argument('--javac',default='javac');a=p.parse_args()
repo=Path(__file__).resolve().parents[1];sys.path[:0]=[str(repo/'ml/src'),str(repo/'ml/tests')]
from test_price_demand_training import prepare
from wattly_ml.price_demand_export import export,load_export
from wattly_ml.price_demand_training import fit,evaluate
from wattly_ml.price_demand_savings import replay
from wattly_ml.data import digest
from wattly_ml.timebase import STEP,instant,floor,targets
import psycopg
from psycopg.types.json import Jsonb

a.private_root.mkdir(parents=True,exist_ok=True)
private=Path(tempfile.mkdtemp(prefix='demand-rehearsal-',dir=a.private_root));private.chmod(0o700)
def port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));value=s.getsockname()[1]
    assert value not in (55449,8080,8001,8876,8877)
    return value
pgport,webport,pyport=port(),port(),port()
assert len({pgport,webport,pyport})==3
env={**os.environ,'PYTHONPATH':str(repo/'ml/src'),'PYTHONDONTWRITEBYTECODE':'1',
    'OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','MKL_NUM_THREADS':'1'}
data=private/'postgres';app=None;python=None;pg_started=False;streams=[];report={'syntheticTestOnly':True,'checks':{}}
dsn=f'postgresql://flow_test@127.0.0.1:{pgport}/flow_fixture'
def run(cmd,**kw):return subprocess.run(list(map(str,cmd)),cwd=repo,env=env,check=True,**kw)
def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
def wait_ready(base,path='/actuator/health'):
    for _ in range(180):
        try:
            with urlopen(base+path,timeout=.5) as r:
                value=json.load(r)
                if value.get('status')=='UP' or value.get('ready') is True:return
        except (OSError,ValueError):pass
        time.sleep(.25)
    raise RuntimeError('Private rehearsal service readiness timed out')
def spring(as_of,python_url):
    log=(private/('spring-'+str(len(streams))+'.log')).open('w');streams.append(log)
    cp=str(repo/'target/classes')+':'+str(repo/'target/test-classes')+':'+(private/'classpath.txt').read_text().strip()
    args=[a.java,'-Xmx512m','-cp',cp,'sg.edu.smu.cs203.forecast.DemandRehearsalApplication',
        '--server.address=127.0.0.1','--server.port='+str(webport),'--spring.profiles.active=demo',
        '--wattly.rehearsal.as-of='+instant(as_of).isoformat(),
        f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:{pgport}/flow_fixture?options=-c%20jit%3Doff',
        '--spring.datasource.username=flow_test','--spring.datasource.password=',
        '--market.feed.enabled=false','--weather.daily.enabled=false','--market.feed.url=http://127.0.0.1:1',
        '--weather.feed.url=http://127.0.0.1:1','--weather.daily.url=http://127.0.0.1:1',
        '--forecast.mode=LIVE','--forecast.import-file=','--forecast.emc-demand.snapshot=',
        '--forecast.live-comparison.enabled=false','--forecast.admin.local-basic-enabled=false',
        '--forecast.admin.username=','--forecast.admin.password=','--forecast.python-url='+python_url,
        '--forecast.allow-experimental-ai=true','--forecast.period-mapping-evidence=EMC_RT_20260929_24_PERIODS',
        '--recommendation.auto-recompute=false','--recommendation.forecast-source=f3',
        '--wattly.demo.accounts.admin.username=flow-admin','--wattly.demo.accounts.admin.password=flow-fixture-only',
        '--wattly.demo.accounts.resident.username=flow-resident','--wattly.demo.accounts.resident.password=flow-fixture-only',
        *['--'+n+'=0 0 0 1 1 *' for n in ('market.feed.cron','market.gaps.cron','weather.feed.cron','weather.daily.cron','forecast.live-comparison.cron','recommendation.recompute-cron')]]
    process=subprocess.Popen(args,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
    wait_ready(f'http://127.0.0.1:{webport}');return process
class Session:
    def __init__(self,user):
        self.base=f'http://127.0.0.1:{webport}';self.opener=build_opener(HTTPCookieProcessor(CookieJar()))
        csrf=self.get('/api/v1/csrf')
        body=urlencode(dict(username=user,password='flow-fixture-only',_csrf=csrf['token'])).encode()
        self.opener.open(Request(self.base+'/login',data=body,headers={'Content-Type':'application/x-www-form-urlencoded'}),timeout=15).read()
    def get(self,path):
        with self.opener.open(self.base+path,timeout=20) as r:return json.load(r)
    def post(self,path,body=None):
        csrf=self.get('/api/v1/csrf')
        request=Request(self.base+path,data=json.dumps(body or {}).encode(),method='POST',
            headers={'Content-Type':'application/json',csrf['headerName']:csrf['token']})
        with self.opener.open(request,timeout=20) as r:return json.load(r)
try:
    run([a.pg_bin/'initdb','-D',data,'-U','flow_test','-A','trust','--no-locale','--encoding=UTF8'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=40)
    run([a.pg_bin/'pg_ctl','-D',data,'-l',private/'postgres.log','-o',f'-h 127.0.0.1 -p {pgport} -k /tmp','-w','start'],stdout=subprocess.DEVNULL,timeout=40);pg_started=True
    run([a.pg_bin/'createdb','-h','127.0.0.1','-p',pgport,'-U','flow_test','flow_fixture'],timeout=20)
    with (private/'maven.log').open('w') as log:
        run(['./mvnw','-B','-ntp','-DskipTests','test-compile','dependency:build-classpath','-Dmdep.outputFile='+str(private/'classpath.txt')],stdout=log,stderr=subprocess.STDOUT,timeout=180)
    issues,prices,protocol,frozen=prepare(private)
    now=instant(protocol['testStart'])+STEP/15
    app=spring(now,'http://127.0.0.1:1');resident=Session('flow-resident')
    households=resident.get('/api/v1/households');household=next(h for h in households if h['exposedToWholesalePrice']);hid=household['id']
    app_profile=dict(name='Synthetic flow appliance',type='WASHING_MACHINE',powerKw=10,runMinutes=60,
        flexibility='FLEXIBLE',earliestStart='00:30',mustFinishBy='12:00',usualStart='07:00',runsPerWeek=7,enabled=True)
    appliance=resident.post(f'/api/v1/households/{hid}/appliances',app_profile)
    profile=dict(syntheticTestOnly=True,minimumSavingSgd='0.05',maxActive=3,households=[dict(id=hid,
        exposedToWholesalePrice=True,appliances=[dict(id=appliance['id'],powerKw='10',runMinutes=60,
        earliestStart='00:30',mustFinishBy='12:00',usualStart='07:00',runsPerWeek=7,enabled=True)])])
    profiles=private/'profiles.json';profiles.write_text(json.dumps(profile))
    protocol['savingsProfileSha256']=digest(profiles)
    stop(app);app=None
    with psycopg.connect(dsn) as conn:
        for row in issues:
            origin=instant(row['asOf']);snapshot={**row['inputs'],'_syntheticTestOnly':True}
            conn.execute("""INSERT INTO forecast_run(id,as_of,generated_at,origin_slot,input_revision,input_snapshot,mode,
                model_type,model_version,selected_model,quality_flags,stale,status)
                VALUES (%s,%s,%s,%s,%s,%s,'LIVE','AI',%s,'AI','[]',FALSE,'AVAILABLE')""",
                (row['id'],origin,origin,floor(origin),row['id'],Jsonb(snapshot),protocol['frozenModelVersion']))
        for r in prices:
            conn.execute("""INSERT INTO market_price_revision(source,period_start,published_at,available_at,usep,price_status,time_mapping)
                VALUES ('NEMS_SN_SG',%s,%s,%s,%s,'PROVISIONAL','EMC_PERIOD')""",
                (r['periodStart'],r['sourceUpdatedAt'],r['availableAt'],r['usep']))
    os.environ['FLOW_EXPORT_DATABASE']=dsn
    train=export(protocol,'train',protocol['trainCutoff'],private/'train','FLOW_EXPORT_DATABASE')
    training_inputs,training_prices=load_export(private/'train',protocol,'train')
    fitted=fit(training_inputs,training_prices,protocol,protocol['trainCutoff'],private/'registry',frozen)
    experiment=Path(fitted['experiment'])
    test=export(protocol,'test',protocol['testEnd'],private/'test','FLOW_EXPORT_DATABASE')
    test_inputs,test_prices=load_export(private/'test',protocol,'test')
    evaluated=evaluate(test_inputs,test_prices,protocol,protocol['testEnd'],experiment,frozen)
    report['checks'].update(readOnlyTrainExport=train['actualCompleteOrigins'],readOnlyTestExport=test['actualCompleteOrigins'],
        candidateAndControlFit=True,commonPricePairs=evaluated['commonPairs'])
    # Remove only synthetic study-capture rows in this new disposable database.
    with psycopg.connect(dsn) as conn:
        conn.execute("DELETE FROM forecast_run WHERE id LIKE 'SYNTHETIC_%'")
        raw=test_inputs[0]['inputs']
        for r in raw['priceHistory']:
            conn.execute("""INSERT INTO market_price_revision(source,period_start,published_at,available_at,usep,price_status,time_mapping)
                VALUES ('NEMS_SN_SG',%s,%s,%s,%s,'PROVISIONAL','EMC_PERIOD') ON CONFLICT DO NOTHING""",
                (r['periodStart'],r['sourceUpdatedAt'],r['availableAt'],r['usep']))
        for r in raw['expectedDemand']:
            conn.execute("INSERT INTO expected_demand_revision(target_period,published_at,available_at,demand_mw,source) VALUES (%s,%s,%s,%s,%s)",
                (r['targetPeriod'],r['publishedAt'],r['availableAt'],r['demandMw'],r['source']))
        conn.execute("INSERT INTO market_price(source,interval_start,source_updated_at,fetched_at,usep_sgd_per_mwh,forecast_demand_mw) VALUES ('NEMS_SN_SG',%s,%s,%s,999999,6000)",(floor(now),now,now))
        conn.execute("INSERT INTO ingestion_run(started_at,finished_at,status,source_updated_at) VALUES (%s,%s,'SUCCESS',%s)",(now,now,now))
    log=(private/'python.log').open('w');streams.append(log)
    pyenv={**env,'WATTLY_ML_HOME':str(experiment),'WATTLY_ALLOW_EXPERIMENTAL_AI':'true',
        'WATTLY_EXPERIMENTAL_MODEL_VERSION':'price-demand-v1',
        'WATTLY_EXPERIMENTAL_MANIFEST_SHA256':digest(experiment/'models/price-demand-v1/manifest.json'),
        'WATTLY_SOLAR_SNAPSHOT':str(private/'no-solar-file')}
    python=subprocess.Popen([sys.executable,'-m','uvicorn','wattly_ml.serving.app:app','--host','127.0.0.1','--port',str(pyport)],cwd=repo,env=pyenv,stdout=log,stderr=subprocess.STDOUT)
    wait_ready(f'http://127.0.0.1:{pyport}','/ready')
    app=spring(now,f'http://127.0.0.1:{pyport}');resident=Session('flow-resident')
    for _ in range(80):
        view=resident.get('/api/v1/forecast/latest')
        if view.get('run',{}).get('selectedModel')=='AI':break
        time.sleep(.1)
    assert view['run']['selectedModel']=='AI' and view['actionable'] and len(view['run']['points'])==24,view
    assert view['run']['modelVersion']=='price-demand-v1'
    report['checks']['actualJavaClientAcceptedExperimentalAI']=True
    recommendations=resident.post(f'/api/v1/households/{hid}/recommendations/generate')
    assert recommendations['forecastSource']=='F3_FORECAST' and recommendations['forecastModel']=='AI (experimental)' and recommendations['recommendations'], 'Actual F3 recommendations required'
    suggestion=recommendations['recommendations'][0];accepted=resident.post(f"/api/v1/recommendations/{suggestion['id']}/accept")
    assert accepted['status']=='ACCEPTED'
    pending=resident.get(f'/api/v1/households/{hid}/savings');assert pending['pendingCount']>=1
    report['checks'].update(realF2ApplianceCreated=True,realF4AIRecommendation=True,acceptanceAndPendingSavings=True)
    savings=replay(experiment/'evaluation',profiles,repo,private/'savings-replay',protocol['testEnd'],a.java,a.javac)
    report['checks']['realF4CommonSavingsCases']=savings['commonCases']
    # Missing mandatory demand must route the real Java application to B3.
    stop(app);app=None
    with psycopg.connect(dsn) as conn:conn.execute('DELETE FROM expected_demand_revision WHERE target_period=(SELECT max(target_period) FROM expected_demand_revision)')
    app=spring(now,f'http://127.0.0.1:{pyport}');resident=Session('flow-resident')
    view=resident.get('/api/v1/forecast/latest')
    assert view['run']['selectedModel']=='B3' and view['available'] and len(view['run']['points'])==24,view
    report['checks']['missingDemandFallsBackToB3']=True
    history=resident.get(f'/api/v1/households/{hid}/recommendations?status=ACCEPTED')
    assert any(r['id']==accepted['id'] for r in history['recommendations'])
    report['checks']['fallbackPreservesAcceptedPlan']=True
    stop(app);app=None
    mature=max(instant(accepted['usualStart']),instant(accepted['acceptedStart']))+2*STEP+STEP
    app=spring(mature,f'http://127.0.0.1:{pyport}');resident=Session('flow-resident')
    realised=resident.get(f'/api/v1/households/{hid}/savings')
    assert realised['realisedCount']>=1,realised
    report['checks']['acceptedRunRealisedFromActualReceipts']=True
    report['status']='PASS'
finally:
    stop(app);stop(python)
    for stream in streams:stream.close()
    if pg_started:run([a.pg_bin/'pg_ctl','-D',data,'-m','fast','-w','stop'],stdout=subprocess.DEVNULL,timeout=35)
    report.update(privateEvidence=str(private),isolatedServicesStopped=True,liveServicesStarted=False,
        upstreamRequests=0,jiraChanged=False,realCandidateTrained=False)
    (private/'rehearsal.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
