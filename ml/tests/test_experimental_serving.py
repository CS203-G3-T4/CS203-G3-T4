from copy import deepcopy
from pathlib import Path
from fastapi.testclient import TestClient
import pytest
from wattly_ml.artifacts import save_bundle, current_bundle
from wattly_ml.data import digest
from wattly_ml.serving.app import create_app
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload


def configure(tmp_path, monkeypatch, *, approved=False):
    b=synthetic_bundle(7);b['manifest']['productionEligible']=approved
    save_bundle(b,tmp_path/'models','local-experimental')
    monkeypatch.setenv('WATTLY_ML_HOME',str(tmp_path))
    monkeypatch.setenv('WATTLY_ALLOW_EXPERIMENTAL_AI','true')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MODEL_VERSION','local-experimental')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MANIFEST_SHA256',digest(tmp_path/'models/local-experimental/manifest.json'))
    return tmp_path/'models/local-experimental/manifest.json'


def test_explicit_opt_in_serves_real_bundle_without_approving_it(tmp_path,monkeypatch):
    manifest=configure(tmp_path,monkeypatch);before=manifest.read_bytes()
    with TestClient(create_app()) as client:
        assert client.get('/ready').json()['servingMode']=='EXPERIMENTAL'
        r=client.post('/forecast',json=request_payload())
        assert r.status_code==200 and len(r.json()['points'])==24
        assert 'EXPERIMENTAL_AI' in r.json()['qualityFlags']
        assert r.json()['manifest']['productionEligible'] is False
    assert manifest.read_bytes()==before and not (tmp_path/'current.json').exists()
    with pytest.raises(FileNotFoundError):current_bundle(tmp_path)


def test_default_does_not_serve_unapproved_bundle(tmp_path,monkeypatch):
    configure(tmp_path,monkeypatch);monkeypatch.delenv('WATTLY_ALLOW_EXPERIMENTAL_AI')
    with TestClient(create_app()) as c:assert c.get('/ready').status_code==503


def test_changed_manifest_fails_closed(tmp_path,monkeypatch):
    manifest=configure(tmp_path,monkeypatch)
    with TestClient(create_app()) as c:
        assert c.get('/ready').status_code==200
        manifest.write_text(manifest.read_text()+' ')
        assert c.get('/ready').status_code==503
        assert c.post('/forecast',json=request_payload()).status_code==503


def test_approved_model_does_not_leak_through_failed_experimental_startup(tmp_path,monkeypatch):
    configure(tmp_path,monkeypatch,approved=True)
    with TestClient(create_app()) as c:
        assert c.get('/ready').status_code==503
        assert c.post('/forecast',json=request_payload()).status_code==503
