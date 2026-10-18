"""Controlled research: retrospective final-price training, real recent vintages.

Earlier validation selects the candidate. Future confirmation never fits models
or promotes artifacts. No historical publication timestamps are fabricated.
"""
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
from statistics import mean
from types import SimpleNamespace

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

from .artifacts import load_bundle, versions
from .backtest import History, backtest, fresh, scores
from .baselines import baselines
from .cli import report
from .data import DatasetRows, digest, load_dataset, truth, write_json
from .features import feature_names, features, holiday_digest
from .operations import TrainingAttempt
from .timebase import DAY, SGT, STEP, floor, instant, targets
from .train import training_examples


SPECS = {
    f'{window}_{family}': {'window': window, 'family': family,
        'parameters': ({'alpha': 10.0} if family == 'RESIDUAL_B2' else
            {'max_iter': 40, 'max_leaf_nodes': 15, 'l2_regularization': 1, 'random_state': 42}
            if family == 'DEFAULT' else
            {'loss': 'absolute_error', 'max_iter': 40, 'max_leaf_nodes': 7,
             'l2_regularization': 10, 'random_state': 42})}
    for window in ('LONG', 'RECENT') for family in ('DEFAULT', 'ROBUST', 'RESIDUAL_B2')
}


def protected(row, protocol):
    return any(instant(p['start']) <= instant(row['periodStart']) < instant(p['end'])
               for p in protocol['protectedIntervals'])


def collector_data(directory):
    rows, manifest = load_dataset(directory)
    q = manifest['quality']
    if (not rows or q['parsingFailures'] or q['duplicateRows'] or not q['publicationVintages']
            or manifest['provisionalOffsetMinutes'] != 0 or any(r['priceStatus'] != 'PROVISIONAL' for r in rows)):
        raise ValueError('Require valid zero-offset provisional collector vintages')
    acquired = max(instant(s['acquiredAt']) for s in manifest['sources'])
    if any(not instant(r['sourceUpdatedAt']) <= instant(r['availableAt']) <= acquired for r in rows):
        raise ValueError('Invalid collector availability/publication provenance')
    return rows, manifest, acquired


def examples(rows, start, end, policy, retrospective=False):
    xs, ys, excluded = training_examples(rows, start, end, policy=policy,
        retrospective=retrospective, feature_schema=2)
    return {'x': [np.asarray(x, dtype=float).reshape(-1, len(feature_names(2))) for x in xs],
            'y': [np.asarray(y, dtype=float) for y in ys], 'excludedOrigins': excluded}


def fit(parts, spec):
    xs = [np.concatenate([p['x'][h] for p in parts]) for h in range(24)]
    ys = [np.concatenate([p['y'][h] for p in parts]) for h in range(24)]
    mask = np.ones(len(ys[0]), dtype=bool)
    anchor_index = feature_names(2).index('yesterday_target')
    if spec['family'] == 'RESIDUAL_B2':
        # A baseline requires all 24 targets; unavailable anchors are never imputed.
        mask = np.logical_and.reduce([np.isfinite(x[:, anchor_index]) for x in xs])
        ys = [y - x[:, anchor_index] for x, y in zip(xs, ys)]
    if int(mask.sum()) < 48:
        raise ValueError('At least 48 eligible training origins required for every candidate')
    models = []
    with threadpool_limits(limits=1):
        for x, y in zip(xs, ys):
            imputer = SimpleImputer(strategy='median', add_indicator=True, keep_empty_features=True)
            model = (make_pipeline(imputer, StandardScaler(), Ridge(**spec['parameters']))
                if spec['family'] == 'RESIDUAL_B2' else
                make_pipeline(imputer, HistGradientBoostingRegressor(**spec['parameters'])))
            models.append(model.fit(x[mask], y[mask]))
    return models, {'trainingOriginsPerHorizon': int(mask.sum()),
        'excludedMissingBaselineOrigins': int((~mask).sum()),
        'finalPriceOriginsBeforeBaselineFilter': len(parts[0]['y'][0]) if spec['window'] == 'LONG' else 0,
        'provisionalOriginsBeforeBaselineFilter': len(parts[-1]['y'][0])}


