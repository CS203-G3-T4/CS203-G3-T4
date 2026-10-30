"""Synthetic contract tests, never accuracy or savings evidence."""
from copy import deepcopy
import json
import math
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from sklearn.tree import DecisionTreeRegressor

from wattly_ml.artifacts import load_bundle, save_bundle
from wattly_ml.data import digest
from wattly_ml.demand_candidate import (PARAMETERS, complete_rows, evaluate, fit, identity,
    known_truth, protocol_check, readiness, select_issues, snapshot_history)
from wattly_ml.features import feature_names, features
from wattly_ml.serving.app import create_app
from wattly_ml.solar import SOLAR_DEMAND_POLICY, read_solar_snapshot
from wattly_ml.timebase import DAY, STEP, floor, instant, targets
from test_acceptance import synthetic_bundle
from test_pipeline import request_payload
from test_solar_serving import snapshot, write_snapshot


def fixture(tmp_path):
    payload = request_payload()
    origin = instant(payload['asOf'])
    demand = [dict(targetPeriod=t.isoformat(), publishedAt=(origin-STEP).isoformat(),
                   availableAt=origin.isoformat(), demandMw=6000+h*10, source='SYNTHETIC_FORECAST')
              for h, t in enumerate(targets(origin))]
    payload.update(schemaVersion=2, expectedDemand=demand)
    _, solar = read_solar_snapshot(write_snapshot(tmp_path, snapshot(origin)), origin)
    issue = dict(id='synthetic-one', asOf=origin.isoformat(), mode='LIVE', stale=False, status='AVAILABLE',
                 modelVersion='synthetic-solar', inputs=dict(marketInputs={**payload, 'mappingVerified':True}, solarInputs=solar))
    start = floor(origin)
    protocol = dict(id='SYNTHETIC_ONLY', trainStart=start.isoformat(), trainCutoff=(start+3*DAY).isoformat(),
        testStart=(start+4*DAY).isoformat(), testEnd=(start+18*DAY).isoformat(), minimumTrainingOrigins=48,
        minimumTestOrigins=48, parameters=PARAMETERS.copy(), truthPolicy='PROVISIONAL',
        spikeConfig=dict(minimumSamples=14, k=3., spreadFloor=1.), frozenModelVersion='synthetic-solar', frozenModelSha256='f'*64)
    return issue, protocol, payload


def prices_for(issue, available=None):
    return [dict(periodStart=t.isoformat(), sourceUpdatedAt=(t+STEP).isoformat(),
                 availableAt=(available or t+STEP).isoformat(), priceStatus='PROVISIONAL',
                 timeMapping='EMC_PERIOD', usep=100+h) for h, t in enumerate(targets(instant(issue['asOf'])))]


def test_schema4_uses_target_demand_and_schema3_stays_identical(tmp_path):
    issue, _, _ = fixture(tmp_path)
    past = snapshot_history(issue); origin = instant(issue['asOf']); target = targets(origin)[0]
    before = features(past, origin, target, schema_version=3)
    aware = features(past, origin, target, schema_version=4)
    assert len(aware) == len(feature_names(4)) == 30 and aware[17:19] == [6000, 1]
    assert math.isnan(before[17]) and before[18] == 0
    past.demand_forecasts[0]['demandMw'] = 9000
    assert features(past, origin, target, schema_version=4)[17] == 9000
    np.testing.assert_allclose(features(past, origin, target, schema_version=3), before, equal_nan=True)
    del past.demand_forecasts[0]
    with pytest.raises(ValueError, match='demand forecast unavailable'):
        features(past, origin, target, schema_version=4)


@pytest.mark.parametrize('failure', ['future_demand', 'future_solar', 'missing_demand', 'missing_solar', 'future_price'])
def test_capture_rejects_unavailable_inputs(tmp_path, failure):
    issue, _, _ = fixture(tmp_path); origin = instant(issue['asOf'])
    raw = issue['inputs']['marketInputs']; solar = issue['inputs']['solarInputs']['periods']
    if failure == 'future_demand': raw['expectedDemand'][0]['availableAt'] = (origin+STEP).isoformat()
    if failure == 'future_solar': solar[0]['availableAt'] = (origin+STEP).isoformat()
    if failure == 'missing_demand': raw['expectedDemand'].pop()
    if failure == 'missing_solar': solar.pop()
    if failure == 'future_price': raw['priceHistory'][-1]['availableAt'] = (origin+STEP).isoformat()
    with pytest.raises(ValueError): snapshot_history(issue)


