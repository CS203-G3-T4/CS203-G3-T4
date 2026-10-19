"""Recheck this saved grade against raw predictions, source prices and event runs."""
import json
from pathlib import Path
from statistics import median

from wattly_ml.backtest import History
from wattly_ml.data import DatasetRows, digest, load_dataset, truth, write_json
from wattly_ml.improvement import load_candidate, protected
from wattly_ml.timebase import DAY, STEP, instant

ROOT = Path('.local/spike-grading-2026-10-18-v1')
STUDY = Path('.local/improvement-2026-10-18-v1/study')


def main():
    result = json.loads((ROOT / 'validation/summary.json').read_text())
    source = Path(result['source'])
    assert digest(source) == result['sourceSha256']
    original = json.loads((STUDY / 'selection.json').read_text())['scores']
    records = [json.loads(line) for line in source.read_text().splitlines()]
    grouped = {m: {(r['asOf'], r['horizon']): r for r in records if r['method'] == m
        and r['exclusionReason'] is None} for m in result['methods']}
    common = set.intersection(*(set(v) for v in grouped.values()))
    assert len(common) == result['commonPricePairs'] == original['commonPairs']
    labelled = {k for k in common if next(iter(grouped.values()))[k]['threshold'] is not None}
    assert len(labelled) == result['commonLabelledPairs']
    protocol = json.loads((STUDY / 'protocol.json').read_text())
    raw, manifest = load_dataset(Path(protocol['collectorDatasetPath']))
    assert manifest['sha256'] == protocol['collectorDatasetDigest']
    history = History(DatasetRows([r for r in raw if not protected(r, protocol)]))
    known = {s: history.at(instant(s)) for s in {s for s, _ in common}}
    for s, past in known.items():
        assert all(max(instant(r[k]) for k in ('periodStart', 'sourceUpdatedAt', 'availableAt')) <= instant(s)
            for r in past.values())
    folds = [(instant(f['start']), instant(f['end'])) for f in protocol['folds']]
    actuals = {end: truth(raw, 'PROVISIONAL', end) for _, end in folds}
    for key in common:
        row = next(iter(grouped.values()))[key]
        target, origin = instant(row['targetPeriod']), instant(row['asOf'])
        past = known[row['asOf']]
        end = next(b for a, b in folds if a <= origin < b)
        assert target + STEP <= end and row['actual'] == actuals[end][target]
        prices = [past[p]['usep'] for n in range(1, 29)
            if (p := target - n * DAY) < origin and p in past]
        threshold = None
        if len(prices) >= 14:
            typical = median(prices)
            threshold = typical + 3 * max(1, 1.4826 * median(abs(p - typical) for p in prices))
        assert threshold == row['threshold']
    for method, saved in result['table'].items():
        valid = [grouped[method][k] for k in labelled]
        labels = [r['actual'] > r['threshold'] for r in valid]
        alarm = [r['predictedUsep'] > r['threshold'] for r in valid]
        confusion = {'tp': sum(y and a for y, a in zip(labels, alarm)),
            'fp': sum(not y and a for y, a in zip(labels, alarm)),
            'fn': sum(y and not a for y, a in zip(labels, alarm)),
            'tn': sum(not y and not a for y, a in zip(labels, alarm))}
        assert all(saved['pooled'][k] == v for k, v in confusion.items())
        assert all(saved['pooled'][k] == original['table'][method]['spikes'][k] for k in ('tp', 'fp', 'fn'))
        for h, score in saved['fixedLeads'].items():
            rows = {instant(r['targetPeriod']): r for r in valid if r['horizon'] == int(h)}
            assert len(rows) == score['forecastPairs']
            onsets = [t for t, r in rows.items() if r['actualSpike'] and t - STEP in rows
                and not rows[t - STEP]['actualSpike']]
            early, late, missed, total = 0, 0, 0, 0
            for onset in onsets:
                stop = onset
                while stop in rows and rows[stop]['actualSpike']:
                    stop += STEP
                if stop not in rows:
                    continue
                total += 1
                alarms = [instant(r['asOf']) for t, r in rows.items() if onset <= t < stop and r['spikeFlag']]
                if any(t < onset for t in alarms):
                    early += 1
                elif alarms:
                    late += 1
                else:
                    missed += 1
            events = score['events']
            assert (total, early, late, missed) == tuple(events[k]
                for k in ('fullyObservedEvents', 'warnedBeforeStart', 'lateOnlyDetection', 'missedEntirely'))
    # Existing model, selection and future plan remain byte-verified by the loader.
    _, _, plan = load_candidate(STUDY)
    priority = json.loads(Path('docs/forecasting/evidence/2026-10-18-spike-grading/spike-priority-plan.json').read_text())
    assert digest(STUDY / 'prospective.json') == priority['existingFuturePlanSha256']
    assert plan['selectionSha256'] == priority['existingSelectionSha256']
    proof = {'status': 'PASS', 'rawPredictionConfusionCounts': True, 'originalScoreCrosscheck': True,
        'pastOnlySameSlotThresholdsRecomputed': True, 'canonicalActualsAndSplitBounds': True,
        'uniqueFixedLeadEventsIndependentlyRecomputed': True,
        'commonPairs': len(common), 'methods': len(grouped), 'frozenArtifactsAndFuturePlanUnchanged': True}
    write_json(ROOT / 'verification.json', proof)
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
