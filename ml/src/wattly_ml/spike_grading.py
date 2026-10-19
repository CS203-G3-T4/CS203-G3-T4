"""Grade frozen price forecasts for rule-defined spikes, without fitting or tuning."""
from collections import Counter
import csv
from datetime import datetime, timezone
import json
import math
from pathlib import Path
from statistics import median

from .data import digest, write_json
from .timebase import STEP, floor, instant, targets

MINIMUM_TARGETS = 20
FIXED_LEADS = (1, 6, 12, 24)


def runs(times):
    groups = []
    for time in sorted(times):
        if not groups or time - groups[-1][-1] != STEP:
            groups.append([])
        groups[-1].append(time)
    return groups


def metrics(rows):
    tp = sum(r['actualSpike'] and r['spikeFlag'] for r in rows)
    fp = sum(not r['actualSpike'] and r['spikeFlag'] for r in rows)
    fn = sum(r['actualSpike'] and not r['spikeFlag'] for r in rows)
    tn = len(rows) - tp - fp - fn
    positive = {r['targetPeriod'] for r in rows if r['actualSpike']}
    negative = {r['targetPeriod'] for r in rows if not r['actualSpike']}
    supported = len(positive) >= MINIMUM_TARGETS and len(negative) >= MINIMUM_TARGETS
    return {'tp': tp, 'fp': fp, 'fn': fn, 'tn': tn, 'forecastPairs': len(rows),
        'distinctTargets': len(positive | negative), 'distinctPositiveTargets': len(positive),
        'distinctNegativeTargets': len(negative), 'targetsWithOriginDependentLabels': len(positive & negative),
        'precision': tp / (tp + fp) if supported and tp + fp else None,
        'recall': tp / (tp + fn) if supported and tp + fn else None,
        'missRate': fn / (tp + fn) if supported and tp + fn else None,
        'falsePositiveRate': fp / (fp + tn) if supported and fp + tn else None,
        'falseAlarmTargets': len({r['targetPeriod'] for r in rows if r['spikeFlag'] and not r['actualSpike']}),
        'rateLimit': None if supported else 'Fewer than 20 distinct positive and negative targets; counts only',
        'precisionUndefinedReason': 'No predicted spikes' if tp + fp == 0 else None}


def event_metrics(rows):
    """One origin per target at a fixed lead; distinguish early from late detection."""
    by_target = {instant(r['targetPeriod']): r for r in rows}
    if len(by_target) != len(rows):
        raise ValueError('Event grading requires one forecast per target at a fixed lead')
    events = runs(t for t, r in by_target.items() if r['actualSpike'])
    complete = [g for g in events if g[0] - STEP in by_target and g[-1] + STEP in by_target]
    early, late, missed, warning = 0, 0, 0, []
    for group in complete:
        alerts = [instant(by_target[t]['asOf']) for t in group if by_target[t]['spikeFlag']]
        before_start = [origin for origin in alerts if origin < group[0]]
        if before_start:
            early += 1
            warning.append(int((group[0] - min(before_start)).total_seconds() / 60))
        elif alerts:
            late += 1
        else:
            missed += 1
    false_targets = {t for t, r in by_target.items() if r['spikeFlag'] and not r['actualSpike']}
    return {'observedPositiveRuns': len(events), 'fullyObservedEvents': len(complete),
        'censoredRuns': len(events) - len(complete), 'warnedBeforeStart': early,
        'lateOnlyDetection': late, 'missedEntirely': missed, 'notWarnedBeforeStart': late + missed,
        'eventEarlyRecall': early / len(complete) if len(complete) >= MINIMUM_TARGETS else None,
        'eventRateLimit': None if len(complete) >= MINIMUM_TARGETS else 'Fewer than 20 fully observed events; counts only',
        'falseAlarmTargets': len(false_targets), 'falseAlarmRuns': len(runs(false_targets)),
        'warningLeadMinutes': {'events': len(warning), 'median': median(warning) if warning else None,
            'minimum': min(warning) if warning else None, 'maximum': max(warning) if warning else None},
        'eventDefinition': 'Adjacent positive half-hours at this fixed lead, bracketed by assessed negative half-hours; coverage gaps censor boundaries'}


