"""Independent confusion/event checks, canonical truth, fit boundaries and frozen identities."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from statistics import median
from types import SimpleNamespace

import joblib
import numpy as np

from wattly_ml.backtest import History
from wattly_ml.data import DatasetRows, digest, load_dataset, truth, write_json
from wattly_ml.improvement import forecast, load_candidate, protected
from wattly_ml.operations import TrainingAttempt
from wattly_ml.timebase import DAY, STEP, instant, targets

ROOT = Path(os.environ.get('WATTLY_SPIKE_TEST_HOME', '.local/additional-spike-tests-2026-10-18-v1'))
STUDY = Path('.local/improvement-2026-10-18-v1/study')


def check_grade(path, final_prices, recent_rows, recent_protocol):
    report = json.loads(path.read_text())
    assert digest(Path(report['source'])) == report['sourceSha256']
    grouped = {m: {} for m in report['methods']}
    with Path(report['source']).open() as stream:
        for line in stream:
            row = json.loads(line)
            if row['exclusionReason'] is None:
                key = (row['asOf'], row['horizon'])
                assert key not in grouped[row['method']]
                grouped[row['method']][key] = row
    common = set.intersection(*(set(v) for v in grouped.values()))
    assert len(common) == report['commonPricePairs']
    labels = {k for k in common if next(iter(grouped.values()))[k]['threshold'] is not None}
    assert len(labels) == report['commonLabelledPairs']
    sample = next(iter(grouped.values()))
    actuals, thresholds = {}, {}
    history = History(recent_rows)
    known = {}
    for key in common:
        row = sample[key]
        origin, target = instant(row['asOf']), instant(row['targetPeriod'])
        assert targets(origin)[row['horizon'] - 1] == target
        if row['truthPolicy'] == 'FINAL':
            assert row['actual'] == final_prices[target]
            if target not in thresholds:
                values = [final_prices[p] for n in range(1, 29) if (p := target - n * DAY) in final_prices]
                thresholds[target] = threshold(values)
            expected = thresholds[target]
            assert all(target - n * DAY + STEP <= origin for n in range(1, 29))
        else:
            end = next(instant(f['end']) for f in recent_protocol['folds']
                if instant(f['start']) <= origin < instant(f['end']))
            assert target + STEP <= end
            if end not in actuals:
                actuals[end] = truth(recent_rows, 'PROVISIONAL', end)
            assert row['actual'] == actuals[end][target]
            if origin not in known:
                known[origin] = history.at(origin)
            values = [known[origin][p]['usep'] for n in range(1, 29)
                if (p := target - n * DAY) < origin and p in known[origin]]
            expected = threshold(values)
        assert row['threshold'] == expected
    for method, rows in grouped.items():
        selected = [rows[k] for k in labels]
        flags = [(r['actual'] > r['threshold'], r['predictedUsep'] > r['threshold']) for r in selected]
        counts = {'tp': sum(y and p for y, p in flags), 'fp': sum(not y and p for y, p in flags),
            'fn': sum(y and not p for y, p in flags), 'tn': sum(not y and not p for y, p in flags)}
        assert all(report['table'][method]['pooled'][k] == v for k, v in counts.items())
        for h, saved in report['table'][method]['fixedLeads'].items():
            by_target = {instant(r['targetPeriod']): r for r in selected if r['horizon'] == int(h)}
            assert len(by_target) == saved['forecastPairs']
            onsets = [t for t, r in by_target.items() if r['actualSpike'] and t - STEP in by_target
                and not by_target[t - STEP]['actualSpike']]
            n, early, late, missed = 0, 0, 0, 0
            for start in onsets:
                stop = start
                while stop in by_target and by_target[stop]['actualSpike']:
                    stop += STEP
                if stop not in by_target:
                    continue
                n += 1
                alarms = [instant(r['asOf']) for t, r in by_target.items() if start <= t < stop and r['spikeFlag']]
                if any(o < start for o in alarms): early += 1
                elif alarms: late += 1
                else: missed += 1
            events = saved['events']
            assert (n, early, late, missed) == tuple(events[k] for k in
                ('fullyObservedEvents', 'warnedBeforeStart', 'lateOnlyDetection', 'missedEntirely'))
    return {'commonPairs': len(labels), 'methods': len(grouped), 'canonicalTruthAndPastOnlyThresholds': True,
        'independentConfusionAndFixedLeadEvents': True}


def threshold(values):
    if len(values) < 14:
        return None
    typical = median(values)
    return typical + 3 * max(1, 1.4826 * median(abs(v - typical) for v in values))


def main():
    os.environ['WATTLY_ML_HOME'] = str((ROOT / 'runtime').resolve())
    os.environ['WATTLY_RUN_API_URL'] = ''
    plan = json.loads((ROOT / 'plan.json').read_text())
    source, manifest = load_dataset(Path('.local/acceptance-2026-10-18/datasets/official-final-v1'))
    final_prices = {instant(r['periodStart']): r['usep'] for r in source}
    protocol = json.loads((STUDY / 'protocol.json').read_text())
    recent, recent_manifest = load_dataset(Path(protocol['collectorDatasetPath']))
    recent = DatasetRows([r for r in recent if not protected(r, protocol)], recent.demand_forecasts)
    reports = [ROOT / 'saved/withoutB3/summary.json', ROOT / 'saved/aiOnly/summary.json',
        ROOT / 'saved/old-ai-eight-week-test/summary.json', ROOT / 'historical/pooled/spikes/summary.json']
    checks = {}
    for path in reports:
        checks[str(path.relative_to(ROOT))] = check_grade(path, final_prices, recent, protocol)
    artifact_checks = []
    for n, window in enumerate(plan['historicalWindows'], 1):
        directory = ROOT / f'historical/window-{n}'
        scores = json.loads((directory / 'prices/summary.json').read_text())
        cutoff, end = instant(window['start']), instant(window['end'])
        history = History(source, retrospective=True)
        for method, spec in plan['historicalCandidates'].items():
            meta = json.loads((directory / f'{method}-manifest.json').read_text())
            model_path = directory / f'{method}.joblib'
            assert digest(model_path) == meta['modelSha256']
            assert instant(meta['trainingCutoff']) == cutoff < end <= instant('2026-07-29T00:00:00+08:00')
            expected = int((cutoff - instant(meta['trainingStart'])) / STEP) - 24
            assert meta['trainingOriginsPerHorizon'] == expected
            saved = joblib.load(model_path)
            values = forecast(saved['models'], saved['spec'], history.at(cutoff), cutoff)
            assert len(values) == 24 and np.all(np.isfinite(values))
            with (directory / 'prices/predictions.jsonl').open() as stream:
                rows = [json.loads(line) for line in stream if f'"method": "{method}"' in line]
            first = [r for r in rows if instant(r['asOf']) == cutoff]
            assert all(abs(r['predictedUsep'] - values[r['horizon'] - 1]) < 1e-9 for r in first)
            artifact_checks.append({'method': method, 'cutoff': cutoff.isoformat(), 'modelSha256': meta['modelSha256'],
                'trainingOrigins': expected, 'reloadAndBatchParity': True})
            # This research audit is explicitly recorded after the completed fit.
            audit_dir = ROOT / 'runtime/training-attempts'
            existing = [json.loads(p.read_text()) for p in audit_dir.glob('*.json')] if audit_dir.exists() else []
            if not any(r.get('modelSha256') == meta['modelSha256'] for r in existing):
                args = SimpleNamespace(output=directory, dataset=Path('.local/acceptance-2026-10-18/datasets/official-final-v1'),
                    version=meta['modelVersion'], start=instant(meta['trainingStart']), cutoff=cutoff, end=end)
                attempt = TrainingAttempt(args)
                try:
                    attempt.start()
                    attempt.update(datasetDigest=manifest['sha256'], scores=scores, modelSha256=meta['modelSha256'],
                        recordedAfterResearchFit=True,
                        fitArtifactCreatedAt=datetime.fromtimestamp(model_path.stat().st_mtime, timezone.utc).isoformat())
                    attempt.finish('SUCCESS', 'Historical fixed-setting research only; no model promotion', 'RETAINED')
                finally:
                    attempt.close()
    load_candidate(STUDY)
    assert digest(STUDY / 'prospective.json') == plan['frozenFuturePlanSha256']
    assert digest(STUDY / 'final-fit/candidate.joblib') == plan['frozenCandidateSha256']
    proof = {'status': 'PASS', 'reports': checks, 'historicalArtifacts': artifact_checks,
        'sourceFinalPricesRetainNullVintages': all(r['availableAt'] is None for r in source),
        'historicalFitsExcludeProtectedOriginalTest': True, 'frozenFutureCandidateAndPlanUnchanged': True,
        'julyOriginalValidationReproduced': json.loads((ROOT / 'historical/summary.json').read_text())['julyOriginalValidationReproduced'],
        'trainingAudits': 'Four research fits audited locally after completion; no claim of pre-fit audit or API synchronization'}
    write_json(ROOT / 'verification.json', proof)
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
