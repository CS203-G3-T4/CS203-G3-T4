import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from urllib.request import Request,urlopen

import pytest
from wattly_ml import weekly_sandbox as sandbox
from wattly_ml.artifacts import load_bundle,save_bundle
from wattly_ml.data import digest
from wattly_ml.timebase import instant
from test_price_only_weekly import prepared


def candidate(p,tmp_path):
    bundle=load_bundle(p['champion'])
    for model in bundle['models']:model.fit([[1.]*19,[2.]*19],[200.,200.])
    save_bundle(bundle,tmp_path/'candidate-source','new-candidate')
    path=tmp_path/'candidate-source/new-candidate'
    return path,digest(path/'manifest.json')


def request(p):
    return dict(schemaVersion=2,requestId='isolated-release-test',asOf=p['as_of'].isoformat(),
        intervalMinutes=30,horizonCount=24,mappingVerified=True,
        priceHistory=[{k:r[k] for k in ('periodStart','sourceUpdatedAt','availableAt','usep')}
                      for r in p['rows'] if instant(r['availableAt'])<=p['as_of']])


def actual_server(root,p,tmp_path):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    assert port not in (8001,8080,8877,55449)
    log=(tmp_path/f'server-{port}.log').open('w')
    proc=subprocess.Popen([sys.executable,'-m','wattly_ml.weekly_sandbox','serve','--sandbox',str(root),'--port',str(port)],
        stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','MKL_NUM_THREADS':'1'})
    try:
        for _ in range(100):
            if proc.poll() is not None:raise AssertionError('Sandbox server exited: '+(tmp_path/f'server-{port}.log').read_text())
            try:
                with urlopen(f'http://127.0.0.1:{port}/ready',timeout=.2) as response:
                    assert json.load(response)['servingMode']=='EXPERIMENTAL'
                break
            except OSError:time.sleep(.1)
        else:raise AssertionError('Sandbox readiness timed out')
        with urlopen(Request(f'http://127.0.0.1:{port}/forecast',data=json.dumps(request(p)).encode(),headers={'Content-Type':'application/json'}),timeout=5) as response:
            return json.load(response)
    finally:
        proc.terminate()
        try:proc.wait(timeout=8)
        except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=3)
        log.close()


def test_real_http_restart_adoption_and_rollback_preserve_source(prepared,tmp_path,monkeypatch):
    p=prepared;monkeypatch.delenv('WATTLY_ML_HOME',raising=False)
    root=tmp_path/'isolated';source,pin=candidate(p,tmp_path)
    before={file:digest(file) for file in (p['champion']/'models.joblib',p['champion']/'manifest.json',source/'models.joblib')}
    first=sandbox.initialize(root,p['champion'],p['pin'])
    old=actual_server(root,p,tmp_path)
    assert old['modelVersion']=='frozen' and [r['predictedUsep'] for r in old['points']]==[124.]*24
    second=sandbox.stage(root,source,pin,first['releaseSha256'])
    assert second['restartRequired'] and not second['adoptedInProduction']
    new=actual_server(root,p,tmp_path)
    assert new['modelVersion']=='new-candidate' and [r['predictedUsep'] for r in new['points']]==[200.]*24
    assert new['manifest']['productionEligible'] is False and 'EXPERIMENTAL_AI' in new['qualityFlags']
    third=sandbox.rollback(root,second['releaseSha256'])
    restored=actual_server(root,p,tmp_path)
    assert restored['modelVersion']==old['modelVersion']
    assert restored['points']==old['points']
    assert before=={file:digest(file) for file in before}
    assert not (root/'current.json').exists()
    assert not third['adoptedInProduction']


def test_bad_pin_and_stale_release_leave_current_untouched(prepared,tmp_path):
    p=prepared;root=tmp_path/'isolated';source,pin=candidate(p,tmp_path)
    first=sandbox.initialize(root,p['champion'],p['pin']);before=digest(root/'experimental-release.json')
    with pytest.raises(ValueError,match='pin mismatch'):sandbox.stage(root,source,'0'*64,before)
    assert digest(root/'experimental-release.json')==before
    second=sandbox.stage(root,source,pin,before)
    with pytest.raises(ValueError,match='release changed'):sandbox.rollback(root,before)
    assert digest(root/'experimental-release.json')==second['releaseSha256']


def test_unmarked_or_live_directory_and_existing_sandbox_are_refused(prepared,tmp_path,monkeypatch):
    p=prepared;monkeypatch.setenv('WATTLY_ML_HOME',str(p['champion'].parents[1]))
    with pytest.raises(ValueError,match='serving runtime'):sandbox.initialize(p['champion'].parents[1]/'new',p['champion'],p['pin'])
    with pytest.raises(FileNotFoundError):sandbox.environment(tmp_path/'unmarked')
    root=tmp_path/'isolated';first=sandbox.initialize(root,p['champion'],p['pin'])
    with pytest.raises(FileExistsError):sandbox.initialize(root,p['champion'],p['pin'])
    assert digest(root/'experimental-release.json')==first['releaseSha256']


def test_corrupt_previous_model_blocks_rollback(prepared,tmp_path):
    p=prepared;root=tmp_path/'isolated';source,pin=candidate(p,tmp_path)
    first=sandbox.initialize(root,p['champion'],p['pin']);second=sandbox.stage(root,source,pin,first['releaseSha256'])
    with (root/'models/frozen/models.joblib').open('ab') as stream:stream.write(b'corrupt fixture')
    with pytest.raises(ValueError,match='Corrupt'):sandbox.rollback(root,second['releaseSha256'])
    assert digest(root/'experimental-release.json')==second['releaseSha256']


def test_model_directory_symlink_cannot_redirect_release_writes(prepared,tmp_path):
    p=prepared;root=tmp_path/'isolated';first=sandbox.initialize(root,p['champion'],p['pin'])
    (root/'models').rename(root/'saved-models')
    (root/'models').symlink_to(p['champion'].parent,target_is_directory=True)
    with pytest.raises(ValueError,match='redirect writes'):sandbox.rollback(root,first['releaseSha256'])
    assert digest(root/'experimental-release.json')==first['releaseSha256']


def test_sandbox_server_cannot_use_live_app_ports(tmp_path):
    result=subprocess.run([sys.executable,'-m','wattly_ml.weekly_sandbox','serve','--sandbox',str(tmp_path/'missing'),'--port','8001'],capture_output=True,text=True,timeout=10)
    assert result.returncode==2 and 'separate unprivileged sandbox port' in result.stderr
