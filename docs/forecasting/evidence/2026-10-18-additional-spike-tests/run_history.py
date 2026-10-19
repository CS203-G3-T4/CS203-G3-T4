"""Fixed-setting chronological diagnostics; never evaluate the October-fitted model in its past."""
from pathlib import Path
import json
import os
import shutil

import joblib

from wattly_ml.artifacts import versions
from wattly_ml.backtest import scores
from wattly_ml.cli import report
from wattly_ml.data import DatasetRows, digest, load_dataset, write_json
from wattly_ml.features import feature_names, holiday_digest
from wattly_ml.improvement import evaluate, examples, fit
from wattly_ml.spike_grading import grade
from wattly_ml.timebase import DAY, instant

ROOT = Path(os.environ.get('WATTLY_SPIKE_TEST_HOME', '.local/additional-spike-tests-2026-10-18-v1'))
SOURCE = Path('.local/acceptance-2026-10-18/datasets/official-final-v1')


def main():
    output = ROOT / 'historical'
    if output.exists():
        raise ValueError('Immutable diagnostic exists; choose a new run directory')
    plan = json.loads((ROOT / 'plan.json').read_text())
    source, manifest = load_dataset(SOURCE)
    assert not manifest['quality']['parsingFailures'] and not manifest['quality']['duplicateRows']
    assert all(r['priceStatus'] == 'FINAL' and r['availableAt'] is None and r['sourceUpdatedAt'] is None for r in source)
    protected_start = instant('2026-07-29T00:00:00+08:00')
    rows = DatasetRows([r for r in source if instant(r['periodStart']) < protected_start])
    output.mkdir(); (output / 'pooled').mkdir()
    source_digest = manifest['sha256']
    summaries = []
    for number, window in enumerate(plan['historicalWindows'], 1):
        cutoff, end = instant(window['start']), instant(window['end'])
        assert cutoff < end <= protected_start
        directory = output / f'window-{number}'
        directory.mkdir()
        candidates, fits = {}, {}
        for name, spec in plan['historicalCandidates'].items():
            start = instant(plan['historicalTrainingStart']) if spec['window'] == 'LONG' else cutoff - 28 * DAY
            samples = examples(rows, start, cutoff, 'FINAL', retrospective=True)
            models, fitted = fit([samples], spec)
            fitted.update(finalPriceOriginsBeforeBaselineFilter=len(samples['y'][0]),
                provisionalOriginsBeforeBaselineFilter=0, trainingStart=start.isoformat(),
                trainingCutoff=cutoff.isoformat(), datasetDigest=source_digest,
                evaluationKind='RETROSPECTIVE_FINAL_PRICE', dependencies=versions(),
                featureSchemaVersion=2, featureOrder=feature_names(2), holidayCalendarSha256=holiday_digest(),
                researchOnly=True, productionEligible=False)
            model_path = directory / f'{name}.joblib'
            joblib.dump({'models': models, 'spec': spec}, model_path, compress=3)
            fitted['modelSha256'] = digest(model_path)
            fitted['modelVersion'] = f'research-{name}-{cutoff:%Y%m%d}-' + fitted['modelSha256'][:12]
            write_json(directory / f'{name}-manifest.json', fitted)
            fits[name] = fitted
            candidates[name] = (models, spec)
            del samples
            print(json.dumps({'window': number, 'method': name, 'trainingOrigins': fitted['trainingOriginsPerHorizon']}), flush=True)
        records = evaluate(rows, cutoff, end, candidates, policy='FINAL', retrospective=True)
        for r in records:
            if r['method'] in fits:
                r['modelVersion'] = fits[r['method']]['modelVersion']
        result = scores(records, 'B3')
        assert result['commonPairs'] > 0
        if number == 2:
            original = json.loads(Path('docs/forecasting/evidence/2026-10-18-acceptance/validation.json').read_text())
            assert result['commonPairs'] == original['commonPairs']
            assert abs(result['table']['LONG_HISTORY']['mae'] - original['table']['AI']['mae']) < 1e-8
        report(directory / 'prices', records, result)
        del records
        with (output / 'pooled/predictions.jsonl').open('ab') as combined:
            with (directory / 'prices/predictions.jsonl').open('rb') as current:
                shutil.copyfileobj(current, combined)
        spike_grade = grade(directory / 'prices/predictions.jsonl', directory / 'spikes')
        summaries.append({'window': window, 'fits': fits, 'scores': result,
            'spikeReportSha256': digest(directory / 'spikes/summary.json')})
        print(json.dumps({'window': number, 'commonPairs': spike_grade['commonLabelledPairs'],
            '3h': {m: r['fixedLeads']['6'] for m, r in spike_grade['table'].items()}}), flush=True)
        del candidates, models, spike_grade
    pooled = grade(output / 'pooled/predictions.jsonl', output / 'pooled/spikes')
    assert load_dataset(SOURCE)[1]['sha256'] == source_digest
    summary = {'status': 'COMPLETE', 'datasetDigest': source_digest, 'planSha256': digest(ROOT / 'plan.json'),
        'windows': summaries, 'pooledCommonPairs': pooled['commonLabelledPairs'],
        'interpretation': plan['interpretation'], 'julyOriginalValidationReproduced': True,
        'frozenOctoberCandidateEvaluatedInItsPast': False, 'protectedOriginalTestUsedByNewFits': False,
        'productionEligible': False}
    write_json(output / 'summary.json', summary)
    print(json.dumps({'status': 'COMPLETE', 'pooledCommonPairs': pooled['commonLabelledPairs']}), flush=True)


if __name__ == '__main__':
    main()
