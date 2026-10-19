"""Scheduling time controls boundaries/identity; audit time remains actual execution time."""
from datetime import datetime, timezone
import json

import pytest

from wattly_ml import operations, scheduled
from wattly_ml.timebase import DAY, instant


@pytest.mark.parametrize('now,end', [
    ('2026-10-18T15:59:59Z', '2026-10-17T16:00:00Z'),
    ('2026-10-18T16:00:00Z', '2026-10-18T16:00:00Z'),
])
def test_singapore_midnight_defaults_and_unique_attempts(tmp_path, monkeypatch, now, end):
    for name in ('WATTLY_VALIDATION_END', 'WATTLY_TRAIN_START', 'WATTLY_VALIDATION_DAYS'):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv('WATTLY_ML_HOME', str(tmp_path))
    monkeypatch.setenv('WATTLY_RUN_API_URL', 'http://unused.invalid')
    monkeypatch.setenv('COLLECTOR_DATA_DIR', str(tmp_path / 'missing'))
    monkeypatch.setattr(operations, 'post_admin', lambda *a: {'saved': True})
    for _ in range(2):
        assert scheduled.main(now=instant(now)) == 2
    records = [json.loads(p.read_text()) for p in (tmp_path / 'training-attempts').glob('*.json')]
    assert len(records) == 2 and len({r['modelVersion'] for r in records}) == 2
    assert len({r['attemptId'] for r in records}) == 2
    for r in records:
        assert instant(r['validationEnd']) == instant(end)
        assert instant(r['trainingEnd']) == instant(end) - 28 * DAY
        assert instant(r['trainingStart']) == instant(end) - 393 * DAY
        assert r['modelVersion'].startswith('weekly-' + instant(now).strftime('%Y%m%dT%H%M%S'))
        assert instant(r['scheduledAt']) == instant(now)
        assert r['state'] == 'FAILED' and r['promotionDecision'] == 'RETAINED'
        assert instant(r['trainingDate']) != instant(now)  # Real audit time, not the injected business clock.


def test_explicit_boundaries_reach_weekly_command_unchanged(tmp_path, monkeypatch):
    monkeypatch.setenv('WATTLY_ML_HOME', str(tmp_path))
    monkeypatch.setenv('WATTLY_RUN_API_URL', 'http://unused.invalid')
    monkeypatch.setenv('COLLECTOR_DATA_DIR', str(tmp_path))
    monkeypatch.setenv('WATTLY_VALIDATION_END', '2026-08-01T00:00:00+08:00')
    monkeypatch.setenv('WATTLY_VALIDATION_DAYS', '7')
    monkeypatch.setenv('WATTLY_TRAIN_START', '2025-07-01T00:00:00+08:00')
    monkeypatch.setenv('WATTLY_PROTECTED_START', '2026-09-01T00:00:00+08:00')
    monkeypatch.setenv('WATTLY_PROTECTED_END', '2026-10-01T00:00:00+08:00')
    monkeypatch.setattr(operations, 'post_admin', lambda *a: {'saved': True})
    monkeypatch.setattr(scheduled, 'snapshot', lambda *a: tmp_path / 'snapshot')
    monkeypatch.setattr(scheduled, 'prepare', lambda *a, **kw: None)
    calls = []
    def run(command, training_attempt):
        calls.append(command)
        training_attempt.finish('REJECTED', 'Test candidate rejected')
        return 2
    monkeypatch.setattr(scheduled, 'cli', run)
    assert scheduled.main(now=instant('2030-01-01T00:00:00Z')) == 2
    command = calls[0]
    assert instant(command[command.index('--end') + 1]) == instant('2026-08-01T00:00:00+08:00')
    assert instant(command[command.index('--cutoff') + 1]) == instant('2026-07-25T00:00:00+08:00')
    assert instant(command[command.index('--start') + 1]) == instant('2025-07-01T00:00:00+08:00')


def test_naive_scheduling_time_is_rejected():
    with pytest.raises(ValueError, match='timezone-aware'):
        scheduled.main(now=datetime(2026, 10, 19))
