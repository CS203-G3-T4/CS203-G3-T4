"""Real FastAPI process for the Java smoke; synthetic data/approval, never evidence."""
import argparse
from datetime import timedelta
import json
import os
from pathlib import Path

from wattly_ml.artifacts import save_bundle
from wattly_ml.data import digest, quality, write_json
from wattly_ml.timebase import DAY, STEP, instant
from wattly_ml.train import train


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    # The parent test creates and removes this empty temporary directory.
    if not args.home.is_dir() or any(args.home.iterdir()):
        raise ValueError("Fixture requires a new, empty test directory")
    gold = json.loads((Path(__file__).parents[1]/"fixtures"/"baseline-golden.json").read_text())
    first = instant(gold["start"])
    rows = []
    for i in range(gold["count"]):
        period = first+i*STEP
        published = period+timedelta(minutes=1)
        rows.append({"periodStart":period.isoformat(),"sourceUpdatedAt":published.isoformat(),
                     "availableAt":published.isoformat(),"usep":gold["firstPrice"]+i*gold["increment"],
                     "source":"SYNTHETIC","priceStatus":"PROVISIONAL","timeMapping":"EMC_PERIOD"})
    cutoff = first+6*DAY
    bundle,_ = train(rows,{"sha256":"SYNTHETIC_HTTP_SMOKE","quality":quality(rows)},
                     first+4*DAY,cutoff,first+7*DAY,policy="PROVISIONAL",exploratory=True,iterations=2)
    # Exercise the approved loader and prior-artifact replay contract with TEST metadata.
    # No real approval or historical model performance is claimed by this fixture.
    bundle["manifest"].update(productionEligible=True,exploratory=False,syntheticTestOnly=True,
                              baselineRanking=["B2","B1","B3"],trainingDate=cutoff.isoformat())
    version = "SYNTHETIC_HTTP_SMOKE"
    save_bundle(bundle,args.home/"models",version)
    write_json(args.home/"current.json",{"version":version,"fixtureOnly":True,
               "manifestSha256":digest(args.home/"models"/version/"manifest.json")})
    os.environ["WATTLY_ML_HOME"] = str(args.home)
    import uvicorn
    uvicorn.run("wattly_ml.serving.app:app",host="127.0.0.1",port=args.port)


if __name__ == "__main__":
    main()
