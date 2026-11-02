"""Explicit offline PRICE_183D rehearsal. No scheduler, API writes or promotion.

Trusted local model/dataset inputs only. Retrospective paired results are not a
prospective live-trial result or an authorization to replace the serving model.
"""
import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
import math
import os
from pathlib import Path

from .artifacts import load_bundle, save_bundle
from .backtest import History, fresh, origins, predict, scores
from .baselines import baselines
from .data import digest, load_dataset, truth, write_json
from .features import features
from .price_only import PRICE_ONLY_POLICY, completed_price_history
from .spikes import reference
from .timebase import DAY, STEP, floor, instant, targets


MAPPING_EVIDENCE = 'EMC_RT_20260929_24_PERIODS'


def mapped(row, evidence):
    # Same narrowly scoped evidence used by the registered demand evaluator.
    # Preserve original UNVERIFIED_FLOOR metadata; never relabel the source file.
    return row.get('timeMapping')=='EMC_PERIOD' or (
        evidence==MAPPING_EVIDENCE and row.get('source')=='NEMS_SN_SG'
        and row.get('timeMapping')=='UNVERIFIED_FLOOR'
        and floor(instant(row['sourceUpdatedAt']))==instant(row['periodStart']))


def training_matrix(rows, start, cutoff):
    """Same completed-price feature function as serving; purge all 24 labels."""
    history, actual = History(rows), truth(rows, 'PROVISIONAL', cutoff)
    xs, ys, excluded = [[] for _ in range(24)], [[] for _ in range(24)], Counter()
    for origin in origins(start, cutoff):
        ts = targets(origin)
        if ts[-1]+STEP > cutoff:
            excluded['PURGED_SPLIT_BOUNDARY'] += 1
            continue
        past = history.at(origin)
        if not fresh(past, origin):
            excluded['STALE_OR_MISSING_INPUT'] += 1
            continue
        try:
            completed = completed_price_history(past, origin)
        except ValueError:
            excluded['LATEST_COMPLETED_PRICE_MISSING'] += 1
            continue
        if not all(t in actual for t in ts):
            excluded['MISSING_RECEIVED_TRAINING_TRUTH'] += 1
            continue
        for h, target in enumerate(ts):
            xs[h].append(features(completed, origin, target, schema_version=2))
            ys[h].append(actual[target])
    return xs, ys, dict(excluded)


def fit_candidate(champion, rows, start, cutoff, as_of):
    from sklearn.base import clone
    from threadpoolctl import threadpool_limits
    xs, ys, excluded = training_matrix(rows, start, cutoff)
    if len(ys[0]) < 48:
        raise ValueError('Need at least 48 complete training origins; keep the current model')
    models = []
    with threadpool_limits(limits=1):
        for template, x, y in zip(champion['models'], xs, ys):
            # Clone resets fitted state, preserving the exact trusted pipeline/parameters.
            models.append(clone(template).fit(x, y))
    manifest = {key:deepcopy(champion['manifest'][key]) for key in (
        'schemaVersion', 'featureSchemaVersion', 'featureOrder', 'inputPolicy',
        'expectedDemandPolicy', 'holidayCalendarVersion', 'holidayCalendarSha256',
        'baselineRanking', 'spikeConfig', 'researchRecipe')}
    manifest.update(version='weekly-price-only-candidate', trainingDate=as_of.isoformat(),
        trainingStart=start.isoformat(), trainingCutoff=cutoff.isoformat(),
        usableFrom=as_of.isoformat(), trainingOrigins=len(ys[0]),
        trainingExclusions=excluded, productionEligible=False, exploratory=True,
        retrospective=True, inputProvenance='Receipt-stamped provisional inputs; offline historical replay',
        baselineRankingSource='Copied unchanged from pinned champion; not selected on this evaluation',
        promotionPolicy='SHADOW_ONLY_NO_AUTOMATIC_ADOPTION')
    return {'manifest':manifest, 'models':models}


def paired_records(rows, candidate, champion, start, end, as_of):
    """One history, reference and truth vintage per pair, shared across five arms."""
    history, actual = History(rows), truth(rows, 'PROVISIONAL', as_of)
    result = []
    for origin in origins(start, end):
        ts = targets(origin)
        past = history.at(origin)
        reason = ('PURGED_SPLIT_BOUNDARY' if ts[-1]+STEP > end else
                  'STALE_OR_MISSING_INPUT' if not fresh(past, origin) else None)
        if not reason:
            try:
                completed_price_history(past, origin)
            except ValueError:
                reason = 'LATEST_COMPLETED_PRICE_MISSING'
        predictions = {'B1':None, 'B2':None, 'B3':None, 'AI':None, 'CURRENT':None}
        if not reason:
            predictions.update(baselines(past, origin))
            # Explicit historical reconstruction: no claim the newly fitted artifact
            # existed at origin. predict() does not read usableFrom/trainingDate.
            predictions['AI'] = predict(candidate, past, origin)
            predictions['CURRENT'] = predict(champion, past, origin)
        for values in predictions.values():
            if values is not None and (len(values)!=24 or not all(math.isfinite(v) for v in values)):
                raise ValueError('Invalid model/baseline output')
        for h, target in enumerate(ts, 1):
            ref = reference(past, target, origin, champion['manifest']['spikeConfig'])
            observed = actual.get(target)
            for method, values in predictions.items():
                why = reason or ('INSUFFICIENT_HISTORY' if values is None else
                                 'MISSING_TRUTH' if observed is None else None)
                price = values[h-1] if why is None else None
                result.append(dict(asOf=origin.isoformat(), targetPeriod=target.isoformat(),
                    horizon=h, method=method, predictedUsep=price, actual=observed,
                    threshold=ref['threshold'], spikeFlag=price > ref['threshold']
                        if price is not None and ref['available'] else None,
                    actualSpike=observed > ref['threshold']
                        if observed is not None and ref['available'] else None,
                    exclusionReason=why))
    return result


