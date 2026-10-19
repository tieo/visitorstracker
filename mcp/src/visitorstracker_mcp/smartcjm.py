"""smartCJM appointment wizard (used by the Landratsamt Alb-Donau-Kreis).

The wizard is server-rendered: each step is a form POST carrying an anti-forgery token
and the current step index. Some calendars first ask for the postcode of the applicant's
residence, then for the services, optionally for the location, and then render every
free start time as a button calling `appointment_reserve(start, duration, location,
resource)`. Reading stops at that page; nothing is ever reserved.
"""

import html
import re
from datetime import datetime
from urllib.parse import unquote

from .http import Session

FORM = re.compile(r'<form id="calendar_[^"]*"[^>]*action="([^"]+)"')
TOKEN = re.compile(r"name='__RequestVerificationToken' value='([^']+)'")
STEP_FIELDS = re.compile(
    r'<input type="hidden" id="(?:steps|step_current|step_current_index)" name="([^"]+)" value="([^"]*)"'
)
STEP_CURRENT = re.compile(r'id="step_current" name="step_current" value="([^"]*)"')
RESERVE = re.compile(r"appointment_reserve\('([^']+)', '(\d+)', '([^']+)', '([^']+)'\)")
SERVICE = re.compile(
    r'<div class="service_container"[^>]*data-duration="(\d*)"[^>]*data-service_uid="([^"]+)".*?'
    r'<label class="service_title"[^>]*>(.*?)</label>(.*?)(?=<div class="service_container"|</fieldset>)',
    re.S,
)
TAG = re.compile(r"<[^>]+>")


class WizardError(Exception):
    pass


def step(page):
    match = STEP_CURRENT.search(page)
    return match.group(1) if match else None


def _next(session, url, page, fields):
    action = FORM.search(page)
    token = TOKEN.search(page)
    if not action or not token:
        raise WizardError("wizard form not found")
    data = [("__RequestVerificationToken", token.group(1)), ("action_type", "")]
    data += STEP_FIELDS.findall(page)
    data += [("step_goto", "+1")] + fields
    return session.post(url + html.unescape(action.group(1)), data).text


def _text(fragment):
    return " ".join(html.unescape(TAG.sub(" ", fragment)).split())


def _services_page(session, office):
    page = session.get(office.booking_url).text
    if 'name="zip_code"' in page:
        page = _next(session, office.url, page, [("zip_code", office.zip_code)])
    if step(page) != "services":
        raise WizardError(f"expected the services step, got {step(page)}")
    return page


def services(office):
    """The calendar's services: id, name, minutes and the documents it lists."""
    with Session() as session:
        page = _services_page(session, office)
    out = []
    for minutes, uid, title, rest in SERVICE.findall(page):
        bring = [_text(li) for li in re.findall(r"<li>(.*?)</li>", rest, re.S)]
        out.append({"id": uid, "name": _text(title),
                    "minutes": int(minutes) if minutes else None, "bring": bring})
    return out


def slots(office, service=None):
    """Free start times for one service, as (start, minutes, resource) in local time."""
    service = service or office.service
    with Session() as session:
        page = _services_page(session, office)
        page = _next(session, office.url, page,
                     [("services", service), (f"service_{service}_amount", "1")])
        if step(page) == "locations":
            if not office.location:
                raise WizardError("calendar asks for a location")
            page = _next(session, office.url, page, [("locations", office.location)])
        if step(page) != "search_results":
            raise WizardError(f"expected search_results, got {step(page)}")
    found = set()
    for start, minutes, location, resource in RESERVE.findall(page):
        begin = datetime.fromisoformat(unquote(start))
        # One calendar can span several locations, each with its own counters.
        found.add((begin, int(minutes), f"{location}/{resource}"))
    return sorted(found)
