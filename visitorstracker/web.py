"""Dashboard: server-rendered HTML with inline SVG charts.

Every request reads the database afresh, so the page is as current as the
last poll. The charts carry their values in ``data-tip`` attributes for the
hover layer, and every chart has a table twin with the same numbers.
"""

import hmac
import html
import json
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import analysis, api, store
from .analysis import BUCKET_MINUTES, WEEKDAYS, local

# Heatmap classes, fastest first. The ramp steps are validated as an ordinal
# ramp in both modes (light: darker is faster, dark: lighter is faster).
CLASSES = [
    ("fast", "under 1 h", 1),
    ("hours", "1 to 12 h", 12),
    ("days", "12 h to 3 days", 72),
    ("slow", "over 3 days", None),
    ("stays", "stays free", None),
]
# A cell counts as "stays free" only once its slots were watched this long
# without half of them going.
STAYS_AFTER_HOURS = 24

STYLE = """
.viz-root {
  color-scheme: light;
  --surface-1: #fcfcfb; --page: #f9f9f7;
  --text-primary: #0b0b0b; --text-secondary: #52514e; --text-muted: #898781;
  --grid: #e1e0d9; --axis: #c3c2b7; --border: rgba(11,11,11,0.10);
  --series-1: #2a78d6; --series-1-wash: rgba(42,120,214,0.10);
  --critical: #d03b3b;
  --heat-fast: #104281; --heat-hours: #1c5cab; --heat-days: #2a78d6;
  --heat-slow: #5598e7; --heat-stays: #86b6ef;
}
@media (prefers-color-scheme: dark) {
  :root:where(:not([data-theme="light"])) .viz-root {
    color-scheme: dark;
    --surface-1: #1a1a19; --page: #0d0d0d;
    --text-primary: #ffffff; --text-secondary: #c3c2b7; --text-muted: #898781;
    --grid: #2c2c2a; --axis: #383835; --border: rgba(255,255,255,0.10);
    --series-1: #3987e5; --series-1-wash: rgba(57,135,229,0.10);
    --heat-fast: #9ec5f4; --heat-hours: #6da7ec; --heat-days: #3987e5;
    --heat-slow: #256abf; --heat-stays: #184f95;
  }
}
* { box-sizing: border-box; }
body { margin: 0; }
.viz-root {
  min-height: 100vh; background: var(--page); color: var(--text-primary);
  font: 15px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.frame { padding: 24px; max-width: 1100px; margin: 0 auto; }
a { color: inherit; }
h1 { font-size: 22px; font-weight: 600; margin: 0 0 4px; }
h2 { font-size: 16px; font-weight: 600; margin: 0 0 2px; }
.sub { color: var(--text-secondary); margin: 0 0 12px; }
nav.offices { display: flex; flex-wrap: wrap; gap: 6px; margin: 16px 0 20px; }
nav.offices a {
  text-decoration: none; padding: 6px 12px; border-radius: 6px;
  border: 1px solid var(--border); color: var(--text-secondary); background: var(--surface-1);
}
nav.offices a[aria-current] { color: var(--text-primary); border-color: var(--series-1); font-weight: 600; }
.card {
  background: var(--surface-1); border: 1px solid var(--border); border-radius: 10px;
  padding: 16px 18px; margin-bottom: 16px;
}
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 16px; }
.tiles .card { margin-bottom: 0; }
.tile .label { color: var(--text-secondary); font-size: 13px; }
.tile .value { font-size: 26px; font-weight: 600; margin-top: 2px; }
.tile .note { color: var(--text-muted); font-size: 13px; }
.status-bad { color: var(--text-primary); }
.status-bad::before { content: "\\26A0\\FE0E  "; color: var(--critical); }
svg text { fill: var(--text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
svg .grid { stroke: var(--grid); stroke-width: 1; }
svg .axis { stroke: var(--axis); stroke-width: 1; }
svg [data-tip] { cursor: default; }
svg rect.cell:hover, svg rect.cell:focus, svg path.bar:hover, svg path.bar:focus { opacity: 0.8; outline: none; }
.chart { width: 100%; overflow-x: auto; }
.legend { display: flex; flex-wrap: wrap; gap: 14px; margin: 10px 0 0; color: var(--text-secondary); font-size: 13px; }
.legend span::before {
  content: ""; display: inline-block; width: 12px; height: 12px; border-radius: 3px;
  margin-right: 6px; vertical-align: -1px; background: var(--swatch);
}
.legend .empty::before { background: transparent; box-shadow: inset 0 0 0 1px var(--axis); }
details { margin-top: 10px; color: var(--text-secondary); font-size: 13px; }
table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
th, td { text-align: left; padding: 5px 10px 5px 0; border-bottom: 1px solid var(--grid); }
th { color: var(--text-secondary); font-weight: 600; }
td.num, th.num { text-align: right; }
table.overview td, table.overview th { font-size: 15px; padding: 9px 12px 9px 0; }
@media (max-width: 520px) {
  .frame { padding: 14px; }
  .tiles { grid-template-columns: 1fr 1fr; gap: 10px; }
  .tiles .tile:last-child { grid-column: span 2; }
  .tile .value { font-size: 20px; }
  .card { padding: 14px; }
}
#tip {
  position: fixed; pointer-events: none; display: none; z-index: 10;
  background: var(--surface-1); color: var(--text-primary); border: 1px solid var(--border);
  border-radius: 8px; padding: 8px 10px; font-size: 13px; white-space: pre-line;
  box-shadow: 0 4px 14px rgba(0,0,0,0.12); max-width: 280px;
}
"""