def comparison(records):
    """Expose both common-pair and own coverage; never compare different subsets."""
    methods = ('AI', 'CURRENT', 'B1', 'B2', 'B3')
    eligible = {m:{(r['asOf'],r['horizon'],r['targetPeriod']) for r in records
                   if r['method']==m and r['exclusionReason'] is None} for m in methods}
    common = set.intersection(*eligible.values())
    summary = scores(records, 'B3')
    for method in methods:
        valid = [r for r in records if r['method']==method and
                 (r['asOf'],r['horizon'],r['targetPeriod']) in common]
        def metrics(items):
            errors = [r['predictedUsep']-r['actual'] for r in items]
            return {'pairs':len(errors), 'mae':sum(map(abs,errors))/len(errors) if errors else None,
                'rmse':math.sqrt(sum(e*e for e in errors)/len(errors)) if errors else None}
        summary['table'][method].update(rmse=metrics(valid)['rmse'],
            priceRegimes={name:metrics([r for r in valid if r['actualSpike'] is flag])
                          for name,flag in (('ordinary',False),('spike',True),('unassessed',None))})
    # The existing score helper keys pairs by origin/horizon. Assert target identity
    # as well, so an accidental target shift can never pass a paired comparison.
    if summary['commonPairs'] != len(common):
        raise ValueError('Paired target identities disagree')
    summary['commonPairIdentitySha256'] = hashlib.sha256(json.dumps(sorted(common)).encode()).hexdigest()
    summary['units'] = 'SGD_PER_MWH'
    summary['warnings'] = 'Repeated origin/horizon statistical flags, not household notifications or independent events'
    ai, current = (summary['table'][m]['mae'] for m in ('AI','CURRENT'))
    summary['candidateMaeNoWorseOnCommonPairs'] = ai <= current if ai is not None and current is not None else None
    summary.update(adoptionEligible=False, promotionDecision='NOT_ATTEMPTED_SHADOW_ONLY',
                   savingsOrHarmEvaluated=False, prospectiveValidation=False)
    return summary


