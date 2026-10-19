"""Baseline-correction research with a frozen, separate future confirmation window."""
from copy import deepcopy
from datetime import datetime, timezone
import csv
import json
import os
from pathlib import Path

import joblib

from .artifacts import versions
from .backtest import backtest
from .cli import report
from .data import digest, load_dataset, truth, write_json
from .experiments import SPECS, CALENDAR, candidate_records, common_horizon_counts, fit_metadata, fit_models, paired_summary
from .retraining import calendar_audit
from .spikes import DEFAULT_CONFIG
from .timebase import DAY, SGT, instant
from .train import training_examples


SPECS_CORRECTION = {m: deepcopy(SPECS[m]) for m in ('CURRENT', 'HGB_MAE', 'MEDIAN')}
small = {**SPECS['HGB_MAE']['parameters'], 'max_leaf_nodes': 7}
SPECS_CORRECTION.update({
    'HGB_SMALL': {'family': 'HGB absolute error', 'features': CALENDAR, 'parameters': small},
    'HGB_WEEKLY': {'family': 'HGB absolute error', 'features': CALENDAR+['last_week_target'], 'parameters': small},
    **{f'{family}_RESIDUAL_{baseline}': {
        'family': 'ridge' if family == 'RIDGE' else 'HGB absolute error',
        'features': CALENDAR, 'baseline': baseline,
        'parameters': {'alpha': 10.0} if family == 'RIDGE' else small}
       for family in ('HGB', 'RIDGE') for baseline in ('B1', 'B2')}
})


def fit(rows, start, cutoff):
    models, missing, samples = {}, {}, {}
    for baseline in (None, 'B1', 'B2'):
        specs = {m: s for m, s in SPECS_CORRECTION.items() if s.get('baseline') == baseline}
        xs, ys, excluded = training_examples(rows, start, cutoff, policy='PROVISIONAL', baseline=baseline)
        fitted, absent = fit_models(xs, ys, specs=specs)
        models.update(fitted); missing.update(absent)
        for m in specs:
            samples[m] = {'perHorizon': [len(y) for y in ys], 'excludedOrigins': excluded}
        if baseline is None:
            metadata = fit_metadata(rows, start, cutoff, ys, excluded, absent)
    metadata.update(featureMissingFraction=missing, trainingSamplesByMethod=samples)
    return models, metadata


def records(rows, start, end, models, metadata, specs=None):
    current = {'models': models['CURRENT'], 'manifest': {'version': 'research-CURRENT',
        'usableFrom': metadata['trainingCutoff'], 'spikeConfig': DEFAULT_CONFIG,
        'productionEligible': False, 'researchOnly': True}}
    base = backtest(rows, start, end, bundle=current, truth_cutoff=end)
    return candidate_records(rows, base, models, metadata, specs=SPECS_CORRECTION if specs is None else specs)


def write_horizons(path, groups):
    with path.open('w', newline='') as stream:
        writer = csv.writer(stream, lineterminator='\n')
        writer.writerow(['split', 'method', 'horizon', 'hours_to_target_start', 'common_pairs', 'mae_sgd_mwh'])
        for name, raw, result in groups:
            counts = common_horizon_counts(raw, result['comparisonMethods'])
            for m in result['comparisonMethods']:
                for h, mae in result['table'][m]['perHorizon'].items():
                    writer.writerow([name, m, h, int(h)/2, counts[int(h)], mae])


