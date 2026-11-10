"""Read-only server data adapters; preserve actual publication/receipt vintages."""
import json
from pathlib import Path

from .data import digest,prepare,quality,snapshot,validate,write_json
from .timebase import DAY


def postgres_connection(variable):
    import os
    try:
        import psycopg
        from psycopg import IsolationLevel
        value=os.environ.get(variable)
        if not value:raise ValueError('Missing database environment variable')
        conn=psycopg.connect(value,connect_timeout=10,client_encoding='UTF8')
        conn.read_only=True
        conn.isolation_level=IsolationLevel.REPEATABLE_READ
        return conn
    except Exception:
        # libpq errors may contain a DSN, username or password. Never spool them.
        raise ValueError('Read-only PostgreSQL connection unavailable; check private configuration') from None


def acquire(cfg,directory,cutoff,end,now):
    source=cfg['source']
    if source['kind']=='sqlite':
        acquired=snapshot(source['path'],directory/'snapshots','sqlite')
        destination=directory/'dataset'
        manifest=prepare([acquired],destination)
        return destination,manifest
    destination=directory/'dataset';destination.mkdir(exist_ok=False)
    path=destination/'prices.jsonl';rows=[]
    try:
        # All eligible revisions, not just the latest value. No retrospective
        # availability stamps and no household/account data are exported.
        with postgres_connection(source['dsnEnvironment']) as conn:
            conn.execute("SET LOCAL statement_timeout='30s'")
            with conn.cursor(name='weekly_price_revisions') as cursor:
                cursor.execute("""
                    SELECT id,source,period_start,published_at,available_at,usep,demand,vcp,
                           price_status,time_mapping
                    FROM market_price_revision
                    WHERE source=%s AND price_status='PROVISIONAL'
                      AND period_start>=%s AND period_start<%s
                      AND published_at<=%s AND available_at<=%s
                    ORDER BY period_start,published_at,available_at,id
                    """,(source['name'],cutoff-211*DAY,end,now,now))
                with path.open('w') as stream:
                    for item in cursor:
                        if len(rows)>=250000:raise ValueError('Server revision limit exceeded')
                        key,name,period,published,available,price,demand,vcp,status,mapping=item
                        row=validate(dict(revisionId=str(key),source=name,periodStart=period.isoformat(),
                            sourceUpdatedAt=published.isoformat(),availableAt=available.isoformat(),
                            usep=float(price),demand=float(demand) if demand is not None else None,
                            vcp=float(vcp) if vcp is not None else None,priceStatus=status,timeMapping=mapping,
                            provenance='application-postgresql-read-only',weather=None))
                        rows.append(row);stream.write(json.dumps(row,allow_nan=False)+'\n')
        manifest=dict(schemaVersion=1,sha256=digest(path),provisionalOffsetMinutes=0,
            sources=[dict(kind='application-postgresql',acquiredAt=now.isoformat(),
                          source=source['name'],readOnly=True,receiptTimesPreserved=True)],quality=quality(rows))
        write_json(destination/'manifest.json',manifest)
        for p in destination.iterdir():p.chmod(0o444)
        return destination,manifest
    except Exception:
        # Do not include connection errors in persisted audit reasons or logs.
        raise ValueError('Read-only PostgreSQL acquisition failed; current model retained') from None
