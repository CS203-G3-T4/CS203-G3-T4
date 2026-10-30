"""Strict public EMC solar adapter for the frozen SOLAR_183D recipe.

Public chart Solar is stacked above Demand: numeric solar = Solar - Demand.
Receipt times are real observations, never backdated publication vintages.
"""
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from pathlib import Path
import re
from statistics import mean, pstdev

from .timebase import DAY, STEP, SGT, floor, instant

SOLAR_NAMES = ['solar_latest', 'solar_lag1', 'solar_lag2', 'solar_lag5',
               'solar_mean6', 'solar_std6', 'solar_change1', 'solar_change2',
               'solar_change5', 'solar_minus_yesterday', 'solar_count6']
SOLAR_POLICY = 'COMPLETED_PRICE_AND_RECEIPT_STAMPED_SOLAR_V1'
SOLAR_DEMAND_POLICY = 'COMPLETED_PRICE_RECEIPT_SOLAR_AS_ISSUED_DEMAND_V1'
BASE_URL = 'https://www.nems.emcsg.com/api/DataSync/Get?value='
MONTHS = {v:i for i,v in enumerate(('Jan','Feb','Mar','Apr','May','Jun',
                                   'Jul','Aug','Sep','Oct','Nov','Dec'),1)}


def emc_time(text):
    match = re.fullmatch(r'(\d{2}) ([A-Z][a-z]{2}) (\d{4}) (\d{2}):(\d{2})', text)
    if not match or match[2] not in MONTHS:
        raise ValueError('Invalid EMC timestamp')
    return datetime(int(match[3]), MONTHS[match[2]], int(match[1]),
                    int(match[4]), int(match[5]), tzinfo=SGT)


def label_time(text, anchor):
    match = re.fullmatch(r'(\d{2}) ([A-Z][a-z]{2}) (\d{2}):(\d{2})-(\d{2}):(\d{2})', text)
    if not match or match[2] not in MONTHS:
        raise ValueError('Invalid EMC interval label')
    candidates = []
    for year in (anchor.year-1, anchor.year, anchor.year+1):
        try:
            candidates.append(datetime(year,MONTHS[match[2]],int(match[1]),
                              int(match[3]),int(match[4]),tzinfo=SGT))
        except ValueError:
            pass
    if not candidates:
        raise ValueError('Invalid EMC date')
    period = min(candidates,key=lambda t:abs(t-anchor))
    end = period+STEP
    if (period != floor(period) or abs(period-anchor)>8*DAY
            or (end.hour,end.minute)!=(int(match[5]),int(match[6]))):
        raise ValueError('Invalid EMC half-hour mapping')
    return instant(period)


def number(value):
    try:
        result = Decimal(str(value).replace(',', ''))
    except InvalidOperation as exc:
        raise ValueError('Missing/non-numeric EMC solar input') from exc
    if not result.is_finite():
        raise ValueError('Non-finite EMC solar input')
    return result