def forecast(models, spec, past, origin):
    anchor = baselines(past, origin)['B2'] if spec['family'] == 'RESIDUAL_B2' else [0] * 24
    if anchor is None or not fresh(past, origin):
        return None
    raw = [features(past, origin, t, schema_version=2) for t in targets(origin)]
    with threadpool_limits(limits=1):
        values = [float(m.predict([raw[h]])[0]) + anchor[h] for h, m in enumerate(models)]
    if not np.all(np.isfinite(values)):
        raise ValueError('Non-finite research forecast')
    return values


def evaluate(rows, start, end, candidates, reference=None, *, policy='PROVISIONAL', retrospective=False):
    """Batch estimator calls; origins/features and exclusion rules stay chronological."""
    if reference is not None and instant(reference['manifest']['usableFrom']) > start:
        raise ValueError('Reference model uses the simulated future')
    output = backtest(rows, start, end, policy=policy, retrospective=retrospective, truth_cutoff=end)
    templates = [r for r in output if r['method'] == 'B1']
    history = History(rows, retrospective)
    stamps = sorted({r['asOf'] for r in templates})
    known = {stamp: history.at(instant(stamp)) for stamp in stamps}
    eligible = [stamp for stamp in stamps if fresh(known[stamp], instant(stamp))
                and targets(instant(stamp))[-1] + STEP <= end]
    raw = np.asarray([[features(known[s], instant(s), t, schema_version=2)
                       for t in targets(instant(s))] for s in eligible])
    entries = dict(candidates)
    if reference is not None:
        entries['PREVIOUS_AI'] = (reference['models'], {'family': 'DEFAULT'})
    for name, (models, spec) in entries.items():
        available = eligible
        anchors = {s: baselines(known[s], instant(s))['B2'] for s in eligible} if spec['family'] == 'RESIDUAL_B2' else {}
        if anchors:
            available = [s for s in eligible if anchors[s] is not None]
        lookup = {s: i for i, s in enumerate(eligible)}
        values = {}
        if available:
            indices = [lookup[s] for s in available]
            with threadpool_limits(limits=1):
                predictions = np.column_stack([m.predict(raw[indices, h, :]) for h, m in enumerate(models)])
            if not np.all(np.isfinite(predictions)):
                raise ValueError('Non-finite validation predictions')
            values = {s: predictions[i] + np.asarray(anchors[s] if anchors else [0] * 24)
                      for i, s in enumerate(available)}
        for r in templates:
            reason = r['exclusionReason']
            if reason == 'INSUFFICIENT_HISTORY':
                reason = 'MISSING_TRUTH' if r['actual'] is None else None
            if reason is None and r['asOf'] not in values:
                reason = 'INSUFFICIENT_BASELINE_HISTORY'
            value = float(values[r['asOf']][r['horizon'] - 1]) if reason is None else None
            output.append({**r, 'method': name, 'modelVersion': reference['manifest']['version']
                if name == 'PREVIOUS_AI' else 'research-' + name, 'exclusionReason': reason,
                'predictedUsep': value, 'spikeFlag': value > r['threshold']
                if value is not None and r['threshold'] is not None else None})
    return output


