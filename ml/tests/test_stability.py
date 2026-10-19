"""Synthetic software checks only; fixtures are never accuracy evidence."""
from datetime import datetime, timezone
import json
from pathlib import Path
from statistics import mean

import joblib
import numpy as np
import pytest
from sklearn.pipeline import Pipeline

from wattly_ml import corrections, stability
from wattly_ml.data import digest, write_json
from wattly_ml.experiments import blend_predictions, columns, fit_models
from wattly_ml.features import FEATURES
from wattly_ml.timebase import DAY, SGT, STEP, instant
from test_corrections import dataset
from test_pipeline import history


def reference(path, start):
    path.mkdir(); (path/'final-fit').mkdir()
    boundary = start+5*DAY
    write_json(path/'selection.json', {'fixture': 'SYNTHETIC ONLY'})
    joblib.dump({'fixture': 'SYNTHETIC ONLY'}, path/'final-fit/candidates.joblib')
    tomorrow = datetime.now(timezone.utc).astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)+DAY
    write_json(path/'prospective.json', {'start': tomorrow.isoformat(), 'end': (tomorrow+7*DAY).isoformat(),
        'selectionSha256': digest(path/'selection.json'), 'modelSha256': digest(path/'final-fit/candidates.joblib')})
    write_json(path/'final-fit/manifest.json', {'prospectiveSha256': digest(path/'prospective.json')})
    write_json(path/'summary.json', {'status': 'COMPLETE', 'protocol': {'trainingStart': start.isoformat(),
        'selectionEnd': boundary.isoformat(), 'folds': [{'cutoff': (start+i*DAY).isoformat(),
        'end': (start+(i+1)*DAY).isoformat()} for i in (3, 4)]}})
    return path


def test_fixed_blends_preserve_missing_negative_prices_and_weights():
    assert blend_predictions([-10]*24, [30]*24, .25) == [20]*24
    assert blend_predictions(None, [30]*24, .25) == [None]*24
    assert blend_predictions([None]+[1]*23, [2]*24, .5) == [None]+[1.5]*23
    with pytest.raises(ValueError, match='weight'):
        blend_predictions([1]*24, [2]*24, 2)


def test_shared_horizon_model_fits_once_with_train_only_preprocessing(monkeypatch):
    xs = [np.full((48, len(FEATURES)), float(h)) for h in range(1, 25)]
    xs[0][0, 0] = np.nan
    ys = [np.full(48, float(h)) for h in range(1, 25)]
    fits = []; original = Pipeline.fit
    def capture(self, x, y, **kwargs):
        fits.append((x.copy(), np.asarray(y).copy()))
        return original(self, x, y, **kwargs)
    monkeypatch.setattr(Pipeline, 'fit', capture)
    models, _ = fit_models(xs, ys, specs={'HGB_SHARED': stability.SPECS_STABILITY['HGB_SHARED']})
    assert len(fits) == 1 and len(fits[0][0]) == 48*24
    np.testing.assert_array_equal(fits[0][0][:, -1], np.repeat(np.arange(1, 25), 48))
    np.testing.assert_array_equal(fits[0][1], np.repeat(np.arange(1, 25), 48))
    saved = models['HGB_SHARED']
    assert len(saved) == 24 and all(m is saved[0] for m in saved)
    assert saved[0][0].statistics_[0] == np.nanmedian(np.concatenate(xs)[:, 0])
    with pytest.raises(ValueError, match='explicit'):
        columns(xs[0], ['forecast_horizon'])
    with pytest.raises(ValueError, match='between 1 and 24'):
        columns(xs[0], ['forecast_horizon'], horizon=25)


