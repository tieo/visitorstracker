"""JSON API for the app.

Every call needs ``Authorization: Bearer <token>``. The dashboard HTML under
``/`` and ``/o/`` carries no token check of its own: the reverse proxy puts a
login in front of it, while ``/api`` and ``/app`` bypass that login and rely on
the token instead.
"""

import re
from datetime import date, datetime

from . import analysis, store
from .analysis import local

UNTIL = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def stamp(moment: datetime | None) -> str | None:
    return local(moment).isoformat() if moment else None


def office_summary(db, office, now) -> dict:
    state = analysis.snapshot(db, office["id"], now)
    last = state["last_poll"]
    return {
        "id": office["id"],
        "name": office["name"],
        "authority": office["authority"],
        "free_now": state["free_now"],
        "next_free": stamp(state["next_free"]),
        "last_poll": stamp(last.at) if last else None,
        "last_poll_ok": last.ok if last else None,
        "last_error": last.error if last and not last.ok else None,
    }


def free_until(db, office_id: str, until: str, now: datetime, limit: int = 20) -> list[str]:
    """Start times of free slots on or before ``until``, earliest first."""
    rows = db.execute(
        "SELECT DISTINCT start FROM slots WHERE office = ? AND gone_seen IS NULL AND start > ? "
        "ORDER BY start",
        (office_id, store.utc(now)),
    ).fetchall()
    starts = [analysis.parse(r["start"]) for r in rows]
    return [stamp(s) for s in starts if local(s).date().isoformat() <= until][:limit]


def alert_json(db, row, now) -> dict:
    return {
        "id": row["id"],
        "office": row["office"],
        "until": row["until_date"],
        "free": free_until(db, row["office"], row["until_date"], now),
    }


def offices(db, all_offices, now):
    return 200, [office_summary(db, o, now) for o in all_offices]


def list_alerts(db, endpoint, now):
    return 200, [alert_json(db, row, now) for row in store.list_alerts(db, endpoint)]


def create_alert(db, by_id, body, now):
    office = body.get("office")
    until = body.get("until", "")
    endpoint = body.get("endpoint", "")
    if office not in by_id:
        return 400, {"error": "unknown office"}
    if not UNTIL.match(until):
        return 400, {"error": "until must be YYYY-MM-DD"}
    try:
        date.fromisoformat(until)
    except ValueError:
        return 400, {"error": "until is not a date"}
    if not endpoint.startswith("https://"):
        return 400, {"error": "endpoint must be an https URL"}
    alert_id = store.add_alert(db, office, until, endpoint, now)
    row = db.execute("SELECT * FROM alerts WHERE id = ?", (alert_id,)).fetchone()
    return 201, alert_json(db, row, now)


def delete_alert(db, alert_id: str):
    if not alert_id.isdigit() or not store.delete_alert(db, int(alert_id)):
        return 404, {"error": "no such alert"}
    return 204, None
