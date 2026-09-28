"""The exact same features are called by fitting, backtests and HTTP inference."""
import math
from statistics import mean, pstdev
from .timebase import DAY, SGT, STEP, floor

FEATURES = ["latest", "lag1", "lag2", "lag3", "lag6", "lag12", "lag48",
            "yesterday_target", "last_week_target", "mean6", "std6", "mean48", "std48",
            "target_half_hour", "target_weekday", "target_weekend"]


def features(history, origin, target):
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
    return result
