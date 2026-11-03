"""Receipt-preserving, read-only PostgreSQL exports for a registered experiment."""
import argparse
from collections import Counter
import json
from pathlib import Path

from .data import digest, write_json
from .demand_candidate import identity, complete_rows
from .price_demand_readiness import snapshot_inputs
from .price_demand_training import protocol_check, truth_at, select_issues
from .timebase import floor, instant
from .weekly_sources import postgres_connection

PRICE_KEYS = ('id','source','periodStart','sourceUpdatedAt','availableAt','usep','demand','vcp','priceStatus','timeMapping')
DEMAND_KEYS = ('targetPeriod','publishedAt','availableAt','demandMw','source')


def window(protocol, split, as_of):
    start,cutoff,test_start,end = protocol_check(protocol)
    as_of = instant(as_of)
    if split == 'train' and cutoff <= as_of < test_start:
        return start,cutoff,cutoff
    if split == 'test' and as_of >= end:
        return test_start,end,end
    raise ValueError('Export is outside its registered fit window or completed holdout')


def market_only(snapshot):
    raw = snapshot.get('marketInputs',snapshot) if isinstance(snapshot,dict) else snapshot
    if isinstance(raw,list):
        return [{k:r[k] for k in PRICE_KEYS if k in r} for r in raw]
    if not isinstance(raw,dict) or not isinstance(raw.get('priceHistory'),list):
        raise ValueError('Invalid original market snapshot')
    return dict(priceHistory=[{k:r[k] for k in PRICE_KEYS if k in r} for r in raw['priceHistory']],
        expectedDemand=[{k:r[k] for k in DEMAND_KEYS if k in r} for r in raw.get('expectedDemand',[])])


