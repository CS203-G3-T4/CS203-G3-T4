"""Offline recursive AR(6) benchmark, trained locally from genuine price vintages."""
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path

import joblib
import numpy as np
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

from .artifacts import versions
from .backtest import History, fresh, origins
from .cli import report
from .corrections import records as hgb_records, write_horizons
from .data import digest, load_dataset, truth, write_json
from .experiments import paired_summary
from .retraining import calendar_audit
from .stability import SPECS_STABILITY
from .timebase import DAY, SGT, STEP, floor, instant, targets

SPECS_AR = {f'AR6_RIDGE_{alpha}': {'lags': 6, 'alpha': float(alpha), 'recursive': True}
            for alpha in (1, 10, 100)}
BENCHMARKS = ['CURRENT', 'HGB_MAE', 'HGB_SMALL', 'HGB_REGULARISED', 'MEDIAN', 'PERSISTENCE', 'B1', 'B2']


def training_examples(rows, start, cutoff):
    """Next-interval labels, with six exact earlier periods known at its start."""
    history, labels = History(rows), truth(rows, 'PROVISIONAL', cutoff)
    xs, ys, stamps, excluded = [], [], [], Counter()
    for period in origins(start, cutoff):
        if period+STEP > cutoff or period not in labels:
            excluded['MISSING_TRAINING_TRUTH'] += 1
            continue
        past = history.at(period)
        lags = [period-STEP*n for n in range(1, 7)]
        if not fresh(past, period) or not all(t in past for t in lags):
            excluded['INSUFFICIENT_OR_STALE_TRAINING_HISTORY'] += 1
            continue
        xs.append([past[t]['usep'] for t in lags]); ys.append(labels[period]); stamps.append(period.isoformat())
    return np.asarray(xs, dtype=float).reshape(-1, 6), np.asarray(ys, dtype=float), stamps, dict(excluded)


def fit(rows, start, cutoff):
    xs, ys, stamps, excluded = training_examples(rows, start, cutoff)
    if len(ys) < 48:
        raise ValueError('AR(6) requires at least 48 eligible training target periods')
    with threadpool_limits(limits=1):
        models = {name: make_pipeline(StandardScaler(), Ridge(alpha=spec['alpha'])).fit(xs, ys)
                  for name, spec in SPECS_AR.items()}
    metadata = {'trainingStart': start.isoformat(), 'trainingCutoff': cutoff.isoformat(),
        'trainingTargetPeriods': len(ys), 'trainingTargetTimestamps': stamps, 'excludedTrainingTargets': excluded,
        'featureOrder': ['previous_'+str(n) for n in range(1, 7)],
        'labelRule': 'Next interval price; interval end and genuine truth publication/availability <= training cutoff',
        'preprocessing': 'StandardScaler fitted only on complete training lag vectors; no imputation',
        'productionEligible': False, 'researchOnly': True}
    return models, metadata


def forecast(model, past, origin):
    """Advance the sequence to exact target starts; never read future actuals."""
    if not past or not fresh(past, origin):
        return None
    boundary = floor(origin)
    latest = max(past)
    if latest not in (boundary, boundary-STEP):
        return None
    lag_periods = [latest-STEP*n for n in range(6)]
    if not all(p in past for p in lag_periods):
        return None
    state = [past[p]['usep'] for p in lag_periods]
    predicted = {}
    period = latest
    with threadpool_limits(limits=1):
        while period < targets(origin)[-1]:
            period += STEP
            value = float(model.predict([state])[0])
            if not np.isfinite(value):
                raise ValueError('Non-finite recursive AR forecast; retain failed experiment')
            predicted[period] = value
            state = [value]+state[:-1]
    return [predicted[t] for t in targets(origin)]


