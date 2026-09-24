"""One polling round over every configured office.

Offices on the same booking system share its rate limit (smartCJM sits behind
a Cloudflare rule that blocks the client IP after a burst). Requests are
spaced out, and once a system answers 429 it is left alone, in this round and
in later ones, until the time its ``Retry-After`` named has passed: requests
sent during a block can only prolong it.
"""

import logging
import time
from datetime import datetime, timedelta, timezone

import requests

from . import alerts, sources, store

log = logging.getLogger(__name__)

USER_AGENT = "visitorstracker (appointment availability statistics)"
DEFAULT_BLOCK = timedelta(minutes=15)


def run(db, offices, pause_seconds: float = 5.0):
    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT
    pushes = requests.Session()
    for office in offices:
        system = office["system"]
        at = datetime.now(timezone.utc)
        until = store.blocked_until(db, system)
        if until and until > store.utc(at):
            store.record_failure(db, office["id"], at, f"skipped: {system} blocked until {until}")
            continue
        try:
            slots = sources.read(office, session)
        except sources.RateLimited as error:
            wait = timedelta(seconds=error.retry_after) if error.retry_after else DEFAULT_BLOCK
            store.block(db, system, at + wait + timedelta(seconds=30))
            log.warning("%s: %s", office["id"], error)
            store.record_failure(db, office["id"], at, str(error))
        except Exception as error:
            log.exception("%s: failed", office["id"])
            store.record_failure(db, office["id"], at, f"{type(error).__name__}: {error}")
        else:
            new_ids = store.record_snapshot(db, office["id"], at, slots)
            log.info("%s: %d free", office["id"], len(slots))
            alerts.notify(db, office, new_ids, at, pushes)
        time.sleep(pause_seconds)
