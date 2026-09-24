"""SQLite history of free slots.

A slot is stored as the stretch of polls during which it was seen free:
``first_seen`` is the first poll showing it, ``last_seen`` the latest, and
``gone_seen`` the first successful poll that no longer showed it. A slot that
disappears and later comes back (a cancellation) starts a new row. Failed polls
are recorded but never close a slot, so an outage does not look like a rush of
bookings.
"""

import sqlite3
from datetime import datetime, timezone

from .sources import appointments

SCHEMA = """
CREATE TABLE IF NOT EXISTS polls (
    id INTEGER PRIMARY KEY,
    office TEXT NOT NULL,
    at TEXT NOT NULL,
    ok INTEGER NOT NULL,
    free INTEGER,
    appointments INTEGER,
    error TEXT
);
CREATE INDEX IF NOT EXISTS polls_office_at ON polls (office, at);
CREATE TABLE IF NOT EXISTS slots (
    id INTEGER PRIMARY KEY,
    office TEXT NOT NULL,
    start TEXT NOT NULL,
    end TEXT NOT NULL,
    resource TEXT NOT NULL,
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL,
    gone_seen TEXT
);
CREATE INDEX IF NOT EXISTS slots_open ON slots (office, gone_seen);
CREATE INDEX IF NOT EXISTS slots_start ON slots (office, start);
CREATE TABLE IF NOT EXISTS blocks (
    system TEXT PRIMARY KEY,
    until TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY,
    office TEXT NOT NULL,
    until_date TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    created TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS alert_hits (
    alert INTEGER NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    slot INTEGER NOT NULL,
    PRIMARY KEY (alert, slot)
);
"""


def utc(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def connect(path) -> sqlite3.Connection:
    db = sqlite3.connect(path, timeout=30)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript(SCHEMA)
    return db


def blocked_until(db, system: str) -> str | None:
    row = db.execute("SELECT until FROM blocks WHERE system = ?", (system,)).fetchone()
    return row["until"] if row else None


def block(db, system: str, until: datetime):
    with db:
        db.execute(
            "INSERT INTO blocks (system, until) VALUES (?, ?) "
            "ON CONFLICT (system) DO UPDATE SET until = excluded.until",
            (system, utc(until)),
        )


def record_failure(db, office: str, at: datetime, error: str):
    with db:
        db.execute(
            "INSERT INTO polls (office, at, ok, error) VALUES (?, ?, 0, ?)",
            (office, utc(at), error[:500]),
        )


def record_snapshot(db, office: str, at: datetime, slots) -> list[int]:
    """Stores one successful poll and returns the ids of newly free slots."""
    now = utc(at)
    seen = {(utc(s.start), s.resource): s for s in slots}
    with db:
        db.execute(
            "INSERT INTO polls (office, at, ok, free, appointments) VALUES (?, ?, 1, ?, ?)",
            (office, now, len(seen), appointments(seen.values()) if seen else 0),
        )
        open_rows = db.execute(
            "SELECT id, start, resource FROM slots WHERE office = ? AND gone_seen IS NULL",
            (office,),
        ).fetchall()
        still_open = set()
        for row in open_rows:
            key = (row["start"], row["resource"])
            if key in seen:
                still_open.add(key)
                db.execute("UPDATE slots SET last_seen = ? WHERE id = ?", (now, row["id"]))
            else:
                db.execute("UPDATE slots SET gone_seen = ? WHERE id = ?", (now, row["id"]))
        new_ids = []
        for key, slot in seen.items():
            if key in still_open:
                continue
            cursor = db.execute(
                "INSERT INTO slots (office, start, end, resource, first_seen, last_seen) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (office, key[0], utc(slot.end), key[1], now, now),
            )
            new_ids.append(cursor.lastrowid)
    return new_ids


def add_alert(db, office: str, until_date: str, endpoint: str, at: datetime) -> int:
    with db:
        cursor = db.execute(
            "INSERT INTO alerts (office, until_date, endpoint, created) VALUES (?, ?, ?, ?)",
            (office, until_date, endpoint, utc(at)),
        )
    return cursor.lastrowid


def list_alerts(db, endpoint: str | None = None) -> list[sqlite3.Row]:
    if endpoint is None:
        return db.execute("SELECT * FROM alerts ORDER BY id").fetchall()
    return db.execute("SELECT * FROM alerts WHERE endpoint = ? ORDER BY id", (endpoint,)).fetchall()


def delete_alert(db, alert_id: int) -> bool:
    with db:
        return db.execute("DELETE FROM alerts WHERE id = ?", (alert_id,)).rowcount > 0


def claim_hits(db, alert_id: int, slot_ids) -> list[int]:
    """Marks slots as announced for an alert; returns those not announced before."""
    fresh = []
    with db:
        for slot in slot_ids:
            cursor = db.execute(
                "INSERT OR IGNORE INTO alert_hits (alert, slot) VALUES (?, ?)", (alert_id, slot)
            )
            if cursor.rowcount:
                fresh.append(slot)
    return fresh
