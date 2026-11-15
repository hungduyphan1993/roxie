"""HTTP plumbing: mirror failover, thread-local sessions, stream probing."""

from __future__ import annotations

import re
import threading
import time
from typing import Iterable

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from .model import Probe, ProbeState

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

MPEGURL_HINTS = ("mpegurl", "x-mpegurl", "vnd.apple.mpegurl")

_META_CHARSET = re.compile(rb"""charset\s*=\s*["']?\s*([\w-]+)""", re.IGNORECASE)


def _decode(response: requests.Response) -> str:
    """Decode a response body, honouring the header charset then ``<meta charset>``.

    ``requests`` defaults to ISO-8859-1 when the server omits a charset, which
    mangles the site's UTF-8 event names.
    """
    encoding = None
    content_type = response.headers.get("Content-Type", "")
    if "charset=" in content_type.lower():
        encoding = (
            content_type.lower()
            .split("charset=", 1)[1]
            .split(";")[0]
            .strip()
            .strip("\"'")
        )
    if not encoding or encoding == "iso-8859-1":
        match = _META_CHARSET.search(response.content[:4096])
        if match:
            encoding = match.group(1).decode("ascii", "ignore")
    try:
        return response.content.decode(encoding or "utf-8", errors="replace")
    except LookupError:
        return response.content.decode("utf-8", errors="replace")


def _build_session(user_agent: str, retries: int) -> requests.Session:
    session = requests.Session()
    retry = Retry(
        total=retries,
        connect=retries,
        read=retries,
        backoff_factor=0.4,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset({"GET", "HEAD"}),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=16, pool_maxsize=16)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(
        {
            "User-Agent": user_agent,
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
        }
    )
    return session


class HttpClient:
    """Thread-safe HTTP client that fails over between site mirrors.

    ``requests.Session`` is not documented as thread-safe, so each worker thread
    gets its own session from a thread-local store.
    """

    def __init__(
        self,
        mirrors: Iterable[str],
        timeout: float = 15.0,
        user_agent: str = DEFAULT_USER_AGENT,
        retries: int = 1,
    ) -> None:
        self.mirrors = list(mirrors)
        if not self.mirrors:
            raise ValueError("at least one mirror is required")
        self.timeout = timeout
        self.user_agent = user_agent
        self._retries = retries
        self._local = threading.local()
        self._preferred = 0
        self._lock = threading.Lock()

    # ---------------------------------------------------------------- session

    @property
    def session(self) -> requests.Session:
        session = getattr(self._local, "session", None)
        if session is None:
            session = _build_session(self.user_agent, self._retries)
            self._local.session = session
        return session

    @property
    def probe_session(self) -> requests.Session:
        """Retry-free session for stream probes.

        A probe that times out has already answered the question, so retrying
        it only doubles the cost.
        """
        session = getattr(self._local, "probe_session", None)
        if session is None:
            session = _build_session(self.user_agent, 0)
            self._local.probe_session = session
        return session

    @property
    def preferred_mirror(self) -> str:
        return self.mirrors[self._preferred]

    # ------------------------------------------------------------------ fetch

    def get(self, url: str, *, timeout: float | None = None, allow_redirects: bool = True):
        """GET a URL and return the response. Raises on transport failure."""
        return self.session.get(
            url, timeout=timeout or self.timeout, allow_redirects=allow_redirects
        )

    def get_text(self, url: str, *, timeout: float | None = None) -> str | None:
        """GET a URL and return decoded text, or ``None`` on any failure."""
        try:
            response = self.get(url, timeout=timeout)
        except requests.RequestException:
            return None
        if response.status_code >= 400:
            return None
        return _decode(response)

    def fetch_route(self, route: str, *, timeout: float | None = None) -> tuple[str, str] | None:
        """Fetch a site route, trying each mirror until one answers.

        Returns ``(absolute_url, html)`` or ``None`` if every mirror failed.
        """
        route = route if route.startswith("/") else f"/{route}"
        order = self._mirror_order()
        for mirror in order:
            url = f"{mirror.rstrip('/')}{route}"
            try:
                response = self.get(url, timeout=timeout)
            except requests.RequestException:
                continue
            if response.status_code >= 400:
                continue
            self._prefer(mirror)
            return url, _decode(response)
        return None

    def fetch_absolute(self, url: str, *, timeout: float | None = None) -> str | None:
        """Fetch an absolute URL, mirroring the same-origin path to other mirrors."""
        try:
            response = self.get(url, timeout=timeout)
            if response.status_code < 400:
                return _decode(response)
        except requests.RequestException:
            pass
        for mirror in self._mirror_order():
            if mirror.rstrip("/") in url:
                continue
            path = url.split(mirror.rstrip("/"), 1)[-1]
            candidate = f"{mirror.rstrip('/')}{path}"
            try:
                response = self.get(candidate, timeout=timeout)
            except requests.RequestException:
                continue
            if response.status_code < 400:
                self._prefer(mirror)
                return _decode(response)
        return None

    # ------------------------------------------------------------------ probe

    def probe(self, url: str, *, timeout: float | None = None) -> Probe:
        """Probe a candidate stream URL and classify the result."""
        timeout = timeout or self.timeout
        started = time.monotonic()
        try:
            response = self.probe_session.get(
                url,
                timeout=(min(5.0, timeout), timeout),
                stream=True,
                allow_redirects=True,
            )
        except requests.Timeout:
            return Probe(
                url, ProbeState.HOST_DOWN, error="timeout",
                elapsed_ms=_elapsed_ms(started),
            )
        except requests.ConnectionError as exc:
            return Probe(
                url, ProbeState.HOST_DOWN, error=_short(exc),
                elapsed_ms=_elapsed_ms(started),
            )
        except requests.RequestException as exc:
            return Probe(
                url, ProbeState.ERROR, error=_short(exc),
                elapsed_ms=_elapsed_ms(started),
            )

        with response:
            code = response.status_code
            ctype = (response.headers.get("Content-Type") or "").lower()
            body = response.raw.read(4096, decode_content=True) or b""
            size = len(body)

            if code in (403, 401):
                state = ProbeState.FORBIDDEN
            elif code in (521, 522, 523, 524):
                state = ProbeState.HOST_DOWN
            elif code in (404, 410):
                state = ProbeState.DEAD
            elif code >= 400:
                state = ProbeState.DEAD
            elif any(hint in ctype for hint in MPEGURL_HINTS) or body.lstrip().startswith(
                b"#EXTM3U"
            ):
                state = ProbeState.LIVE if size else ProbeState.IDLE
            else:
                state = ProbeState.DEAD

            return Probe(
                url=url,
                state=state,
                status_code=code,
                content_type=ctype.split(";")[0] or None,
                size=size,
                elapsed_ms=_elapsed_ms(started),
            )

    # ------------------------------------------------------------- internals

    def mirror_order(self) -> list[str]:
        """Mirrors in preference order, best first."""
        with self._lock:
            start = self._preferred
        return self.mirrors[start:] + self.mirrors[:start]

    def prefer(self, mirror: str) -> None:
        """Remember that ``mirror`` answered, so later calls try it first."""
        with self._lock:
            if mirror in self.mirrors:
                self._preferred = self.mirrors.index(mirror)

    def _mirror_order(self) -> list[str]:
        return self.mirror_order()

    def _prefer(self, mirror: str) -> None:
        self.prefer(mirror)


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _short(exc: BaseException) -> str:
    text = str(exc).replace("\n", " ").strip()
    return text[:200] if text else exc.__class__.__name__
