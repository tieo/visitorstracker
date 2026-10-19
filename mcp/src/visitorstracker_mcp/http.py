"""HTTP session shared by the readers.

smartCJM sits behind a Cloudflare rate limit that blocks an address for about five
minutes after roughly 60 requests a minute, so every request to a host waits until
MIN_SPACING seconds have passed since the previous one to that host.
"""

import threading
import time
from urllib.parse import urlencode, urlsplit

import httpx

USER_AGENT = "visitorstracker (appointment availability statistics)"
MIN_SPACING = 1.5


class RateLimited(Exception):
    def __init__(self, retry_after):
        super().__init__(f"HTTP 429, retry after {retry_after}s")
        self.retry_after = retry_after


_last = {}
_lock = threading.Lock()


def _wait(url):
    host = urlsplit(url).hostname
    with _lock:
        delay = _last.get(host, 0) + MIN_SPACING - time.monotonic()
        _last[host] = time.monotonic() + max(delay, 0)
    if delay > 0:
        time.sleep(delay)


class Session:
    """One cookie jar per booking-wizard run, as a browser tab would have."""

    def __init__(self):
        self.client = httpx.Client(
            headers={"User-Agent": USER_AGENT}, follow_redirects=True, timeout=30
        )

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.client.close()

    def _check(self, response):
        if response.status_code == 429:
            raise RateLimited(response.headers.get("Retry-After"))
        response.raise_for_status()
        return response

    def get(self, url, params=None, headers=None):
        _wait(url)
        return self._check(self.client.get(url, params=params, headers=headers))

    def post(self, url, fields):
        """Form POST of (name, value) pairs; names may repeat, as the wizard needs."""
        _wait(url)
        body = urlencode(fields)
        return self._check(self.client.post(
            url, content=body, headers={"Content-Type": "application/x-www-form-urlencoded"}))
