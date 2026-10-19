"""MCP server over the appointment calendars: which offices there are, what each one
offers with the documents to bring, and its free appointment times. It only reads;
booking stays with the office's own page (booking_url).

Answers are cached for CACHE_SECONDS, so an assistant asking the same question twice
does not spend the calendar's rate limit twice.
"""

import time
from collections import Counter

from mcp.server.mcpserver import MCPServer
from mcp.types import ToolAnnotations

from . import flexappoint, smartcjm
from .offices import OFFICES, OFFICES_BY_ID

CACHE_SECONDS = 300
_cache = {}

mcp = MCPServer("visitorstracker")
# Every tool only reads public calendars.
READ_ONLY = ToolAnnotations(readOnlyHint=True, openWorldHint=True)


def _fresh(key):
    hit = _cache.get(key)
    return hit[1] if hit and time.monotonic() - hit[0] < CACHE_SECONDS else None


def _cached(key, compute):
    value = _fresh(key)
    if value is not None:
        return value
    value = compute()
    _cache[key] = (time.monotonic(), value)
    return value


def _office(office_id):
    office = OFFICES_BY_ID.get(office_id)
    if office is None:
        raise ValueError(f"unknown office {office_id!r}; known: {', '.join(OFFICES_BY_ID)}")
    return office


def _free(office, service, first_only):
    if office.system == "smartcjm":
        return smartcjm.slots(office, service)
    return flexappoint.slots(office, int(service) if service else None, first_only=first_only)


@mcp.tool(annotations=READ_ONLY)
def list_offices() -> list[dict]:
    """Offices with an online appointment calendar: id, name, authority, the booking
    system and the page where an appointment is booked."""
    return [{"id": o.id, "name": o.name, "authority": o.authority, "system": o.system,
             "booking_url": o.booking_url} for o in OFFICES]


@mcp.tool(annotations=READ_ONLY)
def list_services(office_id: str) -> list[dict]:
    """Services an office takes appointments for, with their id, length in minutes and
    the documents the office asks to bring. A matter missing here cannot be booked
    online at that office. Landratsamt (smartCJM) offices only; the Stadt Ehingen
    offices answer with their default service."""
    office = _office(office_id)
    if office.system != "smartcjm":
        return [{"id": str(office.service), "name": "default service", "minutes": None, "bring": []}]
    return _cached(("services", office.id), lambda: smartcjm.services(office))


@mcp.tool(annotations=READ_ONLY)
def free_appointments(office_id: str, service_id: str | None = None, limit: int = 20) -> dict:
    """Free appointment times of an office, earliest first, in local time (Europe/Berlin).
    Without service_id the office's shortest service is asked, whose free times cover
    every longer one. Each time lists how many counters still have it free."""
    office = _office(office_id)
    found = _cached(("slots", office.id, service_id), lambda: _free(office, service_id, False))
    counters = Counter((start, minutes) for start, minutes, _ in found)
    times = [{"start": start.isoformat(), "minutes": minutes, "counters": n}
             for (start, minutes), n in sorted(counters.items())]
    return {"office": office.name, "booking_url": office.booking_url,
            "free_times": len(times), "times": times[:limit]}


@mcp.tool(annotations=READ_ONLY)
def next_free_appointment(office_id: str, service_id: str | None = None) -> dict:
    """The earliest free appointment of an office, or null when the calendar has none."""
    office = _office(office_id)
    # A Stadt Ehingen calendar is read day by day, so stop at the first day with a time.
    found = _fresh(("slots", office.id, service_id))
    if found is None:
        found = _cached(("first", office.id, service_id), lambda: _free(office, service_id, True))
    if not found:
        return {"office": office.name, "booking_url": office.booking_url, "next": None}
    start, minutes, _ = found[0]
    return {"office": office.name, "booking_url": office.booking_url,
            "next": {"start": start.isoformat(), "minutes": minutes}}


def main():
    mcp.run()


if __name__ == "__main__":
    main()
