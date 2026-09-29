"""Read-only comparison of collector observations with EMC's visible period table.
Official rows transcribed from https://www.nems.emcsg.com/nems-prices,
29 September 2026 table, last updated 11:31 SGT. Only past/current rows included.
"""
import csv
import json
import sqlite3
import sys
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

VALUES = '''6508.603 195.94
6381.063 182.84
6445.325 330.98
6375.914 330.96
6321.850 424.57
6281.588 469.18
6237.533 469.16
6215.114 469.16
6227.793 195.98
6282.119 469.15
6385.237 537.76
6538.413 516.59
6723.983 714.27
6958.103 722.87
7168.801 822.04
7364.526 913.39
7496.356 719.64
7526.568 246.14
7384.053 196.33
7209.407 185.79
6994.137 146.27
6738.356 126.22
6539.566 125.84
6481.471 120.76'''

def main():
    db, output = Path(sys.argv[1]), Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    start = datetime.fromisoformat('2026-09-29T00:00:00+08:00')
    official = {start + timedelta(minutes=30*i): tuple(map(Decimal, line.split()))
                for i, line in enumerate(VALUES.splitlines())}
    con = sqlite3.connect(db.resolve().as_uri() + '?mode=ro', uri=True)
    records = []
    for event_id, updated, payload, response_id in con.execute(
            'SELECT id,source_updated,payload_json,price_response_id FROM events ORDER BY source_updated'):
        source = datetime.fromisoformat(updated)
        period = source.replace(minute=source.minute//30*30, second=0, microsecond=0)
        if period not in official:
            continue
        data = json.loads(payload, parse_float=Decimal)
        raw = json.loads(con.execute('SELECT body FROM responses WHERE id=?', (response_id,)).fetchone()[0], parse_float=Decimal)
        assert raw == data, f'Archived response differs: {event_id}'
        assert int(source.timestamp()) == data['updated']
        demand, usep = official[period]
        row = dict(event_id=event_id, source_updated=updated, period_start=period.isoformat(),
                   nems_usep=str(data['usep']), emc_usep=str(usep), nems_demand=str(data['demand']),
                   emc_demand=str(demand), price_match=data['usep']==usep,
                   demand_match=data['demand']==demand.quantize(Decimal('1'), rounding=ROUND_HALF_UP))
        for offset in (-30, 30):
            other = official.get(period + timedelta(minutes=offset))
            row[f'offset_{offset}_joint_match'] = None if other is None else (
                data['usep']==other[1] and data['demand']==other[0].quantize(Decimal('1'), rounding=ROUND_HALF_UP))
        records.append(row)
    assert len({r['period_start'] for r in records}) == 24, 'Missing observed period'
    assert all(r['price_match'] and r['demand_match'] for r in records), 'Comparison mismatch'
    with (output/'period-mapping-comparison.csv').open('w', newline='') as f:
        writer=csv.DictWriter(f, fieldnames=list(records[0])); writer.writeheader(); writer.writerows(records)
    summary = dict(source_url='https://www.nems.emcsg.com/nems-prices',
        official_last_updated='2026-09-29T11:31:00+08:00', price_status='PROVISIONAL',
        verification_run_at=datetime.now().astimezone().isoformat(), observations=len(records),
        matching_periods=24, mapping='floor(updated Unix seconds / 1800) * 1800',
        alternatives={str(offset):dict(comparable=sum(r[f'offset_{offset}_joint_match'] is not None for r in records),
            joint_matches=sum(r[f'offset_{offset}_joint_match'] is True for r in records)) for offset in (-30,30)})
    (output/'period-mapping-summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    main()
