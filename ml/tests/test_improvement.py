"""Synthetic regression fixtures; never used as measured project evidence."""
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path

import numpy as np
import pytest
from sklearn.dummy import DummyRegressor
from sklearn.pipeline import Pipeline

from wattly_ml import improvement
from wattly_ml.artifacts import save_bundle
from wattly_ml.backtest import History
from wattly_ml.data import digest, quality, write_json
from wattly_ml.features import feature_names
from wattly_ml.timebase import DAY, STEP, instant
from wattly_ml.train import training_examples
from test_acceptance import synthetic_bundle
from test_pipeline import history


def dataset(path, rows, acquired):
    path.mkdir()
    prices = path / 'prices.jsonl'
    prices.write_text(''.join(json.dumps(r) + '\n' for r in rows))
    write_json(path / 'manifest.json', {'sha256': digest(prices), 'quality': quality(rows),
        'provisionalOffsetMinutes': 0, 'sources': [{'acquiredAt': acquired.isoformat()}]})
    return path


def test_residual_fit_matches_shared_training_labels_and_train_only_imputer(monkeypatch):
    rows = history(8)
    start, end = instant(rows[48]['periodStart']), instant(rows[-1]['periodStart']) + STEP
    raw = improvement.examples(rows, start, end, 'PROVISIONAL')
    expected_x, expected_y, _ = training_examples(rows, start, end, policy='PROVISIONAL',
        baseline='B2', feature_schema=2)
    calls = []
    original = Pipeline.fit

    def capture(self, x, y, **kwargs):
        calls.append((x.copy(), y.copy()))
        return original(self, x, y, **kwargs)

    monkeypatch.setattr(Pipeline, 'fit', capture)
    fitted, metadata = improvement.fit([raw], improvement.SPECS['RECENT_RESIDUAL_B2'])
    assert len(calls) == len(fitted) == 24
    assert metadata['trainingOriginsPerHorizon'] == len(expected_y[0])
    for h, (x, y) in enumerate(calls):
        np.testing.assert_allclose(x, expected_x[h], rtol=0, atol=0, equal_nan=True)
        np.testing.assert_allclose(y, expected_y[h], rtol=0, atol=0)
        assert fitted[h][0].statistics_[0] == np.nanmedian(x[:, 0])


def test_batch_and_single_predictions_match_and_missing_baselines_stay_unavailable():
    rows = history(10)
    start = instant(rows[8 * 48]['periodStart'])
    origin = start + 6 * STEP
    estimator = DummyRegressor(strategy='constant', constant=-7).fit([[0] * len(feature_names(2))], [0])
    models = [estimator] * 24
    spec = improvement.SPECS['RECENT_RESIDUAL_B2']
    reference = {'models': models, 'manifest': {'version': 'SYNTHETIC_ONLY', 'usableFrom': start.isoformat()}}
    saved = improvement.evaluate(rows, start, start + DAY, {'CORRECTION': (models, spec)}, reference)
    actual = improvement.forecast(models, spec, History(rows).at(origin), origin)
    batch = [r for r in saved if r['method'] == 'CORRECTION' and instant(r['asOf']) == origin]
    assert [r['predictedUsep'] for r in batch] == actual
    missing = [r for r in rows if instant(r['periodStart']) != origin - DAY + STEP]
    assert improvement.forecast(models, spec, History(missing).at(origin), origin) is None
    denied = improvement.evaluate(missing, start, start + DAY, {'CORRECTION': (models, spec)}, reference)
    assert all(r['predictedUsep'] is None and r['exclusionReason'] == 'INSUFFICIENT_BASELINE_HISTORY'
        for r in denied if r['method'] == 'CORRECTION' and instant(r['asOf']) == origin)
    # A missing truth remains excluded even if the six-period baseline is also missing.
    sparse = [r for r in rows if not start - 2 * STEP <= instant(r['periodStart']) <= start + STEP]
    evaluated = improvement.evaluate(sparse, start, start + DAY, {'DIRECT': (models, {'family': 'DEFAULT'})}, reference)
    assert all(r['exclusionReason'] is not None for r in evaluated
        if r['actual'] is None and r['method'] in ('DIRECT', 'PREVIOUS_AI'))


