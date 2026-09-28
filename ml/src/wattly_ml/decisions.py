"""F4 adapter: caller supplies the same feasible appliance plans to both methods."""
import math


def compare(cases):
    results = []
    for case in cases:
        vectors = [case[k] for k in ("actual","ai","baseline")]
        plans = case["feasiblePlansKwh"]
        n = len(vectors[0])
        if n==0 or not plans or any(len(v)!=n for v in vectors+plans+[case["usualPlanKwh"]]):
            raise ValueError("All prices/plans must cover identical intervals")
        if not all(math.isfinite(x) for v in vectors+plans+[case["usualPlanKwh"]] for x in v):
            raise ValueError("Non-finite decision input")
        energy = sum(case["usualPlanKwh"])
        if any(x<0 for v in plans+[case["usualPlanKwh"]] for x in v) or any(abs(sum(p)-energy)>1e-8 for p in plans):
            raise ValueError("Plans must preserve nonnegative appliance energy")
        def cost(plan,prices):
            return sum(kwh*price/1000 for kwh,price in zip(plan,prices))
        usual = cost(case["usualPlanKwh"],case["actual"])
        result = {"caseId":case["caseId"],"householdId":case["householdId"],"usualCostSgd":usual}
        for method in ("ai","baseline"):
            selected = min(plans,key=lambda p: cost(p,case[method])) if case["exposedToWholesalePrice"] else case["usualPlanKwh"]
            result[method+"SavingsSgd"] = usual-cost(selected,case["actual"])
        results.append(result)
    return {"assumptions":["Modelled household consumption","Accepted recommendations assumed followed",
                            "F4 supplies feasibility; fixed-rate households are not shifted"],"cases":results,
            "aiSavingsSgd":sum(r["aiSavingsSgd"] for r in results),
            "baselineSavingsSgd":sum(r["baselineSavingsSgd"] for r in results)}
