"""Bounded weekly price-only worker. Always shadow-only; render does not install."""
import argparse
from datetime import timedelta
import fcntl
import json
import os
from pathlib import Path
import signal
import tempfile

from .data import snapshot, prepare, digest
from .operations import post_admin
from .price_only_weekly import run as train
from .timebase import DAY, SGT, instant


def atomic_json(path, value):
    path=Path(path)
    with tempfile.NamedTemporaryFile(mode='w',dir=path.parent,prefix='.audit-',delete=False) as stream:
        temp=Path(stream.name)
        try:
            json.dump(value,stream,allow_nan=False,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
        except BaseException:
            temp.unlink(missing_ok=True);raise
    os.replace(temp,path)


def config(path):
    value=json.loads(Path(path).read_text())
    keys={'schemaVersion','runtime','champion','championManifestSha256',
          'protectedStart','protectedEnd','mappingEvidence','validationDays','maturityDays'}
    version=value.get('schemaVersion')
    if type(version) is not int or version not in (1,2) or set(value)!=keys|({'collectorDatabase'} if version==1 else {'source'}):
        raise ValueError('Invalid weekly configuration fields')
    if version==2:
        source=value['source']
        if not isinstance(source,dict):raise ValueError('Invalid source configuration')
        if source.get('kind')=='sqlite' and set(source)=={'kind','path'}:
            source_path=Path(source['path']).expanduser()
            if not source_path.is_absolute():raise ValueError('Use an absolute SQLite source path')
            source['path']=source_path.resolve()
        elif source.get('kind')=='postgres' and set(source)=={'kind','dsnEnvironment','name'}:
            if source['dsnEnvironment']!='WATTLY_TRAINING_DATABASE_URL' or source['name']!='NEMS_SN_SG':
                raise ValueError('Use the documented database variable and price source')
        else:raise ValueError('Invalid source configuration')
    for key in ('runtime','champion')+ (('collectorDatabase',) if version==1 else ()):
        raw=Path(value[key]).expanduser()
        if not raw.is_absolute() or '\n' in str(raw) or '\r' in str(raw):
            raise ValueError('Use absolute, single-line weekly paths')
        value[key]=raw.resolve()
    root,champion=(value[k] for k in ('runtime','champion'))
    protected=[champion.parent.parent if version==1 else champion.parent]
    if version==1:protected.append(value['collectorDatabase'].parent)
    elif value['source']['kind']=='sqlite':protected.append(value['source']['path'].parent)
    # Read-only artifact home and collector directories must not overlap runtime writes.
    for source in protected:
        if root==source or root in source.parents or source in root.parents:
            raise ValueError('Weekly runtime overlaps a source/serving directory')
    serving=os.environ.get('WATTLY_ML_HOME')
    if serving:
        serving=Path(serving).resolve()
        if root==serving or root in serving.parents or serving in root.parents:
            raise ValueError('Weekly runtime overlaps the configured serving runtime')
    if any((root/name).exists() for name in ('models','current.json','experimental-release.json','postgres')):
        raise ValueError('Weekly runtime looks like serving/database storage')
    for key in ('validationDays','maturityDays'):
        if type(value[key]) is not int:raise ValueError('Window lengths must be integers')
    if not 7<=value['validationDays']<=28 or not 1<=value['maturityDays']<=7:
        raise ValueError('Require 7–28 validation days and 1–7 mature days')
    start,end=(instant(value[k]) for k in ('protectedStart','protectedEnd'))
    if start>=end:raise ValueError('Invalid protected interval')
    pin=value['championManifestSha256']
    if not isinstance(pin,str) or len(pin)!=64 or any(c not in '0123456789abcdef' for c in pin):
        raise ValueError('Require a champion manifest SHA256')
    value['_sha256']=digest(Path(path))
    return value


class Audit:
    def __init__(self, directory, sender):
        self.path=directory/'audit.json';self.pending=directory/'audit.pending';self.sender=sender

    def send(self,value,required=False):
        atomic_json(self.path,value)
        try:
            saved=self.sender('forecast-training-attempts',value)
            if not isinstance(saved,dict) or saved.get('saved') is not True:
                raise OSError('Training audit endpoint did not acknowledge the record')
            self.pending.unlink(missing_ok=True);return True
        except (OSError,ValueError):
            self.pending.write_text(digest(self.path)+'\n')
            if required:raise
            return False


def run_week(config_path, now, *, sender=post_admin):
    now=instant(now);cfg=config(config_path);root=cfg['runtime']
    root.mkdir(parents=True,exist_ok=True,mode=0o700)
    local=now.astimezone(SGT)
    end=local.replace(hour=0,minute=0,second=0,microsecond=0)-cfg['maturityDays']*DAY
    cutoff=end-cfg['validationDays']*DAY
    year,week,_=local.isocalendar();identity=f'price-only-{year}-W{week:02d}'
    version='weekly-'+identity
    with (root/'weekly.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        attempts=root/'attempts';attempts.mkdir(exist_ok=True)
        directory=attempts/identity
        if directory.exists():
            return {'state':'SKIPPED_ALREADY_ATTEMPTED','attemptId':identity,'refitted':False}
        directory.mkdir(mode=0o700)
        audit=Audit(directory,sender)
        payload=dict(attemptId=identity,modelVersion=version,trainingDate=now.isoformat(),
            trainingStart=(cutoff-183*DAY).isoformat(),trainingEnd=cutoff.isoformat(),
            validationEnd=end.isoformat(),state='RUNNING',reason='',scores={},datasetDigest='',
            promotionDecision='NOT_ATTEMPTED_SHADOW_ONLY',configSha256=cfg['_sha256'])
        previous_handler=None
        def interrupted(*_):raise InterruptedError('Weekly worker interrupted')
        try:
            # No data acquisition/training when the required initial audit cannot be saved.
            audit.send(payload,required=True)
            if signal.getsignal(signal.SIGTERM) is not None:
                try:previous_handler=signal.signal(signal.SIGTERM,interrupted)
                except ValueError:pass  # Tests may invoke this function from a worker thread.
            if digest(Path(config_path))!=cfg['_sha256']:
                raise ValueError('Weekly configuration changed before acquisition')
            if cfg['schemaVersion']==1:
                acquired=snapshot(cfg['collectorDatabase'],directory/'snapshots','sqlite')
                dataset=directory/'dataset';manifest=prepare([acquired],dataset)
            else:
                from .weekly_sources import acquire
                dataset,manifest=acquire(cfg,directory,cutoff,end,now)
            payload['datasetDigest']=manifest['sha256']
            if digest(cfg['champion']/'manifest.json')!=cfg['championManifestSha256']:
                raise ValueError('Weekly champion pin changed')
            summary=train(dataset,cfg['champion'],cfg['championManifestSha256'],directory/'fit',
                cutoff,end,now,protected=(cfg['protectedStart'],cfg['protectedEnd']),
                mapping_evidence=cfg['mappingEvidence'],version=version)
            if digest(Path(config_path))!=cfg['_sha256']:
                raise ValueError('Weekly configuration changed during attempt')
            payload['scores']=summary
            rejected=not summary['commonPairs'] or summary['candidateMaeNoWorseOnCommonPairs'] is not True
            payload.update(state='REJECTED' if rejected else 'SUCCESS',
                reason='No common pairs or candidate MAE worsens; current model retained' if rejected else
                       'Shadow training completed; adoption requires separate review and release')
            synced=audit.send(payload)
            return {'state':payload['state'],'attemptId':identity,'auditPending':not synced,
                    'promotionDecision':payload['promotionDecision'],'commonPairs':summary['commonPairs']}
        except BaseException as exc:
            payload.update(state='FAILED',reason=type(exc).__name__+': '+str(exc))
            audit.send(payload)
            if isinstance(exc,(KeyboardInterrupt,SystemExit)):raise
            return {'state':'FAILED','attemptId':identity,'auditPending':audit.pending.exists(),
                    'promotionDecision':payload['promotionDecision']}
        finally:
            if previous_handler is not None:signal.signal(signal.SIGTERM,previous_handler)


def sync(config_path, now, *, sender=post_admin):
    now=instant(now);root=config(config_path)['runtime'];count=0
    root.mkdir(parents=True,exist_ok=True)
    with (root/'weekly.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        paths=sorted((root/'attempts').glob('*/audit.json'))
        pending=[path for path in paths if path.with_name('audit.pending').exists()
                 or json.loads(path.read_text())['state']=='RUNNING']
        if len(pending)>100:raise ValueError('More than 100 pending attempts; inspect the spool')
        for path in pending:
            marker=path.with_name('audit.pending');value=json.loads(path.read_text())
            if marker.exists() and marker.read_text().strip()!=digest(path):raise ValueError('Pending audit hash changed')
            if value['state']=='RUNNING':
                if now-instant(value['trainingDate'])<=timedelta(hours=1):continue
                value.update(state='FAILED',reason='Abandoned bounded weekly attempt; model retained')
            if Audit(path.parent,sender).send(value,required=True):count+=1
    return {'synced':count,'refitted':False}


def filesystem_guard(config_path):
    """Fail closed when user-manager mount restrictions were silently ignored.

    Called by ExecStartPre in the SAME configured isolation as the worker. No
    model loading, API call or training. statvfs observes effective mount flags.
    """
    cfg=config(config_path);root=cfg['runtime'];settings=Path(config_path).resolve()
    if cfg['schemaVersion']!=1:raise ValueError('Use portable_weekly check for server configuration')
    if root==settings or root in settings.parents:
        raise ValueError('Keep weekly configuration outside writable runtime storage')
    sources=(settings,cfg['collectorDatabase'],cfg['champion']/'manifest.json',cfg['champion']/'models.joblib')
    if not all(os.statvfs(path).f_flag & os.ST_RDONLY for path in sources):
        raise ValueError('Read-only filesystem isolation is not enforced; weekly training refused')
    if os.statvfs(root).f_flag & os.ST_RDONLY:
        raise ValueError('Weekly output runtime is read-only')
    with tempfile.NamedTemporaryFile(dir=root,prefix='.write-readiness-') as stream:
        stream.write(b'READINESS_ONLY');stream.flush();os.fsync(stream.fileno())
    return {'ready':True,'sourceMountsReadOnly':True,'runtimeWritable':True,'trainingStarted':False}


def render(config_path, environment, staging, python, repo):
    cfg=config(config_path)
    if cfg['schemaVersion']!=1:raise ValueError('Use the portable scheduler command for server configuration')
    environment=Path(environment).resolve(strict=True);staging=Path(staging).resolve();repo=Path(repo).resolve()
    if environment.stat().st_mode & 0o777 != 0o600 or environment.stat().st_uid!=os.getuid():
        raise ValueError('Weekly environment must be owner-only mode 600')
    # This command cannot install into a live user/system unit directory.
    unit_paths=[Path(os.environ.get('XDG_CONFIG_HOME',str(Path.home()/'.config')))/'systemd',
                Path('/etc/systemd'),Path('/usr/lib/systemd'),Path('/run/systemd')]
    if any(staging==p or p in staging.parents for p in unit_paths):
        raise ValueError('Render into a separate staging directory, not a systemd directory')
    def quoted(value, *, resolve=True):
        value=str(Path(value).resolve() if resolve else Path(value).expanduser().absolute())
        if '\n' in value or '\r' in value:raise ValueError('Invalid unit path')
        return json.dumps(value,ensure_ascii=False).replace('%','%%')
    service='''[Unit]
Description=Wattly price-only weekly shadow training (no adoption)
After=network.target
[Service]
Type=oneshot
ExecStartPre=%s -m wattly_ml.weekly_runtime guard --config %s
ExecStart=/usr/bin/bash %s %s %s
WorkingDirectory=%s
Environment=%s
Environment=%s
Environment=PYTHONDONTWRITEBYTECODE=1
TimeoutStartSec=47min
CPUQuota=100%%
MemoryMax=2G
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
PrivateUsers=true
ProtectHome=read-only
ProtectSystem=strict
ReadWritePaths=%s
'''%(quoted(python,resolve=False),quoted(config_path),quoted(repo/'scripts/forecast-price-only-scheduled.sh'),quoted(environment),quoted(config_path),
     str(repo).replace('%','%%'),json.dumps('WATTLY_PYTHON='+str(Path(python).expanduser().absolute())).replace('%','%%'),
     json.dumps('PYTHONPATH='+str(repo/'ml/src')).replace('%','%%'),quoted(cfg['runtime']))
    timer='''[Unit]
Description=Wattly price-only weekly shadow schedule
[Timer]
OnCalendar=Sun *-*-* 03:00:00 Asia/Singapore
Persistent=true
Unit=wattly-price-only-weekly.service
[Install]
WantedBy=timers.target
'''
    staging.mkdir(parents=True,exist_ok=False)
    cfg['runtime'].mkdir(parents=True,exist_ok=True,mode=0o700)
    (staging/'wattly-price-only-weekly.service').write_text(service)
    (staging/'wattly-price-only-weekly.timer').write_text(timer)
    return {'rendered':True,'installed':False,'enabled':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    for action in ('run','sync'):
        command=sub.add_parser(action);command.add_argument('--config',type=Path,required=True)
        command.add_argument('--as-of',type=instant,required=True)
    command=sub.add_parser('guard');command.add_argument('--config',type=Path,required=True)
    command=sub.add_parser('render')
    for field in ('config','environment','staging','python','repo'):
        command.add_argument('--'+field,type=Path,required=True)
    args=parser.parse_args()
    if args.action=='guard':result=filesystem_guard(args.config)
    elif args.action=='render':result=render(args.config,args.environment,args.staging,args.python,args.repo)
    else:result=(run_week if args.action=='run' else sync)(args.config,args.as_of)
    print(json.dumps(result))
    return 2 if result.get('state')=='FAILED' or result.get('auditPending') else 0


if __name__=='__main__':raise SystemExit(main())