def test_long_recent_study_protects_test_and_late_revisions_and_freezes_confirmation(tmp_path, monkeypatch):
    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            return instant('2026-01-15T12:00:00+08:00')

    monkeypatch.setattr(improvement, 'datetime', Frozen)
    monkeypatch.setenv('WATTLY_RUN_API_URL', '')
    monkeypatch.delenv('WATTLY_ML_HOME', raising=False)
    specs = {name: deepcopy(improvement.SPECS[name]) for name in ('LONG_DEFAULT', 'RECENT_RESIDUAL_B2')}
    specs['LONG_DEFAULT']['parameters']['max_iter'] = 1
    monkeypatch.setattr(improvement, 'SPECS', specs)
    recent = history(14)
    start = instant(recent[0]['periodStart'])
    final = [{**r, 'periodStart': (instant(r['periodStart']) - 365 * DAY).isoformat(),
        'sourceUpdatedAt': None, 'availableAt': None, 'priceStatus': 'FINAL'} for r in history(7)]
    historical = dataset(tmp_path / 'historical', final, start + 14 * DAY)
    collector = dataset(tmp_path / 'collector', recent, start + 14 * DAY)
    reference = synthetic_bundle()
    reference['manifest'].update(trainingDate=(start - 3 * DAY).isoformat(),
        trainingCutoff=(start - 3 * DAY).isoformat(), usableFrom=(start - 2 * DAY).isoformat())
    save_bundle(reference, tmp_path / 'models', 'SYNTHETIC_REFERENCE')
    reference_path = tmp_path / 'models/SYNTHETIC_REFERENCE'
    protocol = {'historyStart': (start - 364 * DAY).isoformat(), 'historyEnd': (start - 358 * DAY).isoformat(),
        'collectorStart': (start + DAY).isoformat(),
        'folds': [{'start': (start + n * DAY).isoformat(), 'end': (start + (n+1) * DAY).isoformat()} for n in (8, 10)],
        'protectedIntervals': [{'start': (start + 12 * DAY).isoformat(), 'end': (start + 13 * DAY).isoformat()}],
        'confirmationDays': 14}
    protocol_path = tmp_path / 'protocol.json'
    write_json(protocol_path, protocol)
    original_hashes = {str(p): digest(p) for p in reference_path.iterdir()}
    output = tmp_path / 'study'
    result = improvement.study(collector, historical, reference_path, protocol_path, output)
    assert result['status'] == 'COMPLETE' and not result['productionEligible']
    assert result['latestFit']['finalPriceOriginsBeforeBaselineFilter'] >= 0
    assert result['selection']['scores']['commonPairs'] > 0
    assert improvement.verify(output)['status'] == 'PASS'
    assert all(digest(Path(p)) == h for p, h in original_hashes.items())
    assert not (output / 'current.json').exists()
    with pytest.raises(ValueError, match='immutable'):
        improvement.study(collector, historical, reference_path, protocol_path, output)
    with pytest.raises(ValueError, match='pending'):
        improvement.confirm(collector, output, tmp_path / 'too-early')
    assert not (tmp_path / 'too-early').exists()

    changed = [{**r, 'usep': 99999999} if start + 12 * DAY <= instant(r['periodStart']) < start + 13 * DAY
        else r for r in recent]
    changed.append({**recent[50], 'revisionId': 'SYNTHETIC_LATE', 'usep': -99999999,
        'sourceUpdatedAt': (start + 12 * DAY).isoformat(), 'availableAt': (start + 12 * DAY).isoformat()})
    other = improvement.study(dataset(tmp_path / 'changed', changed, start + 14 * DAY), historical,
        reference_path, protocol_path, tmp_path / 'counterfactual')
    assert other['selection']['ranking'] == result['selection']['ranking']
    assert other['selection']['scores'] == result['selection']['scores']
    invalid = deepcopy(protocol)
    invalid['protectedIntervals'].append(invalid['folds'][0])
    write_json(tmp_path / 'invalid-protocol.json', invalid)
    with pytest.raises(ValueError, match='protected'):
        improvement.study(collector, historical, reference_path, tmp_path / 'invalid-protocol.json', tmp_path / 'invalid')
    assert not (tmp_path / 'invalid').exists()

    def forbid_fit(*args, **kwargs):
        raise AssertionError('Confirmation must never fit or reselect')

    monkeypatch.setattr(improvement, 'fit', forbid_fit)
    confirmed = improvement.confirm(dataset(tmp_path / 'future', history(31), start + 31 * DAY),
        output, tmp_path / 'confirmation')
    assert confirmed['status'] == 'COMPLETE' and not confirmed['productionEligible']
    assert confirmed['commonOriginDays'] == 14
    assert all(d['observedTruthPeriods'] == 48 for d in confirmed['dailyCoverage'])
    model = output / 'final-fit/candidate.joblib'
    model.write_bytes(model.read_bytes() + b'corruption')
    with pytest.raises(ValueError, match='changed'):
        improvement.load_candidate(output)


def test_invalid_price_provenance_is_rejected(tmp_path):
    rows = history(2)
    rows[0]['availableAt'] = (instant(rows[0]['sourceUpdatedAt']) - STEP).isoformat()
    with pytest.raises(ValueError, match='provenance|publication|available'):
        improvement.collector_data(dataset(tmp_path / 'bad', rows, instant(rows[-1]['periodStart']) + DAY))


def test_explicit_retrospective_evaluation_keeps_final_vintages_null_and_needs_no_reference():
    rows = [{**r, 'sourceUpdatedAt': None, 'availableAt': None, 'priceStatus': 'FINAL'} for r in history(10)]
    original = deepcopy(rows)
    origin = instant(rows[8 * 48]['periodStart'])
    estimator = DummyRegressor(strategy='constant', constant=-5).fit([[0] * len(feature_names(2))], [0])
    candidates = {'FIXED_HISTORY': ([estimator] * 24, {'family': 'DEFAULT'})}
    result = improvement.evaluate(rows, origin, origin + DAY, candidates, policy='FINAL', retrospective=True)
    assert {r['method'] for r in result} == {'B1', 'B2', 'B3', 'FIXED_HISTORY'}
    eligible = [r for r in result if r['method'] == 'FIXED_HISTORY' and r['exclusionReason'] is None]
    assert eligible and all(r['truthPolicy'] == 'FINAL' and r['predictedUsep'] == -5 for r in eligible)
    denied = improvement.evaluate(rows, origin, origin + DAY, candidates, policy='FINAL')
    assert all(r['exclusionReason'] is not None for r in denied)
    assert rows == original and all(r['availableAt'] is None for r in rows)
