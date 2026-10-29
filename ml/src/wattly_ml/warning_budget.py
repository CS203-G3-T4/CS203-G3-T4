"""Bounded research review gate; never changes serving or promotes a model."""
from fractions import Fraction
import json
from pathlib import Path
import re
from .timebase import DAY, instant
from .spikes import DEFAULT_CONFIG

POLICY_ID = 'K3_POOLED_WORST_WEEK_REVIEW_V1'
UNITS = ('FORECAST_ISSUE_ORIGIN', 'EXPOSED_HOUSEHOLD_WEEK')
ONSET = 'KNOWN_LOW_BEFORE_PARENT_EPISODE_START'

def _count(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(name+' must be a nonnegative integer')
    return value

def _metrics(value, complete):
    episodes = _count(value['falseEpisodes'], 'falseEpisodes')
    halves = _count(value['falseHalfHours'], 'falseHalfHours')
    if episodes > halves:
        raise ValueError('Each false episode must contain a counted false half-hour')
    leads = value['advanceEventLeadsMinutes']
    if not isinstance(leads, dict) or len(leads) > complete:
        raise ValueError('Unique advance event IDs on the complete-event cohort required')
    if any(not isinstance(k,str) or not k or type(v) is not int or not 0 < v <= 720 for k,v in leads.items()):
        raise ValueError('Advance leads must be positive, within the twelve-hour horizon')
    return dict(falseEpisodes=episodes, falseHalfHours=halves, advanceEvents=len(leads),
                advanceEventLeadsMinutes=dict(leads))

def _assess(evidence):
    """Assess one fixed candidate on completed EARLIER weeks, without searching.

    Caller must independently grade warnings against the declared common truth.
    Rate caps are derived from the control alone. Passing means further review,
    never production approval or proof of household savings.
    """
    if evidence.get('schemaVersion') != 1 or type(evidence.get('schemaVersion')) is not int:
        raise ValueError('Require schemaVersion1')
    if evidence.get('metricUnit') not in UNITS or evidence.get('onsetDefinition') != ONSET:
        raise ValueError('Explicit counting units and pre-parent-onset credit required')
    if evidence.get('labelId') != 'PAST_ONLY_SAME_SLOT_K3':
        raise ValueError('Keep the original k3 truth definition fixed')
    if not all(isinstance(evidence.get(k),str) and evidence[k] for k in ('candidateId','controlId')):
        raise ValueError('Frozen candidate/control identities required')
    if evidence['candidateId'] == evidence['controlId']:
        raise ValueError('Distinct candidate and control identities required')
    decision = instant(evidence['decisionAt'])
    weeks = evidence['weeks']
    if not isinstance(weeks,list) or not weeks:
        raise ValueError('Completed matched weekly evidence required')
    previous = None; checked = []; seen = {'control':set(), 'candidate':set()}
    for row in sorted(weeks,key=lambda r:instant(r['start'])):
        start,end,available = [instant(row[k]) for k in ('start','end','labelsAvailableAt')]
        if end-start != 7*DAY or end > available or available > decision or (previous is not None and start < previous):
            raise ValueError('Nonoverlapping completed earlier weeks and real label availability required')
        previous = end
        if row.get('metricUnit',evidence['metricUnit']) != evidence['metricUnit']:
            raise ValueError('Cannot mix forecast origins and household notification units')
        exposure = row['exposureUnits']
        if type(exposure) is not int or exposure <= 0:
            raise ValueError('Positive identical integer exposure units required')
        complete = _count(row['completeEvents'],'completeEvents')
        if not isinstance(row.get('truthDigest'),str) or not re.fullmatch('[a-f0-9]{64}',row['truthDigest']):
            raise ValueError('Common independently graded truth digest required')
        metrics = {name:_metrics(row[name],complete) for name in seen}
        for name in seen:
            ids=set(metrics[name]['advanceEventLeadsMinutes'])
            if seen[name] & ids:
                raise ValueError('Assign each event to its onset week; duplicate IDs forbidden')
            seen[name] |= ids
        checked.append(dict(start=start.isoformat(), end=end.isoformat(), labelsAvailableAt=available.isoformat(),
                            exposureUnits=exposure, completeEvents=complete, truthDigest=row['truthDigest'], **metrics))
    fields=('falseEpisodes','falseHalfHours')
    totals={name:{f:sum(r[name][f] for r in checked) for f in (*fields,'advanceEvents')} for name in seen}
    caps={f:max(Fraction(r['control'][f],r['exposureUnits']) for r in checked) for f in fields}
    violations=[]
    for r in checked:
        bad=[f for f in fields if Fraction(r['candidate'][f],r['exposureUnits']) > caps[f]]
        if bad:violations.append(dict(start=r['start'],fields=bad))
    comparisons={
        'pooledFalseEpisodeBudget':totals['candidate']['falseEpisodes']<=totals['control']['falseEpisodes'],
        'pooledFalseDurationBudget':totals['candidate']['falseHalfHours']<=totals['control']['falseHalfHours'],
        'worstWeekSafeguard':not violations,
        'noLowerAdvanceEventRecall':totals['candidate']['advanceEvents']>=totals['control']['advanceEvents'],
        'strictMeasuredGain':totals['candidate']['advanceEvents']>totals['control']['advanceEvents'] or any(totals['candidate'][f]<totals['control'][f] for f in fields),
    }
    support=len(checked)>=4 and sum(r['completeEvents'] for r in checked)>=20
    control_leads={k:v for r in checked for k,v in r['control']['advanceEventLeadsMinutes'].items()}
    candidate_leads={k:v for r in checked for k,v in r['candidate']['advanceEventLeadsMinutes'].items()}
    shared=control_leads.keys() & candidate_leads.keys()
    eligible=support and all(comparisons.values())
    exposure=sum(r['exposureUnits'] for r in checked)
    return dict(policyId=POLICY_ID,currentPriceRule=dict(DEFAULT_CONFIG),metricUnit=evidence['metricUnit'],labelId=evidence['labelId'],
        candidateId=evidence['candidateId'],controlId=evidence['controlId'],decisionAt=decision.isoformat(),
        counting='False episodes and their duration must use the declared common units, not mixed labels or cohorts',
        pooled=totals,pooledExposureUnits=exposure,
        pooledRates={n:{f:str(Fraction(totals[n][f],exposure)) for f in fields} for n in seen},
        worstWeekRateCaps={f:str(v) for f,v in caps.items()},worstWeekViolations=violations,
        checks=comparisons,supportAdequate=support,eligibleForFurtherReview=eligible,
        selectedBehavior='RETAIN_EXISTING_BEHAVIOR',productionEligible=False,automaticPromotion=False,
        householdSavingsVerified=False,additionalRequirement='Matched household savings/harm validation and fresh confirmation before adoption',
        pairedEvents=dict(new=sorted(candidate_leads.keys()-control_leads.keys()),lost=sorted(control_leads.keys()-candidate_leads.keys()),
            delayed=sorted(k for k in shared if candidate_leads[k]<control_leads[k]),
            earlier=sorted(k for k in shared if candidate_leads[k]>control_leads[k])),
        weekly=checked)

def evaluate_file(source, output):
    source,output=Path(source),Path(output)
    result=assess(json.loads(source.read_text()))
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x') as f:
        json.dump(result,f,indent=2,allow_nan=False);f.write('\n')
    return result
def assess(evidence):
    if not isinstance(evidence,dict):
        raise ValueError('Warning-budget evidence must be an object')
    try:
        return _assess(evidence)
    except (KeyError,TypeError,OverflowError):
        raise ValueError('Malformed warning-budget evidence; required fields and valid metric objects are needed') from None
