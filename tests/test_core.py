import json
import threading
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

import pytest

from visitorstracker import alerts, analysis, store, web
from visitorstracker.sources import Slot, appointments

T0 = datetime(2026, 10, 5, 7, 0, tzinfo=timezone.utc)  # Mon 09:00 in Berlin
OFFICE = {"id": "kfz", "name": "Kfz", "authority": "LRA", "system": "smartcjm"}


def slot(minute, length=10, resource="a"):
    start = T0 + timedelta(minutes=minute)
    return Slot(start, start + timedelta(minutes=length), resource)


@pytest.fixture
def db(tmp_path):
    return store.connect(tmp_path / "t.db")


def test_overlapping_start_times_count_as_one_appointment_each_stretch():
    # 10 minute service offered every 5 minutes: 09:00-09:30 holds three.
    starts = [slot(m) for m in (0, 5, 10, 15, 20)]
    assert appointments(starts) == 3


def test_counters_are_counted_separately():
    assert appointments([slot(0, resource="a"), slot(0, resource="b")]) == 2


def test_snapshot_closes_vanished_slots_and_reports_new_ones(db):
    first = store.record_snapshot(db, "kfz", T0 - timedelta(days=3), [slot(0), slot(15)])
    assert len(first) == 2
    later = store.record_snapshot(db, "kfz", T0 - timedelta(days=2), [slot(15), slot(30)])
    assert len(later) == 1
    gone = db.execute("SELECT start FROM slots WHERE gone_seen IS NOT NULL").fetchall()
    assert [r["start"] for r in gone] == [store.utc(slot(0).start)]


def test_failed_poll_does_not_close_slots(db):
    store.record_snapshot(db, "kfz", T0 - timedelta(days=3), [slot(0)])
    store.record_failure(db, "kfz", T0 - timedelta(days=2), "boom")
    assert db.execute("SELECT COUNT(*) FROM slots WHERE gone_seen IS NULL").fetchone()[0] == 1


def test_median_stays_open_while_most_slots_are_free():
    now = T0 - timedelta(days=1)
    base = now - timedelta(days=2)
    tracked = [analysis.Tracked(T0, T0, "a", base, now, None, True, "free") for _ in range(3)]
    tracked.append(analysis.Tracked(T0, T0, "a", base, base, base + timedelta(hours=2), True, "booked"))
    median, observed = analysis.median_hours_to_booking(tracked, now)
    assert median is None and observed == pytest.approx(48)


class FakeSession:
    def __init__(self, status=200):
        self.status, self.sent = status, []

    def post(self, url, data, timeout, headers):
        self.sent.append((url, json.loads(data)))
        return type("Response", (), {"status_code": self.status})()


def test_alert_pushes_new_matches_once(db):
    now = T0 - timedelta(days=2)
    alert = store.add_alert(db, "kfz", "2026-10-05", "https://push.example/x", now)
    ids = store.record_snapshot(db, "kfz", now, [slot(0), slot(60 * 24 * 3)])
    session = FakeSession()
    alerts.notify(db, OFFICE, ids, now, session)
    alerts.notify(db, OFFICE, ids, now, session)
    assert len(session.sent) == 1
    url, body = session.sent[0]
    assert url == "https://push.example/x" and body["count"] == 1
    assert body["earliest"].startswith("2026-10-05T09:00")
    assert store.list_alerts(db)[0]["id"] == alert


def test_alert_with_gone_endpoint_is_removed(db):
    now = T0 - timedelta(days=2)
    store.add_alert(db, "kfz", "2026-10-31", "https://push.example/x", now)
    ids = store.record_snapshot(db, "kfz", now, [slot(0)])
    alerts.notify(db, OFFICE, ids, now, FakeSession(status=410))
    assert store.list_alerts(db) == []


@pytest.fixture
def server(tmp_path):
    path = tmp_path / "s.db"
    store.connect(path).close()
    httpd = web.make_server(path, [OFFICE], "127.0.0.1", 0, "secret")
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def call(url, method="GET", body=None, token="secret"):
    request = urllib.request.Request(url, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read()
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"null")


def test_api_refuses_without_token(server):
    assert call(server + "/api/offices", token=None)[0] == 401
    assert call(server + "/api/offices", token="wrong")[0] == 401
    assert call(server + "/app/o/kfz", token=None)[0] == 401


def test_alert_lifecycle(server):
    status, created = call(server + "/api/alerts", "POST",
                           {"office": "kfz", "until": "2026-10-09", "endpoint": "https://push.example/y"})
    assert status == 201 and created["office"] == "kfz"
    assert call(server + "/api/alerts", "POST",
                {"office": "kfz", "until": "2026-10-09", "endpoint": "http://plain"})[0] == 400
    assert [a["id"] for a in call(server + "/api/alerts")[1]] == [created["id"]]
    assert call(server + f"/api/alerts/{created['id']}", "DELETE")[0] == 204
    assert call(server + "/api/alerts")[1] == []
