"""Reproducible baseline and spike studies; no model or threshold selection on test data."""
import calendar
from collections import Counter
from pathlib import Path

from .backtest import History, backtest, origins, scores
from .cli import report
from .data import load_dataset, write_json, truth
from .spikes import DEFAULT_CONFIG, reference
from .timebase import DAY, STEP, instant


def baseline_study(dataset: Path, start, end, output: Path, retrospective=False):
    rows, manifest = load_dataset(dataset)
    if manifest['quality']['parsingFailures']:
        raise ValueError('Repair parsing failures before evaluation')
    records = backtest(rows,start,end,policy='FINAL' if retrospective else 'PROVISIONAL',retrospective=retrospective,truth_cutoff=end)
    result = scores(records)
    order=sorted(('B1','B2','B3'),key=lambda m:(result['table'][m]['mae'],m)) if result['commonPairs'] else []
    result.update(selectedBaseline=order[0] if order else None,baselineRanking=order,
                  baselineSelection='COMMON_PAIR_SCOREBOARD' if order else 'UNRANKED')
    month=start.month+3; year=start.year+(month-1)//12; month=(month-1)%12+1
    three_months=start.replace(year=year,month=month,day=min(start.day,calendar.monthrange(year,month)[1]))
    complete = end>=three_months and instant(manifest['quality']['from'])<=start-7*DAY and result['commonOriginDays']>=90
    result.update(start=start.isoformat(),end=end.isoformat(),datasetDigest=manifest['sha256'],
        evaluationKind='RETROSPECTIVE_FINAL_PRICE' if retrospective else 'OPERATIONAL_AS_OF',
        exclusions={m:dict(Counter(r['exclusionReason'] for r in records if r['method']==m and r['exclusionReason'])) for m in ('B1','B2','B3')},
        expectedPairs=len(list(origins(start,end)))*24,threeMonthAcceptance=complete,
        comparison='Only B1/B2/B3 identical eligible origin/target pairs are ranked. Revisions are not observations.')
    report(output,records,result)
    if result['commonPairs']:
        write_json(output/'baseline-validation.json',{'id':output.name+'-'+manifest['sha256'][:12], 'mode':'LIVE',
            'start':start.isoformat(),'end':end.isoformat(),'datasetDigest':manifest['sha256'],'ranking':order,
            'table':result['table'],'commonPairs':result['commonPairs'],'commonOriginDays':result['commonOriginDays'],
            'evaluationKind':result['evaluationKind']})
    return result


def sensitivity(dataset: Path,start,end,output: Path,retrospective=False):
    rows,manifest=load_dataset(dataset)
    labels=truth(rows,'FINAL' if retrospective else 'PROVISIONAL',end,retrospective)
    history=History(rows,retrospective)
    summaries=[]
    for k in (2,3,4):
        counts=Counter(); positives=[]
        for target in origins(start,end):
            past=history.at(target)
            ref=reference(past,target,target,{**DEFAULT_CONFIG,'k':k})
            if target not in labels: counts['missingTruth']+=1
            elif not ref['available']: counts['insufficientReference']+=1
            else:
                counts['assessedTargets']+=1
                if labels[target]>ref['threshold']:
                    positives.append(target)
        summaries.append({'k':k,**dict(counts),'distinctAlertTargets':len(positives),
            'distinctAlertEvents':sum(i==0 or t-positives[i-1]>STEP for i,t in enumerate(positives)),
            'precision':None,'recall':None,'falseAlarms':None,
            'unavailableReason':'No independent labels identifying genuine unusual-price events; rule self-labels cannot validate themselves'})
    result={'datasetDigest':manifest['sha256'],'start':start.isoformat(),'end':end.isoformat(),
        'evaluationKind':'RETROSPECTIVE_FINAL_PRICE' if retrospective else 'OPERATIONAL_AS_OF',
        'reference':'Same half-hour over previous28days, median + k * max(1,1.4826*MAD)',
        'sensitivity':summaries,'recommendation':'Retain k=3, minimum14 same-slot samples and spreadFloor=1 pending team review; no threshold selected from protected test outcomes',
        'teamApproved':False,'limitations':['Descriptive alert counts, not independently validated genuine-spike precision/recall.']}
    report(output,[],result)
    return result
