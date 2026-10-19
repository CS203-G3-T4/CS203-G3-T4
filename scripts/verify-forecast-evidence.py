"""Independent stdlib recalculation of reported errors and realized schedule costs.

Run after the frozen studies and household replay. No model fitting or application imports.
"""
import argparse
from collections import defaultdict
from datetime import datetime,timedelta,time
from decimal import Decimal
import json
from pathlib import Path
from statistics import median
from zoneinfo import ZoneInfo

SGT=ZoneInfo('Asia/Singapore'); STEP=timedelta(minutes=30)


def read(path):
    return json.loads(path.read_text())


def metric_check(directory,canonical):
    summary=read(directory/'summary.json')
    by_method=defaultdict(dict)
    for line in (directory/'predictions.jsonl').open():
        row=json.loads(line)
        if row['exclusionReason'] is not None: continue
        key=(row['asOf'],row['horizon'])
        assert key not in by_method[row['method']], 'Duplicate eligible forecast pair'
        by_method[row['method']][key]=row
    common=set.intersection(*(set(rows) for rows in by_method.values()))
    assert len(common)==summary['commonPairs']>0
    end=datetime.fromisoformat(summary['end']); metrics={}; weekly=defaultdict(dict)
    start=datetime.fromisoformat(summary['start'])
    for method,rows in by_method.items():
        errors=[]; groups=defaultdict(list); tp=fp=fn=0; positives=set(); negatives=set()
        for key in common:
            row=rows[key]; target=datetime.fromisoformat(row['targetPeriod'])
            assert target+STEP<=end and Decimal(str(row['actual']))==canonical[target]
            origin=datetime.fromisoformat(row['asOf'])
            expected=origin.replace(minute=origin.minute//30*30,second=0,microsecond=0)+row['horizon']*STEP
            assert target==expected
            error=abs(Decimal(str(row['predictedUsep']))-Decimal(str(row['actual'])))
            errors.append(error)
            group='1-6' if row['horizon']<=6 else '7-12' if row['horizon']<=12 else '13-24'
            groups[group].append(error)
            week=(origin-start).days//7
            weekly[week].setdefault(method,[]).append(error)
            if row['threshold'] is not None:
                predicted=Decimal(str(row['predictedUsep']))>Decimal(str(row['threshold']))
                actual=Decimal(str(row['actual']))>Decimal(str(row['threshold']))
                assert predicted==row['spikeFlag'] and actual==row['actualSpike']
                tp+=predicted and actual;fp+=predicted and not actual;fn+=not predicted and actual
                (positives if actual else negatives).add(target)
        mae=sum(errors)/len(errors)
        assert abs(mae-Decimal(str(summary['table'][method]['mae'])))<Decimal('0.0000001')
        for group,values in groups.items():
            assert abs(sum(values)/len(values)-Decimal(str(summary['table'][method]['groups'][group])))<Decimal('0.0000001')
        spikes=summary['table'][method]['spikes']
        assert (tp,fp,fn)==(spikes['tp'],spikes['fp'],spikes['fn'])
        assert (len(positives),len(negatives))==(spikes['distinctPositiveTargets'],spikes['distinctNegativeTargets'])
        metrics[method]={'mae':float(mae),'groups':{g:float(sum(v)/len(v)) for g,v in groups.items()},'spikes':spikes}
    week_metrics=[{'block':week+1,'pairs':len(next(iter(methods.values()))),
                   'mae':{m:float(sum(v)/len(v)) for m,v in methods.items()}} for week,methods in sorted(weekly.items())]
    return {'commonPairs':len(common),'expectedPairs':int((end-start)/STEP)*24,
            'metrics':metrics,'weeklyBlocks':week_metrics,'scoresIndependentlyRecomputed':True}


def cost(start,run,prices):
    cursor=datetime.fromisoformat(start); end=cursor+timedelta(minutes=run['runMinutes']); total=Decimal(0)
    while cursor<end:
        period=cursor.replace(minute=cursor.minute//30*30,second=0,microsecond=0)
        finish=min(period+STEP,end)
        assert period in prices, 'Never fill missing actual prices'
        total+=Decimal(str(run['powerKw']))*Decimal(int((finish-cursor).total_seconds()))/3600*prices[period]/1000
        cursor=finish
    return total


def savings_check(directory):
    summary=read(directory/'summary.json');totals={'ai':Decimal(0),'baseline':Decimal(0)}; seen=set()
    for line in (directory/'predictions.jsonl').open():
        case=json.loads(line); assert case['caseId'] not in seen;seen.add(case['caseId'])
        prices={datetime.fromisoformat(p['periodStart']):Decimal(str(p['usepSgdPerMwh'])) for p in case['actual']}
        run=case['run']; origin=datetime.fromisoformat(case['asOf']); usual_start=datetime.fromisoformat(case['usualStart']).astimezone(SGT)
        opens=datetime.combine(usual_start.date(),time.fromisoformat(run['earliestStart']),SGT)
        if opens>usual_start: opens-=timedelta(days=1)
        closes=datetime.combine(opens.date(),time.fromisoformat(run['mustFinishBy']),SGT)
        if closes<=opens: closes+=timedelta(days=1)
        for method in ('ai','baseline'):
            selected=datetime.fromisoformat(case[method+'Start'])
            assert origin<selected and opens<=selected and selected+timedelta(minutes=run['runMinutes'])<=closes
            if selected!=usual_start: assert selected.minute%30==0 and selected.second==0
        usual=cost(case['usualStart'],case['run'],prices)
        for method in ('ai','baseline'):
            realized=cost(case[method+'Start'],case['run'],prices)
            assert abs(realized-Decimal(str(case[method+'CostSgd'])))<Decimal('0.0000001')
            savings=usual-realized
            assert abs(savings-Decimal(str(case[method+'SavingsSgd'])))<Decimal('0.0000001')
            totals[method]+=savings*Decimal(case['frequencyWeight'])
    assert len(seen)==summary['uniqueApplianceRuns']
    for method in totals:
        assert abs(totals[method]-Decimal(summary[method+'SavingsSgd']))<Decimal('0.00001')
    return {'uniqueApplianceRuns':len(seen),'aiSavingsSgd':str(totals['ai']),
            'baselineSavingsSgd':str(totals['baseline']),'realizedCostsIndependentlyRecomputed':True,
            'modelled':True,'simulatedSavings':True}


def sensitivity_check(directory,prices):
    report=read(directory/'summary.json'); start=datetime.fromisoformat(report['start']);end=datetime.fromisoformat(report['end'])
    for entry in report['sensitivity']:
        alerts=[];assessed=0;target=start
        while target<end:
            history=[prices[target-timedelta(days=d)] for d in range(1,29) if target-timedelta(days=d) in prices]
            if target in prices and len(history)>=14:
                assessed+=1; typical=median(history); spread=max(Decimal(1),Decimal('1.4826')*median([abs(p-typical) for p in history]))
                if prices[target]>typical+entry['k']*spread: alerts.append(target)
            target+=STEP
        assert assessed==entry['assessedTargets'] and len(alerts)==entry['distinctAlertTargets']
        assert sum(i==0 or t-alerts[i-1]>STEP for i,t in enumerate(alerts))==entry['distinctAlertEvents']
    return {'independentlyRecomputed':True,'sensitivity':report['sensitivity']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path,help='Acceptance artifact root containing datasets and reports')
    args=parser.parse_args();root=args.root
    canonical={datetime.fromisoformat(r['periodStart']):Decimal(str(r['usep']))
               for r in map(json.loads,(root/'datasets/official-final-v1/prices.jsonl').open())}
    baseline=metric_check(root/'reports/baselines-three-months-v2',canonical)
    validation=metric_check(root/'reports/validation-v1',canonical)
    test=metric_check(root/'reports/unseen-weeks-v1',canonical)
    savings=savings_check(root/'reports/household-savings-v1')
    result={'status':'PASS','baselines':baseline,'validation':validation,'unseenWeeks':test,'savings':savings,
            'sensitivity':sensitivity_check(root/'reports/spike-sensitivity-v1',canonical)}
    (root/'independent-verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'status':'PASS','baselinePairs':baseline['commonPairs'],'testPairs':test['commonPairs'],
                      'savings':savings},indent=2))


if __name__=='__main__':main()
