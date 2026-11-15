"""Parsers for the Roxiestreams static pages and their inline JavaScript."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup

from .model import Category, StreamRef

SITE_TZ = ZoneInfo("America/Los_Angeles")

_STATIC_SUFFIXES = (
    ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp",
    ".txt", ".xml", ".json", ".mp4", ".woff", ".woff2",
)

_TIME_FORMATS = (
    "%b %d, %Y %I:%M %p",
    "%B %d, %Y %I:%M %p",
    "%b %d, %Y %I:%M%p",
    "%B %d, %Y %I:%M%p",
    "%b %d, %Y %H:%M",
    "%B %d, %Y %H:%M",
)

_QUOTED_JS_KV = re.compile(r"(\w+)\s*:\s*'([^']*)'")
_GET_RANDOM_STREAM = re.compile(
    r"getRandomStream\(\s*'([^']+)'\s*(?:,\s*'([^']*)'\s*)?\)"
)
_SHOW_PLAYER_URL = re.compile(r"showPlayer\(\s*'[^']*'\s*,\s*'(https?://[^']+)'")
_FETCH_TXT = re.compile(r"fetch\(\s*'([^']+\.txt)'")


# ------------------------------------------------------------- JS literals


def _scan_quoted(text: str, start: int) -> int:
    """Index just past the closing quote of the string starting at ``start``."""
    quote = text[start]
    index = start + 1
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == quote:
            return index + 1
        index += 1
    return len(text)


def _split_js_objects(block: str) -> list[str]:
    """Split a JS array body into its top-level ``{...}`` object literals."""
    objects: list[str] = []
    depth = 0
    start = -1
    index = 0
    while index < len(block):
        char = block[index]
        if char in "'\"`":
            index = _scan_quoted(block, index)
            continue
        if char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0 and start >= 0:
                objects.append(block[start : index + 1])
                start = -1
        index += 1
    return objects


def _match_bracket(text: str, start: int, opener: str, closer: str) -> int:
    """Index of the bracket closing the one whose body starts at ``start``."""
    depth = 1
    index = start
    while index < len(text):
        char = text[index]
        if char in "'\"`":
            index = _scan_quoted(text, index)
            continue
        if char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return -1


def _extract_bracketed(html: str, name: str, opener: str = "[", closer: str = "]") -> str | None:
    """Extract the body of ``name = [...]`` / ``name: [...]`` / ``name = {...}``."""
    match = re.search(rf"\b{re.escape(name)}\s*[:=]\s*{re.escape(opener)}", html)
    if not match:
        return None
    start = match.end()
    end = _match_bracket(html, start, opener, closer)
    return html[start:end] if end != -1 else None


def _split_prop_array(raw: str, name: str) -> tuple[str, str | None]:
    """Split ``name: [...]`` out of an object literal.

    Returns ``(object_without_the_property, array_body_or_None)``. The property
    must be removed before reading the object's own scalar fields, otherwise the
    nested object's keys would overwrite them.
    """
    match = re.search(rf"\b{re.escape(name)}\s*:\s*\[", raw)
    if not match:
        return raw, None
    start = match.end()
    end = _match_bracket(raw, start, "[", "]")
    if end == -1:
        return raw, None
    return raw[: match.start()] + raw[end + 1 :], raw[start:end]


# ----------------------------------------------------------------- routing


def route_from_href(href: str, hosts: tuple[str, ...]) -> str | None:
    """Normalise an href into a same-site route path, or ``None`` if external."""
    if not href:
        return None
    href = href.split("#", 1)[0].split("?", 1)[0].strip()
    if not href or href.startswith(("mailto:", "javascript:", "data:")):
        return None
    if href.startswith(("http://", "https://")):
        parsed = urlparse(href)
        if parsed.netloc.lower() not in {h.lower() for h in hosts}:
            return None
        path = parsed.path
    elif href.startswith("/"):
        path = href
    else:
        return None
    if path.lower().endswith(_STATIC_SUFFIXES):
        return None
    return path or "/"


def parse_nav_categories(html: str, hosts: tuple[str, ...]) -> list[Category]:
    """Extract the navbar categories, in nav order, de-duplicated."""
    soup = BeautifulSoup(html, "lxml")
    nav = soup.find("nav") or soup
    seen: dict[str, Category] = {}
    for anchor in nav.select("a.nav-link"):
        route = route_from_href(anchor.get("href", ""), hosts)
        if route is None:
            continue
        slug = route.strip("/").split("/")[0] if route != "/" else "index"
        if not slug or slug in seen:
            continue
        seen[slug] = Category(slug=slug, title=anchor.get_text(strip=True), route=route)
    return list(seen.values())


def parse_internal_routes(html: str, hosts: tuple[str, ...]) -> set[str]:
    """Every same-site route referenced anywhere in the document."""
    soup = BeautifulSoup(html, "lxml")
    routes: set[str] = set()
    for anchor in soup.find_all("a", href=True):
        route = route_from_href(anchor["href"], hosts)
        if route:
            routes.add(route)
    return routes


# ------------------------------------------------------------------ events


def parse_event_rows(html: str) -> list[tuple[str, str | None, str | None]]:
    """Extract ``(label, href, start_time_text)`` from every event table.

    A page can hold several tables (a generic upcoming-events table plus one
    per sport); rows are collected from all of them.
    """
    soup = BeautifulSoup(html, "lxml")
    rows: list[tuple[str, str | None, str | None]] = []
    for table in soup.find_all("table"):
        for tr in table.find_all("tr"):
            cells = tr.find_all("td")
            if not cells:
                continue
            label_cell = cells[0]
            anchor = label_cell.find("a")
            label = (
                anchor.get_text(" ", strip=True)
                if anchor
                else label_cell.get_text(" ", strip=True)
            )
            if not label:
                continue
            href = anchor.get("href") if anchor else None
            start = None
            for cell in cells[1:]:
                if "event-start-time" in (cell.get("class") or []):
                    start = cell.get_text(" ", strip=True)
                    break
            rows.append((label, href, start))
    return rows


def parse_start_time(text: str | None) -> datetime | None:
    """Parse a published start time into a naive datetime.

    The site publishes naive wall-clock strings that the frontend interprets
    as US Pacific (``scripts/countdownfinal3.js`` pins ``America/Los_Angeles``).
    """
    if not text:
        return None
    cleaned = " ".join(text.split()).strip()
    for fmt in _TIME_FORMATS:
        try:
            return datetime.strptime(cleaned, fmt)
        except ValueError:
            continue
    return None


def to_utc(local: datetime | None) -> datetime | None:
    """Interpret a naive site timestamp as Pacific time and convert to UTC."""
    if local is None:
        return None
    return local.replace(tzinfo=SITE_TZ).astimezone(timezone.utc)


# ----------------------------------------------------------------- streams


def _parse_stream_object(body: str, *, sport: str | None = None,
                         group: str | None = None) -> StreamRef:
    fields = dict(_QUOTED_JS_KV.findall(body))
    return StreamRef(
        label=fields.get("label", ""),
        subdomain=fields.get("subdomain"),
        path=fields.get("path"),
        pool_file=fields.get("txt"),
        hardcoded=fields.get("hardcoded"),
        sport=fields.get("sport", sport),
        group=group,
    )


def parse_multiview_streams(html: str) -> list[StreamRef]:
    """Parse the ``STREAMS`` inventory array from the multiview page.

    Alternate streams live in a nested ``alts`` array; each becomes its own ref
    carrying the parent event label in ``group``.
    """
    block = _extract_bracketed(html, "STREAMS")
    if not block:
        return []
    refs: list[StreamRef] = []
    for raw in _split_js_objects(block):
        base_body, alts_body = _split_prop_array(raw, "alts")
        base = _parse_stream_object(base_body)
        if not base.path and not base.hardcoded:
            continue
        refs.append(base)
        if not alts_body:
            continue
        for alt_raw in _split_js_objects(alts_body):
            alt = _parse_stream_object(alt_raw, sport=base.sport, group=base.label)
            if alt.path or alt.hardcoded:
                refs.append(alt)
    return refs


def parse_sport_labels(html: str) -> dict[str, str]:
    """Parse the ``SPORT_LABELS`` display-name map."""
    block = _extract_bracketed(html, "SPORT_LABELS", "{", "}")
    if not block:
        return {}
    return dict(_QUOTED_JS_KV.findall(block))


def parse_stream_buttons(html: str) -> list[StreamRef]:
    """Parse the player buttons on an event page.

    Two button styles exist in the wild:

    - ``showPlayer('clappr', getRandomStream('ncaa.m3u8', 'admin'))`` — a path
      plus the subdomain it is served from.
    - ``showPlayer('clappr', 'https://host/ncaa4.m3u8')`` — a fully hardcoded
      URL that bypasses the domain pool.
    """
    soup = BeautifulSoup(html, "lxml")
    pool_file = parse_pool_file(html)
    refs: list[StreamRef] = []
    for button in soup.select("button[onclick]"):
        onclick = button.get("onclick", "")
        label = button.get_text(" ", strip=True)

        match = _GET_RANDOM_STREAM.search(onclick)
        if match:
            refs.append(
                StreamRef(
                    label=label or match.group(1),
                    subdomain=match.group(2),
                    path=match.group(1),
                    pool_file=pool_file,
                )
            )
            continue

        hardcoded = _SHOW_PLAYER_URL.search(onclick)
        if hardcoded:
            url = hardcoded.group(1)
            path = url.rsplit("/", 1)[-1]
            refs.append(
                StreamRef(
                    label=label or path,
                    path=path if path.endswith(".m3u8") else None,
                    hardcoded=url,
                    pool_file=pool_file,
                )
            )
    return refs


def parse_default_streams(html: str) -> list[StreamRef]:
    """Parse the auto-selected stream(s) a page loads on open."""
    pool_file = parse_pool_file(html)
    refs: list[StreamRef] = []
    for path, subdomain in _GET_RANDOM_STREAM.findall(html):
        refs.append(
            StreamRef(
                label=f"default:{path}",
                subdomain=subdomain or None,
                path=path,
                pool_file=pool_file,
            )
        )
    return refs


def parse_pool_file(html: str) -> str | None:
    """Name of the ``.txt`` file a page loads its domain pool from."""
    match = _FETCH_TXT.search(html)
    return match.group(1) if match else None


def parse_pool_domains(text: str) -> list[str]:
    """Split a pool file into individual base domains."""
    return [line.strip() for line in text.splitlines() if line.strip()]


def dedupe_refs(refs: list[StreamRef]) -> list[StreamRef]:
    """Drop refs that point at the same feed, keeping the first occurrence."""
    seen: set[str] = set()
    unique: list[StreamRef] = []
    for ref in refs:
        if ref.key in seen:
            continue
        seen.add(ref.key)
        unique.append(ref)
    return unique