def study(dataset: Path, previous: Path, output: Path):
    if output.exists():
        raise ValueError('Correction studies are immutable; choose a new output directory')
    rows, manifest = load_dataset(dataset)
    if manifest['quality']['parsingFailures'] or not manifest['quality']['publicationVintages'] or manifest['provisionalOffsetMinutes'] != 0:
        raise ValueError('Require valid, zero-offset collector publication/availability vintages')
    if os.environ.get('FORECAST_PERIOD_MAPPING_EVIDENCE') != 'EMC_RT_20260929_24_PERIODS':
        raise ValueError('Source the documented period-mapping evidence')
    prior = json.loads((previous/'summary.json').read_text())
    if prior['status'] != 'COMPLETE' or digest(previous/'selection.json') != prior['selectionSha256']:
        raise ValueError('Require a complete unchanged retraining study')
    reference_hashes = {str(p): digest(p) for p in previous.rglob('*') if p.is_file()}
    audit = calendar_audit(rows, manifest)
    start, test_start, test_end = (instant(audit[k]) for k in ('trainingStart', 'testStart', 'testEnd'))
    selection_end = instant(prior['protocol']['testStart'])
    if selection_end >= test_start or selection_end-start < 5*DAY:
        raise ValueError('Need a newer complete holdout after the protected old test and enough earlier validation')
    development = [r for r in rows if all(instant(r[k]) < selection_end
        for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    # B2 residuals need at least 48 examples; the first two-day fold has too few.
    cutoffs = []; cutoff = start+3*DAY
    while cutoff+DAY <= selection_end:
        cutoffs.append(cutoff); cutoff += DAY
    protocol = {'createdAt': datetime.now(timezone.utc).isoformat(), 'datasetDigest': manifest['sha256'],
        'timezone': 'Asia/Singapore', 'trainingStart': start.isoformat(), 'selectionEnd': selection_end.isoformat(),
        'protectedOldTest': prior['protocol']['testStart'], 'testStart': test_start.isoformat(), 'testEnd': test_end.isoformat(),
        'horizonCount': 24, 'intervalMinutes': 30, 'candidates': SPECS_CORRECTION,
        'folds': [{'cutoff': c.isoformat(), 'end': (c+DAY).isoformat()} for c in cutoffs],
        'selectionRule': 'Lowest pooled earlier-validation MAE on identical eligible pairs; lexical tie break; no horizon selection',
        'residualRule': 'Training label = actual minus origin-known baseline; prediction = baseline plus learned correction',
        'baselineMissingRule': 'Unavailable, with explicit exclusions; never impute an absent baseline anchor',
        'refitRule': 'Fixed final training cutoff remains selectionEnd; old test never enters fitting or selection',
        'mappingEvidenceId': os.environ['FORECAST_PERIOD_MAPPING_EVIDENCE'],
        'dependencies': versions(), 'productionEligible': False, 'demandWeather': 'Excluded', 'promotion': 'Never attempted'}
    output.mkdir(parents=True)
    write_json(output/'protocol.json', protocol); write_json(output/'data-audit.json', audit)
    try:
        validation_rows, folds = [], []
        for number, cutoff in enumerate(cutoffs, 1):
            models, metadata = fit(development, start, cutoff)
            batch = records(development, cutoff, cutoff+DAY, models, metadata)
            validation_rows.extend(batch)
            folds.append({'number': number, **metadata, 'validationStart': cutoff.isoformat(), 'validationEnd': (cutoff+DAY).isoformat()})
            report(output/f'fold-{number}', batch, metadata)
            joblib.dump({'researchOnly': True, 'productionEligible': False, 'models': models,
                         'specs': SPECS_CORRECTION}, output/f'fold-{number}'/'candidates.joblib', compress=3)
        methods = sorted(m for m in {r['method'] for r in validation_rows} if all(any(
            r['method'] == m and r['exclusionReason'] is None and
            instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])
            for r in validation_rows) for f in folds))
        validation = paired_summary(validation_rows, methods)
        if not validation['commonPairs']:
            raise ValueError('No common validation pairs; no model selection')
        order = sorted(methods, key=lambda m: (validation['table'][m]['mae'], m))
        learned = next(m for m in order if m in SPECS_CORRECTION and m != 'MEDIAN')
        selection = {'savedAt': datetime.now(timezone.utc).isoformat(), 'selectedMethod': order[0],
            'selectedLearnedModel': learned, 'ranking': order, 'validation': validation,
            'selectionDataEndsBefore': selection_end.isoformat(), 'productionEligible': False}
        write_json(output/'selection.json', selection)
        selection_hash = digest(output/'selection.json')
        report(output/'validation', validation_rows, validation)
        for f in folds:
            lower, upper = instant(f['validationStart']), instant(f['validationEnd'])
            f['scores'] = paired_summary([r for r in validation_rows if lower <= instant(r['asOf']) < upper], methods)
        models, metadata = fit(development, start, selection_end)
        final = output/'final-fit'; final.mkdir()
        model_path = final/'candidates.joblib'
        joblib.dump({'researchOnly': True, 'productionEligible': False, 'models': models,
                     'specs': SPECS_CORRECTION}, model_path, compress=3)
        frozen_at = datetime.now(timezone.utc)
        future_start = frozen_at.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0)+DAY
        future = {'status': 'AWAITING_FUTURE_DATA', 'frozenAt': frozen_at.isoformat(),
            'start': future_start.isoformat(), 'end': (future_start+7*DAY).isoformat(),
            'modelSha256': digest(model_path), 'selectionSha256': selection_hash,
            'selectedMethod': order[0], 'selectedLearnedModel': learned, 'productionEligible': False,
            'rule': 'Seven complete elapsed SGT days after freeze; fixed model/settings; no fitting in evaluation'}
        write_json(output/'prospective.json', future)
        write_json(final/'manifest.json', {**metadata, 'datasetDigest': manifest['sha256'], 'selectionSha256': selection_hash,
            'modelSha256': future['modelSha256'], 'prospectiveSha256': digest(output/'prospective.json'),
            'dependencies': versions(), 'researchOnly': True, 'productionEligible': False})
        test_rows = records(rows, test_start, test_end, models, metadata)
        old = joblib.load(previous/'final-fit/candidates.joblib')
        frozen_specs = {'FROZEN_HGB_MAE': SPECS['HGB_MAE']}
        frozen_models = {'CURRENT': models['CURRENT'], 'FROZEN_HGB_MAE': old['models']['HGB_MAE']}
        saved = records(rows, test_start, test_end, frozen_models, metadata, specs=frozen_specs)
        test_rows.extend(r for r in saved if r['method'] == 'FROZEN_HGB_MAE')
        primary = paired_summary(test_rows, methods+['FROZEN_HGB_MAE'])
        pairwise = {m: paired_summary(test_rows, list(dict.fromkeys([m, 'HGB_MAE', 'FROZEN_HGB_MAE', 'B1', 'MEDIAN', 'PERSISTENCE'])))
            for m in SPECS_CORRECTION if m != 'MEDIAN'}
        seasonal = paired_summary(test_rows, list(dict.fromkeys([learned, 'HGB_MAE', 'B1', 'B2', 'B3'])))
        report(output/'held-out-test', test_rows, primary)
        write_json(output/'pairwise-test.json', pairwise); write_json(output/'seasonal-test.json', seasonal)
        assert digest(output/'selection.json') == selection_hash
        assert all(digest(Path(p)) == checksum for p, checksum in reference_hashes.items())
        summary = {'status': 'COMPLETE', 'protocol': protocol, 'quality': audit, 'selection': selection,
            'folds': folds, 'finalFit': metadata, 'heldOutTest': primary, 'pairwiseTest': pairwise,
            'seasonalTest': seasonal, 'prospective': future, 'selectionSha256': selection_hash,
            'referenceFileDigests': reference_hashes, 'referenceUnchanged': True,
            'limitations': ['Validation reused earlier development days; no result guarantees new-day accuracy.',
                'Primary holdout common pairs can be zero when yesterday/weekly baselines are absent.',
                'Pairwise tables use their own explicitly reported common pairs; never compare across different sets.',
                'Seven-day prospective confirmation is pending; no accuracy result exists for future observations.',
                'No demand/weather, spike-accuracy, production promotion or household/application integration claim.']}
        write_json(output/'summary.json', summary)
        write_horizons(output/'horizons.csv', [('validation', validation_rows, validation), ('primary_test', test_rows, primary)]+
            [('pairwise_'+m, test_rows, result) for m, result in pairwise.items()])
        return summary
    except BaseException as exc:
        write_json(output/'failure.json', {'status': 'INCOMPLETE', 'reason': str(exc), 'type': type(exc).__name__,
            'note': 'Evidence retained outside serving; retry in a new directory'})
        raise


