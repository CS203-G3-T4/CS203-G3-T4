import json
from statistics import mean

import numpy as np
import pytest
from sklearn.pipeline import Pipeline

from wattly_ml import demo, experiments
from wattly_ml.data import digest, quality, write_json
from wattly_ml.features import FEATURES
from wattly_ml.timebase import instant
from test_pipeline import history


def test_feature_differences_keep_missing_values_and_negative_prices():
    x = np.zeros((2, len(FEATURES)))
    x[:, FEATURES.index('lag1')] = [10, -5]
    x[:, FEATURES.index('lag2')] = [7, np.nan]
    x[:, FEATURES.index('lag6')] = [20, -1]
    transformed = experiments.columns(x, ['lag1', 'change_lag1_lag2', 'change_lag1_lag6'])
    np.testing.assert_allclose(transformed, [[10, 3, -10], [-5, np.nan, -4]], equal_nan=True)


def test_paired_study_freezes_selection_before_test_and_preserves_every_candidate(tmp_path, monkeypatch):
    rows = history(6)
    dataset = tmp_path/'data'; dataset.mkdir()
    prices = dataset/'prices.jsonl'
    prices.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    manifest = {'sha256': digest(prices), 'quality': quality(rows), 'provisionalOffsetMinutes': 0,
                'sources': [{'acquiredAt': rows[-1]['availableAt']}]}
    write_json(dataset/'manifest.json', manifest)
    reference = tmp_path/'reference'
    demo.experiment(dataset, reference)
    original_reference = (reference/'public/forecast-demo.json').read_bytes()
    fits = []
    original_fit = Pipeline.fit
    def observe_fit(self, x, y, **kwargs):
        fits.append((np.array(x, copy=True), np.array(y, copy=True)))
        return original_fit(self, x, y, **kwargs)
    monkeypatch.setattr(Pipeline, 'fit', observe_fit)
    output = tmp_path/'study'
    result = experiments.compare(dataset, reference, output)
    first_fits = fits[:]; fits.clear()
    assert result['referenceUnchanged'] and not (output/'current.json').exists()
    assert len(result['folds']) == 2
    assert result['selection']['validation']['commonPairs'] == 1152
    assert result['selection']['validation']['allMethodCommonPairs'] == 0
    assert result['existingTestDiagnostic']['commonPairs'] == 576
    assert set(result['selection']['comparisonMethods']) == set(experiments.SPECS) | {'PERSISTENCE', 'B1', 'B2'}
    records = [json.loads(line) for line in (output/'validation/predictions.jsonl').read_text().splitlines()]
    pairs = {}
    for method in result['selection']['comparisonMethods']:
        valid = [r for r in records if r['method'] == method and r['exclusionReason'] is None]
        pairs[method] = {(r['asOf'], r['horizon'], r['targetPeriod']) for r in valid}
        assert len(valid) == len(pairs[method]) == 1152
        actual = mean(abs(r['predictedUsep']-r['actual']) for r in valid)
        assert result['selection']['validation']['table'][method]['mae'] == pytest.approx(actual)
    assert all(p == pairs['CURRENT'] for p in pairs.values())
    assert all(f['trainingSamplesPerHorizon'] == [f['trainingSamplesPerHorizon'][0]]*24 for f in result['folds'])
    assert all((output/f'fold-{n}'/'candidates.joblib').exists() for n in (1, 2))
    assert b'\r' not in (output/'horizons.csv').read_bytes()
    assert result['selection']['validation']['table']['CURRENT']['spikes']['precision'] is None
    exported = tmp_path/'latest.json'
    presentation = experiments.presentation(output, reference, exported)
    latest = json.loads(exported.read_text())
    errors = [abs(p['AI']-p['actual']) for o in latest['origins'] for p in o['points']]
    assert len(errors) == 576 and mean(errors) == pytest.approx(presentation['diagnosticMae'])
    assert latest['test']['table']['AI']['mae'] == pytest.approx(mean(errors))
    assert latest['study']['validationPairs'] == 1152
    assert not latest['productionApproved'] and not latest['actionable']
    assert latest['evaluationPurpose'] == 'ALREADY_SEEN_DIAGNOSTIC'
    with pytest.raises(ValueError, match='immutable'):
        experiments.presentation(output, reference, exported)
    with pytest.raises(ValueError, match='immutable'):
        experiments.compare(dataset, reference, output)

    # Change held-out values and inject late revisions of old training periods.
    # Stop immediately after selection, so the reference diagnostic is never reused
    # with these synthetic counterfactual inputs.
    first_test = instant(result['protocol']['existingTest']['start'])
    changed = [{**r, 'usep': 9999999} if instant(r['periodStart']) >= first_test else r for r in rows]
    changed.append({**rows[50], 'usep': -9999999, 'availableAt': first_test.isoformat(), 'revisionId': 'late'})
    monkeypatch.setattr(experiments, 'load_dataset', lambda _: (changed, manifest))
    other = tmp_path/'counterfactual'
    def stop_before_test(*args, **kwargs):
        assert (other/'selection.json').exists()
        raise RuntimeError('Stopped before diagnostic outcomes')
    monkeypatch.setattr(experiments, 'backtest', stop_before_test)
    with pytest.raises(RuntimeError, match='Stopped before diagnostic'):
        experiments.compare(dataset, reference, other)
    second_selection = json.loads((other/'selection.json').read_text())
    assert second_selection['validation'] == result['selection']['validation']
    assert second_selection['ranking'] == result['selection']['ranking']
    assert len(fits) == len(first_fits) == 2*24*len(experiments.SPECS)
    for before, after in zip(first_fits, fits):
        for a, b in zip(before, after):
            np.testing.assert_allclose(a, b, atol=0, rtol=0, equal_nan=True)
    assert (other/'failure.json').exists()  # Failed/losing evidence is retained too.
    assert (reference/'public/forecast-demo.json').read_bytes() == original_reference
