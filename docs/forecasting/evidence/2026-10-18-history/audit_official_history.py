"""Read-only audit of downloaded EMC final CSVs; no availability vintages invented."""
import calendar
import csv
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".local/acceptance-2026-10-18/research").resolve()
SGT = timezone(timedelta(hours=8))
files = []
periods = {}
for path in sorted(root.glob('emc-usep-????-??.csv')):
    year, month = map(int, path.stem.removeprefix('emc-usep-').split('-'))
    rows = list(csv.DictReader(path.open(newline='', encoding='utf-8-sig')))
    expected = calendar.monthrange(year, month)[1] * 48
    assert len(rows) == expected, (path.name, len(rows), expected)
    keys = set()
    for row in rows:
        day = datetime.strptime(row['DATE'], '%d-%b-%Y').replace(tzinfo=SGT)
        assert (day.year, day.month) == (year, month)
        period = int(row['PERIOD'])
        assert 1 <= period <= 48
        assert row['INFORMATION TYPE'] == 'USEP'
        usep = Decimal(row['USEP ($/MWh)'])
        assert usep.is_finite()
        instant = day + timedelta(minutes=30*(period-1))
        assert instant not in periods, ('Duplicate independent period', instant)
        periods[instant] = usep
        keys.add((row['DATE'], period))
    assert len(keys) == expected
    headers = path.with_suffix('.headers').read_text()
    assert 'HTTP/2 200' in headers and 'content-type: text/csv' in headers.lower(), path
    response_date = next(line[6:] for line in headers.splitlines() if line.lower().startswith('date: '))
    files.append({'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'bytes':path.stat().st_size,'rows':len(rows),'uniqueHalfHours':len(keys),
        'from':f'{year}-{month:02d}-01','through':f'{year}-{month:02d}-{calendar.monthrange(year,month)[1]}',
        'sourceUrl':f'https://www.nems.emcsg.com/api/sitecore/DataSync/DataDownload?value=1&fromDate={year}-{month:02d}-01&toDate={year}-{month:02d}-{calendar.monthrange(year,month)[1]}&tpcValue=1',
        'responseDateHeader':response_date,'product':'Uniform Singapore Energy Price and Demand Forecast',
        'priceStatus':'FINAL','sourcePublicationAt':None,'historicalAvailableAt':None,
        'historicalDemandForecastIssuedAt':None})
assert len(files) == 16
first, last = min(periods), max(periods)
expected_total = int((last-first).total_seconds()/1800) + 1
assert len(periods) == expected_total, 'Missing half-hours'
report={'study':'RETROSPECTIVE_FINAL_PRICE','firstPeriod':first.isoformat(),'lastPeriod':last.isoformat(),
    'days':expected_total//48,'rows':len(periods),'uniqueHalfHours':len(periods),'missingHalfHours':0,
    'duplicateHalfHours':0,'nonfinitePrices':0,'minimumUsep':str(min(periods.values())),
    'maximumUsep':str(max(periods.values())),'negativePriceHalfHours':sum(v<0 for v in periods.values()),
    'zeroPriceHalfHours':sum(v==0 for v in periods.values()),
    'units':'SGD/MWh','periodMapping':'EMC period 1 begins00:00SGT; period48 begins23:30SGT',
    'vintageWarning':'Final settlement labels have unknown historical publication/receipt times; demand column has no issue/availability vintage. Do not use as operational origin inputs.',
    'files':files}
(root/'official-history-audit.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='files'},indent=2))
