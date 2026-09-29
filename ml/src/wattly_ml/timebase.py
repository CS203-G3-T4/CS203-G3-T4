from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

SGT = ZoneInfo("Asia/Singapore")
STEP = timedelta(minutes=30)
DAY = timedelta(days=1)


def instant(value: str | datetime) -> datetime:
    value = datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("Timestamp must include a timezone")
    return value.astimezone(timezone.utc)


def floor(value: datetime) -> datetime:
    value = instant(value)
    return value.replace(minute=value.minute // 30 * 30, second=0, microsecond=0)


def targets(as_of: datetime) -> list[datetime]:
    return [floor(as_of) + STEP * h for h in range(1, 25)]
