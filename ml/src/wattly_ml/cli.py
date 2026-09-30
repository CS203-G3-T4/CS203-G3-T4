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


def main(argv=None):
    p = argparse.ArgumentParser(description="Wattly immutable data, offline evaluation and internal serving")
    sub = p.add_subparsers(dest="command",required=True)
    s = sub.add_parser("snapshot"); s.add_argument("source",type=Path); s.add_argument("--kind",choices=["sqlite","combined","emc"],required=True)
    s.add_argument("--output",type=Path,required=True); s.add_argument("--source-url")
    s = sub.add_parser("prepare"); s.add_argument("snapshots",type=Path,nargs="+"); s.add_argument("--output",type=Path,required=True)
    s.add_argument("--provisional-offset-minutes",type=int,default=0)
    s = sub.add_parser("demo-report",help="Separate, unapproved latest-complete-day presentation experiment")
    s.add_argument("dataset",type=Path); s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("compare-models",help="Fixed offline candidates; validation selection and separate already-seen test diagnostics")
    s.add_argument("dataset",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("study-demo",help="Export saved accuracy-study predictions for an unapproved presentation")
    s.add_argument("study",type=Path); s.add_argument("--reference",type=Path,required=True)
    s.add_argument("--output",type=Path,required=True)
    for name in ("backtest","train","evaluate","weekly"):
        s = sub.add_parser(name); s.add_argument("dataset",type=Path)
        s.add_argument("--start",type=instant,required=True); s.add_argument("--end",type=instant,required=True)
        s.add_argument("--truth-policy",choices=["FINAL","PROVISIONAL"],default="FINAL")
        s.add_argument("--retrospective",action="store_true"); s.add_argument("--output",type=Path,required=True)
        if name in ("train","weekly"):
            s.add_argument("--cutoff",type=instant,required=True); s.add_argument("--version",required=True)
            s.add_argument("--exploratory",action="store_true")
        if name=="evaluate":
            s.add_argument("--model",type=Path,required=True); s.add_argument("--final",action="store_true")
    for name in ("promote","rollback"):
        s = sub.add_parser(name); s.add_argument("version")
    s = sub.add_parser("decisions"); s.add_argument("cases",type=Path); s.add_argument("--output",type=Path,required=True)
    s = sub.add_parser("serve"); s.add_argument("--port",type=int,default=8001)
    args = p.parse_args(argv)
    try:
        if args.command=="snapshot":
            print(snapshot(args.source,args.output,args.kind,args.source_url)); return 0
        if args.command=="prepare":
            print(json.dumps(prepare(args.snapshots,args.output,args.provisional_offset_minutes),indent=2)); return 0
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
        if args.command in ("train","weekly"):
            from .train import train
            bundle,records = train(rows,manifest,args.start,args.cutoff,args.end,retrospective=args.retrospective,
                                   policy=args.truth_policy,exploratory=args.exploratory)
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
        result.update(datasetDigest=manifest["sha256"],quality=manifest["quality"],
                      evaluationKind="RETROSPECTIVE_FINAL_PRICE" if args.retrospective else "OPERATIONAL_AS_OF",
                      baselineSelection="VALIDATION" if bundle and bundle["manifest"]["baselineRanking"] else "UNRANKED",
                      start=args.start.isoformat(),end=args.end.isoformat())
        report(args.output,records,result)
        if args.command=="evaluate" and args.final and result["commonOriginDays"]<56:
            raise ValueError("Final evidence blocked: fewer than 56 days with common eligible pairs; see coverage report")
        if args.command=="weekly":
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
        print(json.dumps(result,indent=2)); return 0
    except (ValueError,OSError,KeyError) as exc:
        print(f"F3 unavailable: {exc}",file=sys.stderr); return 2


if __name__=="__main__":
    sys.exit(main())