SCRIPT = """
const tip = document.getElementById('tip');
function place(e, text) {
  tip.textContent = text; tip.style.display = 'block';
  const x = Math.min(e.clientX + 14, window.innerWidth - tip.offsetWidth - 8);
  const y = Math.min(e.clientY + 14, window.innerHeight - tip.offsetHeight - 8);
  tip.style.left = x + 'px'; tip.style.top = y + 'px';
}
document.querySelectorAll('[data-tip]').forEach(el => {
  el.addEventListener('pointermove', e => place(e, el.dataset.tip));
  el.addEventListener('pointerleave', () => tip.style.display = 'none');
  el.addEventListener('focus', () => {
    const r = el.getBoundingClientRect();
    place({clientX: r.right, clientY: r.top}, el.dataset.tip);
  });
  el.addEventListener('blur', () => tip.style.display = 'none');
});
document.querySelectorAll('svg.timeline').forEach(svg => {
  const points = JSON.parse(svg.nextElementSibling.textContent);
  const hair = svg.querySelector('.hair'), dot = svg.querySelector('.hairdot');
  const hit = svg.querySelector('.hit');
  hit.addEventListener('pointermove', e => {
    const box = svg.getBoundingClientRect();
    const x = (e.clientX - box.left) * svg.viewBox.baseVal.width / box.width;
    let best = points[0];
    for (const p of points) if (Math.abs(p.x - x) < Math.abs(best.x - x)) best = p;
    hair.setAttribute('x1', best.x); hair.setAttribute('x2', best.x); hair.style.display = '';
    dot.setAttribute('cx', best.x); dot.setAttribute('cy', best.y); dot.style.display = '';
    place(e, best.free + ' free\\n' + best.label);
  });
  hit.addEventListener('pointerleave', () => {
    tip.style.display = 'none'; hair.style.display = 'none'; dot.style.display = 'none';
  });
});
"""

e = html.escape


def when(moment: datetime | None, with_day=True) -> str:
    if moment is None:
        return "–"
    moment = local(moment)
    clock = moment.strftime("%H:%M")
    if not with_day:
        return clock
    return f"{WEEKDAYS[moment.weekday()]} {moment.strftime('%d %b')} {clock}"