def export(protocol, split, as_of, output, dsn_environment):
    start,end,truth_cutoff = window(protocol,split,as_of)  # Before opening a DB connection.
    output=Path(output)
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    excluded=Counter();seen=set();issues=[];prices=[];read_rows=0
    try:
        with postgres_connection(dsn_environment) as conn:
            conn.execute("SET LOCAL statement_timeout='30s'")
            conn.execute("SET LOCAL idle_in_transaction_session_timeout='60s'")
            with conn.cursor(name='demand_original_issues') as cur:
                cur.execute("""SELECT id,as_of,mode,model_version,stale,status,input_snapshot
                    FROM forecast_run WHERE is_demo=FALSE AND mode='LIVE' AND model_version=%s
                    AND stale=FALSE AND status='AVAILABLE' AND as_of>=%s AND as_of<%s
                    ORDER BY as_of,id""",(protocol['frozenModelVersion'],start,end))
                for key,origin,mode,version,stale,status,snapshot in cur:
                    read_rows+=1
                    if read_rows>10000:raise ValueError('Original issue limit exceeded')
                    if floor(origin) in seen:
                        excluded['REPEATED_HALF_HOUR']+=1;continue
                    synthetic=isinstance(snapshot,dict) and snapshot.get('_syntheticTestOnly') is True
                    if synthetic != protocol.get('syntheticTestOnly',False):
                        raise ValueError('Synthetic source marker does not match the registered study')
                    try:
                        issue=dict(id=key,asOf=origin.isoformat(),mode=mode,modelVersion=version,
                            stale=stale,status=status,isDemo=False,syntheticTestOnly=synthetic,
                            originalSnapshotSha256=identity(snapshot),inputs=market_only(snapshot))
                        snapshot_inputs(issue,protocol['periodMappingEvidence'])
                    except (ValueError,KeyError,TypeError,IndexError,AttributeError):
                        excluded['INVALID_ORIGINAL_INPUTS']+=1;continue
                    seen.add(floor(origin));issues.append(issue)
            with conn.cursor(name='demand_received_truth') as cur:
                cur.execute("""SELECT id,source,period_start,published_at,available_at,usep,price_status,time_mapping
                    FROM market_price_revision WHERE source='NEMS_SN_SG' AND price_status='PROVISIONAL'
                    AND period_start>=%s AND period_start<%s AND period_start+interval '30 minutes'<=%s
                    AND published_at<=%s AND available_at<=%s
                    ORDER BY period_start,published_at,available_at,id""",(start,end,truth_cutoff,truth_cutoff,truth_cutoff))
                for key,source,period,published,received,value,status,mapping in cur:
                    if len(prices)>=250000:raise ValueError('Price revision limit exceeded')
                    prices.append(dict(revisionId=str(key),source=source,periodStart=period.isoformat(),
                        sourceUpdatedAt=published.isoformat(),availableAt=received.isoformat(),usep=float(value),
                        priceStatus=status,timeMapping=mapping))
        for name,rows in (('issues',issues),('prices',prices)):
            path=output/(name+'.jsonl')
            with path.open('x') as stream:
                for row in rows:stream.write(json.dumps(row,allow_nan=False)+'\n')
            path.chmod(0o600)
        selected,_=select_issues(issues,protocol,start,end)
        complete,outcome_excluded=complete_rows(selected,truth_at(prices,truth_cutoff,protocol['periodMappingEvidence']),start,end)
        minimum=protocol['minimumTrainingOrigins' if split=='train' else 'minimumTestOrigins']
        manifest=dict(schemaVersion=1,status='SUCCESS',kind='AS_ISSUED_DEMAND_DATASET_V1',split=split,
            protocolSha256=identity(protocol),asOf=instant(as_of).isoformat(),start=start.isoformat(),
            endExclusive=end.isoformat(),truthCutoff=truth_cutoff.isoformat(),readOnly=True,
            transactionIsolation='REPEATABLE_READ',originalReceiptTimesPreserved=True,
            sourceRows=read_rows,eligibleHalfHourOrigins=len(issues),priceRevisions=len(prices),
            actualCompleteOrigins=len(complete),minimumOrigins=minimum,sufficientSupport=len(complete)>=minimum,
            sourceExclusions=dict(excluded),outcomeExclusions=outcome_excluded,
            syntheticTestOnly=protocol.get('syntheticTestOnly',False),householdOrAccountDataExported=False,
            hashes={name:digest(output/name) for name in ('issues.jsonl','prices.jsonl')})
        write_json(output/'manifest.json',manifest)
        for path in output.iterdir():path.chmod(0o400)
        return manifest
    except Exception as exc:
        write_json(output/'failed.json',dict(status='FAILED',errorType=type(exc).__name__))
        # Database errors may include credentials or captured input contents.
        raise ValueError('Read-only demand export failed; preserve this attempt and check private configuration') from None


def load_export(directory, protocol, split):
    directory=Path(directory);manifest=json.loads((directory/'manifest.json').read_text())
    start,end,cutoff=window(protocol,split,manifest['asOf'])
    if (manifest.get('status')!='SUCCESS' or manifest.get('kind')!='AS_ISSUED_DEMAND_DATASET_V1'
            or manifest.get('protocolSha256')!=identity(protocol) or manifest.get('split')!=split
            or instant(manifest['start'])!=start or instant(manifest['endExclusive'])!=end
            or instant(manifest['truthCutoff'])!=cutoff
            or manifest.get('syntheticTestOnly')!=protocol.get('syntheticTestOnly',False)
            or set(manifest.get('hashes',{}))!={'issues.jsonl','prices.jsonl'}
            or any(digest(directory/name)!=h for name,h in manifest['hashes'].items())):
        raise ValueError('Dataset manifest, split, protocol or file hash changed')
    return tuple([json.loads(line) for line in (directory/name).read_text().splitlines() if line.strip()]
        for name in ('issues.jsonl','prices.jsonl'))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--protocol',type=Path,required=True);p.add_argument('--split',choices=['train','test'],required=True)
    p.add_argument('--as-of',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--dsn-environment',required=True,help='Name of a private PostgreSQL DSN variable, never the DSN value')
    a=p.parse_args();result=export(json.loads(a.protocol.read_text()),a.split,a.as_of,a.output,a.dsn_environment)
    print(json.dumps(result,indent=2,allow_nan=False))


if __name__=='__main__':main()
