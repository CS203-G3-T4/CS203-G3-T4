"""Coverage and day-stability diagnostics using unchanged saved predictions only."""
from pathlib import Path
import json
import os

from wattly_ml.data import digest, write_json
from wattly_ml.spike_grading import analyse, grade
from wattly_ml.timebase import SGT, instant

ROOT = Path(os.environ.get('WATTLY_SPIKE_TEST_HOME', '.local/additional-spike-tests-2026-10-18-v1'))
SOURCE = Path('.local/improvement-2026-10-18-v1/study/validation/predictions.jsonl')
OLD_TEST = Path('.local/acceptance-2026-10-18/reports/unseen-weeks-v1/predictions.jsonl')


def comparison(result):
    old, new = (result['table'][m]['pooled'] for m in ('PREVIOUS_AI', 'LONG_DEFAULT'))
    return {'old': old, 'new': new,
        'recallDifference': new['recall'] - old['recall'] if new['recall'] is not None and old['recall'] is not None else None,
        'falsePositiveDifference': new['fp'] - old['fp'],
        'falsePositiveRateDifference': new['falsePositiveRate'] - old['falsePositiveRate']
            if new['falsePositiveRate'] is not None and old['falsePositiveRate'] is not None else None}


def main():
    output = ROOT / 'saved'
    if output.exists():
        raise ValueError('Immutable saved-prediction diagnostics already exist')
    plan = json.loads((ROOT / 'plan.json').read_text())
    source_hash, old_hash = digest(SOURCE), digest(OLD_TEST)
    output.mkdir()
    records = [json.loads(line) for line in SOURCE.read_text().splitlines()]
    full = analyse(records)
    panels = {}
    for name in ('withoutB3', 'aiOnly'):
        methods = plan['recentPanels'][name]
        file = output / f'{name}-predictions.jsonl'
        file.write_text(''.join(json.dumps(r) + '\n' for r in records if r['method'] in methods))
        result = grade(file, output / name)
        panels[name] = {'commonPairs': result['commonLabelledPairs'], **comparison(result),
            '3h': {m: result['table'][m]['fixedLeads']['6'] for m in methods},
            'limitation': 'Compare only within this panel; B3 is not ranked against different coverage'}
    days = sorted({instant(r['asOf']).astimezone(SGT).date().isoformat() for r in records})
    daily, leave_out = [], []
    for day in days:
        chosen = [r for r in records if instant(r['asOf']).astimezone(SGT).date().isoformat() == day]
        try:
            current = analyse(chosen)
        except ValueError as exc:
            if 'No identical eligible' not in str(exc):
                raise
            daily.append({'date': day, 'status': 'NO_COMMON_COVERAGE'})
            continue
        daily.append({'date': day, 'status': 'DESCRIPTIVE', 'commonPairs': current['commonLabelledPairs'], **comparison(current)})
        remaining = analyse([r for r in records if instant(r['asOf']).astimezone(SGT).date().isoformat() != day])
        leave_out.append({'excludedOriginDay': day, 'commonPairs': remaining['commonLabelledPairs'], **comparison(remaining)})
    del records
    old_grade = grade(OLD_TEST, output / 'old-ai-eight-week-test')
    original = json.loads(Path('docs/forecasting/evidence/2026-10-18-acceptance/unseen-weeks.json').read_text())
    assert old_grade['commonLabelledPairs'] == original['commonPairs']
    for method, item in old_grade['table'].items():
        assert all(item['pooled'][k] == original['table'][method]['spikes'][k] for k in ('tp', 'fp', 'fn'))
    assert digest(SOURCE) == source_hash and digest(OLD_TEST) == old_hash
    summary = {'status': 'COMPLETE', 'recentSourceSha256': source_hash, 'oldTestSourceSha256': old_hash,
        'originalCommonPairs': full['commonLabelledPairs'], 'panels': panels,
        'daily': daily, 'leaveOneOriginDayOut': leave_out,
        'stabilityMeaning': 'Descriptive overlapping development data; not independent replications or a confidence interval',
        'oldEightWeekTest': {'commonPairs': old_grade['commonLabelledPairs'],
            'interpretation': 'Existing July29–September22 old-AI predictions only; new October-fitted model is not evaluated in its past'},
        'sourceFilesUnchanged': True, 'modelsOrThresholdsChanged': False, 'productionEligible': False}
    write_json(output / 'summary.json', summary)
    print(json.dumps({'status': 'COMPLETE', 'recentPanels': {k:v['commonPairs'] for k,v in panels.items()},
        'oldEightWeekPairs': old_grade['commonLabelledPairs']}, indent=2))


if __name__ == '__main__':
    main()
