"""Exact no-solar CONTROL input contract of the saved PRICE_183D research recipe."""
from .timebase import floor, STEP

PRICE_ONLY_POLICY = 'COMPLETED_PRICE_CALENDAR_HOLIDAY_NO_AUXILIARY_V1'

def completed_price_history(history, origin):
    latest = floor(origin)-STEP
    if latest not in history:
        raise ValueError('Latest completed price unavailable')
    # A plain dictionary deliberately has no demand_forecasts or solar_values attributes.
    return {t:r for t,r in history.items() if t<=latest}
