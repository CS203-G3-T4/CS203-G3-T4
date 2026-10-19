"""Hand-made synthetic series, not measured forecasting evidence."""
from copy import deepcopy
import json

import pytest

from wattly_ml import cli, spike_grading
from wattly_ml.data import digest
from wattly_ml.timebase import SGT, STEP, instant


def records(labels, predictions, horizons=(1, 6)):
    start = instant('2026-10-10T00:00:00+08:00')
    rows = []
    for method, predict in predictions.items():
        for h in horizons:
            for i, actual_spike in enumerate(labels):
                target = start + i * STEP
                alarm = predict(i, h)
                rows.append({'method': method, 'asOf': (target - h * STEP).isoformat(),
                    'targetPeriod': target.isoformat(), 'horizon': h, 'exclusionReason': None,
                    'actual': 101 if actual_spike else -5, 'predictedUsep': 101 if alarm else -10,
                    'threshold': 100, 'actualSpike': actual_spike, 'spikeFlag': alarm,
                    'truthPolicy': 'SYNTHETIC_TEST_ONLY'})
    return rows


def test_hand_series_rates_events_and_overlapping_forecasts_are_distinct():
    labels = [i % 2 == 1 for i in range(43)]
    rows = records(labels, {'CATCH': lambda i, h: labels[i] or i == 0, 'MISS': lambda i, h: False})
    report = spike_grading.analyse(rows)
    assert report['commonLabelledPairs'] == 86
    caught = report['table']['CATCH']
    assert {k: caught['pooled'][k] for k in ('tp', 'fp', 'fn', 'tn')} == {'tp': 42, 'fp': 2, 'fn': 0, 'tn': 42}
    assert caught['pooled']['distinctTargets'] == 43
    assert caught['pooled']['recall'] == 1
    assert caught['pooled']['precision'] == pytest.approx(21 / 22)
    assert caught['pooled']['falsePositiveRate'] == pytest.approx(1 / 22)
    for h in (1, 6):
        events = caught['fixedLeads'][str(h)]['events']
        assert events['fullyObservedEvents'] == events['warnedBeforeStart'] == 21
        assert events['eventEarlyRecall'] == 1
        assert events['warningLeadMinutes']['median'] == h * 30
    missed = report['table']['MISS']['pooled']
    assert missed['precision'] is None and missed['recall'] == 0
    assert report['genuineSpikeMetrics']['recall'] is None


def test_late_detection_is_not_advance_warning_and_gaps_censor_events():
    labels = [False, False, True, True, True, True, False, False]
    rows = records(labels, {'LATE': lambda i, h: i == 4})
    report = spike_grading.analyse(rows)['table']['LATE']
    short = report['fixedLeads']['1']['events']
    long = report['fixedLeads']['6']['events']
    assert short['fullyObservedEvents'] == long['fullyObservedEvents'] == 1
    assert short['lateOnlyDetection'] == 1 and short['warnedBeforeStart'] == 0
    assert long['warnedBeforeStart'] == 1 and long['warningLeadMinutes']['median'] == 120
    assert short['eventEarlyRecall'] is None
    # Removing a boundary observation cannot make a partial event fully known.
    without_boundary = [r for r in rows if r['targetPeriod'] != rows[1]['targetPeriod']]
    censored = spike_grading.analyse(without_boundary)['table']['LATE']['fixedLeads']['1']['events']
    assert censored['fullyObservedEvents'] == 0 and censored['censoredRuns'] == 1


def test_common_coverage_unavailable_reference_and_small_support_are_explicit():
    labels = [False, True, False]
    rows = records(labels, {'A': lambda i, h: True, 'B': lambda i, h: False}, horizons=(1,))
    for r in rows:
        if r['targetPeriod'] == rows[0]['targetPeriod']:
            r.update(threshold=None, actualSpike=None, spikeFlag=None)
    rows = [r for r in rows if not (r['method'] == 'B' and r['targetPeriod'] == rows[2]['targetPeriod'])]
    result = spike_grading.analyse(rows)
    assert result['commonPricePairs'] == 2 and result['commonLabelledPairs'] == 1
    assert result['unavailableReferencePairs'] == 1
    assert result['table']['A']['pooled']['recall'] is None
    assert result['table']['A']['pooled']['tp'] == 1
    assert result['table']['B']['pooled']['fn'] == 1
    assert result['table']['A']['fixedLeads']['24']['forecastPairs'] == 0


def test_origin_dependent_labels_are_not_collapsed_and_offsets_are_normalized():
    rows = records([True], {'A': lambda i, h: True, 'B': lambda i, h: False})
    for r in rows:
        if r['horizon'] == 6:
            r.update(threshold=200, actualSpike=False, spikeFlag=False)
        if r['method'] == 'B':
            r['targetPeriod'] = instant(r['targetPeriod']).astimezone(SGT).isoformat()
    result = spike_grading.analyse(rows)['table']['A']
    assert result['pooled']['distinctTargets'] == 1
    assert result['pooled']['targetsWithOriginDependentLabels'] == 1
    assert result['fixedLeads']['1']['distinctPositiveTargets'] == 1
    assert result['fixedLeads']['6']['distinctPositiveTargets'] == 0


def test_inconsistent_flags_truth_duplicates_and_unaligned_origins_are_rejected():
    base = records([False, True], {'A': lambda i, h: False, 'B': lambda i, h: False})
    bad = deepcopy(base); bad[0]['spikeFlag'] = True
    with pytest.raises(ValueError, match='threshold'):
        spike_grading.analyse(bad)
    with pytest.raises(ValueError, match='Duplicate'):
        spike_grading.analyse(base + [base[0]])
    bad = deepcopy(base); bad[-1]['actual'] = 102
    with pytest.raises(ValueError, match='different truth'):
        spike_grading.analyse(bad)
    bad = deepcopy(base); bad[0]['asOf'] = (instant(bad[0]['asOf']) + STEP / 2).isoformat()
    with pytest.raises(ValueError, match='aligned'):
        spike_grading.analyse(bad)


def test_cli_grades_immutable_saved_predictions_without_training(tmp_path, monkeypatch):
    rows = records([False, True, False], {'A': lambda i, h: True})
    source = tmp_path / 'predictions.jsonl'
    source.write_text(''.join(json.dumps(r) + '\n' for r in rows))
    before = digest(source)
    from wattly_ml import improvement

    def no_training(*args, **kwargs):
        raise AssertionError('Grading must not fit or select a model')

    monkeypatch.setattr(improvement, 'fit', no_training)
    output = tmp_path / 'grade'
    assert cli.main(['grade-spikes', str(source), '--output', str(output)]) == 0
    result = json.loads((output / 'summary.json').read_text())
    assert result['sourceSha256'] == before == digest(source)
    assert (output / 'scoreboard.csv').is_file()
    assert cli.main(['grade-spikes', str(source), '--output', str(output)]) == 2
