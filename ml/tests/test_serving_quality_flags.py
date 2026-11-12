"""Pins the exact /forecast qualityFlags per manifest case (characterisation of the original expression)."""
import pytest
from fastapi.testclient import TestClient
from wattly_ml.price_demand import PRICE_DEMAND_POLICY
from wattly_ml.serving import app as serving
from wattly_ml.serving.app import create_app
from wattly_ml.timebase import STEP, instant, targets
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload

NO_AUX = 'COMPLETED_PRICE_CALENDAR_HOLIDAY_NO_AUXILIARY_V1'
DEMAND = ['PRICE_CALENDAR_HOLIDAY_DEMAND']
SOLAR = ['PRICE_CALENDAR_HOLIDAY_SOLAR', 'SOLAR_PUBLIC_PROVISIONAL_INPUTS', 'COMPLETED_PERIOD_PRICE_INPUTS']
# (manifest changes, expected-demand targets supplied, experimental, expected flags)
CASES = {
    'price_demand_policy': ({'inputPolicy': PRICE_DEMAND_POLICY}, 0, True,
        ['PRICE_CALENDAR_HOLIDAY_DEMAND', 'COMPLETED_PERIOD_PRICE_INPUTS', 'AS_ISSUED_EXPECTED_DEMAND', 'EXPERIMENTAL_AI']),
    'no_auxiliary_policy': ({'inputPolicy': NO_AUX}, 0, False,
        ['PRICE_CALENDAR_HOLIDAY_ONLY', 'COMPLETED_PERIOD_PRICE_INPUTS', 'EXPECTED_DEMAND_OMITTED_BY_RECIPE']),
    'schema3': ({'featureSchemaVersion': 3}, 0, True, SOLAR + ['EXPERIMENTAL_AI']),
    'schema4': ({'featureSchemaVersion': 4}, 0, True, SOLAR + ['AS_ISSUED_EXPECTED_DEMAND', 'EXPERIMENTAL_AI']),
    'schema1': ({'featureSchemaVersion': 1}, 0, False, ['PRICE_CALENDAR_ONLY']),
    'schema_missing': ({'featureSchemaVersion': None}, 0, False, ['PRICE_CALENDAR_ONLY']),
    'schema2_partial': ({}, 23, False, DEMAND + ['EXPECTED_DEMAND_PARTIAL_OR_MISSING']),
    'schema2_none': ({}, 0, False, DEMAND + ['EXPECTED_DEMAND_PARTIAL_OR_MISSING']),
    'schema2_full': ({}, 24, False, DEMAND),
    'schema2_retrospective': ({'retrospective': True}, 24, False, DEMAND + ['RETROSPECTIVE_TRAINING']),
    'schema2_retro_experimental': ({'retrospective': True}, 24, True, DEMAND + ['RETROSPECTIVE_TRAINING', 'EXPERIMENTAL_AI']),
    'schema1_retro_experimental': ({'featureSchemaVersion': 1, 'retrospective': True}, 0, True,
        ['PRICE_CALENDAR_ONLY', 'RETROSPECTIVE_TRAINING', 'EXPERIMENTAL_AI']),
}

@pytest.mark.parametrize('name', CASES)
def test_quality_flags_per_manifest_case(name, monkeypatch):
    changes, n_demand, experimental, expected = CASES[name]
    payload = request_payload(); origin = instant(payload['asOf'])
    payload['expectedDemand'] = [{'targetPeriod': t.isoformat(), 'publishedAt': (origin-STEP).isoformat(),
        'availableAt': origin.isoformat(), 'demandMw': 6000, 'source': 'SYNTHETIC'} for t in targets(origin)[:n_demand]]
    bundle = synthetic_bundle(); bundle['manifest']['version'] = 'SYNTHETIC_FLAGS_TEST'
    for key, value in changes.items():
        if value is None: bundle['manifest'].pop(key)
        else: bundle['manifest'][key] = value
    monkeypatch.setattr(serving, 'predict', lambda *a: [1.0]*24)
    monkeypatch.setattr('wattly_ml.solar.read_solar_snapshot', lambda *a: ({}, None))
    app = create_app(lambda: bundle)
    with TestClient(app) as client:
        app.state.experimental = experimental  # loader apps are never experimental; flip it after startup
        response = client.post('/forecast', json=payload)
    assert response.status_code == 200, response.text
    assert response.json()['qualityFlags'] == expected