def read_solar_snapshot(path, as_of):
    """Only completed solar periods; require all six recent periods and yesterday.

    Responses are read atomically from the separate public browser collector.
    A stale snapshot fails closed; no zeros, weather proxies or median fill.
    """
    path = Path(path)
    if path.stat().st_size>512_000:
        raise ValueError('Solar snapshot exceeds limit')
    raw = path.read_bytes()
    snapshot = json.loads(raw)
    if snapshot.get('schemaVersion')!=1 or len(snapshot.get('responses', [])) not in (2,3):
        raise ValueError('Invalid solar snapshot')
    as_of = instant(as_of)
    values = {}; availability = {}; receipts = []; source_updates = []; seen = set()
    # Panels may overlap, but observed values must agree; ordering is not authority.
    # Today is required when the 72-period chart rolls forward past recent periods.
    for item in sorted(snapshot['responses'], key=lambda r: r['value'], reverse=True):
        value = item['value']
        if value not in (10,12,14) or value in seen or item['url']!=BASE_URL+str(value)+'&fromDate=&toDate=':
            raise ValueError('Invalid solar source')
        seen.add(value)
        received = instant(item['receivedAt'])
        if received>as_of or as_of-received>timedelta(minutes=10):
            raise ValueError('Stale or future solar receipt')
        body = item['body']
        if body.get('success') is not True or len(body['data']['data'])!=1:
            raise ValueError('Unsuccessful EMC response')
        panel = body['data']['data'][0]
        updated = emc_time(panel['lastupdate'])
        if updated>received or (value in (10,12) and as_of-updated>timedelta(minutes=40)):
            raise ValueError('Stale or future EMC update')
        labels = panel['labels']
        if len(labels)!=({10:48,12:72,14:336}[value]):
            raise ValueError('Unexpected solar history length')
        datasets = panel['datasets']
        demand = [d['data'] for d in datasets if d['label']=='Demand']
        stacked = [d['data'] for d in datasets if d['label']=='Solar']
        if len(demand)!=1 or len(stacked)!=1 or len(demand[0])!=len(labels) or len(stacked[0])!=len(labels):
            raise ValueError('Ambiguous or misaligned EMC solar series')
        periods = [label_time(label,updated) for label in labels]
        if any(b-a!=STEP for a,b in zip(periods,periods[1:])):
            raise ValueError('Duplicate or nonconsecutive EMC periods')
        if value in (10,12):
            current = emc_time(panel['currentdate'].split('-')[0])
            if floor(current)!=floor(updated):
                raise ValueError('EMC current-period mapping disagrees')
        for period, gross, net in zip(periods,stacked[0],demand[0]):
            # Future/current graph entries are dispatch forecasts, never historical inputs.
            if period+STEP>floor(as_of) or period>floor(updated):
                continue
            try:
                solar = number(gross)-number(net)
                if solar<0 or number(net)<0:
                    raise ValueError('Invalid negative solar/demand')
            except ValueError as exc:
                if period in values:
                    raise ValueError('Invalid overlapping EMC solar value') from exc
                # A gap remains missing; it cannot erase another panel's valid value.
                continue
            if period in values and values[period] != float(solar):
                raise ValueError('Conflicting overlapping EMC solar values')
            values[period] = float(solar)
            availability[period] = received.isoformat()
        receipts.append(received.isoformat());source_updates.append(instant(updated).isoformat())
    if seen not in ({12,14},{10,12,14}):
        raise ValueError('Missing required EMC panel')
    latest = floor(as_of)-STEP
    required = [latest-i*STEP for i in range(6)]+[latest-DAY]
    if any(t not in values for t in required):
        raise ValueError('Required recent/yesterday solar periods unavailable')
    return values, {'source':'EMC_PUBLIC_PROVISIONAL_SOLAR', 'snapshotSha256':hashlib.sha256(raw).hexdigest(),
                    'receivedAt':receipts, 'sourceUpdatedAt':source_updates,
                    'latestCompletedPeriod':latest.isoformat(), 'requiredPeriods':7,
                    'periods':[{'periodStart':t.isoformat(),'solarMw':values[t],'availableAt':availability[t]} for t in required],
                    'availabilityPolicy':'ACTUAL_RECEIPT_NO_BACKDATED_VINTAGES'}


def solar_features(history, origin, target, *, demand_aware=False):
    from .features import features
    latest = floor(origin)-STEP
    completed = {t:r for t,r in history.items() if t<=latest}
    if latest not in completed:
        raise ValueError('Latest completed price unavailable')
    # Schema 3 deliberately keeps demand missing to preserve the frozen recipe.
    if demand_aware:
        from .data import KnownPrices
        completed = KnownPrices(completed, getattr(history, 'demand_forecasts', ()))
    base = features(completed, origin, target, schema_version=2)
    if demand_aware and not base[18]:
        raise ValueError('Target demand forecast unavailable at issue time')
    solar = history.solar_values
    required = [latest-i*STEP for i in range(6)]+[latest-DAY]
    if any(t not in solar or not math.isfinite(solar[t]) or solar[t]<0 for t in required):
        raise ValueError('Required solar history unavailable')
    recent = [solar[t] for t in required[:6]]
    return base+[recent[0],recent[1],recent[2],recent[5],mean(recent),pstdev(recent),
                 recent[0]-recent[1],recent[0]-recent[2],recent[0]-recent[5],
                 recent[0]-solar[latest-DAY],6.]
