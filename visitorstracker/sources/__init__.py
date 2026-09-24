"""Readers for online appointment systems.

Every reader turns one office's booking page into the list of slots that are
free right now. A reader only walks the public booking flow up to the list of
free times and never reserves anything.
"""

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Slot:
    """One free unit of capacity: a start time at one counter.

    ``resource`` tells apart parallel counters offering the same start time,
    so two free counters at 09:30 are two slots.
    """

    start: datetime
    end: datetime
    resource: str


class RateLimited(Exception):
    """The booking system answered HTTP 429.

    ``retry_after`` holds the seconds the server asked to wait, when it said.
    """

    def __init__(self, response):
        header = response.headers.get("Retry-After", "")
        self.retry_after = int(header) if header.isdigit() else None
        super().__init__(f"HTTP 429, retry after {self.retry_after}s")


def appointments(slots) -> int:
    """How many appointments the free slots can still hold.

    Start times overlap: a 10 minute service offered every 5 minutes shows two
    start times per appointment. Each counter's free time is merged into
    stretches and every stretch holds as many whole appointments as fit.
    """
    by_resource = {}
    for slot in slots:
        by_resource.setdefault(slot.resource, []).append(slot)
    total = 0
    for group in by_resource.values():
        group.sort(key=lambda s: s.start)
        length = group[0].end - group[0].start
        begin, end = group[0].start, group[0].end
        for slot in group[1:]:
            if slot.start <= end:
                end = max(end, slot.end)
            else:
                total += (end - begin) // length
                begin, end = slot.start, slot.end
        total += (end - begin) // length
    return total


def read(office: dict, session) -> list[Slot]:
    from . import flexappoint, smartcjm

    readers = {"smartcjm": smartcjm.read, "flexappoint": flexappoint.read}
    return readers[office["system"]](office, session)
