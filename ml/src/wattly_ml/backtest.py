import bisect
from collections import defaultdict
from statistics import mean

from .baselines import baselines
from .data import as_of, truth
from .features import features
from .spikes import DEFAULT_CONFIG, reference, validate_config
from .timebase import DAY, SGT, STEP, floor, instant, targets


class History:
    def __init__(self, rows, retrospective=False):
        self.rows = sorted(rows, key=lambda r: instant(r["periodStart"]))
        self.periods = [instant(r["periodStart"]) for r in self.rows]
        self.retrospective = retrospective

    def at(self, origin):
        lo = bisect.bisect_left(self.periods, origin-29*DAY)
        hi = bisect.bisect_right(self.periods, origin)
        return as_of(self.rows[lo:hi], origin, self.retrospective)


def origins(start, end):
    if start != floor(start) or end != floor(end) or start >= end:
        raise ValueError("Split boundaries must be ordered half-hours")
    origin = start
    while origin < end:
        yield origin
        origin += STEP


def fresh(history, origin):
    return bool(history) and (origin-max(instant(r["sourceUpdatedAt"]) if r.get("sourceUpdatedAt")
            else p+STEP for p,r in history.items())).total_seconds()<=2400


def predict(bundle, history, origin):
    from threadpoolctl import threadpool_limits
    with threadpool_limits(limits=1):
        return [float(model.predict([features(history, origin, target)])[0])
                for model, target in zip(bundle["models"], targets(origin))]


def backtest(rows, start, end, *, policy="PROVISIONAL", retrospective=False, bundle=None, truth_cutoff=None):
    spike_config = validate_config(bundle["manifest"]["spikeConfig"] if bundle else DEFAULT_CONFIG)
    history = History(rows, retrospective)
    actuals = truth(rows, policy,truth_cutoff,retrospective)
    output = []
    for origin in origins(start, end):
        ts = targets(origin)
        # End-exclusive outcomes: target end must precede/equal next split.
        purged = ts[-1] + STEP > end
        past = history.at(origin)
        current = fresh(past,origin)
        predictions = baselines(past, origin)
        if bundle:
            if instant(bundle["manifest"]["usableFrom"]) > origin:
                raise ValueError("Artifact training/selection uses the simulated future")
            predictions["AI"] = predict(bundle, past, origin) if current and not purged else None
        for h, target in enumerate(ts, 1):
            ref = reference(past, target, origin, spike_config)
            actual = actuals.get(target)
            for method, values in predictions.items():
                reason = ("PURGED_SPLIT_BOUNDARY" if purged else "STALE_OR_MISSING_INPUT" if not current
                          else "INSUFFICIENT_HISTORY" if values is None else "MISSING_TRUTH" if actual is None else None)
                prediction = values[h-1] if reason is None else None
                output.append({"asOf": origin.isoformat(), "targetPeriod": target.isoformat(), "horizon": h,
                               "method": method, "modelVersion": bundle["manifest"]["version"] if method=="AI" else method+"-v1",
                               "predictedUsep": prediction, "actual": actual, "truthPolicy": policy,
                               "threshold": ref["threshold"], "spikeFlag": prediction > ref["threshold"]
                               if prediction is not None and ref["available"] else None,
                               "actualSpike": actual > ref["threshold"] if actual is not None and ref["available"] else None,
                               "exclusionReason": reason})
    return output


def scores(records, selected_baseline="B1"):
    methods = sorted({r["method"] for r in records})
    eligible = {m: {(r["asOf"],r["horizon"]) for r in records if r["method"]==m and r["exclusionReason"] is None}
                for m in methods}
    common = set.intersection(*eligible.values()) if methods else set()
    table = {}
    for method in methods:
        all_rows = [r for r in records if r["method"]==method]
        valid = [r for r in all_rows if (r["asOf"],r["horizon"]) in common]
        def mae(items):
            return mean(abs(r["predictedUsep"]-r["actual"]) for r in items) if items else None
        labeled = [r for r in valid if r["actualSpike"] is not None]
        tp = sum(r["spikeFlag"] and r["actualSpike"] for r in labeled)
        fp = sum(r["spikeFlag"] and not r["actualSpike"] for r in labeled)
        fn = sum(not r["spikeFlag"] and r["actualSpike"] for r in labeled)
        eligible_origins = len({o for o,_ in eligible[method]})
        table[method] = {"mae": mae(valid), "eligiblePairs": len(eligible[method]), "commonPairs": len(valid),
                         "ownEligibleMae": mae([r for r in all_rows if r["exclusionReason"] is None]),
                         "eligibleOrigins": eligible_origins,
                         "excludedOrigins": len({r["asOf"] for r in all_rows})-eligible_origins,
                         "coverage": len(eligible[method])/len(all_rows) if all_rows else 0,
                         "perHorizon": {str(h): mae([r for r in valid if r["horizon"]==h]) for h in range(1,25)},
                         "groups": {name: mae([r for r in valid if a<=r["horizon"]<=b])
                                    for name,a,b in (("1-6",1,6),("7-12",7,12),("13-24",13,24))},
                         "spikes": {"tp":tp,"fp":fp,"fn":fn,"support":tp+fn,"falseAlarms":fp,
                                    "precision":tp/(tp+fp) if tp+fp else None,
                                    "recall":tp/(tp+fn) if tp+fn else None,
                                    "assessedPairs":len(labeled), "undefinedReason":"No predicted/actual events or insufficient reference history"}}
    ai, baseline = table.get("AI",{}).get("mae"), table.get(selected_baseline,{}).get("mae")
    skill = 1-ai/baseline if ai is not None and baseline not in (None,0) else None
    return {"table": table, "selectedBaseline": selected_baseline, "skill": skill,
            "skillUndefinedReason": "Missing common pairs or zero baseline MAE" if skill is None else None,
            "commonPairs": len(common), "task": "forecast-spike prediction (origin-frozen thresholds)",
            "commonOriginDays": len({instant(origin).astimezone(SGT).date() for origin,_ in common}),
            "observedAnomalyTask": "operational classification; no independent physical-cause labels",
            "weeklyBlockDifference95": block_difference(records, common, selected_baseline)}


def block_difference(records, common, baseline):
    # Resample whole weeks, never overlapping individual forecast errors.
    pairs = defaultdict(dict)
    for r in records:
        key = (r["asOf"],r["horizon"])
        if key in common and r["method"] in ("AI",baseline):
            pairs[key][r["method"]] = abs(r["predictedUsep"]-r["actual"])
    weeks = defaultdict(list)
    for (origin,_), values in pairs.items():
        if "AI" in values and baseline in values:
            iso = instant(origin).astimezone(SGT).isocalendar()
            weeks[(iso.year,iso.week)].append(values["AI"]-values[baseline])
    if len(weeks)<8:
        return {"interval": None, "reason": "Fewer than eight weeks of paired errors"}
    import numpy as np
    blocks = list(weeks.values())
    rng = np.random.default_rng(42)
    estimates = [mean(v for i in rng.integers(0,len(blocks),len(blocks)) for v in blocks[i]) for _ in range(500)]
    return {"interval": [float(x) for x in np.quantile(estimates,[.025,.975])], "blocks":len(weeks), "unit":"SGD_PER_MWH"}
