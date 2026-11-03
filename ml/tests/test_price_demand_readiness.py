"""Synthetic contracts, not real model accuracy or savings evidence."""
from copy import deepcopy
import json
import math
import sys
import pytest

from wattly_ml.features import feature_names, features
from wattly_ml.price_demand_readiness import main, readiness, snapshot_inputs
from wattly_ml.price_only import completed_price_history
from wattly_ml.timebase import DAY, STEP, floor, instant, targets

ORIGIN=instant('2026-10-30T15:02:00+08:00')
EVIDENCE='EMC_RT_20260929_24_PERIODS'

def issue():
    slot=floor(ORIGIN)
    prices=[dict(source='NEMS_SN_SG',timeMapping='UNVERIFIED_FLOOR',
        periodStart=(slot-n*STEP).isoformat(),sourceUpdatedAt=(slot-n*STEP+STEP/30).isoformat(),
        availableAt=(slot-n*STEP+STEP/20).isoformat(),usep=1000-2*n) for n in range(385,-1,-1)]
    prices[-1]['usep']=999999
    demand=[dict(targetPeriod=t.isoformat(),publishedAt=(ORIGIN-STEP).isoformat(),
        availableAt=ORIGIN.isoformat(),demandMw=6000+h,source='SYNTHETIC_FORECAST')
        for h,t in enumerate(targets(ORIGIN))]
    return dict(id='synthetic-one',asOf=ORIGIN.isoformat(),mode='LIVE',isDemo=False,
        modelVersion='synthetic-price-only',stale=False,status='AVAILABLE',
        inputs=dict(priceHistory=prices,expectedDemand=demand))

def inspect(issues):
    return readiness(issues,start=floor(ORIGIN),end_exclusive=floor(ORIGIN)+STEP,
        as_of=ORIGIN+DAY,model_version='synthetic-price-only',mapping_evidence=EVIDENCE)

def test_no_solar_candidate_has_target_demand_and_exact_completed_price_control():
    row=issue();candidate,control=snapshot_inputs(row,EVIDENCE)
    assert candidate==control and max(candidate)==floor(ORIGIN)-STEP
    assert not hasattr(candidate,'solar_values') and not hasattr(control,'demand_forecasts')
    target=targets(ORIGIN)[0]
    values=dict(zip(feature_names(2),features(candidate,ORIGIN,target,schema_version=2)))
    assert values['latest']==values['lag1']==998
    assert values['expected_demand_mw']==6000 and values['expected_demand_available']==1
    assert values['mean6']==993 and values['std6']==pytest.approx(math.sqrt(35/3))
    assert values['mean48']==951 and values['std48']==pytest.approx(math.sqrt(2303/3))
    original={instant(r['periodStart']):r for r in row['inputs']['priceHistory']}
    expected=completed_price_history(original,ORIGIN)
    assert {t:r['usep'] for t,r in control.items()}=={t:r['usep'] for t,r in expected.items()}
    assert features(control,ORIGIN,target,schema_version=2)[-1]==0
    assert math.isnan(features(control,ORIGIN,target,schema_version=2)[-2])
    candidate.demand_forecasts[0]['demandMw']=9000
    assert features(candidate,ORIGIN,target,schema_version=2)[-2]==9000
    assert math.isnan(features(control,ORIGIN,target,schema_version=2)[-2])

def test_wrapper_does_not_require_or_use_solar_and_does_not_mutate_snapshot():
    row=issue();before=deepcopy(row)
    flat=snapshot_inputs(row,EVIDENCE)
    wrapped=deepcopy(row);wrapped['inputs']=dict(marketInputs=row['inputs'],solarInputs={'ignored':'deliberately invalid'})
    assert snapshot_inputs(wrapped,EVIDENCE)==flat
    assert row==before

@pytest.mark.parametrize('failure', ['missing_all','missing_target','future_demand','negative_demand',
    'publication_after_receipt','duplicate_conflict','future_price','price_receipt_before_publication',
    'unverified_mapping','missing_completed','missing_week','wrong_target'])
