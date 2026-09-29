"""Fixed, offline model comparisons. No serving, promotion or UI changes."""
from collections import Counter
from datetime import datetime, timezone
import csv
import json
from pathlib import Path
from statistics import mean

import joblib
import numpy as np
from sklearn.dummy import DummyRegressor
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

from .backtest import History, backtest, scores
from .cli import report
from .data import digest, load_dataset, truth, write_json
from .features import FEATURES, features
from .timebase import DAY, instant, targets
from .train import train, training_examples

LAGS = ['latest', 'lag1', 'lag2', 'lag3', 'lag6', 'lag12']
DAILY = LAGS + ['lag48', 'yesterday_target']
ROLLING = DAILY + ['mean6', 'std6', 'mean48', 'std48']
CHANGES = ROLLING + ['change_lag1_lag2', 'change_lag1_lag3', 'change_lag1_lag6']
CALENDAR = CHANGES + ['target_half_hour', 'target_weekday', 'target_weekend']
SPECS = {
    'CURRENT': {'family': 'existing HGB squared error', 'features': FEATURES,
                'parameters': {'max_iter': 40, 'max_leaf_nodes': 15, 'l2_regularization': 1, 'random_state': 42}},
    'MEDIAN': {'family': 'training target median', 'features': ['latest'], 'parameters': {}},
    **{name: {'family': 'ridge', 'features': names, 'parameters': {'alpha': 10.0}}
       for name, names in [('RIDGE_LAGS', LAGS), ('RIDGE_DAILY', DAILY), ('RIDGE_ROLLING', ROLLING),
                           ('RIDGE_CHANGES', CHANGES), ('RIDGE_CALENDAR', CALENDAR)]},
    'HGB_MAE': {'family': 'HGB absolute error', 'features': CALENDAR,
                'parameters': {'loss': 'absolute_error', 'max_iter': 40, 'max_leaf_nodes': 15,
                               'l2_regularization': 1, 'random_state': 42}},
}


def columns(matrix, names):
    """The same feature selection/price differences during fitting and prediction."""
    matrix = np.asarray(matrix, dtype=float)
    values = {name: matrix[:, i] for i, name in enumerate(FEATURES)}
    for name, lag in [('change_lag1_lag2', 'lag2'), ('change_lag1_lag3', 'lag3'), ('change_lag1_lag6', 'lag6')]:
        values[name] = values['lag1'] - values[lag]
    return np.column_stack([values[name] for name in names])


def fit_candidates(rows, manifest, start, cutoff, end):
    # Reuse the real trainer for the reference; all other models use its exact examples.
    current, records = train(rows, manifest, start, cutoff, end, policy='PROVISIONAL', exploratory=True)
    xs, ys, excluded = training_examples(rows, start, cutoff, policy='PROVISIONAL')
    models = {'CURRENT': current['models']}
    missing = {}
    with threadpool_limits(limits=1):
        for name, spec in SPECS.items():
            transformed = [columns(x, spec['features']) for x in xs]
            missing[name] = dict(zip(spec['features'], np.isnan(np.concatenate(transformed)).mean(axis=0).tolist()))
            if name == 'CURRENT':
                continue
            models[name] = []
            for x, y in zip(transformed, ys):
                imputer = SimpleImputer(strategy='median', add_indicator=True, keep_empty_features=True)
                if spec['family'] == 'ridge':
                    model = make_pipeline(imputer, StandardScaler(), Ridge(**spec['parameters']))
                elif name == 'MEDIAN':
                    model = make_pipeline(imputer, DummyRegressor(strategy='median'))
                else:
                    model = make_pipeline(imputer, HistGradientBoostingRegressor(**spec['parameters']))
                models[name].append(model.fit(x, y))
    actuals = truth(rows, 'PROVISIONAL', cutoff)
    training_prices = [v for t, v in actuals.items() if start <= t < cutoff]
    metadata = {'trainingStart': start.isoformat(), 'trainingCutoff': cutoff.isoformat(),
                'trainingSamplesPerHorizon': [len(y) for y in ys], 'excludedTrainingOrigins': excluded,
                'uniqueTrainingPricePeriods': len(training_prices), 'featureMissingFraction': missing,
                'highPriceTailThreshold': float(np.quantile(training_prices, .95)),
                'highPriceTailRule': 'Above training-only 95th percentile of unique price periods; NOT a spike label',
                'productionEligible': False}
    return current, models, records, metadata


