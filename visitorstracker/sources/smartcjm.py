"""smartCJM appointment wizard (used by the Landratsamt Alb-Donau-Kreis).

The wizard is server-rendered: each step is a form POST carrying an
anti-forgery token and the current step index. Some calendars first ask for
the postcode of the applicant's residence, then for the services, optionally
for the location, and then render every free start time as a button calling
``appointment_reserve(start, duration, location, resource)``. Reading stops at
that page.
"""

import re
from datetime import datetime, timedelta
from urllib.parse import unquote

from . import RateLimited, Slot

FORM = re.compile(r'<form id="calendar_[^"]*"[^>]*action="([^"]+)"')
TOKEN = re.compile(r"name='__RequestVerificationToken' value='([^']+)'")
STEP_FIELDS = re.compile(
    r'<input type="hidden" id="(?:steps|step_current|step_current_index)" '
    r'name="([^"]+)" value="([^"]*)"'
)
STEP_CURRENT = re.compile(r'id="step_current" name="step_current" value="([^"]*)"')
RESERVE = re.compile(r"appointment_reserve\('([^']+)', '(\d+)', '([^']+)', '([^']+)'\)")


def _check(response):
    if response.status_code == 429:
        raise RateLimited(response)
    response.raise_for_status()
    return response.text


def _next(session, url, page, fields):
    action = FORM.search(page)
    token = TOKEN.search(page)
    if not action or not token:
        raise ValueError("wizard form not found")
    data = [
        ("__RequestVerificationToken", token.group(1)),
        ("action_type", ""),
        *STEP_FIELDS.findall(page),
        ("step_goto", "+1"),
        *fields,
    ]
    target = url + action.group(1).replace("&amp;", "&")
    return _check(session.post(target, data=data, timeout=30))


def _step(page):
    step = STEP_CURRENT.search(page)
    return step and step.group(1)


def read(office, session):
    url = office["url"]
    page = _check(session.get(url, params={"uid": office["uid"]}, timeout=30))
    if 'name="zip_code"' in page:
        page = _next(session, url, page, [("zip_code", office["zip_code"])])
    service = office["service"]
    page = _next(
        session, url, page, [("services", service), (f"service_{service}_amount", "1")]
    )
    if _step(page) == "locations":
        page = _next(session, url, page, [("locations", office["location"])])
    if _step(page) != "search_results":
        raise ValueError(f"expected search_results, got {_step(page)}")

    slots = set()
    for start, minutes, location, resource in RESERVE.findall(page):
        begin = datetime.fromisoformat(unquote(start))
        end = begin + timedelta(minutes=int(minutes))
        # One calendar can span several locations, each with its own counters.
        slots.add(Slot(begin, end, f"{location}/{resource}"))
    return sorted(slots, key=lambda s: (s.start, s.resource))
