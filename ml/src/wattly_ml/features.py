"""The exact same features are called by fitting, backtests and HTTP inference."""
import math
from statistics import mean, pstdev
from .timebase import DAY, SGT, STEP, floor
from .timebase import instant
import json
import hashlib
from pathlib import Path
from functools import lru_cache

FEATURES = ["latest", "lag1", "lag2", "lag3", "lag6", "lag12", "lag48",
            "yesterday_target", "last_week_target", "mean6", "std6", "mean48", "std48",
            "target_half_hour", "target_weekday", "target_weekend"]
ACTIVE_FEATURES = FEATURES + ['target_public_holiday', 'expected_demand_mw', 'expected_demand_available']
FEATURE_SCHEMA_VERSION = 2


@lru_cache(maxsize=1)
def holiday_calendar():
    return json.loads((Path(__file__).parent/'resources/sg-public-holidays.json').read_text())


def holiday_digest():
    return hashlib.sha256(json.dumps(holiday_calendar(),sort_keys=True).encode()).hexdigest()


def holiday_feature(origin, target):
    calendar = holiday_calendar()
    date = target.astimezone(SGT).date().isoformat()
    year = calendar['years'].get(date[:4])
    if year is None or instant(year['publishedAt']) > origin:
        return math.nan
    return int(date in year['dates'])


def feature_names(version):
    if version in (3, 4):
        from .solar import SOLAR_NAMES
        return ACTIVE_FEATURES + SOLAR_NAMES
    if version not in (1,2):
        raise ValueError('Unsupported feature schema')
    return FEATURES if version == 1 else ACTIVE_FEATURES


def features(history, origin, target, *, schema_version=1):
    if schema_version in (3, 4):
        from .solar import solar_features
        return solar_features(history,origin,target, demand_aware=schema_version == 4)
    boundary = floor(origin)
    prices = {p: r["usep"] for p, r in history.items() if p <= origin}
    def get(period):
        return prices.get(period, math.nan)
    result = [prices[max(prices)] if prices else math.nan]
    result.extend(get(boundary - STEP*n) for n in (1,2,3,6,12,48))
    result.extend((get(target-DAY), get(target-7*DAY)))
    for count in (6,48):
        values = [get(boundary-STEP*n) for n in range(1,count+1)]
        result.extend((mean(values), pstdev(values)) if all(math.isfinite(v) for v in values)
                      else (math.nan, math.nan))
    local = target.astimezone(SGT)
    result.extend((local.hour*2+local.minute//30, local.weekday(), int(local.weekday()>=5)))
    if schema_version == 2:
        available = [r for r in getattr(history, 'demand_forecasts', ()) if instant(r['targetPeriod']) == target
                     and max(instant(r['publishedAt']), instant(r['availableAt'])) <= origin]
        value = max(available, key=lambda r:(instant(r['publishedAt']),instant(r['availableAt']))) if available else None
        result.extend((holiday_feature(origin,target), value['demandMw'] if value else math.nan, int(value is not None)))
    elif schema_version != 1:
        raise ValueError('Unsupported feature schema')
    return result
