"""Fresh collector studies, kept outside serving and model promotion."""
from collections import Counter
from datetime import datetime, timezone
import csv
import json
import os
from pathlib import Path

import joblib

from .backtest import backtest
from .cli import report
from .data import as_of, digest, load_dataset, truth, write_json
from .experiments import SPECS, candidate_records, common_horizon_counts, fit_metadata, fit_models, paired_summary
from .spikes import DEFAULT_CONFIG
from .timebase import DAY, SGT, STEP, instant
from .train import training_examples


def calendar_audit(rows, manifest):
    acquired = max(instant(s['acquiredAt']) for s in manifest['sources'])
    today = acquired.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0)
    periods = sorted({instant(r['periodStart']) for r in rows})
    days = sorted({p.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0) for p in periods})
    counts = Counter(p.astimezone(SGT).date().isoformat() for p in periods)
    complete = []
    daily = []
    for day in days:
        observed = counts[day.date().isoformat()]
        labels = truth(rows, 'PROVISIONAL', day + DAY)
        known = sum(day <= p < day + DAY for p in labels)
        qualifies = day < today and observed == known == 48
        if qualifies:
            complete.append(day)
        daily.append({'date': day.date().isoformat(), 'periods': observed,
                      'truthPeriodsAvailableByDayEnd': known, 'completeElapsedDay': qualifies})
    if len(complete) < 4:
        raise ValueError('Need at least four complete elapsed calendar days')
    gaps = [{'after': a.astimezone(SGT).isoformat(), 'before': b.astimezone(SGT).isoformat(),
             'missingHalfHours': int((b-a)/STEP)-1} for a, b in zip(periods, periods[1:]) if b-a > STEP]
    lags = [(instant(r['availableAt'])-instant(r['sourceUpdatedAt'])).total_seconds() for r in rows]
    audit = {**manifest['quality'], 'snapshotAcquiredAt': acquired.isoformat(), 'days': daily, 'gaps': gaps,
             'knownPeriodsAtSnapshot': len(as_of(rows, acquired)),
             'availabilityBeforePublication': sum(v < 0 for v in lags),
             'availabilityLagSeconds': {'minimum': min(lags), 'maximum': max(lags)},
             'trainingStart': complete[0].isoformat(), 'testStart': complete[-1].isoformat(),
             'testEnd': (complete[-1]+DAY).isoformat(),
             'laterIncompleteElapsedDays': [d for d in daily if complete[-1].date().isoformat() < d['date'] < today.date().isoformat()]}
    return audit


def fit(rows, start, cutoff):
    xs, ys, excluded = training_examples(rows, start, cutoff, policy='PROVISIONAL')
    models, missing = fit_models(xs, ys)
    metadata = fit_metadata(rows, start, cutoff, ys, excluded, missing)
    # This is a research template for the shared evaluator, never a serving bundle.
    # Its only fit boundary is cutoff; model selection is frozen separately.
    current = {'models': models['CURRENT'], 'manifest': {
        'version': 'research-CURRENT', 'usableFrom': cutoff.isoformat(),
        'spikeConfig': DEFAULT_CONFIG.copy(), 'productionEligible': False, 'researchOnly': True}}
    return current, models, metadata