def test_one_first_eligible_issue_per_half_hour(tmp_path):
    issue, protocol, _ = fixture(tmp_path); origin = instant(issue['asOf'])
    later = deepcopy(issue); later.update(id='synthetic-two', asOf=(origin+STEP/10).isoformat())
    selected, excluded = select_issues([later, issue], protocol, origin+STEP)
    assert len(selected) == 1 and selected[0][0]['id'] == issue['id']
    assert excluded['REPEATED_HALF_HOUR'] == 1
    with pytest.raises(ValueError, match='Duplicate'): select_issues([issue, issue], protocol, origin+STEP)


def test_actual_receipt_and_split_purge_are_real_not_elapsed_time(tmp_path):
    issue, protocol, _ = fixture(tmp_path); origin = instant(issue['asOf']); cutoff = instant(protocol['trainCutoff'])
    prices = prices_for(issue)
    selected, _ = select_issues([issue], protocol, cutoff)
    complete, _ = complete_rows(selected, known_truth(prices, cutoff), instant(protocol['trainStart']), cutoff)
    assert len(complete) == 1
    prices[-1]['availableAt'] = (cutoff+STEP).isoformat()
    ready = readiness([issue], prices, protocol, cutoff)
    assert ready['completeTrainingOrigins'] == 0 and not ready['readyToFit']
    _, excluded = complete_rows(selected, known_truth(prices, cutoff), origin, targets(origin)[-1])
    assert excluded['PURGED_SPLIT_BOUNDARY'] == 1
    assert readiness([issue], [], protocol, cutoff)['accuracyResult'] is None


def test_truth_freeze_never_uses_later_revision_or_unverified_mapping(tmp_path):
    issue, _, _ = fixture(tmp_path); rows = prices_for(issue); cutoff = instant(rows[-1]['availableAt'])
    first = rows[0]; target = instant(first['periodStart'])
    rows.append({**first, 'usep':9999, 'availableAt':(cutoff+STEP).isoformat()})
    assert known_truth(rows, cutoff)[target] == first['usep']
    rows[0]['timeMapping'] = 'UNVERIFIED_FLOOR'
    assert target not in known_truth(rows, cutoff)


def test_no_early_fit_no_small_support_no_early_test_and_no_overwrite(tmp_path):
    issue, protocol, _ = fixture(tmp_path); origin = instant(issue['asOf']); output = tmp_path/'experiment'
    with pytest.raises(ValueError, match='Fit only'): fit([issue], [], protocol, origin, output)
    with pytest.raises(ValueError, match='Insufficient complete training'):
        fit([issue], prices_for(issue), protocol, instant(protocol['trainCutoff']), output)
    assert not output.exists()
    with pytest.raises(ValueError, match='Untouched holdout'):
        evaluate([], [], protocol, origin, output, output)
    output.mkdir()
    with pytest.raises(ValueError, match='immutable'): fit([], [], protocol, origin, output)
    with pytest.raises(ValueError, match='Fit only'):
        fit([], [], protocol, instant(protocol['testStart']), tmp_path/'late-fit')


def test_protocol_is_fixed_before_later_outcomes(tmp_path):
    _, protocol, _ = fixture(tmp_path)
    protocol_check(protocol)
    changed = deepcopy(protocol); changed['parameters']['max_iter'] = 400
    with pytest.raises(ValueError): protocol_check(changed)
    changed = deepcopy(protocol); changed['testStart'] = changed['trainCutoff']
    with pytest.raises(ValueError): protocol_check(changed)
    assert identity(protocol) == identity(json.loads(json.dumps(protocol)))


def test_actual_saved_row_shape_and_evidenced_floor_mapping(tmp_path):
    issue, protocol, _ = fixture(tmp_path)
    raw = issue['inputs']['marketInputs']; raw.pop('mappingVerified')
    for row in raw['priceHistory']:
        row.update(source='NEMS_SN_SG', timeMapping='UNVERIFIED_FLOOR', id=999, priceStatus='PROVISIONAL')
    with pytest.raises(ValueError, match='mapping lacks'): snapshot_history(issue)
    evidence = 'EMC_RT_20260929_24_PERIODS'
    snapshot_history(issue, evidence)
    prices = prices_for(issue)
    for row in prices:
        row.update(source='NEMS_SN_SG', timeMapping='UNVERIFIED_FLOOR')
        row['sourceUpdatedAt'] = (instant(row['periodStart'])+STEP/10).isoformat()
    assert len(known_truth(prices, instant(protocol['trainCutoff']), evidence)) == 24
    prices[-1]['source'] = 'OTHER_PROVIDER'
    assert len(known_truth(prices, instant(protocol['trainCutoff']), evidence)) == 23