def run(dataset, champion_path, champion_sha, output, cutoff, end, as_of, *, protected=None, mapping_evidence=None):
    dataset, champion_path, output = map(lambda p:Path(p).resolve(), (dataset,champion_path,output))
    cutoff, end, as_of = map(instant, (cutoff,end,as_of))
    # Validate before loading trusted joblib or writing an audit directory.
    if mapping_evidence not in (None,MAPPING_EVIDENCE):
        raise ValueError('Unsupported mapping evidence')
    if cutoff!=floor(cutoff) or end!=floor(end) or cutoff>=end:
        raise ValueError('Split boundaries must be ordered half-hours')
    if end-cutoff < 26*STEP or end-cutoff > 28*DAY or as_of < end:
        raise ValueError('Need a matured evaluation of 13 hours to 28 days')
    if any(output==p or output in p.parents or p in output.parents for p in (dataset,champion_path)):
        raise ValueError('Output must be separate from source dataset and champion')
    if os.environ.get('WATTLY_ML_HOME'):
        home = Path(os.environ['WATTLY_ML_HOME']).resolve()
        if output==home or home in output.parents:
            raise ValueError('Output must be outside the serving runtime')
    protected = tuple(map(instant, protected)) if protected else None
    if protected and (len(protected)!=2 or protected[0]>=protected[1] or
                      max(cutoff,protected[0]) < min(end,protected[1])):
        raise ValueError('Invalid protected interval or evaluation overlaps it')
    manifest_path = champion_path/'manifest.json'
    if len(champion_sha)!=64 or digest(manifest_path)!=champion_sha:
        raise ValueError('Champion manifest pin mismatch')
    hashes = {str(path):digest(path) for path in (dataset/'prices.jsonl',dataset/'manifest.json',
                                                manifest_path,champion_path/'models.joblib')}
    if hashes[str(manifest_path)]!=champion_sha:
        raise ValueError('Champion changed before loading')
    champion = load_bundle(champion_path)
    m = champion['manifest']
    if (m.get('inputPolicy')!=PRICE_ONLY_POLICY or m.get('researchRecipe')!='PRICE_183D'
            or m.get('expectedDemandPolicy')!='ALWAYS_MISSING_AS_TRAINED'
            or m.get('featureSchemaVersion')!=2 or m.get('productionEligible') is not False):
        raise ValueError('Require the pinned experimental PRICE_183D champion')
    if max(instant(m['trainingCutoff']),instant(m['usableFrom'])) > cutoff:
        raise ValueError('Champion learned from the evaluation period')
    if len(champion['models'])!=24 or any(model.n_features_in_!=19 for model in champion['models']):
        raise ValueError('Champion does not have 24 compatible horizon pipelines')
    rows, data_manifest = load_dataset(dataset)
    if (data_manifest['quality']['parsingFailures'] or not rows or any(
            r.get('priceStatus')!='PROVISIONAL' or not r.get('availableAt') or not r.get('sourceUpdatedAt')
            or not instant(r['periodStart'])<=instant(r['sourceUpdatedAt'])<=instant(r['availableAt'])
            or not mapped(r,mapping_evidence) for r in rows)):
        raise ValueError('Require verified, receipt-stamped provisional data; no reconstructed availability')
    if any(digest(Path(path))!=value for path,value in hashes.items()):
        raise ValueError('Input changed while loading; no fit attempted')
    if protected:
        rows = [r for r in rows if not protected[0]<=instant(r['periodStart'])<protected[1]]
    start = cutoff-183*DAY
    protocol = dict(trainingStart=start.isoformat(),trainingCutoff=cutoff.isoformat(),
        validationStart=cutoff.isoformat(), validationEnd=end.isoformat(),asOf=as_of.isoformat(),
        truthPolicy='PROVISIONAL', truthReceiptCutoff=as_of.isoformat(),
        championVersion=m['version'],championManifestSha256=champion_sha,
        inputHashes=hashes,protectedInterval=[t.isoformat() for t in protected] if protected else None,
        mappingEvidence=mapping_evidence,
        scheduleEnabled=False,productionEligible=False,automaticPromotion=False,
        selection='One clone of the pinned price-only pipelines; no search',
        evaluationKind='RECONSTRUCTED_PAIRED_HISTORY_NOT_A_LIVE_TRIAL',
        declaredSynthetic=all(r.get('source')=='SYNTHETIC' for r in rows))
    output.mkdir(parents=True,exist_ok=False)
    write_json(output/'protocol.json',protocol)
    attempt = dict(trainingDate=as_of.isoformat(),dataRange=[start.isoformat(),cutoff.isoformat()],
                   state='RUNNING',promotionDecision='NOT_ATTEMPTED_SHADOW_ONLY',scores={})
    write_json(output/'attempt.json',attempt)
    try:
        candidate = fit_candidate(champion,rows,start,cutoff,as_of)
        candidate['manifest'].update(sourceChampionManifestSha256=champion_sha,
            sourceDatasetSha256=data_manifest['sha256'],validationStart=cutoff.isoformat(),
            validationEnd=end.isoformat())
        saved = save_bundle(candidate,output/'candidates','weekly-price-only-candidate')
        candidate = load_bundle(output/'candidates'/saved['version'])
        records = paired_records(rows,candidate,champion,cutoff,end,as_of)
        summary = comparison(records)
        summary.update(trainingOrigins=candidate['manifest']['trainingOrigins'],
            expectedTrainingWindowOrigins=183*48-24,
            trainingExclusions=candidate['manifest']['trainingExclusions'],
            trainingCoverage=candidate['manifest']['trainingOrigins']/(183*48-24),
            validationDays=(end-cutoff)/DAY,declaredSynthetic=protocol['declaredSynthetic'])
        if any(digest(Path(path))!=value for path,value in hashes.items()):
            raise ValueError('Input changed during the attempt; results invalid')
        write_json(output/'comparison.json',summary)
        with (output/'predictions.jsonl').open('x') as stream:
            for record in records:
                stream.write(json.dumps(record,allow_nan=False)+'\n')
        attempt.update(state='COMPLETE',scores=summary,sourceHashesVerified=True)
        write_json(output/'attempt.json',attempt)
        return summary
    except BaseException as exc:
        attempt.update(state='FAILED',reason=type(exc).__name__+': '+str(exc),resultsValid=False)
        write_json(output/'attempt.json',attempt)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('dataset','champion','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--champion-manifest-sha256',required=True)
    parser.add_argument('--mapping-evidence',choices=[MAPPING_EVIDENCE])
    for name in ('training-cutoff','validation-end','as-of'):
        parser.add_argument('--'+name,type=instant,required=True)
    parser.add_argument('--protected-start',type=instant)
    parser.add_argument('--protected-end',type=instant)
    args = parser.parse_args()
    if bool(args.protected_start)!=bool(args.protected_end):
        parser.error('Supply both protected boundaries')
    result = run(args.dataset,args.champion,args.champion_manifest_sha256,args.output,
        args.training_cutoff,args.validation_end,args.as_of,
        protected=(args.protected_start,args.protected_end) if args.protected_start else None,
        mapping_evidence=args.mapping_evidence)
    print(json.dumps({k:result[k] for k in ('commonPairs','trainingOrigins','adoptionEligible','promotionDecision')}))


if __name__=='__main__':
    main()
