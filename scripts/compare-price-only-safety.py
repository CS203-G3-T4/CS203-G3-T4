"""Fixed six-arm recommendation-safety replay. No model fit or runtime mutation."""
from pathlib import Path
from datetime import datetime,timezone
from decimal import Decimal
from fractions import Fraction
from collections import defaultdict
import gzip,hashlib,json,subprocess
import numpy as np

import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scenario-root',type=Path,required=True,help='Private bounded-recommendation scenario directory')
parser.add_argument('--price-study',type=Path,required=True,help='Saved recency study with week-1..8 and manifests')
parser.add_argument('--reference-replay',type=Path,required=True,help='Previously verified PRICE_ONLY/B3 run-v2 directory')
parser.add_argument('--output',type=Path,required=True,help='New empty output directory; existing reports are never overwritten')
parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--javac',default='javac')
parser.add_argument('--java',default='java')
args=parser.parse_args()
OUT=args.output.resolve();OUT.mkdir(parents=True,exist_ok=True)
if any(OUT.iterdir()):parser.error('--output must be empty')
REPO=args.repo.resolve();SOURCE=args.scenario_root.resolve()
PREVIOUS=args.reference_replay.resolve();PRICE=args.price_study.resolve()
ARMS={'AI_005':('PRICE_ONLY','0.05',False),'AI_010':('PRICE_ONLY','0.10',False),
 'AI_005_AGREE':('PRICE_ONLY','0.05',True),'AI_010_AGREE':('PRICE_ONLY','0.10',True),
 'B3_005':('B3','0.05',False),'B3_010':('B3','0.10',False)}
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(name,data):
 with (OUT/name).open('x') as f:json.dump(data,f,indent=2,allow_nan=False);f.write('\n')
def cost(origin,start,minutes,power,prices):
 assert start>=origin+1800 and start+minutes*60<=origin+25*1800
 total=Decimal(0)
 for h,p in enumerate(prices):
  lo=origin+1800*(h+1);seconds=max(0,min(start+minutes*60,lo+1800)-max(start,lo))
  total+=Decimal(str(power))*Decimal(seconds)*Decimal(str(p))/Decimal(3600000)
 return total
