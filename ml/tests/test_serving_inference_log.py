"""A model crash must be logged, not just mapped to 503."""
import logging
import logging
from fastapi.testclient import TestClient
from wattly_ml.serving import app as serving
from wattly_ml.serving.app import create_app
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload

def test_inference_failure_is_503_and_logged(monkeypatch, caplog):
    def boom(*a): raise RuntimeError('model exploded')
    monkeypatch.setattr(serving, 'predict', boom)
    with TestClient(create_app(lambda: synthetic_bundle())) as client, caplog.at_level(logging.ERROR, logger='wattly_ml'):
        response = client.post('/forecast', json=request_payload())
    assert response.status_code == 503 and response.json()['detail'] == {'code': 'MODEL_INFERENCE_FAILED'}
    assert any(r.message == 'Inference failed' and 'model exploded' in str(r.exc_info[1]) for r in caplog.records)