def retrain(dataset: Path, previous: Path, output: Path):
    if output.exists():
        raise ValueError('Research runs are immutable; choose a new output directory')
    rows, manifest = load_dataset(dataset)
    q = manifest['quality']
    if not rows or q['parsingFailures'] or not q['publicationVintages']:
        raise ValueError('Require valid collector publication and availability vintages')
    if manifest['provisionalOffsetMinutes'] != 0:
        raise ValueError('Documented live mapping evidence covers only zero offset')
    evidence = os.environ.get('FORECAST_PERIOD_MAPPING_EVIDENCE')
    if evidence != 'EMC_RT_20260929_24_PERIODS':
        raise ValueError('Source the documented period-mapping evidence before this study')
    prior = json.loads((previous/'summary.json').read_text())
    if prior['status'] != 'COMPLETE' or digest(previous/'selection.json') != prior['selectionSha256']:
        raise ValueError('Previous study must be complete with unchanged selection')
    reference_hashes = {str(p): digest(p) for p in previous.rglob('*') if p.is_file()}
    old_path = previous/f"fold-{len(prior['folds'])}"/'candidates.joblib'
    old = joblib.load(old_path)  # Trusted local study only; never accept uploaded joblib.
    old_cutoff = instant(prior['folds'][-1]['trainingCutoff'])
    audit = calendar_audit(rows, manifest)
    start, test_start, test_end = (instant(audit[k]) for k in ('trainingStart', 'testStart', 'testEnd'))
    if instant(prior['protocol']['existingTest']['end']) > test_start or old_cutoff > test_start:
        raise ValueError('New holdout must follow the previous study, without overlap')
    development = [r for r in rows if all(instant(r[k]) < test_start
                    for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    cutoffs = []
    cutoff = start + 2*DAY
    while cutoff + DAY <= test_start:
        cutoffs.append(cutoff)
        cutoff += DAY
    if len(cutoffs) < 2:
        raise ValueError('Need at least two earlier daily validation folds')
    protocol = {'createdAt': datetime.now(timezone.utc).isoformat(), 'datasetDigest': manifest['sha256'],
        'timezone': 'Asia/Singapore', 'label': 'Experimental — limited training data.',
        'trainingStart': start.isoformat(), 'testStart': test_start.isoformat(), 'testEnd': test_end.isoformat(),
        'horizonCount': 24, 'intervalMinutes': 30, 'mappingEvidenceId': evidence,
        'mappingScope': 'Recorded 24-period evidence; original unverified manifests remain unchanged',
        'candidates': SPECS, 'folds': [{'cutoff': c.isoformat(), 'end': (c+DAY).isoformat()} for c in cutoffs],
        'previousStudy': str(previous), 'previousTrainingCutoff': old_cutoff.isoformat(),
        'selectionRule': 'Lowest pooled MAE on identical earlier validation pairs; lexical tie break; no per-horizon tuning',
        'comparisonRule': 'Selection requires eligible pairs in every validation fold; B3 tested separately where history exists',
        'finalFitRule': 'Refit fixed candidates on all eligible examples before testStart after freezing selection',
        'truthPolicy': 'PROVISIONAL, latest revision published and available by each split end',
        'purging': 'Every training/validation target interval ends by its split boundary',
        'testPurpose': 'NEW_HELD_OUT_CALENDAR_DAY; reconstructed historical forecasts, not prospective serving',
        'demandWeather': 'Excluded; price and calendar features only',
        'productionEligible': False, 'promotion': 'Never attempted'}
    output.mkdir(parents=True)
    write_json(output/'protocol.json', protocol)
    write_json(output/'data-audit.json', audit)
    try:
        folds, records = [], []
        for number, cutoff in enumerate(cutoffs, 1):
            current, models, metadata = fit(development, start, cutoff)
            base = backtest(development, cutoff, cutoff+DAY, bundle=current, truth_cutoff=cutoff+DAY)
            batch = candidate_records(development, base, models, metadata)
            folds.append({'number': number, **metadata, 'validationStart': cutoff.isoformat(),
                          'validationEnd': (cutoff+DAY).isoformat()})
            records.extend(batch)
            report(output/f'fold-{number}', batch, metadata)
            joblib.dump({'researchOnly': True, 'productionEligible': False, 'specs': SPECS,
                         'models': models}, output/f'fold-{number}'/'candidates.joblib', compress=3)
        methods = sorted(m for m in {r['method'] for r in records} if all(any(
            r['method'] == m and r['exclusionReason'] is None and
            instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])
            for r in records) for f in folds))
        validation = paired_summary(records, methods)
        if not validation['commonPairs']:
            raise ValueError('No identical eligible validation pairs')
        order = sorted(methods, key=lambda m: (validation['table'][m]['mae'], m))
        selection = {'savedAt': datetime.now(timezone.utc).isoformat(), 'selectedMethod': order[0],
            'selectedLearnedModel': next(m for m in order if m in SPECS and m != 'MEDIAN'),
            'ranking': order, 'comparisonMethods': methods, 'validation': validation,
            'selectionDataEndsBefore': test_start.isoformat(), 'productionEligible': False}
        write_json(output/'selection.json', selection)  # Frozen before any test outcomes reach fitting/evaluation.
        selection_hash = digest(output/'selection.json')
        report(output/'validation', records, validation)
        for f in folds:
            lower, upper = instant(f['validationStart']), instant(f['validationEnd'])
            f['scores'] = paired_summary([r for r in records if lower <= instant(r['asOf']) < upper], methods)
        current, models, metadata = fit(development, start, test_start)
        final_dir = output/'final-fit'; final_dir.mkdir()
        write_json(final_dir/'manifest.json', {**metadata, 'datasetDigest': manifest['sha256'],
            'selectionSha256': selection_hash, 'specs': SPECS, 'researchOnly': True, 'productionEligible': False})
        joblib.dump({'researchOnly': True, 'productionEligible': False, 'specs': SPECS,
                     'models': models}, final_dir/'candidates.joblib', compress=3)
        base = backtest(rows, test_start, test_end, bundle=current, truth_cutoff=test_end)
        test_records = candidate_records(rows, base, models, metadata)
        frozen_specs = {'FROZEN_'+m: SPECS[m] for m in ('CURRENT', 'HGB_MAE')}
        frozen_models = {'FROZEN_'+m: old['models'][m] for m in ('CURRENT', 'HGB_MAE')}
        frozen = candidate_records(rows, base, frozen_models, metadata, specs=frozen_specs)
        test_records.extend(r for r in frozen if r['method'] in frozen_specs)
        test_methods = methods + list(frozen_specs)
        test = paired_summary(test_records, test_methods)
        if not test['commonPairs']:
            raise ValueError('No held-out common pairs; retain reports without an accuracy claim')
        selected = selection['selectedLearnedModel']
        b3 = paired_summary(test_records, list(dict.fromkeys([selected, 'HGB_MAE', 'FROZEN_HGB_MAE', 'B1', 'B2', 'B3'])))
        report(output/'held-out-test', test_records, test)
        write_json(output/'b3-common-test.json', b3)
        assert digest(output/'selection.json') == selection_hash
        assert all(digest(Path(p)) == checksum for p, checksum in reference_hashes.items())
        assert load_dataset(dataset)[1]['sha256'] == manifest['sha256']
        summary = {'status': 'COMPLETE', 'protocol': protocol, 'quality': audit, 'selection': selection,
            'folds': folds, 'finalFit': metadata, 'heldOutTest': test, 'b3CommonTest': b3,
            'selectionSha256': selection_hash, 'referenceFileDigests': reference_hashes, 'referenceUnchanged': True,
            'limitations': ['One held-out complete day; overlapping forecasts are not independent observations.',
                'Training dates can contain gaps; missing features are imputed from training only, missing target windows excluded.',
                'Later incomplete elapsed dates are excluded and cannot support a full-day claim.',
                'Provisional prices, not final settlement truth. No weather or demand predictors.',
                'Refitted today using historical availability; not proof the artifact was served on the test day.',
                'Earlier test dates may now be development data; their original reports remain unchanged.',
                'No production promotion. Existing history and approval requirements still apply.']}
        write_json(output/'summary.json', summary)
        with (output/'horizons.csv').open('w', newline='') as stream:
            writer = csv.writer(stream, lineterminator='\n')
            writer.writerow(['split', 'method', 'horizon', 'hours_to_target_start', 'training_samples_per_fit', 'common_pairs', 'mae_sgd_mwh'])
            for label, result, raw in [('pooled_validation', validation, records), ('held_out_test', test, test_records),
                                       ('b3_common_test', b3, test_records)]:
                horizon_counts = common_horizon_counts(raw, result['comparisonMethods'])
                for m in result['comparisonMethods']:
                    counts = ('/'.join(str(f['trainingSamplesPerHorizon'][0]) for f in folds) if label == 'pooled_validation'
                              else str(prior['folds'][-1]['trainingSamplesPerHorizon'][0]) if m.startswith('FROZEN_')
                              else str(metadata['trainingSamplesPerHorizon'][0])) if m in SPECS or m.startswith('FROZEN_') else 'not fitted'
                    for h, mae in result['table'][m]['perHorizon'].items():
                        writer.writerow([label, m, h, int(h)/2, counts, horizon_counts[int(h)], mae])
        return summary
    except BaseException as exc:
        write_json(output/'failure.json', {'status': 'INCOMPLETE', 'reason': str(exc), 'type': type(exc).__name__,
            'note': 'Partial evidence retained outside serving. Choose a new output directory for another run.'})
        raise
