import json
from statistics import mean

import joblib
import numpy as np
import pytest

from wattly_ml import corrections, retraining
from wattly_ml.backtest import History
from wattly_ml.baselines import baselines
from wattly_ml.data import digest, quality, truth, write_json
from wattly_ml.timebase import DAY, STEP, instant, targets
from wattly_ml.train import training_examples
from test_pipeline import history


def dataset(path, rows):
    path.mkdir()
    prices = path/'prices.jsonl'
    prices.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    write_json(path/'manifest.json', {'sha256': digest(prices), 'quality': quality(rows),
        'provisionalOffsetMinutes': 0,
        'sources': [{'acquiredAt': max(instant(r['availableAt']) for r in rows).isoformat()}]})
    return path


def test_residual_targets_use_origin_known_baseline_and_exclude_missing_anchors():
    rows = history(6); start = instant(rows[48]['periodStart']); cutoff = start+3*DAY
    xs, ys, _ = training_examples(rows, start, cutoff, policy='PROVISIONAL', baseline='B2')
    past = History(rows).at(start); anchors = baselines(past, start)['B2']; labels = truth(rows, 'PROVISIONAL', cutoff)
    for h, target in enumerate(targets(start)):
        assert ys[h][0] == labels[target]-anchors[h]
    missing = [r for r in rows if instant(r['periodStart']) != start-DAY+STEP]
    assert len(training_examples(missing,start,cutoff,policy='PROVISIONAL',baseline='B2')[1][0]) < len(ys[0])
    with pytest.raises(ValueError, match='B1 or B2'):
        training_examples(rows, start, cutoff, baseline='AI')


def test_correction_study_freezes_models_and_confirmation_never_fits(tmp_path, monkeypatch):
    monkeypatch.setenv('FORECAST_PERIOD_MAPPING_EVIDENCE', 'EMC_RT_20260929_24_PERIODS')
    rows = history(9); data = dataset(tmp_path/'data', rows)
    start = instant(rows[0]['periodStart']); boundary = start+5*DAY
    previous = tmp_path/'previous'; previous.mkdir(); (previous/'final-fit').mkdir()
    models, metadata = retraining.fit(rows, start, boundary)[1:]
    joblib.dump({'models': models}, previous/'final-fit/candidates.joblib')
    write_json(previous/'selection.json', {'fixture': 'SYNTHETIC ONLY'})
    write_json(previous/'summary.json', {'status': 'COMPLETE', 'selectionSha256': digest(previous/'selection.json'),
        'protocol': {'testStart': boundary.isoformat()}})
    output = tmp_path/'study'
    fitted = []; original_fit = corrections.fit_models
    def capture(xs, ys, **kwargs):
        fitted.append(([np.asarray(x) for x in xs], [np.asarray(y) for y in ys]))
        return original_fit(xs, ys, **kwargs)
    monkeypatch.setattr(corrections, 'fit_models', capture)
    result = corrections.study(data, previous, output)
    first_fit = fitted[:]; fitted.clear()
    assert result['protocol']['selectionEnd'] == boundary.isoformat()
    assert result['finalFit']['trainingCutoff'] == boundary.isoformat()
    assert result['prospective']['status'] == 'AWAITING_FUTURE_DATA'
    assert not result['prospective']['productionEligible'] and not (output/'current.json').exists()
    saved = joblib.load(output/'final-fit/candidates.joblib')
    assert len(saved['models']['HGB_RESIDUAL_B2']) == 24
    test_rows = [json.loads(line) for line in (output/'held-out-test/predictions.jsonl').read_text().splitlines()]
    # The sample has complete preceding-day prices; independently recalculate paired MAE.
    pairwise = result['pairwiseTest']['HGB_RESIDUAL_B2']
    valid = [r for r in test_rows if r['method'] == 'HGB_RESIDUAL_B2' and r['exclusionReason'] is None]
    assert pairwise['table']['HGB_RESIDUAL_B2']['mae'] == pytest.approx(mean(abs(r['predictedUsep']-r['actual']) for r in valid))
    missing_prior = [r for r in rows if not start+6*DAY <= instant(r['periodStart']) < start+7*DAY]
    no_anchor = corrections.records(missing_prior,start+7*DAY,start+8*DAY,saved['models'],result['finalFit'])
    residual = [r for r in no_anchor if r['method']=='HGB_RESIDUAL_B2']
    assert all(r['predictedUsep'] is None and r['exclusionReason'] is not None for r in residual)
    with pytest.raises(ValueError, match='immutable'):
        corrections.study(data,previous,output)

    # Neither the protected old test, the new holdout, nor late revisions may affect selection or final fit.
    changed = [{**r,'usep':9999999} if instant(r['periodStart']) >= boundary else r for r in rows]
    changed.append({**rows[50], 'usep':-9999999, 'revisionId':'late',
        'sourceUpdatedAt':(boundary+STEP).isoformat(), 'availableAt':(boundary+STEP).isoformat()})
    other_data = dataset(tmp_path/'changed', changed); other = tmp_path/'counterfactual'
    original_records = corrections.records
    def stop_before_holdout(rows,start,end,*args,**kwargs):
        if start >= boundary:
            assert (other/'selection.json').exists() and (other/'prospective.json').exists()
            raise RuntimeError('Stop before holdout')
        return original_records(rows,start,end,*args,**kwargs)
    monkeypatch.setattr(corrections,'records',stop_before_holdout)
    with pytest.raises(RuntimeError,match='Stop before holdout'):
        corrections.study(other_data,previous,other)
    selected = json.loads((other/'selection.json').read_text())
    assert selected['validation'] == result['selection']['validation']
    assert selected['ranking'] == result['selection']['ranking']
    assert len(fitted) == len(first_fit)
    for before,after in zip(first_fit,fitted):
        for aa,bb in zip(before,after):
            for a,b in zip(aa,bb):np.testing.assert_allclose(a,b,atol=0,rtol=0,equal_nan=True)
    monkeypatch.setattr(corrections,'records',original_records)
    with pytest.raises(ValueError,match='pending'):
        corrections.evaluate(data,output,tmp_path/'too-soon')
    assert not (tmp_path/'too-soon').exists()

    # Synthetic confirmation window only; never counted as real performance evidence.
    plan = json.loads((output/'prospective.json').read_text())
    plan.update(frozenAt=(start+8*DAY).isoformat(),start=(start+9*DAY).isoformat(),end=(start+16*DAY).isoformat())
    write_json(output/'prospective.json',plan)
    fixture_manifest = json.loads((output/'final-fit/manifest.json').read_text())
    fixture_manifest['prospectiveSha256'] = digest(output/'prospective.json')
    write_json(output/'final-fit/manifest.json',fixture_manifest)
    future_data = dataset(tmp_path/'future',history(20))
    def no_fitting(*args,**kwargs):raise AssertionError('Confirmation must never fit')
    monkeypatch.setattr(corrections,'fit_models',no_fitting)
    confirmed = corrections.evaluate(future_data,output,tmp_path/'confirmed')
    assert confirmed['status']=='COMPLETE' and confirmed['commonOriginDays']==7
    assert not confirmed['productionEligible']
    (output/'selection.json').write_text('{}')
    with pytest.raises(ValueError,match='changed'):
        corrections.evaluate(future_data,output,tmp_path/'tampered')