def evaluate(dataset: Path, study: Path, output: Path):
    if output.exists():
        raise ValueError('Evaluation reports are immutable')
    plan = json.loads((study/'prospective.json').read_text())
    final = study/'final-fit'
    manifest = json.loads((final/'manifest.json').read_text())
    if (digest(final/'candidates.joblib') != plan['modelSha256'] or digest(study/'selection.json') != plan['selectionSha256']
            or digest(study/'prospective.json') != manifest['prospectiveSha256']):
        raise ValueError('Frozen model or selection changed')
    if manifest['dependencies'] != versions():
        raise ValueError('Use the frozen model dependency versions')
    rows, source = load_dataset(dataset)
    start, end = instant(plan['start']), instant(plan['end'])
    if (instant(plan['frozenAt']) >= start or instant(manifest['trainingCutoff']) > start or end-start != 7*DAY
            or start != start.astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)):
        raise ValueError('Artifact does not precede the confirmation window')
    acquired = max(instant(s['acquiredAt']) for s in source['sources'])
    if acquired < end:
        raise ValueError(f"Future confirmation pending: snapshot must be acquired on/after {end.isoformat()}")
    if source['quality']['parsingFailures'] or not source['quality']['publicationVintages'] or source['provisionalOffsetMinutes'] != 0:
        raise ValueError('Invalid operational evaluation dataset')
    saved = joblib.load(final/'candidates.joblib')
    batch = []; day = start; coverage = []
    while day < end:
        batch.extend(records(rows, day, day+DAY, saved['models'], manifest, specs=saved['specs']))
        labels = truth(rows, 'PROVISIONAL', day+DAY)
        coverage.append({'date': day.astimezone(SGT).date().isoformat(),
                         'truthPeriodsAvailableByDayEnd': sum(day <= p < day+DAY for p in labels)})
        day += DAY
    methods = list(dict.fromkeys([plan['selectedMethod'], plan['selectedLearnedModel'], 'HGB_MAE', 'MEDIAN',
        *(['HGB_SMALL'] if 'HGB_SMALL' in saved['models'] else []), 'B1', 'B2', 'PERSISTENCE']))
    result = paired_summary(batch, methods)
    result.update(status='COMPLETE' if result['commonOriginDays'] == 7 and all(d['truthPeriodsAvailableByDayEnd'] == 48 for d in coverage)
                  else 'INSUFFICIENT_CONFIRMATION_COVERAGE',
        plan=plan, dailyCoverage=coverage, datasetDigest=source['sha256'], productionEligible=False)
    report(output, batch, result)
    write_horizons(output/'horizons.csv', [('prospective', batch, result)])
    return result
