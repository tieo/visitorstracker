"""Turns the slot history into what the dashboard shows.

A slot that vanished was either booked or dropped out of the bookable window.
Booking systems stop offering a slot some lead time before it starts; that
lead is estimated per office as the shortest lead at which a slot was ever
still shown. A slot that vanished with less lead than that ran out of the
window unbooked; any other vanishing counts as booked.

How fast slots go is measured from release (the first poll showing the slot)
to booking. Slots still free, or that ran out unbooked, are right-censored, so
the median comes from a Kaplan-Meier estimate rather than from booked slots
alone, which would make every cell look fast. Slots already free at the
office's first poll have an unknown release time and are left out of it.
"""

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .sources import Slot, appointments

BERLIN = ZoneInfo("Europe/Berlin")
WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
BUCKET_MINUTES = 30


def parse(stamp: str) -> datetime:
    return datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


@dataclass
class Poll:
    at: datetime
    ok: bool
    free: int | None
    appointments: int | None
    error: str | None


@dataclass
class Tracked:
    start: datetime
    end: datetime
    resource: str
    first_seen: datetime
    last_seen: datetime
    gone_seen: datetime | None
    released_in_view: bool
    outcome: str  # "free", "booked", "expired"


def polls(db, office: str) -> list[Poll]:
    rows = db.execute(
        "SELECT at, ok, free, appointments, error FROM polls WHERE office = ? ORDER BY at",
        (office,),
    ).fetchall()
    return [Poll(parse(r["at"]), bool(r["ok"]), r["free"], r["appointments"], r["error"])
            for r in rows]


def tracked_slots(db, office: str, now: datetime) -> list[Tracked]:
    first_ok = db.execute(
        "SELECT MIN(at) FROM polls WHERE office = ? AND ok = 1", (office,)
    ).fetchone()[0]
    rows = db.execute(
        "SELECT start, end, resource, first_seen, last_seen, gone_seen FROM slots WHERE office = ?",
        (office,),
    ).fetchall()
    if not rows:
        return []
    shortest_lead = min(parse(r["start"]) - parse(r["last_seen"]) for r in rows)
    slots = []
    for r in rows:
        start = parse(r["start"])
        gone = parse(r["gone_seen"]) if r["gone_seen"] else None
        if gone is None:
            outcome = "free" if start > now else "expired"
        elif start - gone < shortest_lead:
            outcome = "expired"
        else:
            outcome = "booked"
        slots.append(Tracked(start, parse(r["end"]), r["resource"], parse(r["first_seen"]),
                             parse(r["last_seen"]), gone, r["first_seen"] != first_ok, outcome))
    return slots


def median_hours_to_booking(slots: list[Tracked], now: datetime) -> tuple[float | None, float]:
    """Kaplan-Meier median of release-to-booking time, in hours.

    Returns (median, longest observed time). The median is None when fewer
    than half of the slots were booked within the observed time, i.e. the
    slots mostly stay free.
    """
    samples = []
    for slot in slots:
        if not slot.released_in_view:
            continue
        if slot.outcome == "booked":
            # Booked somewhere between the last poll showing it and the next.
            moment = slot.last_seen + (slot.gone_seen - slot.last_seen) / 2
            samples.append(((moment - slot.first_seen).total_seconds() / 3600, True))
        else:
            end = slot.last_seen if slot.gone_seen else min(now, slot.start)
            samples.append(((end - slot.first_seen).total_seconds() / 3600, False))
    if not samples:
        return None, 0.0
    samples.sort()
    at_risk = len(samples)
    survival = 1.0
    index = 0
    while index < len(samples):
        hours = samples[index][0]
        events = censored = 0
        while index < len(samples) and samples[index][0] == hours:
            events += samples[index][1]
            censored += not samples[index][1]
            index += 1
        if events:
            survival *= 1 - events / at_risk
            if survival <= 0.5:
                return hours, samples[-1][0]
        at_risk -= events + censored
    return None, samples[-1][0]


def local(moment: datetime) -> datetime:
    return moment.astimezone(BERLIN)


def bucket(moment: datetime) -> tuple[int, int]:
    start = local(moment)
    minutes = start.hour * 60 + start.minute
    return start.weekday(), minutes - minutes % BUCKET_MINUTES


def heat_cells(slots: list[Tracked], now: datetime) -> dict[tuple[int, int], dict]:
    groups = defaultdict(list)
    for slot in slots:
        groups[bucket(slot.start)].append(slot)
    cells = {}
    for key, members in groups.items():
        median, observed = median_hours_to_booking(members, now)
        counts = defaultdict(int)
        for slot in members:
            counts[slot.outcome] += 1
        cells[key] = {
            "median": median,
            "observed": observed,
            "measured": sum(s.released_in_view for s in members),
            "booked": counts["booked"],
            "expired": counts["expired"],
            "free": counts["free"],
        }
    return cells


def free_appointments(slots: list[Tracked]) -> int:
    free = [Slot(s.start, s.end, s.resource) for s in slots if s.outcome == "free"]
    return appointments(free) if free else 0


def free_by_day(slots: list[Tracked]) -> list[tuple[datetime, int]]:
    days = defaultdict(list)
    for slot in slots:
        if slot.outcome == "free":
            days[local(slot.start).date()].append(slot)
    return sorted((day, free_appointments(group)) for day, group in days.items())


def snapshot(db, office: str, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    history = polls(db, office)
    slots = tracked_slots(db, office, now)
    free = [s for s in slots if s.outcome == "free"]
    ok = [p for p in history if p.ok]
    return {
        "polls": history,
        "last_poll": history[-1] if history else None,
        "last_ok": ok[-1] if ok else None,
        "tracking_since": ok[0].at if ok else None,
        "free_now": free_appointments(free),
        "next_free": min((s.start for s in free), default=None),
        "cells": heat_cells(slots, now),
        "free_by_day": free_by_day(slots),
        "timeline": [(p.at, p.appointments) for p in ok if p.appointments is not None],
    }
