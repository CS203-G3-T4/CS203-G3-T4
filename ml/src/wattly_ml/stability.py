"""Small training-method experiments; development-only selection and later confirmation."""
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path

import joblib

from .artifacts import versions
from .corrections import records, write_horizons
from .cli import report
from .data import digest, load_dataset, write_json
from .experiments import CALENDAR, SPECS, fit_metadata, fit_models, paired_summary
from .timebase import DAY, SGT, instant
from .train import training_examples


SMALL = {**SPECS['HGB_MAE']['parameters'], 'max_leaf_nodes': 7}
REGULARISED = {**SMALL, 'min_samples_leaf': 40, 'l2_regularization': 10}
SPECS_STABILITY = {m: deepcopy(SPECS[m]) for m in ('CURRENT', 'HGB_MAE', 'MEDIAN')}
SPECS_STABILITY.update({
    'HGB_SMALL': {'family': 'HGB absolute error', 'features': CALENDAR, 'parameters': SMALL},
    'HGB_REGULARISED': {'family': 'HGB absolute error', 'features': CALENDAR, 'parameters': REGULARISED},
    'HGB_RECENT5': {'family': 'HGB absolute error', 'features': CALENDAR, 'parameters': SMALL, 'trainingDays': 5},
    **{name: {'family': 'HGB absolute error', 'features': CALENDAR+['forecast_horizon'],
              'parameters': parameters, 'sharedHorizons': True}
       for name, parameters in [('HGB_SHARED', SMALL), ('HGB_SHARED_REGULARISED', REGULARISED)]},
    **{f'BLEND_SMALL_{source}_{percent}': {'family': 'blend', 'features': [], 'parameters': {},
        'sources': ['HGB_SMALL', source], 'weight': percent/100}
       for source in ('MEDIAN', 'B1') for percent in (25, 50, 75)},
})


def fit(rows, start, cutoff):
    models, missing, samples = {}, {}, {}
    for days in (None, 5):
        specs = {m: s for m, s in SPECS_STABILITY.items()
                 if s['family'] != 'blend' and s.get('trainingDays') == days}
        lower = start if days is None else max(start, cutoff-days*DAY)
        xs, ys, excluded = training_examples(rows, lower, cutoff, policy='PROVISIONAL')
        fitted, absent = fit_models(xs, ys, specs=specs)
        models.update(fitted); missing.update(absent)
        for m, spec in specs.items():
            samples[m] = {'trainingStart': lower.isoformat(), 'trainingCutoff': cutoff.isoformat(),
                'perHorizon': [len(y) for y in ys], 'excludedOrigins': excluded,
                'fittedEstimators': 1 if spec.get('sharedHorizons') else 24,
                'rowsPerEstimator': sum(map(len, ys)) if spec.get('sharedHorizons') else len(ys[0]),
                'independentObservationsClaimed': False}
        if days is None:
            metadata = fit_metadata(rows, lower, cutoff, ys, excluded, absent)
    for m, spec in SPECS_STABILITY.items():
        if spec['family'] == 'blend':
            samples[m] = {'fittedEstimators': 0, 'sources': spec['sources'], 'weight': spec['weight']}
    metadata.update(featureMissingFraction=missing, trainingSamplesByMethod=samples)
    return models, metadata


