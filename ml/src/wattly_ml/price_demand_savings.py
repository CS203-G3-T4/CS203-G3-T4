"""Modelled appliance replay through the actual F4 Java calculator, never live writes."""
import argparse
from collections import defaultdict
from datetime import datetime, time, timezone
from decimal import Decimal
import json
from pathlib import Path
import shutil
import subprocess

from .data import digest, write_json
from .demand_candidate import identity
from .price_demand_training import METHODS, protocol_check
from .timebase import DAY, instant, targets


def number(value):
    try:result=Decimal(str(value))
    except Exception:raise ValueError('Invalid replay number') from None
    if not result.is_finite():raise ValueError('Non-finite replay number')
    return result


def appliances(snapshot):
    if type(snapshot.get('maxActive')) is not int or snapshot['maxActive']<1:
        raise ValueError('Freeze a positive maxActive')
    if number(snapshot['minimumSavingSgd'])<0:raise ValueError('Invalid minimum saving')
    result=[];seen=set();households=set();fixed=0
    for household in snapshot['households']:
        if type(household.get('id')) is not int or household['id']<1 or household['id'] in households:
            raise ValueError('Require unique positive household ids')
        households.add(household['id'])
        if type(household.get('exposedToWholesalePrice')) is not bool:raise ValueError('Freeze plan exposure')
        if not household['exposedToWholesalePrice']:fixed+=1;continue
        for app in household['appliances']:
            if type(app.get('id')) is not int or app['id']<1:raise ValueError('Invalid appliance id')
            key=(str(household['id']),str(app['id']))
            if key in seen:raise ValueError('Duplicate appliance in profile')
            seen.add(key)
            if not app.get('enabled',True) or app.get('flexibility','FLEXIBLE')!='FLEXIBLE' or app['runsPerWeek']==0:continue
            if (type(app['runMinutes']) is not int or not 1<=app['runMinutes']<=1440
                    or type(app['runsPerWeek']) is not int or not 0<=app['runsPerWeek']<=70
                    or not 0<number(app['powerKw'])<=50):raise ValueError('Invalid appliance power, duration or frequency')
            clocks=[time.fromisoformat(app[k]) for k in ('earliestStart','mustFinishBy')]
            usual=time.fromisoformat(app.get('usualStart') or app['earliestStart'])
            if any(t.second or t.microsecond for t in [*clocks,usual]):raise ValueError('Use minute-precision profile times')
            minutes=lambda t:t.hour*60+t.minute
            width=(minutes(clocks[1])-minutes(clocks[0]))%1440 or 1440
            if (minutes(usual)-minutes(clocks[0]))%1440+app['runMinutes']>width:
                raise ValueError('Usual run must fit the frozen appliance window')
            result.append(dict(app,householdId=key[0],id=key[1],usualStart=usual.isoformat(timespec='minutes')))
    return result,fixed