def test_study_purges_future_revisions_freezes_later_window_and_never_promotes(tmp_path, monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE', 'EMC_RT_20260929_24_PERIODS')
    rows = history(9); start = instant(rows[0]['periodStart']); boundary = start+5*DAY
    previous = reference(tmp_path/'previous', start)
    checksums = {str(p): digest(p) for p in previous.rglob('*') if p.is_file()}
    fits = []; original = stability.fit_models
    def capture(xs, ys, **kwargs):
        fits.append(([np.asarray(x) for x in xs], [np.asarray(y) for y in ys]))
        return original(xs, ys, **kwargs)
    monkeypatch.setattr(stability, 'fit_models', capture)
    output = tmp_path/'study'
    result = stability.study(dataset(tmp_path/'data', rows), previous, output)
    first = fits[:]; fits.clear()
    assert all(digest(Path(p)) == checksum for p, checksum in checksums.items())
    assert instant(result['prospective']['start']) >= instant(result['protocol']['previousConfirmation']['end'])
    assert result['referenceUnchanged'] and not result['prospective']['productionEligible']
    assert not (output/'current.json').exists()
    samples = result['finalFit']['trainingSamplesByMethod']
    assert samples['HGB_SHARED']['rowsPerEstimator'] == samples['HGB_SMALL']['rowsPerEstimator']*24
    assert samples['HGB_SHARED']['fittedEstimators'] == 1
    assert samples['BLEND_SMALL_MEDIAN_25']['fittedEstimators'] == 0
    saved_rows = [json.loads(line) for line in (output/'validation/predictions.jsonl').read_text().splitlines()]
    methods = result['selection']['validation']['comparisonMethods']
    eligible = [{(r['asOf'], r['horizon'], r['targetPeriod']) for r in saved_rows
                 if r['method'] == m and r['exclusionReason'] is None} for m in methods]
    common = set.intersection(*eligible)
    for m in methods:
        valid = [r for r in saved_rows if r['method'] == m and (r['asOf'],r['horizon'],r['targetPeriod']) in common]
        assert result['selection']['validation']['table'][m]['mae'] == pytest.approx(mean(
            abs(r['predictedUsep']-r['actual']) for r in valid))
        for f in result['folds']:
            in_fold = [r for r in valid if instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])]
            assert all(instant(r['targetPeriod'])+STEP <= instant(f['validationEnd']) for r in in_fold)
    changed = [{**r, 'usep': 99999999} if instant(r['periodStart']) >= boundary else r for r in rows]
    changed.append({**rows[50], 'revisionId': 'late', 'usep': -99999999,
        'sourceUpdatedAt': (boundary+STEP).isoformat(), 'availableAt': (boundary+STEP).isoformat()})
    other = stability.study(dataset(tmp_path/'changed-data',changed), previous,tmp_path/'counterfactual')
    assert other['selection']['ranking'] == result['selection']['ranking']
    assert other['selection']['validation'] == result['selection']['validation']
    assert len(first) == len(fits)
    for before, after in zip(first, fits):
        for aa, bb in zip(before, after):
            for a, b in zip(aa, bb):
                np.testing.assert_allclose(a, b, atol=0, rtol=0, equal_nan=True)
    with pytest.raises(ValueError, match='immutable'):
        stability.study(tmp_path/'data', previous, output)
    with pytest.raises(ValueError, match='pending'):
        corrections.evaluate(tmp_path/'data', output, tmp_path/'early')
    assert not (tmp_path/'early').exists()
    # Synthetic elapsed confirmation: all original/research estimators must remain frozen.
    plan = json.loads((output/'prospective.json').read_text())
    plan.update(frozenAt=(start+8*DAY).isoformat(), start=(start+9*DAY).isoformat(), end=(start+16*DAY).isoformat())
    write_json(output/'prospective.json', plan)
    manifest = json.loads((output/'final-fit/manifest.json').read_text())
    manifest['prospectiveSha256'] = digest(output/'prospective.json')
    write_json(output/'final-fit/manifest.json', manifest)
    def fail_fit(*args, **kwargs):
        raise AssertionError('Frozen confirmation cannot fit')
    monkeypatch.setattr(Pipeline, 'fit', fail_fit)
    confirmed = corrections.evaluate(dataset(tmp_path/'future',history(20)), output, tmp_path/'confirmed')
    assert confirmed['status'] == 'COMPLETE' and confirmed['commonOriginDays'] == 7
    assert not confirmed['productionEligible']


def test_recent_window_restricts_origins_not_feature_context():
    rows = history(10); start = instant(rows[0]['periodStart']); cutoff = start+9*DAY
    _, metadata = stability.fit(rows, start, cutoff)
    samples = metadata['trainingSamplesByMethod']
    assert instant(samples['HGB_RECENT5']['trainingStart']) == cutoff-5*DAY
    assert samples['HGB_RECENT5']['perHorizon'][0] < samples['HGB_SMALL']['perHorizon'][0]
    assert metadata['featureMissingFraction']['HGB_RECENT5']['lag48'] == 0


def test_study_rejects_non_calendar_confirmation_before_writing(tmp_path, monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE', 'EMC_RT_20260929_24_PERIODS')
    rows = history(9); start = instant(rows[0]['periodStart'])
    previous = reference(tmp_path/'previous', start)
    plan = json.loads((previous/'prospective.json').read_text())
    plan.update(start=(instant(plan['start'])+STEP).isoformat(), end=(instant(plan['end'])+STEP).isoformat())
    write_json(previous/'prospective.json', plan)
    write_json(previous/'final-fit/manifest.json', {'prospectiveSha256': digest(previous/'prospective.json')})
    with pytest.raises(ValueError, match='Singapore calendar days'):
        stability.study(dataset(tmp_path/'data', rows), previous, tmp_path/'rejected')
    assert not (tmp_path/'rejected').exists()
