import math
from statistics import median
from .timebase import DAY

DEFAULT_CONFIG = {"minimumSamples": 14, "k": 3.0, "spreadFloor": 1.0}


def validate_config(config):
    if (not isinstance(config, dict) or set(config) != set(DEFAULT_CONFIG)
            or type(config["minimumSamples"]) is not int or not 1 <= config["minimumSamples"] <= 28
            or any(type(config[key]) not in (int, float) or not math.isfinite(config[key]) or config[key] <= 0
                   for key in ("k", "spreadFloor"))):
        raise ValueError("Invalid spike configuration")
    return config


def reference(history, target, origin, config=DEFAULT_CONFIG):
    config = validate_config(config)
    prices = [history[t]["usep"] for n in range(1,29)
              if (t := target - DAY*n) < origin and t in history]
    if len(prices) < config["minimumSamples"]:
        return {"available": False, "sampleCount": len(prices), "windowDays": 28,
                "typical": None, "spread": None, "threshold": None}
    typical = median(prices)
    spread = max(config["spreadFloor"], 1.4826 * median(abs(p-typical) for p in prices))
    return {"available": True, "sampleCount": len(prices), "windowDays": 28,
            "typical": typical, "spread": spread, "threshold": typical + config["k"]*spread}
