"""One registered no-solar demand/control experiment; no collector or promotion."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path

from .artifacts import load_bundle, save_bundle
from .backtest import predict
from .baselines import baselines
from .data import digest, write_json
from .demand_candidate import (PARAMETERS, identity, known_truth, complete_rows,
                               _error, _false_issues)
from .features import feature_names, features, holiday_calendar, holiday_digest
from .price_demand import PRICE_DEMAND_POLICY, DEMAND_POLICY, RESEARCH_RECIPE
from .price_demand_readiness import snapshot_inputs
from .price_only import PRICE_ONLY_POLICY, completed_price_history
from .spike_grading import metrics, event_metrics
from .spikes import DEFAULT_CONFIG, reference
from .timebase import DAY, STEP, floor, instant, targets

METHODS = ('PRICE_DEMAND','PRICE_REFIT','PRICE_FROZEN','B1','B2','B3')
REAL_NOT_BEFORE = instant('2026-11-08T20:00:00+08:00')


def protocol_check(protocol):
    if protocol.get('status') != 'REGISTERED' or protocol.get('kind') != RESEARCH_RECIPE:
        raise ValueError('A separately registered price-demand protocol is required')
    synthetic = protocol.get('syntheticTestOnly', False)
    if type(synthetic) is not bool:
        raise ValueError('syntheticTestOnly must be boolean')
    registered, start, cutoff, test_start, end = [instant(protocol[k]) for k in
        ('registeredAt','trainStart','trainCutoff','testStart','testEnd')]
    minimum = 48 if synthetic else 480
    if (not registered <= start < cutoff < test_start < end
            or any(t != floor(t) for t in (start,cutoff,test_start,end))
            or test_start-cutoff < DAY or end-test_start != 14*DAY
            or (not synthetic and (start < REAL_NOT_BEFORE or cutoff-start < 14*DAY))
            or protocol.get('parameters') != PARAMETERS or protocol.get('spikeConfig') != DEFAULT_CONFIG
            or protocol.get('truthPolicy') != 'PROVISIONAL'
            or any(type(protocol.get(k)) is not int or protocol[k] < minimum
                   for k in ('minimumTrainingOrigins','minimumTestOrigins'))
            or not protocol.get('frozenModelVersion')
            or protocol.get('periodMappingEvidence') != 'EMC_RT_20260929_24_PERIODS'):
        raise ValueError('Invalid price-demand protocol dates, support or fixed recipe')
    pin = protocol.get('frozenModelManifestSha256', '')
    if len(pin) != 64 or any(c not in '0123456789abcdef' for c in pin):
        raise ValueError('Exact frozen manifest SHA256 is required')
    return start, cutoff, test_start, end


def select_issues(issues, protocol, start, end):
    selected, excluded, seen = {}, Counter(), set()
    for issue in sorted(issues,key=lambda r:(instant(r['asOf']),r['id'])):
        if issue['id'] in seen:
            raise ValueError('Duplicate saved forecast issue')
        seen.add(issue['id'])
        origin = instant(issue['asOf'])
        if not start <= origin < end:
            continue
        if (issue.get('mode') != 'LIVE' or issue.get('isDemo',False)
                or issue.get('modelVersion') != protocol['frozenModelVersion']
                or issue.get('stale') is not False or issue.get('status') != 'AVAILABLE'):
            excluded['NOT_FRESH_FROZEN_LIVE_ISSUE'] += 1
            continue
        if protocol.get('syntheticTestOnly') and issue.get('syntheticTestOnly') is not True:
            raise ValueError('Synthetic protocol requires explicitly synthetic issues')
        try:
            candidate, _ = snapshot_inputs(issue,protocol['periodMappingEvidence'])
        except (ValueError,KeyError,TypeError,IndexError,AttributeError):
            excluded['INVALID_ORIGINAL_INPUTS'] += 1
            continue
        slot = floor(origin)
        if slot in selected:
            excluded['REPEATED_HALF_HOUR'] += 1
            continue
        selected[slot] = (issue,candidate)
    return list(selected.values()),dict(excluded)


def truth_at(prices, cutoff, mapping_evidence):
    # Filter using receipt/publication metadata before touching outcome values.
    eligible = (r for r in prices if r.get('sourceUpdatedAt') and r.get('availableAt')
        and max(instant(r['periodStart'])+STEP,instant(r['sourceUpdatedAt']),instant(r['availableAt'])) <= cutoff)
    return known_truth(eligible,cutoff,mapping_evidence)


def frozen_bundle(path, protocol):
    path = Path(path)
    if digest(path/'manifest.json') != protocol['frozenModelManifestSha256']:
        raise ValueError('Frozen model manifest changed')
    bundle = load_bundle(path)
    if (bundle['manifest']['version'] != protocol['frozenModelVersion']
            or bundle['manifest'].get('inputPolicy') != PRICE_ONLY_POLICY):
        raise ValueError('Frozen comparator must be the pinned price-only model')
    start = instant(protocol['trainStart'])
    if max(instant(bundle['manifest'][k]) for k in ('trainingDate','usableFrom')) > start:
        raise ValueError('Frozen comparator is from the future')
    if not protocol.get('syntheticTestOnly') and bundle['manifest'].get('syntheticTestOnly'):
        raise ValueError('Real study cannot use a synthetic frozen model')
    return bundle


def fit(issues, prices, protocol, as_of, root, frozen_path):
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingRegressor
    from sklearn.impute import SimpleImputer
    from sklearn.pipeline import make_pipeline
    from threadpoolctl import threadpool_limits
    start,cutoff,test_start,_ = protocol_check(protocol)
    as_of = instant(as_of)
    if not cutoff <= as_of < test_start:
        raise ValueError('Fit only after training cutoff and before untouched testing starts')
    frozen_bundle(frozen_path,protocol)
    experiment = Path(root)/identity(protocol)
    if experiment.exists():
        raise ValueError('Experiment already claimed; preserve failed and successful attempts')
    selected, input_excluded = select_issues(issues,protocol,start,cutoff)
    rows, outcome_excluded = complete_rows(selected,truth_at(prices,cutoff,protocol['periodMappingEvidence']),start,cutoff)
    if len(rows) < protocol['minimumTrainingOrigins']:
        raise ValueError('Insufficient complete training origins with received outcomes')
    # Claim the canonical study directory before fitting, including failed fits.
    experiment.parent.mkdir(parents=True,exist_ok=True)
    experiment.mkdir()
    write_json(experiment/'protocol.json',protocol)
    attempt = dict(state='RUNNING',asOf=as_of.isoformat(),protocolSha256=identity(protocol))
    write_json(experiment/'fit-attempt.json',attempt)
    try:
        training_digest = identity([dict(issue=issue,actual=actual) for issue,_,actual in rows])
        y = np.asarray([actual for _,_,actual in rows])
        with threadpool_limits(limits=1):
            for method,policy in (('price-demand-v1',PRICE_DEMAND_POLICY),('price-refit-v1',PRICE_ONLY_POLICY)):
                x = np.asarray([[features(past if policy == PRICE_DEMAND_POLICY else completed_price_history(past,instant(issue['asOf'])),
                            instant(issue['asOf']),target,schema_version=2)
                        for target in targets(instant(issue['asOf']))] for issue,past,_ in rows])
                models = []
                for h in range(24):
                    model = make_pipeline(SimpleImputer(strategy='median',add_indicator=True,keep_empty_features=True),
                        HistGradientBoostingRegressor(**PARAMETERS))
                    models.append(model.fit(x[:,h,:],y[:,h]))
                manifest = dict(schemaVersion=1,featureSchemaVersion=2,featureOrder=feature_names(2),
                    inputPolicy=policy,researchRecipe=RESEARCH_RECIPE if policy == PRICE_DEMAND_POLICY else 'PRICE_ONLY_MATCHED_REFIT_V1',
                    expectedDemandPolicy=DEMAND_POLICY if policy == PRICE_DEMAND_POLICY else 'ALWAYS_MISSING_AS_TRAINED',
                    holidayCalendarVersion=holiday_calendar()['version'],holidayCalendarSha256=holiday_digest(),
                    productionEligible=False,exploratory=True,retrospective=False,syntheticTestOnly=protocol.get('syntheticTestOnly',False),
                    trainingDate=as_of.isoformat(),trainingCutoff=cutoff.isoformat(),usableFrom=test_start.isoformat(),
                    validationEnd=test_start.isoformat(),trainingOrigins=len(rows),trainingInputsSha256=training_digest,
                    parameters=PARAMETERS,baselineRanking=['B3','B2','B1'],baselineRankingSource='Existing fixed ranking; all baselines reported',
                    spikeConfig=DEFAULT_CONFIG,experimentProtocolSha256=identity(protocol),
                    validation=dict(table={'AI':{'mae':None}},commonPairs=0,skill=None))
                save_bundle(dict(models=models,manifest=manifest),experiment/'models',method)
        pins = {v:digest(experiment/'models'/v/'manifest.json') for v in ('price-demand-v1','price-refit-v1')}
        result = dict(state='SUCCESS',asOf=as_of.isoformat(),modelManifestSha256=pins,trainingOrigins=len(rows),trainingInputsSha256=training_digest,
            protocolSha256=identity(protocol),experiment=str(experiment),inputExclusions=input_excluded,
            outcomeExclusions=outcome_excluded,holdoutInspected=False,productionEligible=False,automaticPromotion=False)
        write_json(experiment/'fit-attempt.json',result)
        return result
    except BaseException as exc:
        write_json(experiment/'fit-attempt.json',{**attempt,'state':'FAILED','errorType':type(exc).__name__})
        raise


def evaluate(issues, prices, protocol, as_of, experiment, frozen_path):
    from .cli import report
    _,cutoff,test_start,end = protocol_check(protocol)
    if instant(as_of) < end:
        raise ValueError('Untouched holdout is not finished; no interim scoring')
    experiment = Path(experiment)
    if json.loads((experiment/'protocol.json').read_text()) != protocol:
        raise ValueError('Protocol changed after fitting')
    attempt = json.loads((experiment/'fit-attempt.json').read_text())
    if attempt.get('state') != 'SUCCESS' or attempt.get('protocolSha256') != identity(protocol):
        raise ValueError('Successful frozen fit required')
    for version in ('price-demand-v1','price-refit-v1'):
        if digest(experiment/'models'/version/'manifest.json') != attempt['modelManifestSha256'][version]:
            raise ValueError('Fitted model manifest changed after fitting')
    bundles = {method:load_bundle(experiment/'models'/version) for method,version in
        (('PRICE_DEMAND','price-demand-v1'),('PRICE_REFIT','price-refit-v1'))}
    for bundle in bundles.values():
        m = bundle['manifest']
        if (m['experimentProtocolSha256'] != identity(protocol) or instant(m['trainingDate']) >= test_start
                or instant(m['trainingCutoff']) != cutoff or instant(m['usableFrom']) != test_start
                or m['trainingInputsSha256'] != attempt['trainingInputsSha256']):
            raise ValueError('Candidate is not the pre-holdout fit')
    bundles['PRICE_FROZEN'] = frozen_bundle(frozen_path,protocol)
    selected,input_excluded = select_issues(issues,protocol,test_start,end)
    rows,outcome_excluded = complete_rows(selected,truth_at(prices,end,protocol['periodMappingEvidence']),test_start,end)
    if len(rows) < protocol['minimumTestOrigins']:
        raise ValueError('Insufficient complete common holdout origins')
    claim = experiment/'evaluation-attempt.json'
    with claim.open('x') as stream:
        json.dump({'state':'RUNNING','asOf':instant(as_of).isoformat()},stream)
    try:
        records = []
        for issue,past,actuals in rows:
            origin = instant(issue['asOf'])
            forecasts = {**baselines(past.as_issued_prices,origin),
                **{method:predict(bundle,past,origin) for method,bundle in bundles.items()}}
            for h,(target,actual) in enumerate(zip(targets(origin),actuals),1):
                ref = reference(past,target,origin,protocol['spikeConfig'])
                threshold = ref['threshold'] if ref['available'] else None
                for method in METHODS:
                    value = forecasts[method][h-1]
                    if not math.isfinite(value):
                        raise ValueError('Non-finite prediction; do not drop failed origins')
                    records.append(dict(asOf=origin.isoformat(),issueSlot=floor(origin).isoformat(),targetPeriod=target.isoformat(),
                        horizon=h,method=method,predictedUsep=value,actual=actual,truthPolicy='PROVISIONAL',threshold=threshold,
                        actualSpike=actual>threshold if threshold is not None else None,
                        spikeFlag=value>threshold if threshold is not None else None,exclusionReason=None))
        table = {}
        for method in METHODS:
            own = [r for r in records if r['method'] == method]
            labelled = [r for r in own if r['threshold'] is not None]
            table[method] = dict(all=_error(own),ordinary=_error([r for r in labelled if not r['actualSpike']]),
                spike=_error([r for r in labelled if r['actualSpike']]),classification=metrics(labelled),
                unavailableReferencePairs=len(own)-len(labelled),falseIssues=_false_issues(own),
                perHorizon={str(h):_error([r for r in own if r['horizon']==h]) for h in range(1,25)},
                testWeeks={str(w+1):_error([r for r in own if test_start+w*7*DAY<=instant(r['asOf'])<test_start+(w+1)*7*DAY]) for w in range(2)},
                fixedLeadEvents={str(h):event_metrics([r for r in labelled if r['horizon']==h]) for h in (1,6,12,24)})
        result = dict(protocolSha256=identity(protocol),commonOrigins=len(rows),commonPairs=len(rows)*24,
            table=table,inputExclusions=input_excluded,outcomeExclusions=outcome_excluded,
            syntheticTestOnly=protocol.get('syntheticTestOnly',False),priceUnits='SGD_PER_MWH',
            savingsValidation='PENDING_REAL_F4_REPLAY',genuineSpikeValidation=False,automaticPromotion=False,
            decision='REVIEW_PRICE_SPIKE_AND_SAVINGS_OUTCOMES_NO_AUTOMATIC_ADOPTION',
            caveats=['Identical received-outcome pairs for all six methods; missing truth never becomes a negative',
                     'Matched refit isolates demand from retraining on newer data',
                     'Overlapping horizons are dependent; two weeks cannot establish seasonal consistency'])
        report(experiment/'evaluation',records,result)
        write_json(claim,dict(state='SUCCESS',asOf=instant(as_of).isoformat(),commonPairs=result['commonPairs']))
        return result
    except BaseException as exc:
        write_json(claim,dict(state='FAILED',asOf=instant(as_of).isoformat(),errorType=type(exc).__name__))
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['fit','evaluate'])
    for name in ('issues','prices','protocol','frozen-model'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--as-of',type=instant,required=True)
    parser.add_argument('--root',type=Path)
    parser.add_argument('--experiment',type=Path)
    args = parser.parse_args()
    protocol = json.loads(args.protocol.read_text())
    _,cutoff,test_start,end = protocol_check(protocol)
    if args.action == 'fit':
        if args.root is None or not cutoff <= args.as_of < test_start:
            parser.error('Fit requires --root and the registered pre-holdout fit window')
    elif args.experiment is None or args.as_of < end:
        parser.error('Evaluation requires --experiment and the completed holdout')
    # Do not open issue/outcome exports before the timing gate passes.
    issues = [json.loads(line) for line in args.issues.read_text().splitlines() if line.strip()]
    prices = [json.loads(line) for line in args.prices.read_text().splitlines() if line.strip()]
    result = (fit(issues,prices,protocol,args.as_of,args.root,args.frozen_model) if args.action == 'fit'
        else evaluate(issues,prices,protocol,args.as_of,args.experiment,args.frozen_model))
    print(json.dumps(result,indent=2,allow_nan=False))


if __name__ == '__main__':
    main()
