"""Shared, mandatory as-issued demand recipe; no solar or missing-demand fallback."""
from .data import KnownPrices, validate_demand
from .features import features
from .price_only import completed_price_history
from .timebase import instant, targets

PRICE_DEMAND_POLICY = 'COMPLETED_PRICE_CALENDAR_HOLIDAY_AS_ISSUED_DEMAND_V1'
DEMAND_POLICY = 'MANDATORY_ALL_24_AS_ISSUED'
RESEARCH_RECIPE = 'PRICE_DEMAND_COMPLETED_V1'


def completed_price_demand_history(history, origin):
    origin = instant(origin)
    control = completed_price_history(history, origin)
    demands, seen = [], {}
    for value in getattr(history, 'demand_forecasts', ()):
        row = validate_demand(value)
        published, received = instant(row['publishedAt']), instant(row['availableAt'])
        if max(published, received) > origin:
            raise ValueError('Future demand input')
        key = (instant(row['targetPeriod']), published, received)
        if key in seen and seen[key] != row['demandMw']:
            raise ValueError('Conflicting demand vintage')
        seen[key] = row['demandMw']
        demands.append(row)
    candidate = KnownPrices(control, demands)
    for target in targets(origin):
        if features(candidate, origin, target, schema_version=2)[-1] != 1:
            raise ValueError('Missing target demand')
    return candidate