def study(dataset: Path, previous: Path, output: Path):
    if output.exists():
        raise ValueError('Training-method studies are immutable; choose a new output directory')
    rows, manifest = load_dataset(dataset)
    if (manifest['quality']['parsingFailures'] or not manifest['quality']['publicationVintages']
            or manifest['provisionalOffsetMinutes'] != 0):
        raise ValueError('Require valid zero-offset publication/availability vintages')
    if os.environ.get('FORECAST_PERIOD_MAPPING_EVIDENCE') != 'EMC_RT_20260929_24_PERIODS':
        raise ValueError('Source the documented period-mapping evidence')
    prior = json.loads((previous/'summary.json').read_text())
    plan = json.loads((previous/'prospective.json').read_text())
    final_manifest = json.loads((previous/'final-fit/manifest.json').read_text())
    if (prior['status'] != 'COMPLETE' or digest(previous/'selection.json') != plan['selectionSha256']
            or digest(previous/'final-fit/candidates.joblib') != plan['modelSha256']
            or digest(previous/'prospective.json') != final_manifest['prospectiveSha256']):
        raise ValueError('Require an unchanged complete correction study and confirmation plan')
    reference_hashes = {str(p): digest(p) for p in previous.rglob('*') if p.is_file()}
    start, selection_end = (instant(prior['protocol'][k]) for k in ('trainingStart', 'selectionEnd'))
    previous_end = instant(plan['end'])
    if (previous_end-instant(plan['start']) != 7*DAY or
            previous_end != previous_end.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0)):
        raise ValueError('Previous confirmation must cover seven Singapore calendar days')
    cutoffs = [instant(f['cutoff']) for f in prior['protocol']['folds']]
    if len(cutoffs) < 2 or any(c < start+3*DAY or c+DAY > selection_end for c in cutoffs):
        raise ValueError('Need at least two earlier chronological validation days')
    development = [r for r in rows if all(instant(r[k]) < selection_end
                   for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    protocol = {'createdAt': datetime.now(timezone.utc).isoformat(), 'datasetDigest': manifest['sha256'],
        'label': 'Experimental — limited training data.', 'timezone': 'Asia/Singapore',
        'trainingStart': start.isoformat(), 'selectionEnd': selection_end.isoformat(),
        'horizonCount': 24, 'intervalMinutes': 30, 'forecastHours': 12,
        'candidates': SPECS_STABILITY, 'folds': prior['protocol']['folds'],
        'selectionRule': 'Lowest pooled earlier-validation MAE on identical eligible pairs; lexical tie break',
        'testRule': 'No October 3/5 diagnostics used or regenerated; later separate untouched confirmation only',
        'purging': 'Every training and validation target interval ends by its split boundary',
        'recentWindowRule': 'Restrict training origins; retain only origin-known older feature context',
        'sharedModelRule': 'One estimator with explicit horizon; pooled rows are dependent, not extra price observations',
        'blendRule': 'Fixed 25/50/75 percent HGB weight, chosen only on earlier validation; missing anchors unavailable',
        'demandWeather': 'Excluded', 'mappingEvidenceId': os.environ['FORECAST_PERIOD_MAPPING_EVIDENCE'],
        'previousStudy': str(previous), 'previousConfirmation': plan,
        'dependencies': versions(), 'researchOnly': True, 'productionEligible': False, 'promotion': 'Never attempted'}
    output.mkdir(parents=True)
    write_json(output/'protocol.json', protocol)
    try:
        validation_rows, folds = [], []
        for number, cutoff in enumerate(cutoffs, 1):
            models, metadata = fit(development, start, cutoff)
            batch = records(development, cutoff, cutoff+DAY, models, metadata, specs=SPECS_STABILITY)
            validation_rows.extend(batch)
            folds.append({'number': number, **metadata, 'validationStart': cutoff.isoformat(),
                          'validationEnd': (cutoff+DAY).isoformat()})
            report(output/f'fold-{number}', batch, metadata)
            joblib.dump({'researchOnly': True, 'productionEligible': False, 'models': models,
                         'specs': SPECS_STABILITY}, output/f'fold-{number}'/'candidates.joblib', compress=3)
        methods = sorted(m for m in {r['method'] for r in validation_rows} if all(any(
            r['method'] == m and r['exclusionReason'] is None and
            instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])
            for r in validation_rows) for f in folds))
        if not set(SPECS_STABILITY).issubset(methods):
            raise ValueError('Some fixed candidates lack validation coverage; no selection')
        validation = paired_summary(validation_rows, methods)
        if not validation['commonPairs']:
            raise ValueError('No common validation pairs; no model selection')
        order = sorted(methods, key=lambda m: (validation['table'][m]['mae'], m))
        learned = next(m for m in order if m in SPECS_STABILITY and m != 'MEDIAN')
        selection = {'savedAt': datetime.now(timezone.utc).isoformat(), 'selectedMethod': order[0],
            'selectedLearnedModel': learned, 'ranking': order, 'validation': validation,
            'selectionDataEndsBefore': selection_end.isoformat(), 'productionEligible': False}
        write_json(output/'selection.json', selection)
        report(output/'validation', validation_rows, validation)
        for f in folds:
            lower, upper = instant(f['validationStart']), instant(f['validationEnd'])
            f['scores'] = paired_summary([r for r in validation_rows if lower <= instant(r['asOf']) < upper], methods)
        consistency = {m: {'dailyMae': [f['scores']['table'][m]['mae'] for f in folds],
            'winsAgainst': {ref: sum(f['scores']['table'][m]['mae'] < f['scores']['table'][ref]['mae']
                                    for f in folds) for ref in ('MEDIAN', 'HGB_MAE', 'B1')},
            'worstDayMae': max(f['scores']['table'][m]['mae'] for f in folds)} for m in methods}
        models, metadata = fit(development, start, selection_end)
        final = output/'final-fit'; final.mkdir()
        model_path = final/'candidates.joblib'
        joblib.dump({'researchOnly': True, 'productionEligible': False, 'models': models,
                     'specs': SPECS_STABILITY}, model_path, compress=3)
        frozen_at = datetime.now(timezone.utc)
        tomorrow = frozen_at.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0)+DAY
        future_start = max(tomorrow, previous_end)
        future = {'status': 'AWAITING_FUTURE_DATA', 'frozenAt': frozen_at.isoformat(),
            'start': future_start.isoformat(), 'end': (future_start+7*DAY).isoformat(),
            'modelSha256': digest(model_path), 'selectionSha256': digest(output/'selection.json'),
            'selectedMethod': order[0], 'selectedLearnedModel': learned, 'productionEligible': False,
            'rule': 'Seven later complete SGT days, disjoint from the original confirmation; no fitting or reselection'}
        write_json(output/'prospective.json', future)
        write_json(final/'manifest.json', {**metadata, 'datasetDigest': manifest['sha256'],
            'selectionSha256': future['selectionSha256'], 'modelSha256': future['modelSha256'],
            'prospectiveSha256': digest(output/'prospective.json'), 'dependencies': versions(),
            'researchOnly': True, 'productionEligible': False})
        if not all(digest(Path(p)) == checksum for p, checksum in reference_hashes.items()):
            raise ValueError('Previous study changed during the experiment')
        summary = {'status': 'COMPLETE', 'protocol': protocol, 'selection': selection, 'folds': folds,
            'consistency': consistency, 'finalFit': metadata, 'prospective': future,
            'selectionSha256': future['selectionSha256'], 'referenceFileDigests': reference_hashes,
            'referenceUnchanged': True, 'datasetQuality': manifest['quality'],
            'limitations': ['Repeated development days are exploratory selection evidence, not an untouched test.',
                'Overlapping horizons and shared-model rows are dependent; training sample count is not independent history.',
                'Only six validation days in the current collector dataset; no consistent future accuracy claim.',
                'Missing configured spike history prevents spike precision/recall claims.',
                'Future confirmation pending; no serving/UI/production promotion changes.']}
        write_json(output/'summary.json', summary)
        write_horizons(output/'horizons.csv', [('validation', validation_rows, validation)])
        return summary
    except BaseException as exc:
        write_json(output/'failure.json', {'status': 'INCOMPLETE', 'type': type(exc).__name__, 'reason': str(exc),
            'note': 'Research evidence retained outside serving; retry in a new directory'})
        raise