def study(collector, historical, reference_path, protocol_path, output):
    if output.exists():
        raise ValueError('Improvement studies are immutable; choose a new output directory')
    recent, recent_manifest, acquired = collector_data(collector)
    final, final_manifest = load_dataset(historical)
    if (final_manifest['quality']['parsingFailures'] or not final
            or any(r['priceStatus'] != 'FINAL' for r in final)):
        raise ValueError('Require valid separately identified final-price history')
    protocol = json.loads(protocol_path.read_text())
    folds = [(instant(f['start']), instant(f['end'])) for f in protocol['folds']]
    history_start, history_end, recent_start = (instant(protocol[k])
        for k in ('historyStart', 'historyEnd', 'collectorStart'))
    if (len(folds) < 2 or history_start >= history_end or history_end > recent_start
            or any(a != floor(a) or b != floor(b) or a >= b or b > acquired for a, b in folds)
            or any(b > c for (_, b), (c, _) in zip(folds, folds[1:]))
            or any(a < instant(p['end']) and b > instant(p['start'])
                   for a, b in folds for p in protocol['protectedIntervals'])):
        raise ValueError('Invalid chronological folds or overlap with protected intervals')
    if protocol['confirmationDays'] < 7:
        raise ValueError('Future confirmation requires at least seven days')
    final = DatasetRows([r for r in final if not protected(r, protocol)
        and instant(r['periodStart']) < history_end])
    recent = DatasetRows([r for r in recent if not protected(r, protocol)], recent.demand_forecasts)
    reference = load_bundle(reference_path)
    if reference['manifest'].get('featureSchemaVersion') != 2:
        raise ValueError('Reference must use the active feature schema')
    ref_hashes = {str(p.resolve()): digest(p) for p in reference_path.iterdir() if p.is_file()}
    output.mkdir(parents=True)
    protocol.update(candidates=deepcopy(SPECS), featureSchemaVersion=2, createdAt=datetime.now(timezone.utc).isoformat(),
        collectorDatasetDigest=recent_manifest['sha256'], historicalDatasetDigest=final_manifest['sha256'],
        collectorDatasetPath=str(collector.resolve()), historicalDatasetPath=str(historical.resolve()),
        historicalTruth='RETROSPECTIVE_FINAL_PRICE; period-end availability is an assumption, not a receipt timestamp',
        recentTruth='PROVISIONAL; real publication and collection vintages', researchOnly=True, productionEligible=False)
    write_json(output / 'protocol.json', protocol)
    attempt = TrainingAttempt(SimpleNamespace(output=output, dataset=collector, version='history-improvement-research',
        start=history_start, cutoff=folds[0][0], end=folds[-1][1]))
    try:
        attempt.start()
        attempt.update(datasetDigest=recent_manifest['sha256'], historicalDatasetDigest=final_manifest['sha256'])
        print('Building shared historical examples; no protected targets or fabricated vintages', flush=True)
        old_examples = examples(final, history_start, history_end, 'FINAL', retrospective=True)
        print(json.dumps({'historicalOrigins': len(old_examples['y'][0])}), flush=True)
        validation_records, fold_results = [], []
        for number, (start, end) in enumerate(folds, 1):
            new_examples = examples(recent, max(recent_start, start - 28 * DAY), start, 'PROVISIONAL')
            candidates, fitted = {}, {}
            for name, spec in SPECS.items():
                parts = [old_examples, new_examples] if spec['window'] == 'LONG' else [new_examples]
                models, metadata = fit(parts, spec)
                candidates[name] = (models, spec)
                fitted[name] = metadata
            records = evaluate(recent, start, end, candidates, reference)
            result = scores(records)
            if result['commonPairs'] < 48:
                raise ValueError('Inadequate common-pair validation coverage in a fold; no selection')
            fold_results.append({'number': number, 'start': start.isoformat(), 'end': end.isoformat(),
                'fits': fitted, 'scores': result})
            report(output / f'fold-{number}', records, result)
            validation_records.extend(records)
            print(json.dumps({'fold': number, 'commonPairs': result['commonPairs'],
                'mae': {m: v['mae'] for m, v in result['table'].items()}}), flush=True)
        pooled = scores(validation_records)
        ranking = sorted(pooled['table'], key=lambda m: (pooled['table'][m]['mae'], m))
        chosen = next(m for m in ranking if m in SPECS)
        baseline = next(m for m in ranking if m in ('B1', 'B2', 'B3'))
        selection = {'selectedCandidate': chosen, 'bestMethod': ranking[0], 'bestBaseline': baseline,
            'ranking': ranking, 'scores': pooled, 'purpose': 'EXPLORATORY_EARLIER_VALIDATION',
            'selectedAt': datetime.now(timezone.utc).isoformat(), 'productionEligible': False}
        write_json(output / 'selection.json', selection)
        report(output / 'validation', validation_records, pooled)
        cutoff = floor(acquired)
        latest_examples = examples(recent, max(recent_start, cutoff - 28 * DAY), cutoff, 'PROVISIONAL')
        parts = [old_examples, latest_examples] if SPECS[chosen]['window'] == 'LONG' else [latest_examples]
        models, fitted = fit(parts, SPECS[chosen])
        final_dir = output / 'final-fit'
        final_dir.mkdir()
        path = final_dir / 'candidate.joblib'
        joblib.dump({'models': models, 'spec': SPECS[chosen]}, path, compress=3)
        frozen_at = datetime.now(timezone.utc)
        future_start = frozen_at.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0) + DAY
        plan = {'frozenAt': frozen_at.isoformat(), 'start': future_start.isoformat(),
            'end': (future_start + protocol['confirmationDays'] * DAY).isoformat(),
            'selectedCandidate': chosen, 'baseline': baseline, 'modelSha256': digest(path),
            'selectionSha256': digest(output / 'selection.json'), 'status': 'AWAITING_FUTURE_DATA'}
        write_json(output / 'prospective.json', plan)
        metadata = {**fitted, 'trainingCutoff': cutoff.isoformat(), 'spec': SPECS[chosen],
            'featureOrder': feature_names(2), 'holidayCalendarSha256': holiday_digest(),
            'dependencies': versions(), 'researchOnly': True, 'productionEligible': False,
            'modelSha256': digest(path), 'selectionSha256': plan['selectionSha256'],
            'prospectiveSha256': digest(output / 'prospective.json'), 'referenceFileDigests': ref_hashes,
            'referencePath': str(reference_path.resolve()), 'protocolSha256': digest(output / 'protocol.json')}
        write_json(final_dir / 'manifest.json', metadata)
        if any(digest(Path(p)) != h for p, h in ref_hashes.items()):
            raise ValueError('Reference model changed during the study')
        summary = {'status': 'COMPLETE', 'selection': selection, 'folds': fold_results, 'latestFit': metadata,
            'prospective': plan, 'collectorQuality': recent_manifest['quality'], 'historicalQuality': final_manifest['quality'],
            'referenceUnchanged': True, 'productionEligible': False,
            'limitations': ['Repeated development windows are exploratory, not an untouched test.',
                'Final-price training assumes period-end availability; current evaluation uses genuine provisional vintages.',
                'No origin-known demand forecasts or independent genuine-spike labels; no savings improvement claim.',
                'Final fitted weights have not been independently evaluated; future confirmation remains pending.']}
        write_json(output / 'summary.json', summary)
        verify(output)
        from .spike_grading import grade
        grade(output / 'validation/predictions.jsonl', output / 'spikes')
        attempt.update(scores=pooled, selectedCandidate=chosen)
        attempt.finish('SUCCESS', 'Research candidate frozen; future confirmation and production eligibility pending', 'RETAINED')
        return summary
    except BaseException as exc:
        attempt.finish('FAILED', str(exc), 'RETAINED')
        write_json(output / 'failure.json', {'type': type(exc).__name__, 'reason': str(exc)})
        raise
    finally:
        attempt.close()