def test_complete_synthetic_fit_save_load_and_frozen_holdout(tmp_path):
    issue, protocol, _ = fixture(tmp_path); origin = instant(issue['asOf'])
    frozen = synthetic_bundle()
    from sklearn.dummy import DummyRegressor
    from wattly_ml.solar import SOLAR_POLICY
    frozen['models'] = [DummyRegressor(strategy='constant', constant=150).fit([[0]*30], [150])]*24
    frozen['manifest'].update(featureSchemaVersion=3, featureOrder=feature_names(3), inputPolicy=SOLAR_POLICY,
                              productionEligible=False, exploratory=True)
    manifest = save_bundle(frozen, tmp_path/'frozen', 'synthetic-solar')
    protocol['frozenModelSha256'] = manifest['modelSha256']
    # All feature/solar/demand timestamps move together; no later input reconstruction.
    date_keys = {'asOf','periodStart','sourceUpdatedAt','availableAt','targetPeriod','publishedAt','latestCompletedPeriod'}
    def shift(value, delta):
        if isinstance(value, list): return [shift(v, delta) for v in value]
        if isinstance(value, dict):
            return {k: (instant(v)+delta).isoformat() if k in date_keys and isinstance(v,str) else shift(v,delta) for k,v in value.items()}
        return value
    issues = []
    for part, offset in [('train', 0*DAY), ('test', 4*DAY)]:
        for i in range(48):
            row = shift(issue, offset+i*STEP)
            row['id'] = f'{part}-{i}'
            issues.append(row)
    by_period = {}
    for row in issues:
        for price in prices_for(row):
            t = instant(price['periodStart'])
            price['usep'] = 100+t.hour*2+t.minute/30
            by_period[t] = price
    prices = list(by_period.values())
    experiment = tmp_path/'experiment'
    result = fit(issues, prices, protocol, instant(protocol['trainCutoff']), experiment)
    assert result['trainingOrigins'] == 48 and not result['holdoutInspected']
    candidate = load_bundle(experiment/'models/solar_demand-v1')
    control = load_bundle(experiment/'models/solar_refit-v1')
    assert candidate['manifest']['expectedDemandCoverage'] == 1.
    assert candidate['manifest']['trainingInputsSha256'] == control['manifest']['trainingInputsSha256']
    records, report = evaluate(issues, prices, protocol, instant(protocol['testEnd']), experiment, tmp_path/'frozen/synthetic-solar')
    assert report['commonOrigins'] == 48 and len(records) == 48*24*6
    assert len({report['table'][m]['all']['pairs'] for m in report['table']}) == 1
    assert report['savingsValidation'] == 'PENDING_REAL_F4_REPLAY' and not report['automaticPromotion']
    assert not (tmp_path/'current.json').exists()


def test_demand_aware_http_prediction_changes_with_demand_only(tmp_path, monkeypatch):
    issue, _, payload = fixture(tmp_path); past = snapshot_history(issue); origin = instant(issue['asOf'])
    # A fitted synthetic tree proves feature use, not real predictive skill.
    x0 = features(past, origin, targets(origin)[0], schema_version=4)
    x1 = x0.copy(); x1[17] = 9000
    tree = DecisionTreeRegressor(random_state=42).fit([x0, x1], [100, 300])
    bundle = synthetic_bundle(); bundle['models'] = [tree]*24
    bundle['manifest'].update(featureSchemaVersion=4, featureOrder=feature_names(4), inputPolicy=SOLAR_DEMAND_POLICY,
                               productionEligible=False, exploratory=True)
    save_bundle(bundle, tmp_path/'models', 'demand-synthetic')
    path = tmp_path/'models/demand-synthetic'
    monkeypatch.setenv('WATTLY_ML_HOME', str(tmp_path)); monkeypatch.setenv('WATTLY_ALLOW_EXPERIMENTAL_AI', 'true')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MODEL_VERSION', 'demand-synthetic')
    monkeypatch.setenv('WATTLY_EXPERIMENTAL_MANIFEST_SHA256', digest(path/'manifest.json'))
    monkeypatch.setenv('WATTLY_SOLAR_SNAPSHOT', str(write_snapshot(tmp_path, snapshot(origin))))
    with TestClient(create_app()) as client:
        first = client.post('/forecast', json=payload)
        assert first.status_code == 200
        assert first.json()['points'][0]['predictedUsep'] == 100
        assert 'AS_ISSUED_EXPECTED_DEMAND' in first.json()['qualityFlags']
        payload['expectedDemand'][0]['demandMw'] = 9000
        assert client.post('/forecast', json=payload).json()['points'][0]['predictedUsep'] == 300
        payload['expectedDemand'].pop()
        assert client.post('/forecast', json=payload).status_code == 503
    assert not (tmp_path/'current.json').exists()
    manifest = json.loads((path/'manifest.json').read_text()); manifest['productionEligible'] = True
    (path/'manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match='experimental only'): load_bundle(path)
