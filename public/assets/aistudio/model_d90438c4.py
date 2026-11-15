"""Data model for the Roxiestreams enumerator.

The site is a static HTML app: categories are nav routes, events are table rows
that link to per-event pages, and streams are ``(path, subdomain)`` descriptors
that get turned into ``https://<subdomain>.<pool-domain>/<path>`` URLs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class ProbeState(str, Enum):
    """Outcome of a single stream-URL probe."""

    LIVE = "live"            # 200 + mpegurl + non-empty playlist
    IDLE = "idle"            # 200 + mpegurl but empty body (feed idle, no segments yet)
    DEAD = "dead"            # 404 / 410 - path does not exist on this host
    HOST_DOWN = "host-down"  # 521/522/524 or connection failure
    FORBIDDEN = "forbidden"  # 403 - needs a signed token
    ERROR = "error"          # anything else


class EventStatus(str, Enum):
    UPCOMING = "upcoming"
    LIVE = "live"
    ENDED = "ended"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Category:
    """A top-level group of events.

    ``kind`` distinguishes navbar groups (which publish their own event table)
    from standalone stream pages that the homepage links to directly.
    """

    slug: str
    title: str
    route: str
    kind: str = "nav"

    @property
    def is_multiview(self) -> bool:
        return self.slug == "multiview"


@dataclass
class Event:
    """A single scheduled event linking to its own stream page."""

    label: str
    route: str
    category: str
    start_local: datetime | None = None   # naive wall-clock, as published
    start_utc: datetime | None = None
    status: EventStatus = EventStatus.UNKNOWN
    viewers: int = 0
    sources: list[str] = field(default_factory=list)  # "upcoming" | "category"


@dataclass(frozen=True)
class StreamRef:
    """A stream descriptor as published by the site.

    Either ``subdomain`` + ``path`` (resolved against the domain pool) or a
    fully ``hardcoded`` absolute URL that bypasses the pool.
    """

    label: str
    subdomain: str | None = None
    path: str | None = None
    pool_file: str | None = None
    hardcoded: str | None = None
    sport: str | None = None
    group: str | None = None  # parent event label, for alternate streams

    @property
    def is_hardcoded(self) -> bool:
        return bool(self.hardcoded)

    @property
    def key(self) -> str:
        """Identity of the underlying feed, ignoring which host serves it."""
        if self.hardcoded:
            return self.hardcoded
        return f"{self.subdomain}/{self.path}" if self.subdomain else (self.path or "")


@dataclass
class Probe:
    """Result of probing one candidate URL."""

    url: str
    state: ProbeState
    status_code: int | None = None
    content_type: str | None = None
    size: int = 0
    elapsed_ms: int = 0
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.state in (ProbeState.LIVE, ProbeState.IDLE)


@dataclass
class ResolvedStream:
    """A stream descriptor resolved to a concrete (verified) URL."""

    ref: StreamRef
    probe: Probe
    candidates: list[str] = field(default_factory=list)
    live_url: str | None = None
    viewers: int = 0

    @property
    def state(self) -> ProbeState:
        return self.probe.state


@dataclass
class SiteStatus:
    """Snapshot of the Uptime Kuma status page."""

    title: str = ""
    description: str = ""
    monitors: list[dict[str, Any]] = field(default_factory=list)
    incidents: list[dict[str, Any]] = field(default_factory=list)
    maintenance: list[dict[str, Any]] = field(default_factory=list)
    heartbeat: dict[str, Any] = field(default_factory=dict)
    error: str | None = None

    @property
    def all_up(self) -> bool:
        return bool(self.monitors) and all(m.get("up") for m in self.monitors)
