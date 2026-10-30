"""Budget-policy regressions, synthetic evidence only; no accuracy claims."""
from copy import deepcopy
import json
import pytest
from wattly_ml.timebase import DAY, instant
from wattly_ml.warning_budget import assess, evaluate_file, ONSET

def evidence():
    start=instant('2026-01-05T00:00:00+08:00');weeks=[]
    for i,(old,new) in enumerate(zip([1,4,1,2],[2,2,1,1])):
        weeks.append(dict(start=(start+7*i*DAY).isoformat(),end=(start+7*(i+1)*DAY).isoformat(),
            labelsAvailableAt=(start+7*(i+1)*DAY).isoformat(),truthDigest='a'*64,exposureUnits=100,completeEvents=6,
            control=dict(falseEpisodes=old,falseHalfHours=old*2,advanceEventLeadsMinutes={f'e{i}':60}),
            candidate=dict(falseEpisodes=new,falseHalfHours=new*2,advanceEventLeadsMinutes={f'e{i}':60})))
    return dict(schemaVersion=1,candidateId='SYNTHETIC_CANDIDATE',controlId='SYNTHETIC_CONTROL',decisionAt=(start+28*DAY).isoformat(),
        metricUnit='FORECAST_ISSUE_ORIGIN',labelId='PAST_ONLY_SAME_SLOT_K3',onsetDefinition=ONSET,weeks=weeks)

def test_overall_gain_can_allow_one_worse_week_within_historical_worst():
    r=assess(evidence())
    assert r['eligibleForFurtherReview']
    assert r['pooled']['candidate']['falseEpisodes']==6
    assert r['worstWeekRateCaps']['falseEpisodes']=='1/25'
    assert r['capWindow']['end']==evidence()['weeks'][-1]['end']
    assert 'not an online' in r['capWindow']['interpretation']
    assert r['weekly'][0]['candidate']['falseEpisodes']>r['weekly'][0]['control']['falseEpisodes']
    assert r['currentPriceRule']==dict(minimumSamples=14,k=3.0,spreadFloor=1.0)
    assert not r['productionEligible'] and not r['automaticPromotion']
    assert r['selectedBehavior']=='RETAIN_EXISTING_BEHAVIOR'

def test_good_overall_average_cannot_hide_a_bad_week():
    e=evidence()
    for i,n in enumerate([5,0,0,0]):
        e['weeks'][i]['candidate'].update(falseEpisodes=n,falseHalfHours=n*2)
    r=assess(e)
    assert r['checks']['pooledFalseEpisodeBudget']
    assert not r['checks']['worstWeekSafeguard'] and not r['eligibleForFurtherReview']

def test_rates_account_for_different_weekly_exposure():
    e=evidence();e['weeks'][0]['exposureUnits']=10
    r=assess(e)
    assert not r['checks']['worstWeekSafeguard']

def test_fewer_episodes_does_not_hide_longer_false_warnings():
    e=evidence();e['weeks'][0]['candidate']['falseHalfHours']=30
    r=assess(e)
    assert r['checks']['pooledFalseEpisodeBudget'] and not r['checks']['pooledFalseDurationBudget']
    assert not r['eligibleForFurtherReview']

def test_silence_does_not_qualify_as_an_improvement():
    e=evidence()
    for r in e['weeks']:r['candidate']=dict(falseEpisodes=0,falseHalfHours=0,advanceEventLeadsMinutes={})
    assert not assess(e)['checks']['noLowerAdvanceEventRecall']
    assert not assess(e)['eligibleForFurtherReview']

def test_equal_policy_has_no_strict_gain():
    e=evidence()
    for r in e['weeks']:r['candidate']=deepcopy(r['control'])
    assert not assess(e)['checks']['strictMeasuredGain']

def test_event_loss_and_delay_are_visible_even_with_net_count_retained():
    e=evidence();e['weeks'][0]['candidate']['advanceEventLeadsMinutes']={'new-event':30}
    e['weeks'][1]['candidate']['advanceEventLeadsMinutes']={'e1':30}
    r=assess(e)
    assert r['pairedEvents']['lost']==['e0'] and r['pairedEvents']['new']==['new-event']
    assert r['pairedEvents']['delayed']==['e1']
    assert not r['householdSavingsVerified'] and not r['productionEligible']

@pytest.mark.parametrize('change',[
    lambda e:e.update(decisionAt='2026-01-31T00:00:00+08:00'),
    lambda e:e['weeks'][0].update(labelsAvailableAt='2026-01-01T00:00:00+08:00'),
    lambda e:e['weeks'][0].update(metricUnit='EXPOSED_HOUSEHOLD_WEEK'),
    lambda e:e.update(onsetDefinition='BEFORE_COSTLY_CROSSING_ONLY'),
    lambda e:e.update(labelId='TUNED_ON_LATER_OUTCOMES'),
    lambda e:e['weeks'][0].update(exposureUnits=0),
    lambda e:e['weeks'][0]['candidate'].update(falseEpisodes=-1),
    lambda e:e['weeks'][0]['candidate'].update(advanceEventLeadsMinutes={'e0':0}),
    lambda e:e['weeks'][1]['candidate'].update(advanceEventLeadsMinutes={'e0':60}),
    lambda e:e['weeks'].append(deepcopy(e['weeks'][0])),
])
def test_invalid_or_leaky_evidence_is_rejected(change):
    e=evidence();change(e)
    with pytest.raises(ValueError):assess(e)

def test_insufficient_event_support_retains_control():
    e=evidence()
    for r in e['weeks']:r['completeEvents']=1
    r=assess(e)
    assert not r['supportAdequate'] and not r['eligibleForFurtherReview']

def test_report_is_write_once_and_cli_is_wired(tmp_path,capsys):
    from wattly_ml.cli import main
    source=tmp_path/'input.json';source.write_text(json.dumps(evidence()));out=tmp_path/'output.json'
    assert main(['assess-warning-budget',str(source),'--output',str(out)])==0
    assert json.loads(out.read_text())['policyId']=='K3_POOLED_WORST_WEEK_REVIEW_V1'
    with pytest.raises(FileExistsError):evaluate_file(source,out)
@pytest.mark.parametrize('bad',[[],{},None,{'schemaVersion':1,'metricUnit':'FORECAST_ISSUE_ORIGIN','onsetDefinition':ONSET,'labelId':'PAST_ONLY_SAME_SLOT_K3','candidateId':'c','controlId':'b'}])
def test_malformed_input_is_a_clear_validation_error(bad):
    with pytest.raises(ValueError):assess(bad)
