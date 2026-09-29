"""Offline, unapproved presentation experiment using the existing 24-horizon pipeline."""
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
import tempfile

from .artifacts import save_bundle
from .backtest import backtest, scores
from .cli import report
from .data import as_of, load_dataset, truth, write_json
from .timebase import DAY, SGT, STEP, instant
from .train import train

LABEL = "Experimental — limited training data."


def experiment(dataset: Path, output: Path):
    if output.exists():
        raise ValueError("Demo experiments are immutable; choose a new output directory")
    rows, manifest = load_dataset(dataset)
    if not rows or manifest["quality"]["parsingFailures"] or not manifest["quality"]["publicationVintages"]:
        raise ValueError("Demo requires valid collector publication/availability vintages")
    if manifest["provisionalOffsetMinutes"] != 0:
        raise ValueError("The documented live mapping evidence covers only the zero-offset floor rule")
    acquired = max(instant(s["acquiredAt"]) for s in manifest["sources"])
    test_end = acquired.astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)
    test_start, cutoff = test_end-DAY, test_end-2*DAY
    periods = sorted({instant(r["periodStart"]) for r in rows})
    # Start on the first fully observed calendar date, retaining earlier input warm-up.
    start = periods[0].astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)
    if periods[0] > start:
        start += DAY
    if cutoff-start < 2*DAY:
        raise ValueError("Not enough earlier days for the 24-horizon experiment; no contract was shortened")
    protocol = {"label":LABEL,"horizonCount":24,"intervalMinutes":30,"timezone":"Asia/Singapore",
                "trainingStart":start.isoformat(),"trainingCutoff":cutoff.isoformat(),
                "validationStart":cutoff.isoformat(),"validationEnd":test_start.isoformat(),
                "testStart":test_start.isoformat(),"testEnd":test_end.isoformat(),
                "parameters":"Existing 40-iteration, 15-leaf models, seed 42; no hyperparameter search",
                "comparisonRule":"Include baselines with eligible validation pairs; freeze before testing; score common pairs only",
                "truthPolicy":"PROVISIONAL, latest revision published and available by each split end",
                "purging":"Exclude origins whose last target interval ends after the split boundary",
                "datasetDigest":manifest["sha256"],"snapshotAcquiredAt":acquired.isoformat(),
                "mappingEvidenceId":os.environ.get("FORECAST_PERIOD_MAPPING_EVIDENCE")}
    output.parent.mkdir(parents=True,exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".demo-",dir=output.parent))
    try:
        write_json(work/"protocol.json",protocol)
        # The trainer never even receives held-out or later revisions. The shared
        # trainer also enforces its own training/validation availability cutoffs.
        earlier = [r for r in rows if max(instant(r[k]) for k in
                   ("periodStart","sourceUpdatedAt","availableAt")) < test_start]
        bundle, validation = train(earlier,manifest,start,cutoff,test_start,policy="PROVISIONAL",exploratory=True)
        methods = ["AI"]+[m for m in ("B1","B2","B3")
                           if any(r["method"]==m and r["exclusionReason"] is None for r in validation)]
        validation_scores = scores([r for r in validation if r["method"] in methods])
        if len(methods)<2 or not validation_scores["commonPairs"]:
            raise ValueError("No common validation pairs for the existing 24-horizon comparison")
        selected = min(methods[1:],key=lambda m:validation_scores["table"][m]["mae"])
        protocol.update(comparisonMethods=methods,validationSelectedBaseline=selected)
        write_json(work/"protocol.json",protocol)  # Freeze the validation choice before opening test outcomes.
        version = "experimental-"+test_start.date().isoformat()+"-"+manifest["sha256"][:12]
        saved = save_bundle(bundle,work/"models",version)
        bundle["manifest"] = saved
        records = backtest(rows,test_start,test_end,policy="PROVISIONAL",bundle=bundle,truth_cutoff=test_end)
        paired = scores([r for r in records if r["method"] in methods],selected)
        if not paired["commonPairs"]:
            raise ValueError("No held-out common pairs; no 24-horizon accuracy claim can be made")
        all_methods = scores(records,selected)
        exclusions = {m:dict(Counter(r["exclusionReason"] for r in records
                                    if r["method"]==m and r["exclusionReason"])) for m in ("AI","B1","B2","B3")}
        gaps = [a+STEP*i for a,b in zip(periods,periods[1:]) for i in range(1,int((b-a)/STEP))]
        lags = [(instant(r["availableAt"])-instant(r["sourceUpdatedAt"])).total_seconds() for r in rows]
        audit = {**manifest["quality"],"from":periods[0].astimezone(SGT).isoformat(),
                 "to":periods[-1].astimezone(SGT).isoformat(),
                 "missingPeriods":[p.astimezone(SGT).isoformat() for p in gaps],
                 "periodsBySingaporeDay":dict(Counter(str(p.astimezone(SGT).date()) for p in periods)),
                 "knownPeriodsAtSnapshot":len(as_of(rows,acquired)),
                 "availabilityBeforePublication":sum(v<0 for v in lags),
                 "availabilityLagSeconds":{"minimum":min(lags),"maximum":max(lags)}}
        summary = {"schemaVersion":1,"label":LABEL,"actionable":False,"productionApproved":False,
                   "generatedAt":datetime.now(timezone.utc).isoformat(),"modelVersion":version,
                   "protocol":protocol,"quality":audit,"trainingOrigins":saved["trainingOrigins"],
                   "excludedTrainingOrigins":saved["excludedTrainingOrigins"],
                   "validation":validation_scores,"test":paired,"allMethods":all_methods,"exclusions":exclusions,
                   "limitations":["One held-out day; overlapping forecasts are not independent samples.",
                       "Provisional prices, not final settlement truth. No weather or demand predictors.",
                       "Retrospective experiment trained today; not proof an artifact existed on the test day.",
                       "B3 needs seven prior days; its availability and the all-method common set are reported separately.",
                       "No production promotion, validated spike accuracy, household savings or application replay claim."]}
        report(work/"validation",validation,validation_scores)
        report(work/"test",records,summary)
        # Public export contains only the offline results, never model files or collector payloads.
        by_pair = {}
        for r in records:
            key = (r["asOf"],r["horizon"])
            point = by_pair.setdefault(key,{"horizon":r["horizon"],"targetPeriod":r["targetPeriod"],"actual":r["actual"]})
            point[r["method"]] = r["predictedUsep"] if r["exclusionReason"] is None else None
        origins = {}
        for (origin,_), point in sorted(by_pair.items()):
            if all(point[m] is not None for m in methods):
                origins.setdefault(origin,[]).append(point)
        actuals = truth(rows,"PROVISIONAL",test_end)
        public = {**summary,"origins":[{"asOf":o,"points":ps} for o,ps in origins.items()],
                  "actuals":[{"targetPeriod":p.isoformat(),"actual":v} for p,v in sorted(actuals.items())
                             if test_start<=p<test_end]}
        (work/"public").mkdir()
        write_json(work/"public"/"forecast-demo.json",public)
        work.rename(output)
        return summary
    except BaseException:
        shutil.rmtree(work,ignore_errors=True)
        raise
