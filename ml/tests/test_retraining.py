import json
from pathlib import Path
from statistics import mean

import numpy as np
import pytest

from wattly_ml import demo, experiments, retraining
from wattly_ml.data import digest, quality, write_json
from wattly_ml.timebase import STEP, instant
from test_pipeline import history


def dataset_at(path, rows):
    path.mkdir()
    prices = path/'prices.jsonl'
    prices.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    write_json(path/'manifest.json', {'sha256': digest(prices), 'quality': quality(rows),
        'provisionalOffsetMinutes': 0, 'sources': [{'acquiredAt': max(rows, key=lambda r: instant(r['availableAt']))['availableAt']}]})
    return path


def test_horizon_coverage_does_not_assume_complete_origins():
    assert retraining.common_horizon_counts([], []) == {}
    records = [{'method': m, 'asOf': o, 'horizon': h, 'exclusionReason': None}
               for m in ('AI', 'B1') for o, h in [('a', 1), ('a', 2), ('b', 1)]]
    assert retraining.common_horizon_counts(records, ['AI', 'B1']) == {1: 2, 2: 1}
    records[-1]['exclusionReason'] = 'MISSING_TRUTH'
    assert retraining.common_horizon_counts(records, ['AI', 'B1']) == {1: 1, 2: 1}


def test_retraining_uses_new_complete_holdout_and_never_fits_future_revisions(tmp_path, monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE', 'EMC_RT_20260929_24_PERIODS')
    old_data = dataset_at(tmp_path/'old-data', history(6))
    reference = tmp_path/'reference'
    demo.experiment(old_data, reference)
    previous = tmp_path/'previous'
    experiments.compare(old_data, reference, previous)
    checksums = {str(p): digest(p) for p in previous.rglob('*') if p.is_file()}
    # Jan 9 is an elapsed but incomplete day; Jan 10 is the acquisition day.
    rows = [r for i, r in enumerate(history(10)) if not (8*48+10 <= i < 9*48)]
    new_data = dataset_at(tmp_path/'new-data', rows)
    fits = []
    original = retraining.fit_models
    def capture(xs, ys):
        fits.append(([np.asarray(x) for x in xs], [np.asarray(y) for y in ys]))
        return original(xs, ys)
    monkeypatch.setattr(retraining, 'fit_models', capture)
    output = tmp_path/'retrained'
    result = retraining.retrain(new_data, previous, output)
    assert instant(result['protocol']['testStart']) == instant('2026-01-08T00:00:00+08:00')
    assert result['quality']['laterIncompleteElapsedDays'][0]['periods'] == 10
    assert result['finalFit']['trainingSamplesPerHorizon'] == [7*48-24-6]*24  # No warm-up history in this fixture.
    assert result['heldOutTest']['commonPairs'] == 576
    assert result['referenceUnchanged'] and not (output/'current.json').exists()
    assert all(digest(Path(p)) == checksum for p, checksum in checksums.items())
    assert result['heldOutTest']['table']['HGB_MAE']['spikes']['precision'] is None
    test_rows = [json.loads(line) for line in (output/'held-out-test/predictions.jsonl').read_text().splitlines()]
    pairs = {}
    for m in result['heldOutTest']['comparisonMethods']:
        valid = [r for r in test_rows if r['method'] == m and r['exclusionReason'] is None]
        pairs[m] = {(r['asOf'], r['horizon'], r['targetPeriod']) for r in valid}
        assert len(valid) == len(pairs[m]) == 576
        assert result['heldOutTest']['table'][m]['mae'] == pytest.approx(mean(abs(r['predictedUsep']-r['actual']) for r in valid))
        assert all(instant(r['targetPeriod'])+STEP <= instant(result['protocol']['testEnd']) for r in valid)
    assert all(p == pairs['CURRENT'] for p in pairs.values())
    frozen = [r for r in test_rows if r['method'] == 'FROZEN_HGB_MAE']
    assert len(frozen) == 48*24
    assert sum(r['exclusionReason'] == 'PURGED_SPLIT_BOUNDARY' for r in frozen) == 576
    with pytest.raises(ValueError, match='immutable'):
        retraining.retrain(new_data, previous, output)

    original_fits = fits[:]; fits.clear()
    test_start = instant(result['protocol']['testStart'])
    changed = [{**r, 'usep': 9999999} if instant(r['periodStart']) >= test_start else r for r in rows]
    changed.append({**rows[50], 'revisionId': 'late-training-revision', 'usep': -9999999,
        'sourceUpdatedAt': (test_start+STEP).isoformat(), 'availableAt': (test_start+STEP).isoformat()})
    changed_data = dataset_at(tmp_path/'changed-data', changed)
    other = tmp_path/'counterfactual'
    original_backtest = retraining.backtest
    def stop_at_test(rows, start, end, **kwargs):
        if start == test_start:
            assert (other/'selection.json').exists() and (other/'final-fit/candidates.joblib').exists()
            raise RuntimeError('Stop before test')
        return original_backtest(rows, start, end, **kwargs)
    monkeypatch.setattr(retraining, 'backtest', stop_at_test)
    with pytest.raises(RuntimeError, match='Stop before test'):
        retraining.retrain(changed_data, previous, other)
    selected = json.loads((other/'selection.json').read_text())
    assert selected['validation'] == result['selection']['validation']
    assert selected['ranking'] == result['selection']['ranking']
    assert len(fits) == len(original_fits)
    for before, after in zip(original_fits, fits):
        for a_group, b_group in zip(before, after):
            for a, b in zip(a_group, b_group):
                np.testing.assert_allclose(a, b, atol=0, rtol=0, equal_nan=True)
    assert (other/'failure.json').exists()
