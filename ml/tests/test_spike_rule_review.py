"""Synthetic verification of the review workflow, never human truth or price-performance evidence."""
from copy import deepcopy
from datetime import datetime,timedelta,timezone
import pytest
from wattly_ml.spike_rule_review import build,score,write_new,identity,STEP,DAY

T=datetime(2026,4,1,tzinfo=timezone.utc)
DECISION=datetime(2026,10,30,tzinfo=timezone.utc)

def fixture():
    prices={T+i*STEP-n*DAY:10. for n in range(1,29) for i in range(3)}
    prices.update({T:14.,T+STEP:12.,T+2*STEP:10.})
    return build(prices,T,T+3*STEP,'a'*64)

def labels(cohort):
    return {'schemaVersion':1,'cohortDigest':cohort['cohortDigest'],'reviewer':'SYNTHETIC_TEST_REVIEWER',
        'labelScope':'HUMAN_UNUSUAL_PRICE','reviewedAt':DECISION.isoformat(),
        'labelDefinition':'SYNTHETIC_TEST_ONLY','independentOfRule':True,'ruleRevealed':False,
        'labels':[{'id':c['id'],'label':v,'reason':'SYNTHETIC_TEST_ONLY'} for c,v in zip(cohort['cases'],['SPIKE','NORMAL','SPIKE'])]}

def test_strict_threshold_floor_and_past_only_reference():
    cohort,audit=fixture();rows=list(audit['cases'].values())
    assert rows[0]['typical']==10 and rows[0]['spread']==1
    assert rows[0]['flags']=={'2':True,'3':True,'4':False}
    assert rows[1]['flags']=={'2':False,'3':False,'4':False}
    assert all(c['priorSameSlotPrices']==[10.]*28 for c in cohort['cases'])
    assert not any('flags' in c or 'threshold' in c or 'forecast' in c for c in cohort['cases'])

def test_absent_labels_do_not_turn_rule_outputs_into_genuine_precision():
    cohort,audit=fixture();report=score(audit)
    assert report['precision'] is None and report['recall'] is None and report['methods'] is None
    assert not report['teamApproved'] and not report['runtimeChanged'] and not report['automaticPromotion']
    assert 'recommendedK' not in report

def test_complete_labels_produce_hand_calculated_confusion_counts():
    cohort,audit=fixture();report=score(audit,labels(cohort),DECISION)
    assert report['methods']['3']=={'tp':1,'fp':0,'fn':1,'tn':1,'precision':1.,'recall':.5}
    assert report['methods']['4']['precision'] is None and report['methods']['4']['recall']==0
    assert report['coverage']==1 and not report['teamApproved'] and not report['physicalScarcityValidated']
    assert 'recommendedK' not in report

def test_partial_and_uncertain_labels_only_produce_explicit_subset_metrics():
    cohort,audit=fixture();review=labels(cohort);review['labels'][1]['label']='UNCERTAIN';review['labels'].pop()
    report=score(audit,review,DECISION)
    assert report['reviewedTargets']==1 and report['uncertainTargets']==1 and report['methods'] is None
    assert report['labelledSubsetMethods']['3']['tp']==1 and report['coverage']==1/3

@pytest.mark.parametrize('kind',['cohort','duplicate','unknown','reveal','attestation','scope','reviewer','reason','future','before','definition','label'])
def test_unusable_review_labels_are_rejected(kind):
    cohort,audit=fixture();review=labels(cohort)
    if kind=='cohort':review['cohortDigest']='b'*64
    if kind=='duplicate':review['labels'].append(review['labels'][0])
    if kind=='unknown':review['labels'][0]['id']='not-in-cohort'
    if kind=='reveal':review['ruleRevealed']=True
    if kind=='attestation':review['independentOfRule']=False
    if kind=='scope':review['labelScope']='PHYSICAL_SCARCITY'
    if kind=='reviewer':review['reviewer']=' '
    if kind=='reason':review['labels'][0]['reason']=''
    if kind=='future':review['reviewedAt']=(DECISION+DAY).isoformat()
    if kind=='before':review['reviewedAt']=(T-DAY).isoformat()
    if kind=='definition':review['labelDefinition']=''
    if kind=='label':review['labels'][0]['label']='PROBABLY'
    with pytest.raises(ValueError):score(audit,review,DECISION)

def test_changed_audit_and_naive_clock_are_rejected():
    cohort,audit=fixture();changed=deepcopy(audit);next(iter(changed['cases'].values()))['flags']['3']=False
    with pytest.raises(ValueError,match='Changed'):score(changed)
    with pytest.raises(ValueError,match='decision'):score(audit,labels(cohort),DECISION.replace(tzinfo=None))

def test_missing_reference_is_excluded_rather_than_called_normal():
    cohort,audit=build({T:10.},T,T+STEP,'a'*64)
    assert not cohort['cases'] and audit['insufficientReference']==1
    with pytest.raises(ValueError,match='Empty'):score(audit)

def test_review_files_are_not_overwritten(tmp_path):
    p=tmp_path/'review.json';write_new(p,{'testOnly':True})
    with pytest.raises(FileExistsError):write_new(p,{})


def test_cli_scores_exported_labels_without_changing_runtime_or_overwriting(tmp_path,monkeypatch):
    import json,runpy,sys
    from pathlib import Path
    cohort,audit=fixture();a=tmp_path/'audit.json';b=tmp_path/'labels.json';out=tmp_path/'score.json'
    a.write_text(json.dumps(audit));b.write_text(json.dumps(labels(cohort)))
    command=Path(__file__).resolve().parents[2]/'scripts/review-spike-rule.py'
    monkeypatch.setattr(sys,'argv',[str(command),'score',str(a),str(b),'--output',str(out),'--decision-at',DECISION.isoformat()])
    runpy.run_path(str(command),run_name='__main__')
    result=json.loads(out.read_text());assert result['methods']['3']['recall']==.5
    assert result['teamApproved'] is False and result['automaticPromotion'] is False
    with pytest.raises(FileExistsError):runpy.run_path(str(command),run_name='__main__')


def test_future_prices_do_not_change_an_earlier_reference_or_flag():
    prices={T-n*DAY:10. for n in range(1,29)};prices[T]=14.
    before=build(prices,T,T+STEP,'a'*64)[1]
    prices[T+STEP]=999999.;prices[T+DAY]=999999.
    after=build(prices,T,T+STEP,'a'*64)[1]
    assert before==after


def test_final_price_loader_rejects_conflicting_truth_and_keeps_negative_prices(tmp_path):
    import json
    from wattly_ml.spike_rule_review import load_final_prices
    p=tmp_path/'prices.jsonl';row={'priceStatus':'FINAL','periodStart':T.isoformat(),'usep':-5.}
    p.write_text(json.dumps(row)+'\n');assert load_final_prices(p)[T]==-5.
    p.write_text(json.dumps(row)+'\n'+json.dumps({**row,'usep':10.})+'\n')
    with pytest.raises(ValueError,match='Conflicting'):load_final_prices(p)