def main():
 sources=[Path(__file__),SOURCE/'inputs.npz',SOURCE/'cohort.json',SOURCE/'plan.json',SOURCE/'raw/opportunities.jsonl.gz',
  PREVIOUS/'protocol.json',PREVIOUS/'summary.json',PREVIOUS/'verification.json',PREVIOUS/'F4Replay.java',
  PREVIOUS/'PRICE_ONLY-input.tsv',PREVIOUS/'B3-input.tsv',REPO/'src/main/java/sg/edu/smu/cs203/recommendation/CheapestStartCalculator.java',
  REPO/'src/main/java/sg/edu/smu/cs203/household/appliance/TimeWindow.java',REPO/'scripts/forecast-replay/PriceForecast.java']
 sources += [PRICE/f'week-{w}/predictions.jsonl' for w in range(1,9)]
 hashes={str(p):sha(p) for p in sources}
 prior_protocol=read(PREVIOUS/'protocol.json')
 # Match logical input suffixes, allowing archives to move between computers.
 for rel in ['inputs.npz','cohort.json','plan.json','raw/opportunities.jsonl.gz']:
  matches=[v for k,v in prior_protocol['sourceChecksums'].items() if k.endswith('/'+rel)]
  assert len(matches)==1 and sha(SOURCE/rel)==matches[0], 'Scenario input differs from reference replay'
 checks=read(PRICE/'artifact-checksums.json')
 study=read(PRICE/'results.json');windows=read(PRICE/'protocol.json')['evaluationWeeks']
 for w,window in enumerate(windows,1):
  assert sha(PRICE/f'week-{w}/predictions.jsonl')==checks[f'week-{w}/predictions.jsonl']
  assert datetime.fromisoformat(study['folds'][w-1]['training']['PRICE_183D']['lastTrainingTargetEnd'])<=datetime.fromisoformat(window['start'])
 oldverify=read(PREVIOUS/'verification.json')
 for m in ['PRICE_ONLY','B3']:assert sha(PREVIOUS/(m+'-input.tsv'))==oldverify['outputChecksums'][m+'-input.tsv']
 for p in sources[11:14]:
  matches=[v for k,v in prior_protocol['sourceChecksums'].items() if k.endswith('/'+p.relative_to(REPO).as_posix())]
  assert len(matches)==1 and sha(p)==matches[0], 'F4 calculator changed'
 write('protocol.json',{'createdAt':datetime.now(timezone.utc).isoformat(),'arms':ARMS,'sourceSha256':hashes,
  'comparison':'Same fixed 600-case cohort and all 12064 eligible refresh opportunities; own forecast flags, no new price model.',
  'minimumSaving':'0.05 existing control versus owner-proposed 0.10 SGD; applied inside unchanged Java calculator before choosing the result.',
  'agreement':'For AI arms only, reject its proposed start when exact B3 forecast cost at that same start yields <=0 saving versus the usual start. Different preferred starts alone are not disagreement.',
  'ranking':'After filtering, top 3 by AI predicted saving per household/refresh; original deterministic ties; first accepted plan locks. Rejected plans may qualify on later refreshes.',
  'labels':'Canonical same-time statistical spike proxy; not new human truth.',
  'dates':prior_protocol['dates'],'scope':'Previously examined chronological development forecasts; simulated adherence/cost, not fresh validation or real bills.',
  'promotion':'No automatic adoption or change to the frozen live trial.',
  'stopping':'All six fixed arms once; no threshold grid, model fitting or follow-on search, including if no policy dominates.',
  'filtersNeverUseActuals':True})
 cohort=read(SOURCE/'cohort.json');apps={a['id']:a for a in cohort['appliances']}
 end=int(datetime.fromisoformat(read(SOURCE/'plan.json')['evaluation']['endExclusive']).timestamp())
 with gzip.open(SOURCE/'raw/opportunities.jsonl.gz','rt') as f:
  rows=[r for line in f if (r:=json.loads(line)).get('evaluation') is True and r['usual']+r['minutes']*60<=end]
 assert len(rows)==12064
 with np.load(SOURCE/'inputs.npz') as z:data={k:z[k] for k in z.files}
 price={}
 for w in range(1,9):
  for line in (PRICE/f'week-{w}/predictions.jsonl').open():
   r=json.loads(line)
   if r['method']=='PRICE_183D':
    t=int(datetime.fromisoformat(r['asOf']).timestamp());price.setdefault(t,[None]*24)[r['horizon']-1]=r['predictedUsep']
 for method in ['PRICE_ONLY','B3']:
  lines=(PREVIOUS/(method+'-input.tsv')).read_text().splitlines();assert len(lines)==len(rows)
  for n,(line,r) in enumerate(zip(lines,rows)):
   parts=line.split('\t');a=apps[r['applianceId']];assert len(parts)==9 and int(parts[0])==n and int(parts[1])==r['origin']
   assert parts[2:7]==[a['powerKw'],str(a['runMinutes']),a['earliest'],a['finish'],a['usual']]
   expected=price[r['origin']] if method=='PRICE_ONLY' else data['baseline'][r['index']]
   assert np.array_equal(np.array(list(map(float,parts[7].split(',')))),expected)
   assert [x=='1' for x in parts[8].split(',')]==list(np.asarray(expected)>data['canonicalThresholds'][r['index']])
 classes=OUT/'classes';classes.mkdir()
 adapter=(PREVIOUS/'F4Replay.java').read_text().replace('new BigDecimal("0.05")','new BigDecimal(args[0])')
 assert adapter!=(PREVIOUS/'F4Replay.java').read_text()
 (OUT/'F4Replay.java').write_text(adapter)
 subprocess.run([args.javac,'--release','21','-d',str(classes),*map(str,sources[11:14]),str(OUT/'F4Replay.java')],check=True)
 proposals={}
 for method in ['PRICE_ONLY','B3']:
  for minimum in ['0.05','0.10']:
   path=OUT/(method+'-'+minimum+'-output.tsv')
   with (PREVIOUS/(method+'-input.tsv')).open('rb') as f,path.open('xb') as o:
    subprocess.run([args.java,'-cp',str(classes),'sg.edu.smu.cs203.recommendation.F4Replay',minimum],stdin=f,stdout=o,check=True,timeout=180)
   lines=path.read_text().splitlines();assert len(lines)==len(rows)
   if minimum=='0.05':assert lines==(PREVIOUS/(method+'-output.tsv')).read_text().splitlines(),'Existing decisions drifted'
   props=[]
   for n,(r,line) in enumerate(zip(rows,lines)):
    c=line.split('\t');assert int(c[0])==n and int(c[2])==r['usual'] and c[10]==r['feasibleStarts']
    moved=c[1] in ['SHIFT','AVOID'];chosen=int(c[3]) if moved else r['usual']
    pp=price[r['origin']] if method=='PRICE_ONLY' else data['baseline'][r['index']]
    saving=cost(r['origin'],r['usual'],r['minutes'],apps[r['applianceId']]['powerKw'],pp)-cost(r['origin'],chosen,r['minutes'],apps[r['applianceId']]['powerKw'],pp)
    if moved:
     assert chosen in list(map(int,r['feasibleStarts'].split(',')))
     assert abs(saving-Decimal(c[6]))<=Decimal('.0000501') and Decimal(c[6])>=Decimal(minimum)
    b3=data['baseline'][r['index']]
    b3_saving=cost(r['origin'],r['usual'],r['minutes'],apps[r['applianceId']]['powerKw'],b3)-cost(r['origin'],chosen,r['minutes'],apps[r['applianceId']]['powerKw'],b3)
    props.append({'move':moved,'chosen':chosen,'saving':c[6] if moved else '0','outcome':c[1],'b3Saving':str(b3_saving)})
   proposals[method,minimum]=props
 def key(r):return r['householdId'],r['applianceId'],r['usual']
 by_origin=defaultdict(list);initial={}
 for n,r in enumerate(rows):by_origin[r['origin']].append(n);initial.setdefault(key(r),n)
 assert len(initial)==600
 start=int(datetime.fromisoformat(read(SOURCE/'plan.json')['calibration']['endExclusive']).timestamp())
 summaries={};decisions=[];arm_records={}
 for arm,(method,minimum,agree) in ARMS.items():
  locked={};rejected=0
  for origin,group in sorted(by_origin.items()):
   candidates=defaultdict(list)
   for n in group:
    r=rows[n];p=proposals[method,minimum][n]
    if key(r) in locked or not p['move']:continue
    if agree and Decimal(p['b3Saving'])<=0:rejected+=1;continue
    candidates[r['householdId']].append(n)
   for indexes in candidates.values():
    indexes.sort(key=lambda n:(-Decimal(proposals[method,minimum][n]['saving']),proposals[method,minimum][n]['chosen'],rows[n]['applianceId']))
    for n in indexes[:3]:locked[key(rows[n])]=n
  records=[]
  for k,first in sorted(initial.items()):
   n=locked.get(k,first);r=rows[n];p=proposals[method,minimum][n];moved=k in locked;chosen=p['chosen'] if moved else r['usual']
   a=apps[r['applianceId']];actual=data['actual'][r['index']]
   saving=cost(r['origin'],r['usual'],r['minutes'],a['powerKw'],actual)-cost(r['origin'],chosen,r['minutes'],a['powerKw'],actual)
   # Independent exact rational recomputation of each final outcome.
   def rational(s):
    total=Fraction(0)
    for h,value in enumerate(actual):
     lo=r['origin']+(h+1)*1800;seconds=max(0,min(s+r['minutes']*60,lo+1800)-max(s,lo))
     total+=Fraction(a['powerKw'])*Fraction(seconds,3600)*Fraction(str(value))/1000
    return total
   assert abs(Fraction(saving)-(rational(r['usual'])-rational(chosen)))<Fraction(1,10**20)
   if moved:assert r['origin']<chosen and r['origin']<r['usual'] and (not agree or Decimal(p['b3Saving'])>0)
   week=(rows[first]['usual']-start)//604800+1
   records.append({'arm':arm,'householdId':k[0],'applianceId':k[1],'usual':k[2],'chosen':chosen,'week':week,
    'weight':str(r['weight']),'moved':moved,'saving':str(saving),'harmful':saving<0,'notifiedAt':r['origin'] if moved else None,
    'outcome':p['outcome'] if moved else 'NO_MOVE','predictedSaving':p['saving'] if moved else '0','b3SavingAtChosen':p['b3Saving'] if moved else '0',
    'falseAdvice':bool(moved and (saving<=0 or (p['outcome']=='AVOID' and not r['AI']['usualEvents'])))})
  weighted=lambda rs:sum((Decimal(r['saving'])*Decimal(r['weight']) for r in rs),Decimal(0))
  harmful=[r for r in records if r['harmful']];moves=sum(r['moved'] for r in records)
  summaries[arm]={'cases':600,'moves':moves,'savingsSgd':str(weighted(records)),'harmfulMoves':len(harmful),
   'harmfulRate':len(harmful)/moves if moves else None,'harmfulLossSgd':str(-weighted(harmful)),
   'worstIndividualLossSgd':str(max((-Decimal(r['saving']) for r in harmful),default=Decimal(0))),
   'falseAdviceCases':sum(r['falseAdvice'] for r in records),'disagreementRejectedRefreshes':rejected,
   'weeklySavingsSgd':[str(weighted([r for r in records if r['week']==w])) for w in range(1,9)]}
  decisions.extend(records);arm_records[arm]=records
 # Reproduce controls and quantify savings lost versus harms prevented on matched cases.
 prior=read(PREVIOUS/'summary.json')['methods']
 for arm,m in [('AI_005','PRICE_ONLY'),('B3_005','B3')]:
  assert abs(Decimal(summaries[arm]['savingsSgd'])-Decimal(prior[m]['savingsSgd']))<Decimal('1e-18')
  assert summaries[arm]['harmfulMoves']==prior[m]['harmfulMoves']
  assert summaries[arm]['falseAdviceCases']==prior[m]['falseNotifications']
 for arm,s in summaries.items():
  ref=summaries['B3_010' if ARMS[arm][1]=='0.10' else 'B3_005']
  differences=[Decimal(a)-Decimal(b) for a,b in zip(s['weeklySavingsSgd'],ref['weeklySavingsSgd'])]
  s['versusSameFloorB3']={'savingsDifferenceSgd':str(sum(differences)),'weeklyWins':sum(x>Decimal('.0001') for x in differences),'weeklyLosses':sum(x<Decimal('-.0001') for x in differences)}
  if arm.startswith('AI_'):
   control=arm_records['AI_005'];current=arm_records[arm]
   assert [(r['householdId'],r['applianceId'],r['usual']) for r in control]==[(r['householdId'],r['applianceId'],r['usual']) for r in current]
   s['versusCurrentAi']={'netSavingsChangeSgd':str(Decimal(s['savingsSgd'])-Decimal(summaries['AI_005']['savingsSgd'])),
    'harmfulCasesAvoided':sum(a['harmful'] and not b['harmful'] for a,b in zip(control,current)),
    'newHarmfulCases':sum(not a['harmful'] and b['harmful'] for a,b in zip(control,current)),
    'withheldOriginallyProfitableCases':sum(Decimal(a['saving'])>0 and not b['moved'] for a,b in zip(control,current)),
    'withheldOriginalWeightedSavingsSgd':str(sum((Decimal(a['saving'])*Decimal(a['weight']) for a,b in zip(control,current) if Decimal(a['saving'])>0 and not b['moved']),Decimal(0)))}
 assert all(sha(Path(p))==v for p,v in hashes.items())
 with gzip.open(OUT/'decisions.jsonl.gz','xt') as f:
  for r in decisions:f.write(json.dumps(r)+'\n')
 write('summary.json',{'arms':summaries,'scenarios':600,'refreshOpportunities':len(rows),'stopping':'Fixed six-arm experiment complete; no extra search or automatic adoption.',
  'freshValidation':False,'livePolicyChanged':False,'modelChanged':False,'retrained':False})
 write('verification.json',{'status':'PASS','existingAiAndB3RefreshDecisionsReproduced':24128,'finalExactRationalCostsChecked':len(decisions),
  'matchedCasesPerArm':600,'inputsAndFeasibleStartsVerified':True,'allSourceHashesUnchanged':True,'noActualsUsedByGateOrRanking':True,
  'trainingBoundariesPreviouslyVerifiedAndChecksumsMatched':True,'liveTrialChanged':False})
 print(json.dumps(summaries,indent=2))
if __name__=='__main__':main()