def analyse(records):
    methods = sorted({r['method'] for r in records})
    if not methods:
        raise ValueError('No saved prediction methods')
    grouped, excluded = {m: {} for m in methods}, {m: Counter() for m in methods}
    seen = set()
    for row in records:
        method, h = row['method'], row['horizon']
        if type(h) is not int or not 1 <= h <= 24:
            raise ValueError('Invalid saved forecast horizon')
        origin, target = instant(row['asOf']), instant(row['targetPeriod'])
        if origin != floor(origin) or target != targets(origin)[h - 1]:
            raise ValueError('Spike grading requires aligned half-hour backtest origins and targets')
        row = {**row, 'asOf': origin.isoformat(), 'targetPeriod': target.isoformat()}
        key = (origin, h)
        if (method, key) in seen:
            raise ValueError('Duplicate saved origin/horizon prediction')
        seen.add((method, key))
        if row['exclusionReason'] is not None:
            excluded[method][row['exclusionReason']] += 1
            continue
        if any(type(row[k]) not in (int, float) or not math.isfinite(row[k]) for k in ('actual', 'predictedUsep')):
            raise ValueError('Eligible predictions require finite price and truth')
        threshold = row['threshold']
        if threshold is None:
            if row['actualSpike'] is not None or row['spikeFlag'] is not None:
                raise ValueError('Unavailable reference cannot become a spike label')
        elif (type(threshold) not in (int, float) or not math.isfinite(threshold)
                or type(row['actualSpike']) is not bool or type(row['spikeFlag']) is not bool
                or row['actualSpike'] != (row['actual'] > threshold)
                or row['spikeFlag'] != (row['predictedUsep'] > threshold)):
            raise ValueError('Saved spike flags do not match their frozen threshold')
        grouped[method][key] = row
    common = set.intersection(*(set(rows) for rows in grouped.values()))
    labelled = set()
    for key in common:
        labels = {(rows[key]['actual'], rows[key]['threshold'], rows[key]['actualSpike'],
                   rows[key]['targetPeriod'], rows[key]['truthPolicy']) for rows in grouped.values()}
        if len(labels) != 1:
            raise ValueError('Methods use different truth, threshold or target for a shared pair')
        if next(iter(grouped.values()))[key]['threshold'] is not None:
            labelled.add(key)
    if not common:
        raise ValueError('No identical eligible forecast pairs')
    table = {}
    for method, rows in grouped.items():
        valid = [rows[k] for k in sorted(labelled)]
        leads = {}
        for h in FIXED_LEADS:
            selected = [r for r in valid if r['horizon'] == h]
            leads[str(h)] = {'leadMinutes': h * 30, **metrics(selected), 'events': event_metrics(selected)}
        table[method] = {'pooled': metrics(valid),
            'horizonGroups': {name: metrics([r for r in valid if lo <= r['horizon'] <= hi])
                for name, lo, hi in (('next3h', 1, 6), ('3to6h', 7, 12), ('6to12h', 13, 24))},
            'fixedLeads': leads}
    return {'methods': methods, 'commonPricePairs': len(common), 'commonLabelledPairs': len(labelled),
        'unavailableReferencePairs': len(common - labelled),
        'eligiblePairsByMethod': {m: len(rows) for m, rows in grouped.items()},
        'exclusionsByMethod': {m: dict(counts) for m, counts in excluded.items()}, 'table': table,
        'objective': 'Spike detection and advance warning, reporting missed events alongside false alarms; MAE alone cannot determine the preferred model',
        'labelMeaning': 'Price exceeds the saved origin-frozen, past-only same-half-hour reference threshold; a price-anomaly proxy, not independent genuine-spike truth',
        'rateSupportRule': 'At least20 distinct positive and negative targets for classification rates; at least20 fully observed events for event recall',
        'overlapPolicy': 'Pooled and horizon-group rows are dependent forecast pairs; fixed leads use one origin per target and lead; never add event counts across leads',
        'genuineSpikeMetrics': {'precision': None, 'recall': None, 'reason': 'No independent genuine-event labels'},
        'overallGrade': None,
        'overallGradeReason': 'No agreed miss-versus-false-alarm cost or acceptance threshold; report tradeoffs instead of an invented percentage/letter grade',
        'productionApproved': False}


def grade(predictions: Path, output: Path):
    if output.exists():
        raise ValueError('Spike grading reports are immutable; choose a new output directory')
    checksum = digest(predictions)
    result = analyse([json.loads(line) for line in predictions.read_text().splitlines()])
    if digest(predictions) != checksum:
        raise ValueError('Prediction source changed during grading')
    result.update(source=str(predictions.resolve()), sourceSha256=checksum,
        gradedAt=datetime.now(timezone.utc).isoformat())
    output.mkdir(parents=True)
    write_json(output / 'summary.json', result)
    with (output / 'scoreboard.csv').open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['method', 'cohort', 'pairs', 'distinct_targets', 'tp', 'fp', 'fn', 'tn',
            'precision', 'recall', 'miss_rate', 'false_positive_rate'])
        for method, item in result['table'].items():
            groups = {'pooled': item['pooled'], **item['horizonGroups'], **{'lead' + h: r for h, r in item['fixedLeads'].items()}}
            for cohort, row in groups.items():
                writer.writerow([method, cohort, row['forecastPairs'], row['distinctTargets'],
                    row['tp'], row['fp'], row['fn'], row['tn'], row['precision'], row['recall'],
                    row['missRate'], row['falsePositiveRate']])
    return result