def load_candidate(study_path):
    metadata = json.loads((study_path / 'final-fit/manifest.json').read_text())
    plan = json.loads((study_path / 'prospective.json').read_text())
    if (metadata['dependencies'] != versions() or metadata['featureOrder'] != feature_names(2)
            or metadata['holidayCalendarSha256'] != holiday_digest()
            or metadata['prospectiveSha256'] != digest(study_path / 'prospective.json')
            or metadata['selectionSha256'] != digest(study_path / 'selection.json')
            or metadata['protocolSha256'] != digest(study_path / 'protocol.json')
            or metadata['modelSha256'] != digest(study_path / 'final-fit/candidate.joblib')
            or metadata['modelSha256'] != plan['modelSha256']
            or metadata['selectionSha256'] != plan['selectionSha256']
            or any(digest(Path(p)) != h for p, h in metadata['referenceFileDigests'].items())):
        raise ValueError('Frozen candidate, reference, schema or selection changed')
    candidate = joblib.load(study_path / 'final-fit/candidate.joblib')
    if candidate['spec'] != metadata['spec'] or len(candidate['models']) != 24:
        raise ValueError('Invalid research model shape or specification')
    return candidate, metadata, plan


def verify(output):
    """Recalculate errors from saved predictions, without calling score helpers."""
    candidate, _, _ = load_candidate(output)
    protocol = json.loads((output / 'protocol.json').read_text())
    source_rows, source_manifest, acquired = collector_data(Path(protocol['collectorDatasetPath']))
    _, history_manifest = load_dataset(Path(protocol['historicalDatasetPath']))
    if (source_manifest['sha256'] != protocol['collectorDatasetDigest']
            or history_manifest['sha256'] != protocol['historicalDatasetDigest']):
        raise ValueError('Frozen source dataset changed')
    folds = [(instant(f['start']), instant(f['end'])) for f in protocol['folds']]
    actuals = {end: truth(source_rows, 'PROVISIONAL', end) for _, end in folds}
    checked = {}
    for path in sorted(output.glob('fold-*')) + [output / 'validation']:
        records = [json.loads(line) for line in (path / 'predictions.jsonl').read_text().splitlines()]
        saved = json.loads((path / 'summary.json').read_text())
        grouped = {m: {} for m in saved['table']}
        for r in records:
            if r['exclusionReason'] is None:
                key = (r['asOf'], r['horizon'])
                if key in grouped[r['method']] or targets(instant(r['asOf']))[r['horizon']-1] != instant(r['targetPeriod']):
                    raise ValueError('Duplicate or misaligned saved prediction')
                split = [(a, b) for a, b in folds if a <= instant(r['asOf']) < b]
                if (len(split) != 1 or instant(r['targetPeriod']) + STEP > split[0][1]
                        or r['actual'] != actuals[split[0][1]].get(instant(r['targetPeriod']))):
                    raise ValueError('Saved truth or purged split boundary differs')
                grouped[r['method']][key] = r
        common = set.intersection(*(set(v) for v in grouped.values()))
        if len(common) != saved['commonPairs'] or not common:
            raise ValueError('Saved common coverage differs')
        for method, rows in grouped.items():
            error = mean(abs(rows[k]['actual'] - rows[k]['predictedUsep']) for k in common)
            if abs(error - saved['table'][method]['mae']) > 1e-9:
                raise ValueError('Saved MAE differs from independent recalculation')
            for h in range(1, 25):
                items = [rows[k] for k in common if k[1] == h]
                error_h = mean(abs(r['actual'] - r['predictedUsep']) for r in items) if items else None
                if error_h != saved['table'][method]['perHorizon'][str(h)]:
                    raise ValueError('Saved per-horizon error differs')
        checked[path.name] = {'commonPairs': len(common), 'distinctOrigins': len({k[0] for k in common}),
            'distinctTargets': len({next(iter(grouped.values()))[k]['targetPeriod'] for k in common})}
    current = forecast(candidate['models'], candidate['spec'], History(source_rows).at(acquired), acquired)
    proof = {'status': 'PASS', 'independentSavedPredictionMae': True, 'coverageAndTargetAlignment': True,
        'canonicalTruthAndSplitPurging': True, 'artifactIntegrityAndDependencies': True,
        'fittedHorizons': len(candidate['models']), 'forecastSmokePoints': len(current) if current is not None else 0,
        'splits': checked}
    write_json(output / 'verification.json', proof)
    return proof


