from statistics import median
from .timebase import DAY


def reference(history, target, origin, minimum=14, k=3.0, spread_floor=1.0):
    if not 1 <= minimum <= 28 or k <= 0 or spread_floor <= 0:
        raise ValueError("Invalid spike configuration")
    prices = [history[t]["usep"] for n in range(1,29)
              if (t := target - DAY*n) < origin and t in history]
    if len(prices) < minimum:
        return {"available": False, "sampleCount": len(prices), "windowDays": 28,
                "typical": None, "spread": None, "threshold": None}
    typical = median(prices)
    spread = max(spread_floor, 1.4826 * median(abs(p-typical) for p in prices))
    return {"available": True, "sampleCount": len(prices), "windowDays": 28,
            "typical": typical, "spread": spread, "threshold": typical + k*spread}