def ago(moment: datetime | None, now: datetime) -> str:
    if moment is None:
        return "never"
    minutes = int((now - moment).total_seconds() // 60)
    if minutes < 1:
        return "just now"
    if minutes < 60:
        return f"{minutes} min ago"
    if minutes < 48 * 60:
        return f"{minutes // 60} h ago"
    return f"{minutes // 1440} days ago"


def hours_text(hours: float) -> str:
    if hours < 1:
        return f"{max(1, round(hours * 60))} min"
    if hours < 48:
        return f"{hours:.1f} h"
    return f"{hours / 24:.1f} days"


def classify(cell: dict) -> str | None:
    if cell["median"] is None:
        if cell["measured"] and cell["observed"] >= STAYS_AFTER_HOURS:
            return "stays"
        return None
    for key, _label, limit in CLASSES:
        if limit is None or cell["median"] < limit:
            return key
    return None


def clock_label(minutes: int) -> str:
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def heatmap(cells: dict) -> str:
    if not cells:
        return '<p class="sub">No slots seen yet.</p>'
    days = sorted({d for d, _ in cells})
    first = min(m for _, m in cells)
    last = max(m for _, m in cells)
    columns = list(range(first, last + BUCKET_MINUTES, BUCKET_MINUTES))
    size, gap, left, top = 30, 2, 34, 22
    width = left + len(columns) * (size + gap)
    height = top + len(days) * (size + gap)
    parts = [f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" '
             f'aria-label="How fast slots get booked, by weekday and time of day">']
    for index, minutes in enumerate(columns):
        if minutes % 60 == 0:
            x = left + index * (size + gap)
            parts.append(f'<text x="{x}" y="14">{clock_label(minutes)}</text>')
    for row, day in enumerate(days):
        y = top + row * (size + gap)
        parts.append(f'<text x="0" y="{y + size / 2 + 4}">{WEEKDAYS[day]}</text>')
        for index, minutes in enumerate(columns):
            cell = cells.get((day, minutes))
            if not cell:
                continue
            x = left + index * (size + gap)
            kind = classify(cell)
            fill = f"var(--heat-{kind})" if kind else "transparent"
            stroke = "" if kind else ' stroke="var(--axis)" stroke-width="1"'
            parts.append(
                f'<rect class="cell" tabindex="0" x="{x}" y="{y}" width="{size}" height="{size}" rx="4" '
                f'fill="{fill}"{stroke} data-tip="{e(cell_tip(day, minutes, cell, kind))}"/>'
            )
    parts.append("</svg>")
    legend = "".join(
        f'<span style="--swatch: var(--heat-{key})">{label}</span>' for key, label, _ in CLASSES
    ) + '<span class="empty">not watched long enough</span>'
    return f'<div class="chart">{"".join(parts)}</div><div class="legend">{legend}</div>'


def cell_tip(day, minutes, cell, kind) -> str:
    head = f"{WEEKDAYS[day]} {clock_label(minutes)} to {clock_label(minutes + BUCKET_MINUTES)}"
    if cell["median"] is not None:
        speed = f"half booked after {hours_text(cell['median'])}"
    elif kind == "stays":
        speed = f"less than half booked after {hours_text(cell['observed'])}"
    elif cell["measured"]:
        speed = f"watched for {hours_text(cell['observed'])} so far"
    else:
        speed = "release not observed"
    return (f"{speed}\n{head}\nstart times: {cell['booked']} booked, {cell['free']} still free, "
            f"{cell['expired']} expired unbooked")


def heat_table(cells: dict) -> str:
    rows = []
    for (day, minutes), cell in sorted(cells.items()):
        median = hours_text(cell["median"]) if cell["median"] is not None else "–"
        rows.append(
            f"<tr><td>{WEEKDAYS[day]}</td><td>{clock_label(minutes)}</td><td class=num>{median}</td>"
            f"<td class=num>{cell['booked']}</td><td class=num>{cell['free']}</td>"
            f"<td class=num>{cell['expired']}</td><td class=num>{cell['measured']}</td></tr>"
        )
    return ("<details><summary>Table</summary><table><tr><th>Day</th><th>Time</th>"
            "<th class=num>Median until booked</th><th class=num>Start times booked</th>"
            "<th class=num>Free</th><th class=num>Expired</th><th class=num>Release observed</th></tr>"
            + "".join(rows) + "</table></details>")


def column_path(x, y, width, height, radius=4) -> str:
    radius = min(radius, height, width / 2)
    return (f"M{x},{y + height} V{y + radius} Q{x},{y} {x + radius},{y} "
            f"H{x + width - radius} Q{x + width},{y} {x + width},{y + radius} V{y + height} Z")


def nice_scale(value: int) -> tuple[int, int]:
    """Axis top and tick step: at most five clean steps covering ``value``."""
    step = 1
    for candidate in (1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000):
        step = candidate
        if -(-max(value, 1) // step) <= 5:
            break
    return -(-max(value, 1) // step) * step, step


def free_days_chart(days) -> str:
    if not days:
        return '<p class="sub">No free appointment right now.</p>'
    peak, step = nice_scale(max(count for _, count in days))
    slot, bar, left, top, plot = 40, 24, 36, 10, 160
    width = left + len(days) * slot
    height = top + plot + 36
    parts = [f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" '
             f'aria-label="Free appointments per day">']
    for tick in range(0, peak + 1, step):
        y = top + plot - plot * tick / peak
        parts.append(f'<line class="grid" x1="{left}" x2="{width}" y1="{y}" y2="{y}"/>')
        parts.append(f'<text x="{left - 6}" y="{y + 4}" text-anchor="end">{tick}</text>')
    for index, (day, count) in enumerate(days):
        x = left + index * slot + (slot - bar) / 2
        tall = plot * count / peak
        label = f"{WEEKDAYS[day.weekday()]} {day.strftime('%d %b')}"
        parts.append(
            f'<path class="bar" tabindex="0" fill="var(--series-1)" '
            f'd="{column_path(x, top + plot - tall, bar, tall)}" data-tip="{e(f"{count} free{chr(10)}{label}")}"/>'
        )
        parts.append(f'<text x="{x + bar / 2}" y="{top + plot + 14}" text-anchor="middle">'
                     f'{WEEKDAYS[day.weekday()]}</text>')
        parts.append(f'<text x="{x + bar / 2}" y="{top + plot + 28}" text-anchor="middle">'
                     f'{day.strftime("%d %b")}</text>')
    parts.append(f'<line class="axis" x1="{left}" x2="{width}" y1="{top + plot}" y2="{top + plot}"/>')
    parts.append("</svg>")
    rows = "".join(f"<tr><td>{WEEKDAYS[d.weekday()]} {d.strftime('%d %b %Y')}</td>"
                   f"<td class=num>{c}</td></tr>" for d, c in days)
    return (f'<div class="chart">{"".join(parts)}</div><details><summary>Table</summary>'
            f"<table><tr><th>Appointment day</th><th class=num>Free</th></tr>{rows}</table></details>")


def timeline_chart(points, now) -> str:
    if len(points) < 2:
        return '<p class="sub">The history appears from the second poll on.</p>'
    start, end = points[0][0], max(points[-1][0], points[0][0] + timedelta(hours=1))
    peak, step = nice_scale(max(free for _, free in points))
    left, top, plot_w, plot_h = 40, 10, 1000, 180
    width, height = left + plot_w + 10, top + plot_h + 24
    span = (end - start).total_seconds()

    def xy(at, free):
        return (round(left + plot_w * (at - start).total_seconds() / span, 1),
                round(top + plot_h - plot_h * free / peak, 1))

    coords = [xy(at, free) for at, free in points]
    line = " ".join(f"{'M' if i == 0 else 'L'}{x},{y}" for i, (x, y) in enumerate(coords))
    area = f"{line} L{coords[-1][0]},{top + plot_h} L{coords[0][0]},{top + plot_h} Z"
    parts = [f'<svg class="timeline" viewBox="0 0 {width} {height}" width="100%" role="img" '
             f'aria-label="Free appointments over time">']
    for tick in range(0, peak + 1, step):
        y = top + plot_h - plot_h * tick / peak
        parts.append(f'<line class="grid" x1="{left}" x2="{left + plot_w}" y1="{y}" y2="{y}"/>')
        parts.append(f'<text x="{left - 6}" y="{y + 4}" text-anchor="end">{tick}</text>')
    # Day boundaries mark the axis; a span of under two days gets hour ticks.
    hours = span / 3600
    tick = timedelta(days=1) if hours > 48 else timedelta(hours=next(
        h for h in (1, 2, 3, 6, 12) if hours / h <= 12))
    moment = local(start).replace(minute=0, second=0, microsecond=0)
    step_hours = int(tick.total_seconds() // 3600)
    moment = moment.replace(hour=moment.hour - moment.hour % step_hours if step_hours < 24 else 0)
    moment += tick
    while moment < end:
        x = xy(moment, 0)[0]
        label = (f'{WEEKDAYS[moment.weekday()]} {moment.strftime("%d %b")}'
                 if moment.hour == 0 else moment.strftime("%H:%M"))
        parts.append(f'<line class="grid" x1="{x}" x2="{x}" y1="{top}" y2="{top + plot_h}"/>')
        parts.append(f'<text x="{x}" y="{top + plot_h + 16}" text-anchor="middle">{label}</text>')
        moment = local(moment + tick)
    parts += [
        f'<path d="{area}" fill="var(--series-1-wash)"/>',
        f'<path d="{line}" fill="none" stroke="var(--series-1)" stroke-width="2" '
        f'stroke-linejoin="round" stroke-linecap="round"/>',
        f'<line class="axis" x1="{left}" x2="{left + plot_w}" y1="{top + plot_h}" y2="{top + plot_h}"/>',
        f'<line class="hair axis" y1="{top}" y2="{top + plot_h}" style="display:none"/>',
        f'<circle class="hairdot" r="4" fill="var(--series-1)" stroke="var(--surface-1)" '
        f'stroke-width="2" style="display:none"/>',
        f'<rect class="hit" x="{left}" y="{top}" width="{plot_w}" height="{plot_h}" fill="transparent"/>',
        "</svg>",
    ]
    data = [{"x": x, "y": y, "free": free, "label": when(at)}
            for (x, y), (at, free) in zip(coords, points)]
    # json.dumps output cannot close the script element: "<" is escaped.
    payload = json.dumps(data).replace("<", "\\u003c")
    rows = "".join(f"<tr><td>{when(at)}</td><td class=num>{free}</td></tr>" for at, free in points[::-1])
    return (f'<div class="chart">{"".join(parts)}<script type="application/json">{payload}</script></div>'
            f"<details><summary>Table</summary><table><tr><th>Poll</th><th class=num>Free</th></tr>"
            f"{rows}</table></details>")


def poll_status(state, now) -> str:
    last = state["last_poll"]
    if last is None:
        return '<div class="value">–</div><div class="note">not polled yet</div>'
    if last.ok:
        return f'<div class="value">{ago(last.at, now)}</div><div class="note">{when(last.at)}</div>'
    good = state["last_ok"]
    return (f'<div class="value status-bad">failed</div>'
            f'<div class="note">{e(last.error or "")}<br>last success {ago(good and good.at, now)}</div>')


def page(title: str, body: str, offices, current: str | None, embed: bool = False) -> str:
    """A full page; ``embed`` drops the heading and office links for the app,
    which shows its own title bar and navigation around the page."""
    links = ['<a href="/"' + (' aria-current="page"' if current is None else "") + ">Overview</a>"]
    links += [f'<a href="/o/{e(o["id"])}"' + (' aria-current="page"' if o["id"] == current else "")
              + f">{e(o['name'])}</a>" for o in offices]
    head = "" if embed else f'<h1>{e(title)}</h1><nav class="offices">{"".join(links)}</nav>'
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width, initial-scale=1">'
            f"<title>{e(title)}</title><style>{STYLE}</style></head>"
            f'<body><div class="viz-root"><div class="frame">{head}{body}'
            f'<div id="tip" role="tooltip"></div>'
            f"</div></div><script>{SCRIPT}</script></body></html>")


def overview(db, offices, now) -> str:
    rows = []
    for office in offices:
        state = analysis.snapshot(db, office["id"], now)
        last = state["last_poll"]
        status = ago(last.at, now) if last and last.ok else (
            '<span class="status-bad">failed</span>' if last else "–")
        rows.append(
            f'<tr><td><a href="/o/{e(office["id"])}">{e(office["name"])}</a><br>'
            f'<span class="sub">{e(office["authority"])}</span></td>'
            f'<td class=num>{state["free_now"]}</td><td>{when(state["next_free"])}</td><td>{status}</td></tr>'
        )
    body = ('<div class="card"><table class="overview"><tr><th>Office</th><th class=num>Free</th>'
            "<th>Next free appointment</th><th>Last poll</th></tr>" + "".join(rows) + "</table></div>")
    return page("Appointment watch", body, offices, None)


def office_page(db, office, offices, now, embed: bool = False) -> str:
    state = analysis.snapshot(db, office["id"], now)
    since = state["tracking_since"]
    body = f"""
<p class="sub">{e(office["authority"])} · watched since {when(since)}</p>
<div class="tiles">
  <div class="card tile"><div class="label">Free now</div><div class="value">{state["free_now"]}</div>
    <div class="note">appointments across all released days</div></div>
  <div class="card tile"><div class="label">Next free appointment</div>
    <div class="value">{when(state["next_free"])}</div></div>
  <div class="card tile"><div class="label">Last poll</div>{poll_status(state, now)}</div>
</div>
<div class="card"><h2>How fast slots go</h2>
  <p class="sub">Time from release until half of the start times are booked, by weekday and time of the appointment</p>
  {heatmap(state["cells"])}{heat_table(state["cells"])}</div>
<div class="card"><h2>What is left</h2>
  <p class="sub">Free appointments per day</p>{free_days_chart(state["free_by_day"])}</div>
<div class="card"><h2>Free appointments over time</h2>
  <p class="sub">Number of free appointments at each poll</p>{timeline_chart(state["timeline"], now)}</div>
"""
    return page(office["name"], body, offices, office["id"], embed)


def serve(db_path, offices, host: str, port: int, token: str | None):
    make_server(db_path, offices, host, port, token).serve_forever()


def make_server(db_path, offices, host: str, port: int, token: str | None) -> ThreadingHTTPServer:
    by_id = {o["id"]: o for o in offices}

    class Handler(BaseHTTPRequestHandler):
        def handle_request(self, method):
            url = urlparse(self.path)
            path = url.path
            now = datetime.now(timezone.utc)
            guarded = path.startswith("/api/") or path.startswith("/app/")
            if guarded and not self.authorized():
                return self.reply_json(401, {"error": "missing or wrong token"})
            db = store.connect(db_path)
            try:
                if method == "GET" and path == "/":
                    self.reply(200, overview(db, offices, now))
                elif method == "GET" and path.startswith("/o/") and path[3:] in by_id:
                    self.reply(200, office_page(db, by_id[path[3:]], offices, now))
                elif method == "GET" and path.startswith("/app/o/") and path[7:] in by_id:
                    self.reply(200, office_page(db, by_id[path[7:]], offices, now, embed=True))
                elif method == "GET" and path == "/api/offices":
                    self.reply_json(*api.offices(db, offices, now))
                elif method == "GET" and path == "/api/alerts":
                    endpoint = parse_qs(url.query).get("endpoint", [None])[0]
                    self.reply_json(*api.list_alerts(db, endpoint, now))
                elif method == "POST" and path == "/api/alerts":
                    body = self.read_json()
                    if body is None:
                        self.reply_json(400, {"error": "body must be a JSON object"})
                    else:
                        self.reply_json(*api.create_alert(db, by_id, body, now))
                elif method == "DELETE" and path.startswith("/api/alerts/"):
                    self.reply_json(*api.delete_alert(db, path[len("/api/alerts/"):]))
                elif method == "GET" and path == "/healthz":
                    self.reply(200, "ok", "text/plain")
                else:
                    self.reply(404, "not found", "text/plain")
            finally:
                db.close()

        def do_GET(self):
            self.handle_request("GET")

        def do_POST(self):
            self.handle_request("POST")

        def do_DELETE(self):
            self.handle_request("DELETE")

        def authorized(self) -> bool:
            if not token:
                return False
            given = self.headers.get("Authorization", "")
            return hmac.compare_digest(given.encode(), f"Bearer {token}".encode())

        def read_json(self):
            length = int(self.headers.get("Content-Length") or 0)
            if not 0 < length <= 10_000:
                return None
            try:
                body = json.loads(self.rfile.read(length))
            except ValueError:
                return None
            return body if isinstance(body, dict) else None

        def reply_json(self, status, body):
            if body is None:
                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.reply(status, json.dumps(body, ensure_ascii=False), "application/json")

        def reply(self, status, text, kind="text/html"):
            data = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", f"{kind}; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    return ThreadingHTTPServer((host, port), Handler)