def records(rows, reference_rows, models, metadata, *, benchmarks=None):
    if any(instant(r['asOf']) < instant(metadata['trainingCutoff']) for r in reference_rows):
        raise ValueError('AR model fitting crosses the forecast origin')
    benchmarks = BENCHMARKS if benchmarks is None else benchmarks
    base = [dict(r) for r in reference_rows if r['method'] in benchmarks or r['method'] == 'B3']
    templates = [r for r in reference_rows if r['method'] == 'HGB_MAE']
    history = History(rows)
    for stamp in sorted({r['asOf'] for r in templates}):
        template = [r for r in templates if r['asOf'] == stamp]
        origin = instant(stamp); past = history.at(origin)
        for name, model in models.items():
            values = None
            if any(r['exclusionReason'] is None for r in template):
                if metadata.get('candidateSpecs'):
                    from .ts_variants import forecast as variant_forecast
                    values = variant_forecast(model,past,origin,metadata['candidateSpecs'][name])
                else:
                    values = forecast(model,past,origin)
            for r in template:
                reason = r['exclusionReason'] or ('INSUFFICIENT_HISTORY' if values is None else None)
                value = values[r['horizon']-1] if reason is None else None
                base.append({**r, 'method': name, 'modelVersion': 'research-'+name,
                    'predictedUsep': value, 'exclusionReason': reason,
                    'spikeFlag': value > r['threshold'] if value is not None and r['threshold'] is not None else None})
    return base


