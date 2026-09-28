from statistics import mean
from .timebase import DAY, STEP, floor, targets

DEFAULT_ORDER = ["B1", "B2", "B3"]


def baselines(history, origin):
    values = {p: r["usep"] for p, r in history.items()}
    def average(periods):
        return mean(values[p] for p in periods) if all(p in values for p in periods) else None
    boundary = floor(origin)
    latest = boundary if boundary in values else boundary-STEP
    b1 = average([latest - STEP*n for n in range(6)])
    result = {"B1": [b1] * 24,
              "B2": [values.get(t - DAY) for t in targets(origin)],
              "B3": [average([t - DAY*n for n in range(1, 8)]) for t in targets(origin)]}
    return {k: v if all(x is not None for x in v) else None for k, v in result.items()}
