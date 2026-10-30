"""One fixed saved-forecast comparison; no fitting, thresholds or promotion."""
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP
from collections import defaultdict
import gzip, hashlib, json, shutil, subprocess
import numpy as np

import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--research-root',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True,help='New immutable output directory')
parser.add_argument('--javac',default=shutil.which('javac'),help='Compiler supporting --release 21 (default: PATH)')
parser.add_argument('--java',default=shutil.which('java'),help='Java 21+ runtime (default: PATH)')
args=parser.parse_args()
RESEARCH=args.research_root.resolve()
DELIVERY=Path(__file__).resolve().parents[1]
SOURCE=RESEARCH/'.local/bounded-recommendation-2026-10-29-v1'
BLEND=RESEARCH/'.local/raw-score-blend-2026-10-27-v1/study'
OUT=args.output.resolve()
METHODS=('SOLAR_MEAN','RAW_SCORE_BLEND','B3')
FROZEN_PRICE_FORECAST_SHA256='ccf3cccf373a4b045a061b1d869661704824ed7166cc4041a3185ebf3962315f'
def read(p): return json.loads(p.read_text())
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,x):
    with p.open('x') as f: json.dump(x,f,indent=2,allow_nan=False);f.write('\n')

def evaluation_opportunities(path):
    with gzip.open(path,'rt') as stream:
        return [row for line in stream if (row:=json.loads(line)).get('evaluation') is True]

def replay_sources(research,delivery):
    # Freeze the original replay DTO; live provenance fields do not change run costs.
    relative='src/main/java/sg/edu/smu/cs203/'
    sources=[]
    for name in ('household/appliance/TimeWindow.java','recommendation/CheapestStartCalculator.java'):
        rel=relative+name
        if sha(research/rel)!=sha(delivery/rel):
            raise ValueError('Delivery calculator differs; stop instead of mixing versions')
        sources.append(delivery/rel)
    original=research/(relative+'recommendation/PriceForecast.java')
    frozen=delivery/'scripts/forecast-replay/PriceForecast.java'
    if sha(original)!=FROZEN_PRICE_FORECAST_SHA256 or sha(frozen)!=FROZEN_PRICE_FORECAST_SHA256:
        raise ValueError('Frozen replay PriceForecast changed')
    return sources+[frozen]

def cost(origin,start,minutes,power,prices):
    # Independent time-overlap calculation; never uses future actuals for planning.
    end=start+minutes*60; total=Decimal(0)
    for h,p in enumerate(prices):
        lo=origin+(h+1)*1800; overlap=max(0,min(end,lo+1800)-max(start,lo))
        total+=Decimal(str(power))*Decimal(overlap)*Decimal(str(p))/Decimal(3600000)
    if start<origin+1800 or end>origin+25*1800: raise ValueError('Uncovered run')
    return total