def replay(evaluation, profiles_path, source_root, output, as_of, java=None, javac=None):
    evaluation=Path(evaluation);experiment=evaluation.parent;output=Path(output);source_root=Path(source_root)
    protocol=json.loads((experiment/'protocol.json').read_text());_,_,test_start,test_end=protocol_check(protocol)
    if instant(as_of)<test_end:raise ValueError('No savings replay before the complete holdout')
    if json.loads((experiment/'evaluation-attempt.json').read_text()).get('state')!='SUCCESS':
        raise ValueError('Successful held-out price evaluation required')
    summary=json.loads((evaluation/'summary.json').read_text());profiles_path=Path(profiles_path)
    profile=json.loads(profiles_path.read_text())
    if protocol.get('savingsProfileSha256')!=digest(profiles_path):
        raise ValueError('Profiles and savings policy must be pinned in the registered protocol')
    if (summary.get('protocolSha256')!=identity(protocol)
            or profile.get('syntheticTestOnly')!=protocol.get('syntheticTestOnly',False)
            or summary.get('syntheticTestOnly')!=protocol.get('syntheticTestOnly',False)):
        raise ValueError('Evaluation, profiles and protocol must match')
    apps,fixed=appliances(profile)
    sources=[source_root/p for p in ('scripts/forecast-replay/DemandSavingsReplay.java',
        'src/main/java/sg/edu/smu/cs203/recommendation/CheapestStartCalculator.java',
        'src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java',
        'src/main/java/sg/edu/smu/cs203/household/appliance/TimeWindow.java')]
    checks={str(p):digest(p) for p in [*sources,profiles_path,evaluation/'summary.json',evaluation/'predictions.jsonl',
        experiment/'protocol.json',experiment/'evaluation-attempt.json']}
    grouped={};truth={};references={}
    with (evaluation/'predictions.jsonl').open() as stream:
        for n,line in enumerate(stream):
            if n>=500000:raise ValueError('Prediction row limit exceeded')
            row=json.loads(line);origin=instant(row['asOf']);h=row['horizon'];method=row['method']
            if (not test_start<=origin<test_end or method not in METHODS or type(h) is not int or not 1<=h<=24
                    or instant(row['targetPeriod'])!=targets(origin)[h-1]
                    or row['spikeFlag'] is not None and type(row['spikeFlag']) is not bool):
                raise ValueError('Invalid common prediction target or flag')
            prediction=number(row['predictedUsep']);actual=number(row['actual']);target=instant(row['targetPeriod'])
            threshold=number(row['threshold']) if row.get('threshold') is not None else None
            if (row.get('truthPolicy')!='PROVISIONAL' or row['spikeFlag']!=(prediction>threshold if threshold is not None else None)
                    or row.get('actualSpike')!=(actual>threshold if threshold is not None else None)):
                raise ValueError('Prediction flags or truth policy differ from their recorded threshold')
            pair=(origin,h)
            if pair in references and references[pair]!=threshold:raise ValueError('Conflicting reference for a common pair')
            references[pair]=threshold
            if target in truth and truth[target]!=actual:raise ValueError('Conflicting actuals for a common target')
            truth[target]=actual
            key=(origin,method);slots=grouped.setdefault(key,{})
            if h in slots:raise ValueError('Duplicate prediction target')
            slots[h]=row
    origins=sorted({origin for origin,_ in grouped})
    if len(origins)!=summary.get('commonOrigins') or len(origins)*24!=summary.get('commonPairs'):
        raise ValueError('Evaluation support does not match predictions')
    for origin in origins:
        for method in METHODS:
            if set(grouped.get((origin,method),{}))!=set(range(1,25)):
                raise ValueError('All six methods require the same complete pairs')
    if len(origins)*len(apps)*6>100000:raise ValueError('Appliance replay case limit exceeded')
    java=java or shutil.which('java');javac=javac or shutil.which('javac')
    if not java or not javac:raise ValueError('Java21+ and javac21+ are required')
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    try:
        classes=output/'classes';classes.mkdir()
        with (output/'compile.log').open('x') as log:
            subprocess.run([str(javac),'--release','21','-d',str(classes),*map(str,sources)],check=True,
                stdout=log,stderr=subprocess.STDOUT,timeout=60)
        requests=[];metadata=[]
        for origin in origins:
            for method in METHODS:
                rows=[grouped[(origin,method)][h] for h in range(1,25)]
                for app in apps:
                    index=len(metadata);unknown=sum(r['spikeFlag'] is None for r in rows)
                    fields=[str(index),origin.isoformat(),str(app['powerKw']),str(app['runMinutes']),
                        app['earliestStart'],app['mustFinishBy'],app['usualStart'],str(profile['minimumSavingSgd']),
                        ','.join(str(number(r['predictedUsep'])) for r in rows),
                        ','.join('1' if r['spikeFlag'] is True else '0' for r in rows),
                        ','.join(str(number(r['actual'])) for r in rows),str(unknown)]
                    if any('\t' in f or '\n' in f for f in fields):raise ValueError('Invalid profile field')
                    requests.append('\t'.join(fields));metadata.append((origin,method,app))
        result=subprocess.run([str(java),'-cp',str(classes),'sg.edu.smu.cs203.recommendation.DemandSavingsReplay'],
            input='\n'.join(requests)+'\n' if requests else '',text=True,capture_output=True,check=True,timeout=180)
        lines=result.stdout.splitlines()
        if len(lines)!=len(metadata):raise ValueError('F4 replay response count differs')
        opportunities=[];excluded=0
        for index,(line,(origin,method,app)) in enumerate(zip(lines,metadata)):
            cells=line.split('\t')
            if cells[0]!=str(index):raise ValueError('F4 replay response order differs')
            if cells[1]=='NOT_ENOUGH_FORECAST':excluded+=1;continue
            if len(cells)!=8:raise ValueError('Invalid F4 replay response')
            usual,chosen=int(cells[2]),int(cells[3]);usual_cost=number(cells[5]);chosen_cost=number(cells[6])
            opportunities.append(dict(origin=origin,method=method,app=app,usual=usual,chosen=chosen,
                move=cells[1] in ('SHIFT','AVOID'),predictedSaving=number(cells[4]),
                actualUsual=usual_cost,actualChosen=chosen_cost,unknownSpikeTargets=int(cells[7])))
        population={};by_origin=defaultdict(list)
        key=lambda r:(r['app']['householdId'],r['app']['id'],r['usual'])
        for r in opportunities:
            k=key(r);population.setdefault(k,r);by_origin[r['origin']].append(r)
            if population[k]['actualUsual']!=r['actualUsual']:raise ValueError('Actual usual cost differs across common cases')
        common=defaultdict(set)
        for row in opportunities:common[key(row)].add(row['method'])
        if any(methods!=set(METHODS) for methods in common.values()):
            raise ValueError('Every appliance run must have common coverage for all methods')
        decisions=[];table={}
        for method in METHODS:
            locked={}
            for origin,rows in sorted(by_origin.items()):
                households=defaultdict(list)
                for r in rows:
                    if r['method']==method and r['move'] and key(r) not in locked:
                        households[r['app']['householdId']].append(r)
                for rows in households.values():
                    rows.sort(key=lambda r:(-r['predictedSaving'],r['chosen'],int(r['app']['id'])))
                    for r in rows[:profile['maxActive']]:locked[key(r)]=r
            own=[]
            for k,first in sorted(population.items()):
                r=locked.get(k,first);moved=k in locked
                saving=r['actualUsual']-r['actualChosen'] if moved else Decimal(0)
                weight=Decimal(r['app']['runsPerWeek'])/7
                week=int((datetime.fromtimestamp(r['usual'],timezone.utc)-test_start)//(7*DAY))+1
                own.append(dict(method=method,householdId=k[0],applianceId=k[1],usualStart=k[2],
                    chosenStart=r['chosen'] if moved else k[2],moved=moved,savingSgd=str(saving),
                    weight=str(weight),harmful=bool(moved and saving<0),week=week,
                    unknownSpikeTargets=r['unknownSpikeTargets'],issueTime=r['origin'].isoformat() if moved else None))
            weighted=lambda rows:sum((number(r['savingSgd'])*number(r['weight']) for r in rows),Decimal(0))
            harmful=[r for r in own if r['harmful']]
            table[method]=dict(cases=len(own),moves=sum(r['moved'] for r in own),savingsSgd=str(weighted(own)),
                harmfulMoves=len(harmful),harmfulLossSgd=str(-weighted(harmful)),
                worstRunLossSgd=str(max((-number(r['savingSgd']) for r in harmful),default=Decimal(0))),
                weeklySavingsSgd=[str(weighted([r for r in own if r['week']==w])) for w in (1,2)])
            decisions.extend(own)
        for method in METHODS:
            table[method]['versusB3Sgd']=str(number(table[method]['savingsSgd'])-number(table['B3']['savingsSgd']))
            table[method]['weeklyVersusB3Sgd']=[str(number(a)-number(b)) for a,b in zip(table[method]['weeklySavingsSgd'],table['B3']['weeklySavingsSgd'])]
        if any(digest(Path(p))!=h for p,h in checks.items()):raise ValueError('Replay inputs or F4 sources changed')
        report=dict(status='MODELED_F4_REPLAY_COMPLETE' if population else 'INSUFFICIENT_COMMON_APPLIANCE_CASES',
            savingsComparisonSupported=bool(population),syntheticTestOnly=protocol.get('syntheticTestOnly',False),
            protocolSha256=identity(protocol),methods=table,sourceHashes=checks,commonCases=len(population),
            fixedRateHouseholdsNotShifted=fixed,uncoveredOpportunities=excluded,minimumSavingSgd=str(profile['minimumSavingSgd']),
            maxActive=profile['maxActive'],automaticPromotion=False,measuredHouseholdBills=False,
            assumptions=['Frozen profiles are counterfactual, not independently verified historical configuration',
                'Real F4 calculator; top-three per refresh; first suggested move is assumed accepted and locks that run',
                'Each appliance/usual run counted once; frequency weight is runsPerWeek/7',
                'Unknown spike flags permit ordinary price planning but are never graded as normal negatives',
                'No delivered-notification, snooze or actual resident-adoption claims'])
        with (output/'decisions.jsonl').open('x') as f:
            for row in decisions:f.write(json.dumps(row,allow_nan=False)+'\n')
        write_json(output/'summary.json',report)
        return report
    except Exception as exc:
        write_json(output/'failed.json',dict(status='FAILED',errorType=type(exc).__name__))
        raise


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('evaluation','profiles','source-root','output'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--as-of',required=True);p.add_argument('--java');p.add_argument('--javac');a=p.parse_args()
    result=replay(a.evaluation,a.profiles,a.source_root,a.output,a.as_of,a.java,a.javac)
    print(json.dumps(result,indent=2,allow_nan=False))


if __name__=='__main__':main()