def candidate_records(rows, baseline_records, models, metadata):
    history = History(rows)
    output = [dict(r) for r in baseline_records if r['method'] != 'AI']
    ai = [r for r in baseline_records if r['method'] == 'AI']
    for stamp in sorted({r['asOf'] for r in ai}):
        template = [r for r in ai if r['asOf'] == stamp]
        origin = instant(stamp)
        past = history.at(origin)
        raw = [features(past, origin, t) for t in targets(origin)]
        eligible = any(r['exclusionReason'] is None for r in template)
        predictions = {'CURRENT': [r['predictedUsep'] for r in template],
                       'PERSISTENCE': [past[max(past)]['usep'] if past else None]*24}
        with threadpool_limits(limits=1):
            for name, spec in SPECS.items():
                if name != 'CURRENT':
                    x = columns(raw, spec['features'])
                    predictions[name] = [float(model.predict([x[h]])[0]) for h, model in enumerate(models[name])] if eligible else [None]*24
        for name, values in predictions.items():
            for r in template:
                value = values[r['horizon']-1] if r['exclusionReason'] is None else None
                output.append({**r, 'method': name, 'modelVersion': 'research-'+name,
                               'predictedUsep': value,
                               'spikeFlag': value > r['threshold'] if value is not None and r['threshold'] is not None else None})
    for r in output:
        r['priceRegime'] = None if r['actual'] is None else 'HIGH_PRICE_TAIL' if r['actual'] > metadata['highPriceTailThreshold'] else 'NON_TAIL'
    return output


def paired_summary(records, methods):
    subset = [r for r in records if r['method'] in methods]
    result = scores(subset)
    eligible = [{(r['asOf'], r['horizon']) for r in subset if r['method'] == m and r['exclusionReason'] is None} for m in methods]
    common = set.intersection(*eligible)
    result['comparisonMethods'] = methods
    result['allMethodCommonPairs'] = scores(records)['commonPairs']
    result['exclusions'] = {m: dict(Counter(r['exclusionReason'] for r in records if r['method'] == m and r['exclusionReason']))
                            for m in sorted({r['method'] for r in records})}
    result['coverage'] = {m: {'eligiblePairs': sum(r['method'] == m and r['exclusionReason'] is None for r in records)}
                          for m in result['exclusions']}
    result['errorByPriceRegime'] = {}
    for method in methods:
        valid = [r for r in subset if r['method'] == method and (r['asOf'], r['horizon']) in common]
        total = sum(abs(r['predictedUsep']-r['actual']) for r in valid)
        result['errorByPriceRegime'][method] = {}
        for regime in ('NON_TAIL', 'HIGH_PRICE_TAIL'):
            group = [r for r in valid if r['priceRegime'] == regime]
            errors = [abs(r['predictedUsep']-r['actual']) for r in group]
            result['errorByPriceRegime'][method][regime] = {'pairs': len(group), 'distinctTargets': len({r['targetPeriod'] for r in group}),
                'mae': mean(errors) if errors else None, 'absoluteErrorShare': sum(errors)/total if total else None}
        spikes = result['table'][method]['spikes']
        positive = {r['targetPeriod'] for r in valid if r['actualSpike'] is True}
        negative = {r['targetPeriod'] for r in valid if r['actualSpike'] is False}
        spikes.update(labelledPositiveTargets=len(positive), labelledNegativeTargets=len(negative),
                      unassessedPairs=sum(r['actualSpike'] is None for r in valid))
        if min(len(positive), len(negative)) < 20:
            spikes.update(precision=None, recall=None, undefinedReason='Fewer than 20 distinct labelled positive and negative targets; no spike accuracy claim')
    result['distinctTargets'] = len({r['targetPeriod'] for r in subset if (r['asOf'], r['horizon']) in common})
    return result


