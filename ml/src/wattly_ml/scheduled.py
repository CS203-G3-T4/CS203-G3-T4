"""One bounded local weekly attempt, including failures while acquiring its data."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import uuid

from .cli import main as cli
from .data import snapshot, prepare
from .operations import TrainingAttempt, sync_runs
from .timebase import DAY, SGT, instant


def main():
    home=Path(os.environ['WATTLY_ML_HOME']).expanduser()
    end=instant(os.environ['WATTLY_VALIDATION_END']) if os.environ.get('WATTLY_VALIDATION_END') else datetime.now(timezone.utc).astimezone(SGT).replace(hour=0,minute=0,second=0,microsecond=0)
    cutoff=end-int(os.environ.get('WATTLY_VALIDATION_DAYS','28'))*DAY
    start=instant(os.environ['WATTLY_TRAIN_START']) if os.environ.get('WATTLY_TRAIN_START') else cutoff-365*DAY
    version='weekly-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:8]
    dataset=home/'datasets'/version
    args=argparse.Namespace(dataset=dataset,version=version,start=start,cutoff=cutoff,end=end,output=home/'reports'/version)
    attempt=TrainingAttempt(args)
    try:
        if not os.environ.get('WATTLY_RUN_API_URL'):
            raise ValueError('Scheduled operation requires WATTLY_RUN_API_URL and admin credentials; local-only audit is not sufficient')
        sync_runs(home)
        attempt.start()
        attempt.update(promotionDecision='DATA_ACQUISITION')
        source=Path(os.environ['COLLECTOR_DATA_DIR'])/'collector.sqlite3'
        acquired=snapshot(source,home/'snapshots','sqlite')
        snapshots=[acquired]
        if os.environ.get('WATTLY_HISTORY_SNAPSHOTS'):
            snapshots += sorted(p for p in Path(os.environ['WATTLY_HISTORY_SNAPSHOTS']).iterdir() if (p/'manifest.json').is_file())
        prepare(snapshots,dataset,demand_file=os.environ.get('WATTLY_DEMAND_FORECASTS') or None)
        command=['weekly',str(dataset),'--start',start.isoformat(),'--cutoff',cutoff.isoformat(),
                 '--end',end.isoformat(),'--version',version,'--truth-policy',os.environ.get('WATTLY_TRUTH_POLICY','PROVISIONAL'),
                 '--output',str(args.output),'--protected-start',os.environ['WATTLY_PROTECTED_START'],
                 '--protected-end',os.environ['WATTLY_PROTECTED_END']]
        if os.environ.get('WATTLY_TRAIN_RETROSPECTIVE')=='true':
            command.append('--retrospective')
        return cli(command,training_attempt=attempt)
    except (OSError,ValueError,KeyError) as exc:
        attempt.finish('FAILED',str(exc)); print(json.dumps({'state':'FAILED','reason':str(exc)})); return 2
    finally:
        attempt.close()


if __name__=='__main__':
    raise SystemExit(main())