def test_invalid_or_unavailable_inputs_fail_closed(failure):
    row=issue();raw=row['inputs'];d=raw['expectedDemand'];p=raw['priceHistory']
    if failure=='missing_all':raw.pop('expectedDemand')
    if failure=='missing_target':d.pop()
    if failure=='future_demand':d[0]['availableAt']=(ORIGIN+STEP).isoformat()
    if failure=='negative_demand':d[0]['demandMw']=-1
    if failure=='publication_after_receipt':d[0]['publishedAt']=(ORIGIN+STEP).isoformat()
    if failure=='duplicate_conflict':d.append({**d[0],'demandMw':1})
    if failure=='future_price':p[-1]['availableAt']=(ORIGIN+STEP).isoformat()
    if failure=='price_receipt_before_publication':p[-1]['availableAt']=floor(ORIGIN).isoformat()
    if failure=='unverified_mapping':raw['mappingVerified']=True;p[0]['timeMapping']='UNKNOWN'
    if failure=='missing_completed':p.pop(-2)
    if failure=='missing_week':p[:]=[r for r in p if instant(r['periodStart'])!=targets(ORIGIN)[0]-7*DAY]
    if failure=='wrong_target':d[-1]['targetPeriod']=(targets(ORIGIN)[-1]+STEP).isoformat()
    with pytest.raises(ValueError):snapshot_inputs(row,EVIDENCE)
    result=inspect([row]);assert result['eligibleHalfHourOrigins']==0
    assert not result['readyToFit'] and result['accuracyResult'] is None

def test_first_eligible_original_issue_is_distinct_from_repeated_updates():
    row=issue();later=deepcopy(row);later.update(id='synthetic-later',asOf=(ORIGIN+STEP/10).isoformat())
    invalid=deepcopy(row);invalid.update(id='synthetic-early',asOf=(ORIGIN-STEP/60).isoformat())
    invalid['inputs']['expectedDemand'].pop()
    for demand in invalid['inputs']['expectedDemand']:demand['availableAt']=invalid['asOf']
    result=inspect([later,invalid,row])
    assert result['eligibleHalfHourOrigins']==1 and result['firstEligibleIssue']==ORIGIN.isoformat()
    assert result['excluded']['REPEATED_HALF_HOUR']==1
    assert result['excluded']['MISSING_TARGET_DEMAND']==1
    assert result['elapsedMatureOriginsBeforeReceiptCheck']==1
    assert result['actualReceiptValidatedOrigins'] is None and result['readyToFit'] is False
    assert not result['realCandidateFitted'] and not result['productionEligible']
    with pytest.raises(ValueError,match='Duplicate'):inspect([row,row])

@pytest.mark.parametrize('change',[{'isDemo':True},{'mode':'REPLAY'},{'stale':True},
    {'stale':None},{'modelVersion':'other-model'},{'status':'UNAVAILABLE'}])
def test_demo_stale_other_model_and_unavailable_issues_are_not_training_support(change):
    row=issue();row.update(change);result=inspect([row])
    assert result['eligibleHalfHourOrigins']==0
    assert result['excluded']=={'NOT_FRESH_ORIGINAL_MODEL_ISSUE':1}

def test_cli_preserves_existing_output_and_never_emits_raw_snapshots(tmp_path,monkeypatch,capsys):
    source=tmp_path/'issues.jsonl';source.write_text(json.dumps(issue())+'\n')
    output=tmp_path/'readiness.json'
    monkeypatch.setattr(sys,'argv',['preflight','--issues',str(source),'--output',str(output),
        '--start',floor(ORIGIN).isoformat(),'--end-exclusive',(floor(ORIGIN)+STEP).isoformat(),
        '--as-of',(ORIGIN+DAY).isoformat(),'--model-version','synthetic-price-only','--mapping-evidence',EVIDENCE])
    main();before=output.read_bytes();report=json.loads(before)
    assert report['eligibleHalfHourOrigins']==1 and 'inputs' not in report
    assert 'synthetic-one' not in capsys.readouterr().out
    with pytest.raises(SystemExit) as error:main()
    assert error.value.code==2 and output.read_bytes()==before

def test_inspection_window_cannot_include_future_inputs():
    with pytest.raises(ValueError,match='closed'):
        readiness([],start=ORIGIN,end_exclusive=ORIGIN+DAY,as_of=ORIGIN,
            model_version='synthetic-price-only',mapping_evidence=EVIDENCE)
