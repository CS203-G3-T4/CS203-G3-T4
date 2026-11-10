"""Explicit local experimental release rehearsal. Never writes a live trial/pointer.

Only newly initialized, marked sandbox directories are accepted. All changes
require an expected old release hash. Restart the sandbox server after a switch.
"""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from .artifacts import load_bundle
from .data import digest
from .price_only import PRICE_ONLY_POLICY
from .weekly_runtime import atomic_json


def isolated(root):
    root=Path(root).resolve()
    marker=json.loads((root/'sandbox.json').read_text())
    if marker!={'schemaVersion':1,'mode':'ISOLATED_REHEARSAL','root':str(root)}:
        raise ValueError('Not a marked isolated release sandbox')
    home=os.environ.get('WATTLY_ML_HOME')
    if home:
        home=Path(home).resolve()
        if root!=home and (home in root.parents or root in home.parents):
            raise ValueError('Sandbox overlaps the configured serving runtime')
    if (root/'models').is_symlink() or (root/'models').resolve().parent!=root:
        raise ValueError('Sandbox model directory cannot redirect writes')
    return root


def checked_model(source,pin):
    source=Path(source).resolve()
    if digest(source/'manifest.json')!=pin:raise ValueError('Source model manifest pin mismatch')
    bundle=load_bundle(source)
    manifest=bundle['manifest']
    if (manifest.get('inputPolicy')!=PRICE_ONLY_POLICY or manifest.get('productionEligible') is not False
            or manifest.get('researchRecipe')!='PRICE_183D'):
        raise ValueError('Require an experimental price-only model')
    if digest(source/'manifest.json')!=pin:raise ValueError('Source manifest changed while loading')
    return source,manifest


def copy_model(root,source,pin):
    source,manifest=checked_model(source,pin)
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',manifest['version']) or manifest['version'] in ('.','..'):
        raise ValueError('Unsafe model version')
    destination=root/'models'/manifest['version']
    if destination.parent.resolve()!= (root/'models').resolve() or destination.name in ('.','..'):
        raise ValueError('Unsafe model version')
    if destination.exists():raise ValueError('Sandbox model versions are immutable')
    work=Path(tempfile.mkdtemp(prefix='.release-',dir=root/'models'))
    try:
        for name in ('manifest.json','models.joblib'):shutil.copyfile(source/name,work/name)
        if digest(work/'manifest.json')!=pin:raise ValueError('Copied manifest changed')
        load_bundle(work)
        if digest(source/'manifest.json')!=pin or digest(source/'models.joblib')!=manifest['modelSha256']:
            raise ValueError('Source model changed while copying')
        work.rename(destination)
    except BaseException:
        shutil.rmtree(work,ignore_errors=True);raise
    return {'version':manifest['version'],'manifestSha256':pin}


@contextmanager
def locked(root,expected):
    root=isolated(root)
    with (root/'release.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if digest(root/'experimental-release.json')!=expected:
            raise ValueError('Sandbox release changed; inspect it before proceeding')
        yield root


def initialize(root,source,pin):
    root=Path(root).resolve();source=Path(source).resolve()
    if root==source or root in source.parents or source in root.parents:
        raise ValueError('Sandbox must be separate from its source')
    home=os.environ.get('WATTLY_ML_HOME')
    if home and (Path(home).resolve()==root or Path(home).resolve() in root.parents or root in Path(home).resolve().parents):
        raise ValueError('Sandbox overlaps the serving runtime')
    checked_model(source,pin)
    root.mkdir(parents=True,exist_ok=False,mode=0o700)
    atomic_json(root/'sandbox.json',{'schemaVersion':1,'mode':'ISOLATED_REHEARSAL','root':str(root)})
    (root/'models').mkdir(mode=0o700)
    current=copy_model(root,source,pin)
    atomic_json(root/'experimental-release.json',{'current':current,'previous':None,'kind':'ISOLATED_REHEARSAL'})
    return {'releaseSha256':digest(root/'experimental-release.json'),'current':current,'adoptedInProduction':False}


def stage(root,source,pin,expected):
    with locked(root,expected) as root:
        prior=json.loads((root/'experimental-release.json').read_text())
        # Validate the outgoing release too: no forward transition from corrupt state.
        environment(root)
        source=Path(source).resolve()
        if source==root or root in source.parents or source in root.parents:
            raise ValueError('Incoming source must be outside the sandbox')
        current=copy_model(root,source,pin)
        atomic_json(root/'experimental-release.json',{'current':current,'previous':prior['current'],'kind':'ISOLATED_REHEARSAL'})
        return {'releaseSha256':digest(root/'experimental-release.json'),'current':current,'restartRequired':True,
                'adoptedInProduction':False}


def rollback(root,expected):
    with locked(root,expected) as root:
        prior=json.loads((root/'experimental-release.json').read_text())
        previous=prior['previous']
        if not previous:raise ValueError('No previous sandbox release')
        checked_model(model_path(root,previous),previous['manifestSha256'])
        atomic_json(root/'experimental-release.json',{'current':previous,'previous':prior['current'],'kind':'ISOLATED_REHEARSAL'})
        return {'releaseSha256':digest(root/'experimental-release.json'),'current':previous,'restartRequired':True,
                'adoptedInProduction':False}


def model_path(root,pointer):
    if set(pointer)!={'version','manifestSha256'} or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',pointer['version']):
        raise ValueError('Invalid release model identity')
    path=(root/'models'/pointer['version']).resolve()
    if path.parent!=(root/'models').resolve():raise ValueError('Unsafe release model path')
    return path


def environment(root):
    root=isolated(root);release=json.loads((root/'experimental-release.json').read_text())
    if set(release)!={'current','previous','kind'} or release['kind']!='ISOLATED_REHEARSAL':
        raise ValueError('Invalid sandbox release')
    current=release['current'];path=model_path(root,current)
    checked_model(path,current['manifestSha256'])
    return {'WATTLY_ML_HOME':str(root),'WATTLY_ALLOW_EXPERIMENTAL_AI':'true',
            'WATTLY_EXPERIMENTAL_MODEL_VERSION':current['version'],
            'WATTLY_EXPERIMENTAL_MANIFEST_SHA256':current['manifestSha256']}


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    for action in ('initialize','stage','rollback','serve'):
        p=sub.add_parser(action);p.add_argument('--sandbox',type=Path,required=True)
        if action in ('initialize','stage'):
            p.add_argument('--model',type=Path,required=True);p.add_argument('--manifest-sha256',required=True)
        if action in ('stage','rollback'):p.add_argument('--expected-release-sha256',required=True)
        if action=='serve':p.add_argument('--port',type=int,required=True)
    args=parser.parse_args()
    if args.action=='initialize':result=initialize(args.sandbox,args.model,args.manifest_sha256)
    elif args.action=='stage':result=stage(args.sandbox,args.model,args.manifest_sha256,args.expected_release_sha256)
    elif args.action=='rollback':result=rollback(args.sandbox,args.expected_release_sha256)
    else:
        if not 1024<=args.port<=65535 or args.port in (8001,8080,8877,55449):
            parser.error('Choose a separate unprivileged sandbox port')
        values=environment(args.sandbox);os.environ.update(values)
        import uvicorn
        uvicorn.run('wattly_ml.serving.app:app',host='127.0.0.1',port=args.port,limit_concurrency=4)
        return
    print(json.dumps(result))


if __name__=='__main__':main()
