"""Compare saved forecasts through the real F2/F4 contracts, without training."""
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path

from .cli import report
from .operations import post_admin
from .timebase import SGT, instant


def study(predictions: Path, validation: Path, model_manifest: Path, households, output: Path):
    if output.exists():
        raise ValueError('Savings studies are immutable')
    validation=json.loads(validation.read_text())
    model=json.loads(model_manifest.read_text())
    ranking=model['baselineRanking']
    if not ranking or validation.get('datasetDigest')!=model['datasetDigest']:
        raise ValueError('Require matching earlier validation and frozen model baseline ranking')
    baseline=ranking[0]
    groups={}
    for line in predictions.open():
        row=json.loads(line)
        if row['method'] not in ('AI',baseline):
            continue
        origin=instant(row['asOf'])
        if origin.astimezone(SGT).hour not in (0,12) or origin.astimezone(SGT).minute!=0:
            continue
        groups.setdefault(row['asOf'],{}).setdefault(row['method'],[]).append(row)
    cases={}; exclusions=Counter(); fixed=set(); contexts={}
    for stamp,methods in sorted(groups.items()):
        if instant(stamp)<instant(model['usableFrom']) or instant(stamp)<instant(validation['end']):
            raise ValueError('Replay overlaps fitting/selection')
        if any(len(methods.get(m,[]))!=24 or any(r['exclusionReason'] is not None for r in methods[m]) for m in ('AI',baseline)):
            exclusions['INCOMPLETE_IDENTICAL_FORECAST_WINDOW']+=1; continue
        ai=sorted(methods['AI'],key=lambda r:r['horizon']); b=sorted(methods[baseline],key=lambda r:r['horizon'])
        if [(r['targetPeriod'],r['actual']) for r in ai]!=[(r['targetPeriod'],r['actual']) for r in b]:
            raise ValueError('Forecast methods do not share targets/truth')
        for household in households:
            payload={'householdId':household,'asOf':stamp,'modelUsableFrom':model['usableFrom'],
                'baselineValidationEnd':validation['end'],'modelVersion':model['version'],'baselineMethod':baseline,
                'truthPolicy':ai[0]['truthPolicy'],'datasetDigest':model['datasetDigest'],
                'ai':[{'periodStart':r['targetPeriod'],'usepSgdPerMwh':r['predictedUsep']} for r in ai],
                'baseline':[{'periodStart':r['targetPeriod'],'usepSgdPerMwh':r['predictedUsep']} for r in b],
                'actual':[{'periodStart':r['targetPeriod'],'usepSgdPerMwh':r['actual']} for r in ai]}
            response=post_admin('forecast-savings-replay',payload)
            if not response:
                raise ValueError('Set WATTLY_RUN_API_URL and admin credentials for the actual F2/F4 application')
            context=response['household']
            if household in contexts and contexts[household]!=context:
                raise ValueError('Household configuration changed during replay')
            contexts[household]=context
            exclusions.update(response['exclusions'])
            if response['fixedRateNotShifted']:
                fixed.add(household)
            for case in response['cases']:
                if case['caseId'] not in cases:  # Earliest preset origin; never count a run twice.
                    case['frequencyWeight']=str(Decimal(case['runsPerWeek'])/7)
                    cases[case['caseId']]=case
    totals={method:sum((Decimal(str(c[method+'SavingsSgd']))*Decimal(c['frequencyWeight']) for c in cases.values()),Decimal(0))
            for method in ('ai','baseline')}
    result={'status':'COMPLETE' if cases else 'NO_ELIGIBLE_CASES', 'modelled':True,'simulatedSavings':True,
        'aiSavingsSgd':str(totals['ai']),'baselineSavingsSgd':str(totals['baseline']),
        'aiMinusBaselineSgd':str(totals['ai']-totals['baseline']), 'baselineMethod':baseline,
        'uniqueApplianceRuns':len(cases),'fixedRateHouseholdsNotShifted':sorted(fixed),'exclusions':dict(exclusions),
        'householdContexts':contexts,'householdIds':households,'modelVersion':model['version'],
        'datasetDigest':model['datasetDigest'], 'validationEnd':validation['end'],
        'assumptions':['Current F2 project profiles held fixed as counterfactual configurations; not verified historical residents',
            'Unchanged F4 optimizer and appliance constraints; identical feasible windows and realized prices',
            'Two preset forecast origins per day (00:00,12:00SGT); earliest eligible decision per appliance run',
            'Expected runs weighted runsPerWeek/7; accepted recommendations assumed followed; no actual bill-savings claim',
            'Losses retained; fixed-rate households never shifted. Missing actual/window cases excluded for both methods.']}
    report(output,list(cases.values()),result)
    return result