def study(dataset: Path, reference: Path, output: Path, *, improvements=False):
    if output.exists():
        raise ValueError('Time-series reports are immutable; choose a new output directory')
    rows, manifest = load_dataset(dataset)
    prior = json.loads((reference/'summary.json').read_text())
    final = json.loads((reference/'final-fit/manifest.json').read_text())
    plan = final if improvements else json.loads((reference/'prospective.json').read_text())
    if (prior['status'] != 'COMPLETE' or manifest['sha256'] != prior['protocol']['datasetDigest']
            or digest(reference/'selection.json') != plan['selectionSha256']
            or digest(reference/'final-fit/candidates.joblib') != plan['modelSha256']
            or (not improvements and digest(reference/'prospective.json') != final['prospectiveSha256'])
            or final['dependencies'] != versions()):
        raise ValueError('Require the unchanged HGB study, matching dataset and dependency versions')
    trainer, candidate_specs, benchmarks = fit, SPECS_AR, BENCHMARKS
    if improvements:
        if prior['protocol']['candidates'] != SPECS_AR:
            raise ValueError('Require the preserved original AR(6) study for these ablations')
        if not all(digest(Path(p)) == checksum for p,checksum in prior['referenceFileDigests'].items()):
            raise ValueError('Original HGB references changed')
        from .ts_variants import fit as trainer, SPECS as candidate_specs
        benchmarks = BENCHMARKS+[prior['selection']['selectedMethod']]
    if (manifest['quality']['parsingFailures'] or not manifest['quality']['publicationVintages']
            or manifest['provisionalOffsetMinutes'] != 0):
        raise ValueError('Require valid zero-offset publication/availability vintages')
    if os.environ.get('FORECAST_PERIOD_MAPPING_EVIDENCE') != 'EMC_RT_20260929_24_PERIODS':
        raise ValueError('Source the documented period-mapping evidence')
    hashes = {str(p): digest(p) for p in reference.rglob('*') if p.is_file()}
    start, boundary = (instant(prior['protocol'][k]) for k in ('trainingStart', 'selectionEnd'))
    development = [r for r in rows if all(instant(r[k]) < boundary
                   for k in ('periodStart', 'sourceUpdatedAt', 'availableAt'))]
    protocol = {'createdAt': datetime.now(timezone.utc).isoformat(), 'label': 'Experimental — limited training data.',
        'family': ('AR ablations: daily lags, Huber loss, price changes and direct horizons' if improvements else
                   'Locally trained ridge-regularised recursive AR(6); not a pretrained neural time-series model'),
        'datasetDigest': manifest['sha256'], 'trainingStart': start.isoformat(), 'selectionEnd': boundary.isoformat(),
        'folds': prior['protocol']['folds'], 'candidates': candidate_specs, 'benchmarks': benchmarks,
        'selectionRule': 'Lowest pooled earlier-validation AR MAE on identical pairs; lexical tie break; no test-day tuning',
        'horizonCount': 24, 'intervalMinutes': 30, 'forecastHours': 12,
        'alignmentRule': 'Predict any unpublished current interval internally, then return only the next 24 future interval starts',
        'gapRule': 'Require six contiguous exact origin-known lags; never fill gaps or shift rows across missing periods',
        'diagnosticPurpose': 'ALREADY_SEEN_DIAGNOSTIC; complete later days in the frozen known dataset, not independent confirmation',
        'reference': str(reference), 'mappingEvidenceId': os.environ['FORECAST_PERIOD_MAPPING_EVIDENCE'],
        'dependencies': versions(), 'demandWeather': 'Excluded', 'researchOnly': True,
        'productionEligible': False, 'promotion': 'Never attempted'}
    if improvements:
        protocol.update(featureRule='Six contiguous recent prices; five adjacent changes in delta models; exact target-minus-one-day seasonal reference',
            robustRule='Huber epsilon 1.35, alpha .01, max_iter 1000, tol 1e-6; retain all observations and convergence evidence',
            comparisonRule='Primary intersection across all candidates and benchmarks; separate per-candidate matched comparisons report lost coverage')
    output.mkdir(parents=True); write_json(output/'protocol.json', protocol)
    try:
        validation_rows, folds = [], []
        for number, split in enumerate(protocol['folds'], 1):
            cutoff, end = instant(split['cutoff']), instant(split['end'])
            if not start < cutoff < end <= boundary or end-cutoff != DAY:
                raise ValueError('Invalid chronological validation split')
            models, metadata = trainer(development, start, cutoff)
            source = [json.loads(line) for line in (reference/f'fold-{number}'/'predictions.jsonl').read_text().splitlines()]
            if not source or any(not cutoff <= instant(r['asOf']) < end for r in source):
                raise ValueError('HGB reference forecast dates differ from the fold')
            batch = records(development, source, models, metadata,benchmarks=benchmarks)
            validation_rows.extend(batch)
            folds.append({'number': number, **metadata, 'validationStart': cutoff.isoformat(), 'validationEnd': end.isoformat()})
            report(output/f'fold-{number}', batch, metadata)
            joblib.dump({'models': models, 'specs': candidate_specs, 'researchOnly': True, 'productionEligible': False},
                        output/f'fold-{number}'/'candidates.joblib', compress=3)
        methods = benchmarks+list(candidate_specs)
        if any(not any(
                r['method'] == m and r['exclusionReason'] is None and
                instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])
                for r in validation_rows) for m in methods for f in folds):
            raise ValueError('Insufficient common validation coverage')
        validation = paired_summary(validation_rows, methods)
        if not validation['commonPairs']:
            raise ValueError('No common validation pairs')
        order = sorted(candidate_specs, key=lambda m: (validation['table'][m]['mae'], m))
        selection = {'savedAt': datetime.now(timezone.utc).isoformat(), 'selectedMethod': order[0], 'ranking': order,
            'overallValidationWinner': min(methods, key=lambda m:(validation['table'][m]['mae'],m)),
            'validation': validation, 'selectionDataEndsBefore': boundary.isoformat(), 'productionEligible': False}
        write_json(output/'selection.json', selection)
        report(output/'validation', validation_rows, validation)
        for f in folds:
            batch = [r for r in validation_rows if instant(f['validationStart']) <= instant(r['asOf']) < instant(f['validationEnd'])]
            f['scores'] = paired_summary(batch, methods)
        models, metadata = trainer(development, start, boundary)
        final_fit = output/'final-fit'; final_fit.mkdir()
        saved = {'models':models,'specs':candidate_specs,'researchOnly':True,'productionEligible':False}
        if improvements:
            hgb_path = Path(prior['protocol']['reference'])
            saved.update(referenceArModels=joblib.load(reference/'final-fit/candidates.joblib')['models'],
                referenceArManifest=final,
                referenceHgbModels=joblib.load(hgb_path/'final-fit/candidates.joblib')['models'],
                referenceHgbManifest=json.loads((hgb_path/'final-fit/manifest.json').read_text()))
        joblib.dump(saved,
                    final_fit/'candidates.joblib', compress=3)
        model_manifest = {**metadata, 'dependencies': versions(),
            'selectionSha256': digest(output/'selection.json'), 'modelSha256': digest(final_fit/'candidates.joblib'),
            'datasetDigest': manifest['sha256'], 'specs': candidate_specs}
        future = None
        if improvements:
            frozen_at = datetime.now(timezone.utc)
            tomorrow = frozen_at.astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)+DAY
            hgb_plan = json.loads((hgb_path/'prospective.json').read_text())
            future_start = max(tomorrow,instant(hgb_plan['end']))
            future = {'status':'AWAITING_FUTURE_DATA','frozenAt':frozen_at.isoformat(),
                'start':future_start.isoformat(),'end':(future_start+7*DAY).isoformat(),
                'selectedMethod':order[0],'referenceArMethod':prior['selection']['selectedMethod'],
                'modelSha256':model_manifest['modelSha256'],'selectionSha256':model_manifest['selectionSha256'],
                'rule':'Separate later seven SGT days after both existing confirmation windows; no fitting or reselection',
                'productionEligible':False}
            write_json(output/'prospective.json',future)
            model_manifest['prospectiveSha256'] = digest(output/'prospective.json')
        write_json(final_fit/'manifest.json',model_manifest)
        # Selection and final fit are saved before any already-seen diagnostic truth is evaluated.
        old = joblib.load(reference/'final-fit/candidates.joblib')
        specs = {m: SPECS_STABILITY[m] for m in BENCHMARKS if m in SPECS_STABILITY}
        audit = calendar_audit(rows, manifest)
        diagnostics, groups = [], [('validation', validation_rows, validation)]
        for day in audit['days']:
            lower = instant(day['date']+'T00:00:00+08:00'); upper = lower+DAY
            if lower < boundary or not day['completeElapsedDay']:
                continue
            source = ([json.loads(line) for line in (reference/('diagnostic-'+day['date'])/'predictions.jsonl').read_text().splitlines()]
                      if improvements else hgb_records(rows, lower, upper, old['models'], final, specs=specs))
            batch = records(rows,source,models,metadata,benchmarks=benchmarks)
            paired = paired_summary(batch, methods)
            report(output/('diagnostic-'+day['date']), batch, paired)
            diagnostics.append({'date': day['date'], 'purpose': 'ALREADY_SEEN_DIAGNOSTIC', 'comparison': paired})
            groups.append(('diagnostic_'+day['date'],batch,paired))
            # B2 can be unavailable after an outage; retain a clearly separate available-method table.
            available = [m for m in methods if any(r['method'] == m and r['exclusionReason'] is None for r in batch)]
            subset = paired_summary(batch, available)
            diagnostics[-1]['availableMethodComparison'] = subset
            groups.append(('available_'+day['date'],batch,subset))
        if not all(digest(Path(p)) == checksum for p, checksum in hashes.items()):
            raise ValueError('HGB reference changed during the experiment')
        summary = {'status': 'COMPLETE', 'protocol': protocol, 'selection': selection, 'folds': folds,
            'finalFit': metadata, 'diagnostics': diagnostics, 'dataAudit': audit,
            'referenceFileDigests': hashes, 'referenceUnchanged': True,
            'selectionSha256': digest(output/'selection.json'),
            'limitations': ['AR trains one-step labels; HGB trains 24 direct horizon models on complete windows, so sample counts differ.',
                'Recursive AR feeds back predictions, so errors may accumulate with horizon.',
                'Development dates and later diagnostics were already seen; no independent test claim.',
                'Not Chronos/TimesFM, not pretrained, and no neural fine-tuning performed.',
                'Configured spike history remains insufficient; no spike-accuracy or production claim.',
                'Existing future confirmation plans remain unchanged; no new automatic job or serving changes.']}
        if improvements:
            matched = {m:paired_summary(validation_rows,benchmarks+[m]) for m in candidate_specs}
            summary.update(prospective=future,matchedValidation=matched)
            summary['limitations'][0] = 'Recursive AR fits one-step labels; direct AR and HGB fit 24 models on complete target windows; seasonal anchors can further reduce samples.'
            summary['limitations'].append('Seasonal variants require additional daily anchors and can reduce the primary intersection; compare within explicitly reported sets.')
            write_json(output/'matched-validation.json',matched)
            groups.extend(('matched_'+m,validation_rows,result) for m,result in matched.items())
        write_json(output/'summary.json', summary); write_horizons(output/'horizons.csv', groups)
        return summary
    except BaseException as exc:
        write_json(output/'failure.json', {'status': 'INCOMPLETE', 'reason': str(exc), 'type': type(exc).__name__,
            'note': 'Evidence retained outside serving; retry in a new directory'})
        raise


