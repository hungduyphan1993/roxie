"""Discovery: walk categories -> events -> per-event stream descriptors."""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from .http import HttpClient
from .model import Category, Event, EventStatus, StreamRef
from .parse import (
    dedupe_refs,
    parse_default_streams,
    parse_event_rows,
    parse_internal_routes,
    parse_multiview_streams,
    parse_nav_categories,
    parse_pool_domains,
    parse_start_time,
    parse_stream_buttons,
    route_from_href,
    to_utc,
)
from .viewers import fetch_viewers

# How long after its published start time an event still counts as "live".
LIVE_WINDOW = timedelta(minutes=180)


@dataclass
class Catalog:
    """Everything discovered in one enumeration pass."""

    categories: list[Category] = field(default_factory=list)
    events: list[Event] = field(default_factory=list)
    streams: list[StreamRef] = field(default_factory=list)
    pool: list[str] = field(default_factory=list)
    pool_file: str | None = None
    routes: set[str] = field(default_factory=set)
    event_paths: dict[str, list[str]] = field(default_factory=dict)


class Enumerator:
    """Walks the site and collects categories, events and stream descriptors."""

    def __init__(self, client: HttpClient, hosts: tuple[str, ...]) -> None:
        self.client = client
        self.hosts = hosts
        self._nav_categories: list[Category] = []
        self._event_paths: dict[str, list[str]] = {}

    # ------------------------------------------------------------ categories

    def discover_categories(self) -> list[Category]:
        """Navbar categories from the homepage."""
        fetched = self.client.fetch_route("/")
        if fetched is None:
            raise RuntimeError(
                f"homepage unreachable on all mirrors: {', '.join(self.client.mirrors)}"
            )
        _, html = fetched
        return parse_nav_categories(html, self.hosts)

    # ---------------------------------------------------------------- events

    def collect_featured_events(self) -> list[Event]:
        """The homepage "Upcoming Events" table.

        Its rows point at standalone stream pages (``/ufc``, ``/aew``,
        ``/ppv-streams-3`` ...) that never appear in the navbar, so this is the
        only place they are advertised.
        """
        fetched = self.client.fetch_route("/")
        if fetched is None:
            return []
        _, html = fetched
        events: list[Event] = []
        for label, href, start_text in parse_event_rows(html):
            route = route_from_href(href, self.hosts) if href else None
            if not route:
                continue
            local = parse_start_time(start_text)
            events.append(
                Event(
                    label=label,
                    route=route,
                    category=_slug_of(route),
                    start_local=local,
                    start_utc=to_utc(local),
                    status=_event_status(local),
                    sources=["featured"],
                )
            )
        return events

    def collect_events(self, categories: list[Category]) -> list[Event]:
        """Collect the event rows published on every category page."""
        events: list[Event] = []
        seen: set[tuple[str, str, str]] = set()

        for category in categories:
            if category.kind != "nav":
                continue
            fetched = self.client.fetch_route(category.route)
            if fetched is None:
                continue
            _, html = fetched
            for label, href, start_text in parse_event_rows(html):
                route = route_from_href(href, self.hosts) if href else None
                key = (category.slug, label, route or "")
                if key in seen:
                    continue
                seen.add(key)
                local = parse_start_time(start_text)
                events.append(
                    Event(
                        label=label,
                        route=route or "",
                        category=category.slug,
                        start_local=local,
                        start_utc=to_utc(local),
                        status=_event_status(local),
                        sources=["category"],
                    )
                )
        return events

    # --------------------------------------------------------------- streams

    def collect_event_streams(self, events: list[Event]) -> list[StreamRef]:
        """Fetch each event page and pull out its player buttons."""
        refs: list[StreamRef] = []
        nav_routes = {c.route for c in self._nav_categories}
        seen_routes: set[str] = set()

        for event in events:
            if not event.route or event.route in nav_routes:
                continue
            if event.route in seen_routes:
                continue
            seen_routes.add(event.route)
            fetched = self.client.fetch_route(event.route)
            if fetched is None:
                continue
            _, html = fetched
            buttons = parse_stream_buttons(html) or parse_default_streams(html)
            paths: list[str] = []
            for ref in buttons:
                if ref.path:
                    paths.append(ref.path)
                if ref.sport is None or ref.group is None:
                    ref = dataclasses.replace(
                        ref,
                        sport=ref.sport or event.category,
                        group=ref.group or event.label,
                    )
                refs.append(ref)
            if paths:
                self._event_paths[event.route] = paths
        return dedupe_refs(refs)

    def collect_multiview_streams(self) -> list[StreamRef]:
        """The multiview page carries the full labelled stream inventory."""
        fetched = self.client.fetch_route("/multiview")
        if fetched is None:
            return []
        _, html = fetched
        return dedupe_refs(parse_multiview_streams(html))

    # ------------------------------------------------------------------ pool

    def fetch_pool(self, pool_file: str = "domainsz77.txt") -> list[str]:
        """Read the domain-rotation pool used to build stream URLs."""
        for mirror in self.client.mirror_order():
            text = self.client.fetch_absolute(f"{mirror.rstrip('/')}/{pool_file}")
            if not text:
                continue
            domains = parse_pool_domains(text)
            if domains:
                self.client.prefer(mirror)
                return domains
        return []

    # ------------------------------------------------------------- combined

    def enumerate_all(self, *, include_multiview: bool = True) -> Catalog:
        """Run the full discovery pass."""
        catalog = Catalog()
        nav_categories = self.discover_categories()
        self._nav_categories = nav_categories
        nav_slugs = {c.slug for c in nav_categories}

        featured = self.collect_featured_events()

        page_categories: dict[str, Category] = {}
        for event in featured:
            if event.category in nav_slugs or event.category in page_categories:
                continue
            page_categories[event.category] = Category(
                slug=event.category,
                title=_title_for(event.category),
                route=event.route,
                kind="page",
            )
        catalog.categories = nav_categories + list(page_categories.values())

        homepage = self.client.fetch_route("/")
        if homepage:
            _, html = homepage
            catalog.routes |= parse_internal_routes(html, self.hosts)

        catalog.events = _merge_events(featured, self.collect_events(nav_categories))

        event_streams = self.collect_event_streams(catalog.events)
        multiview_streams = self.collect_multiview_streams() if include_multiview else []
        catalog.streams = dedupe_refs(multiview_streams + event_streams)
        catalog.event_paths = dict(self._event_paths)

        catalog.pool_file = next(
            (ref.pool_file for ref in catalog.streams if ref.pool_file), "domainsz77.txt"
        )
        catalog.pool = self.fetch_pool(catalog.pool_file)

        for category in catalog.categories:
            catalog.routes.add(category.route)
        for event in catalog.events:
            if event.route:
                catalog.routes.add(event.route)
        catalog.routes.add("/")
        return catalog

    def collect_viewers(self, catalog: Catalog, client_id: str) -> dict[str, int]:
        """Watcher counts per event route, attached to the events."""
        pages = sorted(
            {event.route.strip("/") for event in catalog.events if event.route}
        )
        if not pages:
            return {}
        counts = fetch_viewers(self.client, pages, client_id=client_id)
        for event in catalog.events:
            if not event.route:
                continue
            event.viewers = counts.get(event.route.strip("/"), 0)
        return counts


