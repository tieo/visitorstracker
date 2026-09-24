"""Free-slot alerts delivered over UnifiedPush.

An alert asks for any free appointment at one office on or before a date. After
each poll the newly free slots of that office are matched against its alerts,
and every alert with matches gets one push naming the earliest time. A slot is
announced once per free episode: when it is booked and later freed again it
is a new slot row and is announced again.

The push endpoint is the URL a UnifiedPush distributor (the ntfy app) handed to
the phone; POSTing a body to it delivers that body to the app.
"""

import json
import logging
from datetime import datetime

from . import store
from .analysis import local, parse

log = logging.getLogger(__name__)


def matching(db, alert, slot_ids, now: datetime) -> list[tuple[int, datetime]]:
    if not slot_ids:
        return []
    marks = ",".join("?" * len(slot_ids))
    rows = db.execute(f"SELECT id, start FROM slots WHERE id IN ({marks})", list(slot_ids)).fetchall()
    found = []
    for row in rows:
        start = parse(row["start"])
        if start > now and local(start).date().isoformat() <= alert["until_date"]:
            found.append((row["id"], start))
    return sorted(found, key=lambda item: item[1])


def message(office: dict, starts: list[datetime]) -> dict:
    earliest = local(starts[0])
    return {
        "type": "free_slot",
        "office": office["id"],
        "name": office["name"],
        "count": len(starts),
        "earliest": earliest.isoformat(),
    }


def notify(db, office: dict, slot_ids, now: datetime, session):
    for alert in db.execute("SELECT * FROM alerts WHERE office = ?", (office["id"],)).fetchall():
        found = matching(db, alert, slot_ids, now)
        fresh = set(store.claim_hits(db, alert["id"], [slot for slot, _ in found]))
        starts = [start for slot, start in found if slot in fresh]
        if not starts:
            continue
        body = json.dumps(message(office, starts))
        try:
            response = session.post(alert["endpoint"], data=body.encode(), timeout=15,
                                    headers={"Content-Type": "application/json"})
        except Exception:
            log.exception("alert %s: push failed", alert["id"])
            continue
        if response.status_code in (404, 410):
            # The distributor dropped the registration; nobody receives this alert any more.
            log.warning("alert %s: endpoint gone, removing", alert["id"])
            store.delete_alert(db, alert["id"])
        elif response.status_code >= 400:
            log.warning("alert %s: push answered %s", alert["id"], response.status_code)
        else:
            log.info("alert %s: pushed %d new slots", alert["id"], len(starts))
