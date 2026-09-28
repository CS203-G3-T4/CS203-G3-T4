from datetime import datetime, timezone

from .backtest import History, origins, backtest, scores, fresh
from .data import truth
from .features import FEATURES, features
from .timebase import DAY, STEP, instant, targets


def train(rows, dataset_manifest, start, cutoff, validation_end, *, retrospective=False,
          policy="FINAL", exploratory=False, iterations=40):
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingRegressor
    from sklearn.impute import SimpleImputer
    from sklearn.pipeline import make_pipeline
    from threadpoolctl import threadpool_limits
    if not start < cutoff < validation_end:
        raise ValueError("Require train start < training cutoff < validation end")
    quality = dataset_manifest["quality"]
    if quality["parsingFailures"]:
        raise ValueError("Repair parsing failures before training")
    enough = (cutoff-start >= 365*DAY and validation_end-cutoff >= 28*DAY
              and quality["from"] is not None and instant(quality["from"])<=start-28*DAY and quality["mappingVerified"]
              and not quality["missingHalfHours"])
    if not exploratory and not enough:
        raise ValueError("Insufficient verified history; use --exploratory only for non-promotable experiments")
    history, actuals = History(rows, retrospective), truth(rows,policy,cutoff,retrospective)
    xs, ys = [[] for _ in range(24)], [[] for _ in range(24)]
    excluded = 0
    for origin in origins(start, cutoff):
        ts = targets(origin)
        if ts[-1]+STEP > cutoff or not all(t in actuals for t in ts):
            excluded += 1
            continue
        past = history.at(origin)
        if len(past)<6 or not fresh(past,origin):
            excluded += 1
            continue
        for h,t in enumerate(ts):
            xs[h].append(features(past,origin,t)); ys[h].append(actuals[t])
    if len(ys[0])<48:
        raise ValueError("At least 48 complete eligible training origins required")
    models = []
    with threadpool_limits(limits=1):
        for x,y in zip(xs,ys):
            model = make_pipeline(SimpleImputer(strategy="median",add_indicator=True,keep_empty_features=True),
                                  HistGradientBoostingRegressor(max_iter=iterations,max_leaf_nodes=15,
                                                                l2_regularization=1,random_state=42))
            models.append(model.fit(np.asarray(x),y))
    manifest = {"schemaVersion":1,"version":"candidate", "featureOrder":FEATURES, "trainingSeed":42,
                "trainingDate":datetime.now(timezone.utc).isoformat(), "trainingStart":start.isoformat(),
                "trainingCutoff":cutoff.isoformat(), "usableFrom":cutoff.isoformat(),
                "datasetDigest":dataset_manifest["sha256"],"datasetQuality":quality,
                "truthPolicy":policy,"retrospective":retrospective,"exploratory":exploratory,
                "productionEligible":enough and not exploratory,
                "trainingOrigins":len(ys[0]),"excludedTrainingOrigins":excluded,
                "parameters":{"max_iter":iterations,"max_leaf_nodes":15,"l2_regularization":1},
                "spikeConfig":{"minimumSamples":14,"k":3.0,"spreadFloor":1.0},
                "validationStart":cutoff.isoformat(),"validationEnd":validation_end.isoformat()}
    bundle = {"manifest":manifest,"models":models}
    records = backtest(rows,cutoff,validation_end,policy=policy,retrospective=retrospective,bundle=bundle,truth_cutoff=validation_end)
    result = scores(records)
    ranking = sorted((m for m in ("B1","B2","B3") if result["table"][m]["mae"] is not None),
                     key=lambda m: result["table"][m]["mae"])
    manifest.update(validation=scores(records,ranking[0] if ranking else "B1"),baselineRanking=ranking,
                    usableFrom=validation_end.isoformat())
    return bundle, records
