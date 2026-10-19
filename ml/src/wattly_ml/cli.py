import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

from .data import snapshot, prepare, load_dataset, write_json
from .timebase import DAY, instant


def report(directory, records, result):
    if directory.exists():
        raise ValueError("Report directory already exists")
    directory.parent.mkdir(parents=True,exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix=".report-",dir=directory.parent))
    try:
        with (work/"predictions.jsonl").open("w") as out:
            for row in records:
                out.write(json.dumps(row,allow_nan=False)+"\n")
        write_json(work/"summary.json",result)
        work.rename(directory)
    except BaseException:
        shutil.rmtree(work,ignore_errors=True)
        raise


def main(argv=None, *, training_attempt=None):
    p = argparse.ArgumentParser(description="Wattly immutable data, offline evaluation and internal serving")
    sub = p.add_subparsers(dest="command",required=True)
    s = sub.add_parser("snapshot"); s.add_argument("source",type=Path); s.add_argument("--kind",choices=["sqlite","combined","emc"],required=True)
    s.add_argument("--output",type=Path,required=True); s.add_argument("--source-url")
    s = sub.add_parser("prepare"); s.add_argument("snapshots",type=Path,nargs="+"); s.add_argument("--output",type=Path,required=True)
    s.add_argument("--provisional-offset-minutes",type=int,default=0)
    s.add_argument('--demand-forecasts',type=Path)
    s = sub.add_parser("demo-report",help="Separate, unapproved latest-complete-day presentation experiment")
    s.add_argument("dataset",type=Path); s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("compare-models",help="Fixed offline candidates; validation selection and separate already-seen test diagnostics")
    s.add_argument("dataset",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("study-demo",help="Export saved accuracy-study predictions for an unapproved presentation")
    s.add_argument("study",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("retrain-study",help="Fresh chronological holdout; compare refitted models with the frozen previous study")
    s.add_argument("dataset",type=Path); s.add_argument("--previous-study",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("correction-study",help="Baseline residuals selected before the protected old holdout; freeze future confirmation")
    s.add_argument("dataset",type=Path); s.add_argument("--previous-study",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("confirm-study",help="Evaluate frozen research models on seven future days; never fit or promote")
    s.add_argument("dataset",type=Path); s.add_argument("--study",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("stability-study",help="Compare fixed blends, shared horizons, regularisation and recent training; freeze later confirmation")
    s.add_argument("dataset",type=Path); s.add_argument("--previous-study",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("time-series-study",help="Train recursive AR(6) on earlier data and compare with saved HGB forecasts")
    s.add_argument("dataset",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("time-series-improvement-study",help="Daily lags, robust loss, changes and direct horizons; preserve original AR/HGB")
    s.add_argument("dataset",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("confirm-time-series",help="Evaluate frozen time-series ablations on seven later days; never fit or promote")
    s.add_argument("dataset",type=Path); s.add_argument("--study",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser('improve-history',help='Compare fixed long/recent schema2 models on earlier validation; freeze future confirmation')
    s.add_argument('dataset',type=Path); s.add_argument('--historical',type=Path,required=True)
    s.add_argument('--reference-model',type=Path,required=True); s.add_argument('--protocol',type=Path,required=True)
    s.add_argument('--output',type=Path,required=True)
    s = sub.add_parser('confirm-improvement',help='Evaluate the frozen research candidate on its future window; never fit or promote')
    s.add_argument('dataset',type=Path); s.add_argument('--study',type=Path,required=True)
    s.add_argument('--output',type=Path,required=True)
    s = sub.add_parser('grade-spikes',help='Grade saved predictions for missed spikes, false alarms and advance event warnings without fitting')
    s.add_argument('predictions',type=Path); s.add_argument('--output',type=Path,required=True)
    for name in ("backtest","train","evaluate","weekly"):
        s = sub.add_parser(name); s.add_argument("dataset",type=Path)
        s.add_argument("--start",type=instant,required=True); s.add_argument("--end",type=instant,required=True)
        s.add_argument("--truth-policy",choices=["FINAL","PROVISIONAL"],default="FINAL")
        s.add_argument("--retrospective",action="store_true"); s.add_argument("--output",type=Path,required=True)
        if name in ("train","weekly"):
            s.add_argument("--cutoff",type=instant,required=True); s.add_argument("--version",required=True)
            s.add_argument("--exploratory",action="store_true")
            s.add_argument('--protected-start',type=instant)
            s.add_argument('--protected-end',type=instant)
        if name=="evaluate":
            s.add_argument("--model",type=Path,required=True); s.add_argument("--final",action="store_true")
    for name in ("promote","rollback"):
        s = sub.add_parser(name); s.add_argument("version")
    s = sub.add_parser("decisions"); s.add_argument("cases",type=Path); s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("serve"); s.add_argument("--port",type=int,default=8001)
    s = sub.add_parser('sync-runs'); s.add_argument('home',type=Path)
    s = sub.add_parser('savings-study'); s.add_argument('predictions',type=Path)
    s.add_argument('--validation',type=Path,required=True); s.add_argument('--model-manifest',type=Path,required=True)
    s.add_argument('--households',type=int,nargs='+',required=True); s.add_argument('--output',type=Path,required=True)
    for name in ('baseline-study','spike-study'):
        s=sub.add_parser(name); s.add_argument('dataset',type=Path)
        s.add_argument('--start',type=instant,required=True); s.add_argument('--end',type=instant,required=True)
        s.add_argument('--output',type=Path,required=True); s.add_argument('--retrospective',action='store_true')
    args = p.parse_args(argv)
    attempt = training_attempt
    try:
        if args.command == 'weekly' and attempt is None:
            from .operations import TrainingAttempt
            attempt = TrainingAttempt(args)
            attempt.start()
        if args.command == 'sync-runs':
            from .operations import sync_runs
            print(json.dumps(sync_runs(args.home))); return 0
        if args.command == 'savings-study':
            from .household_replay import study
            print(json.dumps(study(args.predictions,args.validation,args.model_manifest,args.households,args.output),indent=2)); return 0
        if args.command in ('baseline-study','spike-study'):
            from .evidence import baseline_study, sensitivity
            result=(baseline_study if args.command=='baseline-study' else sensitivity)(args.dataset,args.start,args.end,args.output,args.retrospective)
            print(json.dumps(result,indent=2)); return 0
        if args.command=="snapshot":
            print(snapshot(args.source,args.output,args.kind,args.source_url)); return 0
        if args.command=="prepare":
            print(json.dumps(prepare(args.snapshots,args.output,args.provisional_offset_minutes,args.demand_forecasts),indent=2)); return 0
        if args.command=="demo-report":
            from .demo import experiment
            print(json.dumps(experiment(args.dataset,args.output),indent=2)); return 0
        if args.command=="compare-models":
            from .experiments import compare
            result = compare(args.dataset,args.reference,args.output)
            print(json.dumps({'status':result['status'],'output':str(args.output),
                              'selectedMethod':result['selection']['selectedMethod']},indent=2)); return 0
        if args.command=="study-demo":
            from .experiments import presentation
            print(json.dumps(presentation(args.study,args.reference,args.output),indent=2)); return 0
        if args.command=="retrain-study":
            from .retraining import retrain
            result = retrain(args.dataset,args.previous_study,args.output)
            print(json.dumps({'status':result['status'],'output':str(args.output),
                'selectedMethod':result['selection']['selectedMethod'],
                'selectedLearnedModel':result['selection']['selectedLearnedModel'],
                'testStart':result['protocol']['testStart'],
                'testPairs':result['heldOutTest']['commonPairs']},indent=2)); return 0
        if args.command in ('correction-study', 'confirm-study'):
            from .corrections import study, evaluate
            result = study(args.dataset,args.previous_study,args.output) if args.command == 'correction-study' else evaluate(args.dataset,args.study,args.output)
            print(json.dumps({'status':result['status'],'output':str(args.output),
                'selection':result.get('selection',{}).get('selectedLearnedModel'),
                'prospective':result.get('prospective',{})},indent=2)); return 0
        if args.command == 'stability-study':
            from .stability import study
            result = study(args.dataset,args.previous_study,args.output)
            print(json.dumps({'status':result['status'],'output':str(args.output),
                'selectedMethod':result['selection']['selectedMethod'],
                'selectedLearnedModel':result['selection']['selectedLearnedModel'],
                'prospective':result['prospective']},indent=2)); return 0
        if args.command in ('time-series-study','time-series-improvement-study'):
            from .timeseries import study
            result = study(args.dataset,args.reference,args.output,improvements=args.command=='time-series-improvement-study')
            print(json.dumps({'status':result['status'],'output':str(args.output),
                'selectedMethod':result['selection']['selectedMethod'],
                'validationPairs':result['selection']['validation']['commonPairs']},indent=2)); return 0
        if args.command == 'confirm-time-series':
            from .timeseries import confirm
            result = confirm(args.dataset,args.study,args.output)
            print(json.dumps({'status':result['status'],'output':str(args.output),'commonPairs':result['commonPairs']},indent=2)); return 0
        if args.command == 'improve-history':
            from .improvement import study
            result = study(args.dataset,args.historical,args.reference_model,args.protocol,args.output)
            print(json.dumps({'status':result['status'],'selectedCandidate':result['selection']['selectedCandidate'],
                'bestMethod':result['selection']['bestMethod'],'prospective':result['prospective']},indent=2)); return 0
        if args.command == 'confirm-improvement':
            from .improvement import confirm
            result = confirm(args.dataset,args.study,args.output)
            print(json.dumps({'status':result['status'],'commonPairs':result['commonPairs']},indent=2)); return 0
        if args.command == 'grade-spikes':
            from .spike_grading import grade
            result = grade(args.predictions,args.output)
            print(json.dumps({'commonLabelledPairs':result['commonLabelledPairs'],
                'methods':result['methods'],'output':str(args.output)},indent=2)); return 0
        if args.command=="decisions":
            from .decisions import compare
            write_json(args.output,compare(json.loads(args.cases.read_text()))); return 0
        if args.command=="serve":
            import uvicorn
            uvicorn.run("wattly_ml.serving.app:app",host="127.0.0.1",port=args.port,limit_concurrency=4); return 0
        from .artifacts import load_bundle, save_bundle, promote, current_bundle
        if args.command in ("promote","rollback","train","weekly"):
            value = os.environ.get("WATTLY_ML_HOME")
            if not value:
                raise ValueError("Set WATTLY_ML_HOME; .env is not automatically loaded")
            home = Path(value).expanduser()
        if args.command in ("promote","rollback"):
            print(json.dumps(promote(home,args.version,rollback=args.command=="rollback"))); return 0
        from .backtest import backtest,scores
        rows,manifest = load_dataset(args.dataset)
        if attempt:
            attempt.update(datasetDigest=manifest.get('featureInputsDigest',manifest['sha256']))
        if args.command in ("train","weekly"):
            if bool(args.protected_start) != bool(args.protected_end):
                raise ValueError('Supply both protected interval boundaries')
            if args.protected_start:
                if not args.protected_start < args.protected_end:
                    raise ValueError('Invalid protected interval')
                # Remove protected prices from both labels and lagged inputs; never fill the resulting gaps.
                from .data import DatasetRows
                rows = DatasetRows([r for r in rows if not args.protected_start <= instant(r['periodStart']) < args.protected_end],
                    getattr(rows,'demand_forecasts',()))
            from .train import train
            bundle,records = train(rows,manifest,args.start,args.cutoff,args.end,retrospective=args.retrospective,
                                   policy=args.truth_policy,exploratory=args.exploratory)
            bundle['manifest']['protectedInterval'] = ([args.protected_start.isoformat(),args.protected_end.isoformat()]
                                                       if args.protected_start else None)
            saved = save_bundle(bundle,home/"models",args.version)
            result = saved["validation"]
        else:
            bundle = load_bundle(args.model) if args.command=="evaluate" else None
            if args.command=="evaluate" and args.final:
                if args.end-args.start<56*DAY:
                    raise ValueError("Final evaluation requires at least eight untouched weeks")
                if bundle["manifest"]["exploratory"] or manifest["quality"]["parsingFailures"]:
                    raise ValueError("Exploratory/invalid data cannot support final evidence")
            records = backtest(rows,args.start,args.end,policy=args.truth_policy,retrospective=args.retrospective,bundle=bundle)
            ranking = bundle["manifest"]["baselineRanking"] if bundle else []
            result = scores(records,ranking[0] if ranking else "B1")
        result.update(datasetDigest=manifest.get('featureInputsDigest',manifest["sha256"]),quality=manifest["quality"],
                      evaluationKind="RETROSPECTIVE_FINAL_PRICE" if args.retrospective else "OPERATIONAL_AS_OF",
                      baselineSelection="VALIDATION" if bundle and bundle["manifest"]["baselineRanking"] else "UNRANKED",
                      start=args.start.isoformat(),end=args.end.isoformat())
        report(args.output,records,result)
        if args.command=="evaluate" and args.final and result["commonOriginDays"]<56:
            raise ValueError("Final evidence blocked: fewer than 56 days with common eligible pairs; see coverage report")
        if args.command=="weekly":
            attempt.stage = 'PROMOTION'
            attempt.update(scores=result)
            # Save rejected candidate evidence too. Promotion never reads final-test results.
            champion_score = None
            if (home/"current.json").exists():
                champion = current_bundle(home)
                prior = backtest(rows,args.cutoff,args.end,policy=args.truth_policy,retrospective=args.retrospective,
                                 bundle=champion,truth_cutoff=args.end)
                candidate_pairs = {(r["asOf"],r["horizon"]) for r in records if r["method"]=="AI" and r["exclusionReason"] is None}
                prior_pairs = {(r["asOf"],r["horizon"]) for r in prior if r["method"]=="AI" and r["exclusionReason"] is None}
                if candidate_pairs!=prior_pairs:
                    raise ValueError("Champion/candidate validation coverage differs")
                champion_score = {"version":champion["manifest"]["version"],"validationEnd":args.end.isoformat(),
                                  "mae":scores(prior)["table"]["AI"]["mae"]}
                if champion_score["mae"] is None:
                    raise ValueError("No common champion validation pairs")
            promote(home,args.version,champion_score=champion_score)
            attempt.finish('SUCCESS',decision='PROMOTED')
        print(json.dumps(result,indent=2)); return 0
    except (ValueError,OSError,KeyError) as exc:
        if attempt:
            attempt.finish('REJECTED' if attempt.stage=='PROMOTION' else 'FAILED',str(exc))
        print(f"F3 unavailable: {exc}",file=sys.stderr); return 2
    finally:
        if attempt:
            attempt.close()


if __name__=="__main__":
    sys.exit(main())
