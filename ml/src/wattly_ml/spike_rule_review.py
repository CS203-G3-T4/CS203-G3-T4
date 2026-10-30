"""Retrospective spike-rule audit and blind human-label review. Never train or select a model."""
import hashlib
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from statistics import median

STEP=timedelta(minutes=30)
DAY=timedelta(days=1)
KS=(2,3,4)  # Already reported settings: descriptive comparison only, no sweep.


def stamp(text):
    value=datetime.fromisoformat(text.replace('Z','+00:00'))
    if value.tzinfo is None:raise ValueError('Timezone required')
    return value.astimezone(timezone.utc)


def identity(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


def build(prices,start,end,source_digest):
    if start.tzinfo is None or end.tzinfo is None or start>=end or start.minute%30 or end.minute%30 or start.second or end.second or start.microsecond or end.microsecond:
        raise ValueError('Ordered half-hour bounds required')
    if len(source_digest)!=64 or any(c not in '0123456789abcdef' for c in source_digest):raise ValueError('Source SHA256 required')
    cases=[];truth={};missing=reference_missing=0;t=start
    while t<end:
        values=[prices[t-n*DAY] for n in range(1,29) if t-n*DAY in prices]
        if t not in prices:missing+=1
        elif len(values)<14:reference_missing+=1
        else:
            if not math.isfinite(prices[t]) or not all(math.isfinite(x) for x in values):raise ValueError('Nonfinite price')
            typical=median(values);spread=max(1.,1.4826*median(abs(x-typical) for x in values))
            key=identity({'source':source_digest,'periodStart':t.isoformat()})[:24]
            # No rule thresholds, decisions or forecasts in the annotation payload.
            cases.append({'id':key,'periodStart':t.isoformat(),'price':prices[t],
                          'priorSameSlotPrices':[prices.get(t-n*DAY) for n in range(1,29)]})
            truth[key]={'periodStart':t.isoformat(),'price':prices[t],'typical':typical,'spread':spread,
                        'flags':{str(k):prices[t]>typical+k*spread for k in KS}}
        t+=STEP
    cohort={'schemaVersion':1,'start':start.isoformat(),'end':end.isoformat(),'sourceDigest':source_digest,
            'evaluationKind':'RETROSPECTIVE_REVISED_FINAL_PRICES','cases':cases}
    cohort['cohortDigest']=identity(cohort)
    summaries=[]
    for k in KS:
        alerts=[stamp(v['periodStart']) for v in truth.values() if v['flags'][str(k)]]
        summaries.append({'k':k,'assessedTargets':len(cases),'alertTargets':len(alerts),
            'alertEpisodes':sum(i==0 or t-alerts[i-1]!=STEP for i,t in enumerate(alerts)),
            'flaggedHours':len(alerts)/2,'flaggedTargetPercent':100*len(alerts)/len(cases) if cases else None})
    audit={'schemaVersion':1,'cohortDigest':cohort['cohortDigest'],'sourceDigest':source_digest,
           'start':start.isoformat(),'end':end.isoformat(),'missingPrices':missing,'insufficientReference':reference_missing,
           'cases':truth,'sensitivity':summaries,'teamApproved':False,'runtimeChanged':False,
           'interpretation':'Rule flag burden only; alerts are observed-price periods, not advance forecasts or delivered notifications.'}
    audit["auditDigest"]=identity(audit)
    return cohort,audit


def load_final_prices(path):
    prices={}
    with path.open() as f:
        for line in f:
            row=json.loads(line)
            if row.get('priceStatus')!='FINAL':continue
            t=stamp(row['periodStart']);value=float(row['usep'])
            if t.minute%30 or t.second or t.microsecond or not math.isfinite(value):raise ValueError('Invalid price grid/value')
            if t in prices and prices[t]!=value:raise ValueError('Conflicting final revisions; freeze one explicit truth policy first')
            prices[t]=value
    return prices


def score(audit,labels=None,decision_at=None):
    if audit.get('auditDigest')!=identity({k:v for k,v in audit.items() if k!='auditDigest'}):raise ValueError('Changed rule audit')
    if not audit.get('cases'):raise ValueError('Empty review cohort')
    base={'schemaVersion':1,'cohortDigest':audit['cohortDigest'],'teamApproved':False,'automaticPromotion':False,
          'runtimeChanged':False,'recommendedK':3,'physicalScarcityValidated':False}
    if labels is None:
        return {**base,'status':'AWAITING_INDEPENDENT_LABELS','reviewedTargets':0,'totalTargets':len(audit['cases']),
                'precision':None,'recall':None,'methods':None}
    if labels.get('schemaVersion')!=1 or labels.get('cohortDigest')!=audit['cohortDigest']:raise ValueError('Wrong review cohort')
    if labels.get('labelScope')!='HUMAN_UNUSUAL_PRICE':raise ValueError('This scorer requires unusual-price labels, not causal market-event claims')
    if not isinstance(labels.get('reviewer'),str) or not labels['reviewer'].strip():raise ValueError('Named reviewer required')
    if not isinstance(labels.get('labelDefinition'),str) or not labels['labelDefinition'].strip():raise ValueError('Written label definition required')
    if decision_at is None or decision_at.tzinfo is None:raise ValueError('Explicit aware decision time required')
    reviewed=stamp(labels['reviewedAt'])
    if reviewed>decision_at:raise ValueError('Review comes from the future')
    if reviewed<stamp(audit['end']):raise ValueError('Review cannot precede the observed cohort')
    if labels.get('independentOfRule') is not True or labels.get('ruleRevealed') is not False:
        raise ValueError('Independent blind labels required; this is a self-attestation, not verified human identity')
    seen={}
    for row in labels.get('labels',[]):
        key=row.get('id');value=row.get('label')
        if key in seen or key not in audit['cases']:raise ValueError('Duplicate or unknown review case')
        if value not in ('SPIKE','NORMAL','UNCERTAIN'):raise ValueError('Invalid human label')
        if not isinstance(row.get('reason'),str) or not row['reason'].strip():raise ValueError('A review rationale is required')
        seen[key]=value
    usable={key:value for key,value in seen.items() if value!='UNCERTAIN'}
    complete=len(usable)==len(audit['cases'])
    methods={}
    for k in KS:
        tp=fp=fn=tn=0
        for key,value in usable.items():
            flag=audit['cases'][key]['flags'][str(k)];positive=value=='SPIKE'
            tp+=flag and positive;fp+=flag and not positive;fn+=not flag and positive;tn+=not flag and not positive
        methods[str(k)]={'tp':tp,'fp':fp,'fn':fn,'tn':tn,
                        'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/(tp+fn) if tp+fn else None}
    return {**base,'status':'COMPLETE_HUMAN_LABELLED_PRICE_REVIEW' if complete else 'PARTIAL_LABEL_REVIEW',
            'reviewer':labels['reviewer'],'reviewedAt':reviewed.isoformat(),'decisionAt':decision_at.isoformat(),'labelDefinition':labels['labelDefinition'],'independence':'SELF_ATTESTED_NOT_IDENTITY_VERIFIED',
            'reviewedTargets':len(usable),'uncertainTargets':sum(x=='UNCERTAIN' for x in seen.values()),
            'totalTargets':len(audit['cases']),'coverage':len(usable)/len(audit['cases']) if audit['cases'] else 0,
            'methods':methods if complete else None,'labelledSubsetMethods':methods,
            'interpretation':'Human unusual-price labels, not independently established physical scarcity. Partial-subset rates do not describe the whole cohort.'}


def write_new(path,value):
    # An existing review/audit is never silently replaced.
    with path.open('x') as f:json.dump(value,f,indent=2,allow_nan=False)