def confirm(dataset: Path, study: Path, output: Path):
    """Score the frozen ablation and frozen references; never fit or promote."""
    if output.exists():
        raise ValueError('Confirmation reports are immutable')
    plan = json.loads((study/'prospective.json').read_text())
    folder = study/'final-fit'; manifest = json.loads((folder/'manifest.json').read_text())
    if (digest(folder/'candidates.joblib') != plan['modelSha256']
            or digest(study/'selection.json') != plan['selectionSha256']
            or digest(study/'prospective.json') != manifest['prospectiveSha256']):
        raise ValueError('Frozen model, selection or plan changed')
    if manifest['dependencies'] != versions():
        raise ValueError('Use frozen dependency versions')
    start,end = instant(plan['start']),instant(plan['end'])
    if (instant(plan['frozenAt']) >= start or instant(manifest['trainingCutoff']) > start or end-start != 7*DAY
            or start != start.astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)):
        raise ValueError('Artifact does not precede a seven-day Singapore confirmation window')
    rows,source = load_dataset(dataset)
    if max(instant(s['acquiredAt']) for s in source['sources']) < end:
        raise ValueError(f'Future confirmation pending: snapshot must be acquired on/after {end.isoformat()}')
    if (source['quality']['parsingFailures'] or not source['quality']['publicationVintages']
            or source['provisionalOffsetMinutes'] != 0):
        raise ValueError('Require valid operational vintages')
    saved = joblib.load(folder/'candidates.joblib')
    old_ar = plan['referenceArMethod']; selected = plan['selectedMethod']
    specs = {m:SPECS_STABILITY[m] for m in BENCHMARKS if m in SPECS_STABILITY}
    batch,coverage = [],[]; day = start
    while day < end:
        base = hgb_records(rows,day,day+DAY,saved['referenceHgbModels'],saved['referenceHgbManifest'],specs=specs)
        base = records(rows,base,{old_ar:saved['referenceArModels'][old_ar]},saved['referenceArManifest'])
        batch.extend(records(rows,base,saved['models'],manifest,benchmarks=BENCHMARKS+[old_ar]))
        labels = truth(rows,'PROVISIONAL',day+DAY)
        coverage.append({'date':day.astimezone(SGT).date().isoformat(),
                         'truthPeriodsAvailableByDayEnd':sum(day <= p < day+DAY for p in labels)})
        day += DAY
    methods = [selected,old_ar,'HGB_REGULARISED','HGB_MAE','MEDIAN','B1','B2','PERSISTENCE']
    result = paired_summary(batch,methods)
    result.update(status='COMPLETE' if result['commonOriginDays']==7 and all(d['truthPeriodsAvailableByDayEnd']==48 for d in coverage)
                  else 'INSUFFICIENT_CONFIRMATION_COVERAGE', plan=plan,dailyCoverage=coverage,
                  datasetDigest=source['sha256'],productionEligible=False)
    report(output,batch,result);write_horizons(output/'horizons.csv',[('prospective',batch,result)])
    return result