def confirm(collector, study_path, output):
    if output.exists():
        raise ValueError('Confirmation reports are immutable')
    candidate, metadata, plan = load_candidate(study_path)
    rows, manifest, acquired = collector_data(collector)
    start, end = instant(plan['start']), instant(plan['end'])
    if (instant(plan['frozenAt']) >= start or instant(metadata['trainingCutoff']) >= start
            or start != start.astimezone(SGT).replace(hour=0, minute=0, second=0, microsecond=0) or end - start < 7 * DAY):
        raise ValueError('Candidate was not frozen before the future window')
    if acquired < end:
        raise ValueError(f'Future confirmation pending: need a snapshot acquired on/after {end.isoformat()}')
    reference = load_bundle(Path(metadata['referencePath']))
    records = evaluate(rows, start, end, {plan['selectedCandidate']: (candidate['models'], candidate['spec'])}, reference)
    result = scores(records, plan['baseline'])
    labels = truth(rows, 'PROVISIONAL', end)
    daily = [{'date': (start + n * DAY).astimezone(SGT).date().isoformat(),
              'observedTruthPeriods': sum(start + n * DAY <= p < start + (n+1) * DAY for p in labels)}
             for n in range((end-start).days)]
    result.update(status='COMPLETE' if result['commonOriginDays'] == (end-start).days
        and all(d['observedTruthPeriods'] == 48 for d in daily) else 'INSUFFICIENT_CONFIRMATION_COVERAGE',
        plan=plan, dailyCoverage=daily, datasetDigest=manifest['sha256'], productionEligible=False)
    report(output, records, result)
    from .spike_grading import grade
    grade(output / 'predictions.jsonl', output / 'spikes')
    return result