def _slug_of(route: str) -> str:
    stripped = route.strip("/")
    return stripped.split("/")[0] if stripped else "index"


_TITLES = {
    "ufc": "UFC",
    "aew": "AEW",
    "nfl": "NFL",
    "nba": "NBA",
    "nhl": "NHL",
    "mlb": "MLB",
    "soccer": "Soccer",
    "motorsports": "Motorsports",
    "fighting": "Fighting",
    "multiview": "MultiView",
}


def _title_for(slug: str) -> str:
    if slug in _TITLES:
        return _TITLES[slug]
    if slug.startswith("ppv-streams"):
        return f"PPV Streams {slug.rsplit('-', 1)[-1]}"
    return slug.replace("-", " ").title()


def _merge_events(featured: list[Event], from_categories: list[Event]) -> list[Event]:
    """Combine homepage-featured events with category-page events.

    The site lists some events twice: once on the sport's schedule page and
    once in the homepage "Upcoming Events" table. The schedule page is
    authoritative for grouping, so a homepage row whose target route already
    hosts a scheduled event is treated as a pointer and dropped. Rows pointing
    at routes that schedule nothing (``/darts``, ``/nfl``) are kept.
    """
    scheduled_routes = {event.route for event in from_categories if event.route}
    merged = list(from_categories)
    seen = {(event.label, event.route) for event in merged}
    for event in featured:
        if (event.label, event.route) in seen:
            continue
        if event.route and event.route in scheduled_routes:
            continue
        merged.append(event)
        seen.add((event.label, event.route))
    return merged


def _event_status(local: datetime | None) -> EventStatus:
    """Classify an event against the wall clock."""
    if local is None:
        return EventStatus.UNKNOWN
    start = to_utc(local)
    if start is None:
        return EventStatus.UNKNOWN
    now = datetime.now(timezone.utc)
    if now < start:
        return EventStatus.UPCOMING
    if now <= start + LIVE_WINDOW:
        return EventStatus.LIVE
    return EventStatus.ENDED