def compare(dataset: Path, reference: Path, output: Path):
    if output.exists():
        raise ValueError('Research runs are immutable; choose a new output directory')
    rows, manifest = load_dataset(dataset)
    prior = json.loads((reference/'protocol.json').read_text())
    if manifest['sha256'] != prior['datasetDigest']:
        raise ValueError('Use the same frozen dataset as the reference for this controlled comparison')
    if manifest['quality']['parsingFailures'] or not manifest['quality']['publicationVintages']:
        raise ValueError('Operational comparison requires valid publication/availability vintages')
    start, first_test, test_end = [instant(prior[k]) for k in ('trainingStart', 'testStart', 'testEnd')]
    reference_hashes = {str(p): digest(p) for p in reference.rglob('*') if p.is_file()}
    # Nothing from the existing test day reaches selection, including late revisions of older periods.
    development = [r for r in rows if all(instant(r[k]) < first_test for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    cutoffs = []
    cutoff = start+2*DAY
    while cutoff+DAY <= first_test:
        cutoffs.append(cutoff)
        cutoff += DAY
    if len(cutoffs) < 2:
        raise ValueError('Need two chronological validation days after at least two training days')
    protocol = {'createdAt': datetime.now(timezone.utc).isoformat(), 'datasetDigest': manifest['sha256'], 'timezone': 'Asia/Singapore',
                'reference': str(reference), 'trainingStart': start.isoformat(),
                'folds': [{'cutoff': c.isoformat(), 'end': (c+DAY).isoformat()} for c in cutoffs],
                'candidates': SPECS, 'statisticalMethods': ['PERSISTENCE', 'B1', 'B2', 'B3'],
                'selectionRule': 'Lowest pooled validation MAE on common pairs; lexical tie break; no horizon-specific selection',
                'comparisonRule': 'Include a method only when it has eligible pairs in every validation fold; freeze before diagnostics',
                'existingTest': {'start': first_test.isoformat(), 'end': test_end.isoformat(),
                                 'purpose': 'Already-seen post-selection diagnostic ONLY, not fresh test evidence'},
                'tailDiagnostic': 'Training-only 95th percentile of unique price periods; not a spike definition',
                'spikeMinimumLabelledTargets': 20, 'demandWeather': 'Excluded from this controlled price/calendar study',
                'productionEligible': False, 'promotion': 'Never attempted by this command'}
    output.mkdir(parents=True)
    write_json(output/'protocol.json', protocol)  # Written before fitting or inspecting candidate errors.
    try:
        folds, records = [], []
        for number, cutoff in enumerate(cutoffs, 1):
            current, models, base_records, metadata = fit_candidates(development, manifest, start, cutoff, cutoff+DAY)
            batch = candidate_records(development, base_records, models, metadata)
            folds.append({'number': number, **metadata, 'validationStart': cutoff.isoformat(), 'validationEnd': (cutoff+DAY).isoformat()})
            records.extend(batch)
            directory = output/f'fold-{number}'
            report(directory, batch, metadata)
            joblib.dump({'researchOnly': True, 'productionEligible': False, 'specs': SPECS, 'models': models}, directory/'candidates.joblib', compress=3)
        methods = sorted(m for m in {r['method'] for r in records}
                         if all(any(r['method'] == m and r['exclusionReason'] is None and
                                    instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd']) for r in records) for f in folds))
        validation = paired_summary(records, methods)
        if not validation['commonPairs']:
            raise ValueError('No identical eligible validation pairs')
        order = sorted(methods, key=lambda m: (validation['table'][m]['mae'], m))
        selection = {'savedAt': datetime.now(timezone.utc).isoformat(), 'selectedMethod': order[0],
                     'selectedLearnedModel': next(m for m in order if m in SPECS and m != 'MEDIAN'),
                     'ranking': order, 'comparisonMethods': methods, 'validation': validation,
                     'selectionDataEndsBefore': first_test.isoformat(), 'productionEligible': False}
        write_json(output/'selection.json', selection)  # Freeze selection BEFORE touching diagnostic outcomes.
        selection_hash = digest(output/'selection.json')
        for fold in folds:
            lower, upper = instant(fold['validationStart']), instant(fold['validationEnd'])
            fold['scores'] = paired_summary([r for r in records if lower <= instant(r['asOf']) < upper], methods)
        report(output/'validation', records, validation)
        # All candidates retained and reported; diagnostic scores never alter selection/settings.
        base = backtest(rows, first_test, test_end, policy='PROVISIONAL', bundle=current, truth_cutoff=test_end)
        diagnostic_records = candidate_records(rows, base, models, folds[-1])
        diagnostic = paired_summary(diagnostic_records, methods)
        old = [json.loads(line) for line in (reference/'test/predictions.jsonl').read_text().splitlines()]
        reference_ai = {(instant(r['asOf']), r['horizon']): r['predictedUsep'] for r in old if r['method'] == 'AI'}
        for r in diagnostic_records:
            if r['method'] == 'CURRENT':
                expected = reference_ai[(instant(r['asOf']), r['horizon'])]
                if expected is None:
                    assert r['predictedUsep'] is None
                else:
                    assert abs(r['predictedUsep']-expected) < 1e-9, 'Reference model changed'
        report(output/'existing-test-diagnostic', diagnostic_records, diagnostic)
        assert digest(output/'selection.json') == selection_hash
        assert all(digest(Path(p)) == checksum for p, checksum in reference_hashes.items())
        assert load_dataset(dataset)[1]['sha256'] == manifest['sha256']
        summary = {'status': 'COMPLETE', 'protocol': protocol, 'selection': selection, 'folds': folds,
                   'existingTestDiagnostic': diagnostic, 'referenceFileDigests': reference_hashes,
                   'selectionSha256': selection_hash, 'referenceUnchanged': True,
                   'limitations': [f'{len(folds)} validation days; overlapping pairs are not independent.',
                       'Existing test was already inspected; diagnostic results are not fresh confirmation.',
                       'No prospective test, seven-day baseline coverage, qualified spike evidence or production promotion.']}
        write_json(output/'summary.json', summary)
        with (output/'horizons.csv').open('w', newline='') as stream:
            writer = csv.writer(stream, lineterminator='\n')
            writer.writerow(['split', 'method', 'horizon', 'hours_to_target_start', 'training_samples_per_fit', 'common_pairs', 'mae_sgd_mwh'])
            for label, result, counts in [('pooled_validation', validation, '/'.join(str(f['trainingSamplesPerHorizon'][0]) for f in folds)),
                                           ('existing_test_diagnostic', diagnostic, str(folds[-1]['trainingSamplesPerHorizon'][0]))]:
                for m in methods:
                    for h, mae in result['table'][m]['perHorizon'].items():
                        writer.writerow([label, m, h, int(h)/2, counts if m in SPECS else 'not fitted', result['commonPairs']//24, mae])
        return summary
    except BaseException as exc:
        write_json(output/'failure.json', {'status': 'INCOMPLETE', 'reason': str(exc), 'type': type(exc).__name__,
                                          'note': 'Partial evidence retained; never a serving artifact. Use a new output directory for another run.'})
        raise
