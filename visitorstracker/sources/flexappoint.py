"""flexappoint JSON API (used by the Stadt Ehingen).

``/api/available-times/disabled-days`` lists the days of a month without any
free time for the chosen service; every other day up to the horizon is then
asked for its free times. A time carries ``count``, the number of parallel
appointments still bookable at that start, and each of them becomes a slot.
Times are local to the office.
"""

import time
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from . import RateLimited, Slot

BERLIN = ZoneInfo("Europe/Berlin")


def _get(session, url, params):
    response = session.get(
        url, params=params, headers={"X-Requested-With": "XMLHttpRequest"}, timeout=30
    )
    if response.status_code == 429:
        raise RateLimited(response)
    response.raise_for_status()
    body = response.json()
    if not body.get("status", True):
        raise ValueError(body.get("message", "request failed"))
    return body.get("items", [])


def _months(first, last):
    year, month = first.year, first.month
    while (year, month) <= (last.year, last.month):
        yield year, month
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)


def read(office, session):
    api = office["url"].rstrip("/") + "/api"
    service = {"[0][id]": office["service"], "[0][count]": 1}
    today = datetime.now(BERLIN).date()
    last = today + timedelta(days=office.get("horizon_days", 60))
    pause = office.get("pause_seconds", 1.0)

    disabled = set()
    for year, month in _months(today, last):
        params = {"departmentId": office["department"], "year": year, "month": month,
                  "format": "Y-m-d", **{f"services{k}": v for k, v in service.items()}}
        disabled.update(_get(session, api + "/available-times/disabled-days", params))
        time.sleep(pause)

    slots = []
    day = today
    while day <= last:
        if day.isoformat() not in disabled:
            params = {"departmentId": office["department"], "date": day.isoformat(),
                      **{f"service{k}": v for k, v in service.items()}}
            for item in _get(session, api + "/available-times", params):
                start = _at(day, item["start_time"])
                end = _at(day, item["end_time"])
                slots.extend(Slot(start, end, f"#{n}") for n in range(int(item["count"])))
            time.sleep(pause)
        day += timedelta(days=1)
    return slots


def _at(day: date, clock: str) -> datetime:
    hour, minute = map(int, clock.split(":"))
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=BERLIN)
