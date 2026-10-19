"""flexappoint JSON API (used by the Stadt Ehingen).

`/api/available-times/disabled-days` lists the days of a month without any free time
for the chosen service; every other day up to the horizon is then asked for its free
times. A time carries `count`, the number of parallel appointments still bookable at
that start. Times are local to the office.
"""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from .http import Session

BERLIN = ZoneInfo("Europe/Berlin")
AJAX = {"X-Requested-With": "XMLHttpRequest"}


class ApiError(Exception):
    pass


def _items(response):
    root = response.json()
    if root.get("status") is False:
        raise ApiError(root.get("message") or "request failed")
    return root.get("items") or []


def slots(office, service=None, first_only=False):
    """Free start times as (start, minutes, resource); with first_only the reading stops
    at the first day that has any."""
    service = service or office.service
    api = office.url.rstrip("/") + "/api"
    today = datetime.now(BERLIN).date()
    last = today + timedelta(days=office.horizon_days)
    with Session() as session:
        disabled = set()
        month = today.replace(day=1)
        while month <= last:
            params = {"departmentId": office.department, "year": month.year, "month": month.month,
                      "format": "Y-m-d", "services[0][id]": service, "services[0][count]": 1}
            disabled.update(_items(session.get(f"{api}/available-times/disabled-days", params, AJAX)))
            month = (month + timedelta(days=32)).replace(day=1)
        found = []
        day = today
        while day <= last:
            if day.isoformat() not in disabled:
                params = {"departmentId": office.department, "date": day.isoformat(),
                          "service[0][id]": service, "service[0][count]": 1}
                for item in _items(session.get(f"{api}/available-times", params, AJAX)):
                    start = datetime.combine(day, time.fromisoformat(item["start_time"]), BERLIN)
                    end = datetime.combine(day, time.fromisoformat(item["end_time"]), BERLIN)
                    minutes = int((end - start).total_seconds() // 60)
                    found += [(start, minutes, f"#{i}") for i in range(int(item["count"]))]
                if first_only and found:
                    break
            day += timedelta(days=1)
    return sorted(found)