def main():
    if not args.javac or not args.java:
        parser.error('Set --javac and --java, or put Java tools on PATH')
    OUT.mkdir()  # write-once; no automatic retry
    sources=[SOURCE/'inputs.npz',SOURCE/'cohort.json',SOURCE/'plan.json',SOURCE/'summary.json',SOURCE/'F4Replay.java',SOURCE/'raw/opportunities.jsonl.gz',BLEND/'protocol.json',BLEND/'verification.json',Path(__file__)]
    java=replay_sources(RESEARCH,DELIVERY)
    sources.extend(java)
    sources.append(RESEARCH/'src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java')
    for w in range(1,9): sources.append(BLEND/f'week-{w}/predictions.jsonl')
    checks={str(p):sha(p) for p in sources}
    write(OUT/'protocol.json',dict(methods=METHODS,sourceChecksums=checks,priceOnlyComparison=True,warningPolicy='Retain saved solar warning flags for BOTH AI candidates; B3 retains own flags',minimumSavingSgd='0.05',selection='Report one fixed comparison; no threshold/model search or live promotion',evaluation='Previously examined June1-July26 2026 development weeks; no fresh validation',householdPolicy='Same 600 cases, original usual start, windows, energy, frequency and refresh opportunities; first acceptance locks; top3/household/refresh',stopping='Stop after verification; no follow-on experiment'))
    with np.load(SOURCE/'inputs.npz') as z: data={k:z[k] for k in z.files}
    forecasts={}
    for w in range(1,9):
        for line in (BLEND/f'week-{w}/predictions.jsonl').open():
            r=json.loads(line); t=int(__import__('datetime').datetime.fromisoformat(r['asOf']).timestamp()); h=r['horizon']-1
            if t not in forecasts: forecasts[t]=[None]*24
            assert forecasts[t][h] is None
            forecasts[t][h]=r['prices']['RAW_SCORE_BLEND']
    expected=set(map(int,data['origins'][data['evaluation']]))
    assert set(forecasts)==expected and all(None not in v for v in forecasts.values())
    cohort=read(SOURCE/'cohort.json'); apps={a['id']:a for a in cohort['appliances']}
    rows=evaluation_opportunities(SOURCE/'raw/opportunities.jsonl.gz')
    if not rows:raise ValueError('No evaluation opportunities in the supplied immutable research bundle')
    from datetime import datetime
    plan=read(SOURCE/'plan.json')
    end=int(datetime.fromisoformat(plan['evaluation']['endExclusive']).timestamp())
    rows=[r for r in rows if r['usual']+r['minutes']*60<=end]
    adapter=OUT/'F4Replay.java'; shutil.copyfile(SOURCE/'F4Replay.java',adapter)
    classes=OUT/'classes'; classes.mkdir()
    subprocess.run([args.javac,'--release','21','-d',str(classes),*map(str,java),str(adapter)],check=True)
    inp=OUT/'blend-input.tsv'; output=OUT/'blend-output.tsv'
    with inp.open('x') as f:
        for n,r in enumerate(rows):
            a=apps[r['applianceId']]; i=r['index']; p=forecasts[r['origin']]
            assert int(data['origins'][i])==r['origin']
            flags=data['controlPrices'][i]>data['canonicalThresholds'][i]
            f.write('\t'.join([str(n),str(r['origin']),a['powerKw'],str(a['runMinutes']),a['earliest'],a['finish'],a['usual'],','.join(map(str,p)),','.join('1' if v else '0' for v in flags)])+'\n')
    with inp.open('rb') as f,output.open('xb') as o:
        subprocess.run([args.java,'-cp',str(classes),'sg.edu.smu.cs203.recommendation.F4Replay'],stdin=f,stdout=o,timeout=180,check=True)
    raw=output.read_text().splitlines(); assert len(raw)==len(rows)
    checks_done=0
    for n,(r,line) in enumerate(zip(rows,raw)):
        cols=line.split('\t'); assert len(cols)==11 and cols[0]==str(n)
        assert cols[1]!='NOT_ENOUGH_FORECAST' and int(cols[2])==r['usual']
        assert cols[10]==r['feasibleStarts'] and int(cols[8])==r['opens'] and int(cols[9])==r['closes']
        a=apps[r['applianceId']]; i=r['index']; p=forecasts[r['origin']]
        move=cols[1] in ('SHIFT','AVOID'); chosen=int(cols[3]) if move else r['usual']
        proposed=cost(r['origin'],chosen,r['minutes'],a['powerKw'],p)
        usual=cost(r['origin'],r['usual'],r['minutes'],a['powerKw'],p)
        if move:
            # Result DTO rounds display costs to four decimals; actual replay costs stay exact.
            assert proposed.quantize(Decimal('.0001'),rounding=ROUND_HALF_UP)==Decimal(cols[5])
            assert usual.quantize(Decimal('.0001'),rounding=ROUND_HALF_UP)==Decimal(cols[4])
        actual=cost(r['origin'],chosen,r['minutes'],a['powerKw'],data['actual'][i])
        orig=cost(r['origin'],r['usual'],r['minutes'],a['powerKw'],data['actual'][i])
        for m in ('AI','B3'):
            assert abs(orig-Decimal(r[m]['actualUsual']))<Decimal('0.0000001')
            assert abs(cost(r['origin'],r[m]['chosen'],r['minutes'],a['powerKw'],data['actual'][i])-Decimal(r[m]['actualChosen']))<Decimal('0.0000001')
            checks_done+=2
        r['SOLAR_MEAN']=r['AI']
        r['RAW_SCORE_BLEND']=dict(move=move,chosen=chosen,predictedSaving=cols[6] if move else '0',actualUsual=str(orig),actualChosen=str(actual),outcome=cols[1],usualEvents=r['AI']['usualEvents'])
        checks_done+=4
    by_origin=defaultdict(list); initial={}
    key=lambda r:(r['householdId'],r['applianceId'],r['usual'])
    for r in rows: by_origin[r['origin']].append(r); initial.setdefault(key(r),r)
    assert len(initial)==600
    summaries={}; decisions=[]; start=int(datetime.fromisoformat(plan['calibration']['endExclusive']).timestamp())
    for method in METHODS:
        locked={}
        for origin,group in sorted(by_origin.items()):
            groups=defaultdict(list)
            for r in group:
                if key(r) not in locked and r[method]['move']: groups[r['householdId']].append(r)
            for g in groups.values():
                g.sort(key=lambda r:(-Decimal(r[method]['predictedSaving']),r[method]['chosen'],r['applianceId']))
                for r in g[:3]: locked[key(r)]=r
        records=[]
        for k,first in sorted(initial.items()):
            r=locked.get(k,first); m=r[method]; moved=k in locked
            saving=Decimal(m['actualUsual'])-Decimal(m['actualChosen']) if moved else Decimal(0)
            week=(first['usual']-start)//604800+1; assert 1<=week<=8
            rec=dict(method=method,householdId=k[0],applianceId=k[1],usual=k[2],chosen=m['chosen'] if moved else k[2],notifiedAt=r['origin'] if moved else None,week=week,weight=str(first['weight']),moved=moved,saving=str(saving),harmful=bool(moved and saving<0),falseNotification=bool(moved and (saving<=0 or(m['outcome']=='AVOID' and not m['usualEvents']))))
            records.append(rec)
        weighted=lambda rs:sum((Decimal(r['saving'])*Decimal(r['weight']) for r in rs),Decimal(0))
        harmful=[r for r in records if r['harmful']]
        summaries[method]=dict(cases=len(records),moves=sum(r['moved'] for r in records),savingsSgd=str(weighted(records)),harmfulMoves=len(harmful),harmfulLossSgd=str(-weighted(harmful)),worstIndividualLossSgd=str(max((-Decimal(r['saving']) for r in harmful),default=Decimal(0))),falseNotifications=sum(r['falseNotification'] for r in records),weeklySavingsSgd=[str(weighted([r for r in records if r['week']==w])) for w in range(1,9)])
        decisions+=records
    old=read(SOURCE/'summary.json')['methods']
    for name,prior in [('SOLAR_MEAN','AI'),('B3','B3')]:
        previous=old['EXISTING_F4:'+prior]; now=summaries[name]
        assert abs(float(now['savingsSgd'])-previous['simulatedSavingsSgd'])<1e-8
        assert now['harmfulMoves']==previous['harmfulMoves'] and now['falseNotifications']==previous['falseNotificationEpisodes']
    for m in METHODS[:2]:
        diffs=[Decimal(a)-Decimal(b) for a,b in zip(summaries[m]['weeklySavingsSgd'],summaries['B3']['weeklySavingsSgd'])]
        summaries[m]['versusBaseline']=dict(totalSgd=str(sum(diffs)),wins=sum(v>Decimal('.0001') for v in diffs),losses=sum(v<Decimal('-.0001') for v in diffs),ties=sum(abs(v)<=Decimal('.0001') for v in diffs),weeklyDifferenceSgd=list(map(str,diffs)))
    assert all(sha(Path(p))==h for p,h in checks.items())
    write(OUT/'summary.json',dict(status='VERIFIED_DEVELOPMENT_COMPARISON',methods=summaries,noFits=True,noPromotion=True,retrospective=True,scalarCostsChecked=checks_done,sourceChecksumsUnchanged=True,warningPolicy='Frozen solar flags on both AI candidates',stopping='Comparison complete; no further tuning'))
    with gzip.open(OUT/'decisions.jsonl.gz','xt') as f:
        for r in decisions: f.write(json.dumps(r)+'\n')
    write(OUT/'verification.json',dict(status='PASS',costChecks=checks_done,matchedCases=600,earlierSolarAndBaselineResultsReproduced=True,allSourcesUnchanged=True,outputChecksums={p.name:sha(p) for p in OUT.iterdir() if p.is_file()}))
    print(json.dumps(summaries,indent=2))
if __name__=='__main__': main()
