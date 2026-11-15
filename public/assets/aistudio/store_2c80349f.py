"""Persistent observation history and site-rotation detection.

The site's own warning banner says domains get seized without notice, so the
interesting signal is not "how many URLs per second" but "did the site move
underneath us": a new pool domain, a new stream host, a retired subdomain, a
mirror going away. This module stores observations over time and diffs the
site's shape between runs.
"""

from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

SCHEMA = """
CREATE TABLE IF NOT EXISTS observations (
    observed_at TEXT NOT NULL,
    path        TEXT NOT NULL,
    host        TEXT,
    url         TEXT,
    state       TEXT NOT NULL,
    viewers     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (observed_at, path)
);
CREATE INDEX IF NOT EXISTS observations_path ON observations(path, observed_at);
CREATE TABLE IF NOT EXISTS site_states (
    observed_at TEXT PRIMARY KEY,
    payload     TEXT NOT NULL
);
"""


@dataclass
class SiteState:
    """A snapshot of the site's shape, used to detect rotation."""

    pool: list[str] = field(default_factory=list)
    hosts: list[str] = field(default_factory=list)
    subdomains: list[str] = field(default_factory=list)
    mirrors: list[str] = field(default_factory=list)

    def to_json(self) -> str:
        return json.dumps(
            {
                "pool": sorted(self.pool),
                "hosts": sorted(self.hosts),
                "subdomains": sorted(self.subdomains),
                "mirrors": sorted(self.mirrors),
            },
            sort_keys=True,
        )

    @classmethod
    def from_json(cls, payload: str) -> "SiteState":
        data = json.loads(payload)
        return cls(
            pool=list(data.get("pool", [])),
            hosts=list(data.get("hosts", [])),
            subdomains=list(data.get("subdomains", [])),
            mirrors=list(data.get("mirrors", [])),
        )


def _host_of(url: str | None) -> str | None:
    """Extract the host from a URL, tolerating malformed input."""
    if not url:
        return None
    parsed = urlparse(url)
    return parsed.netloc or None


def diff_states(old: SiteState, new: SiteState) -> list[str]:
    """Human-readable description of how the site's shape changed."""
    changes: list[str] = []
    for field_name in ("pool", "hosts", "subdomains", "mirrors"):
        before = set(getattr(old, field_name))
        after = set(getattr(new, field_name))
        for added in sorted(after - before):
            changes.append(f"{field_name} added: {added}")
        for removed in sorted(before - after):
            changes.append(f"{field_name} removed: {removed}")
    return changes


class HistoryStore:
    """SQLite-backed observation log."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.path)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> "HistoryStore":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    # ---------------------------------------------------------- observations

    def record(
        self,
        observed_at: datetime,
        streams: list[dict],
    ) -> None:
        """Store one sweep's stream results."""
        stamp = observed_at.astimezone(timezone.utc).isoformat(timespec="seconds")
        rows = [
            (
                stamp,
                stream.get("path") or stream.get("hardcoded") or "",
                _host_of(stream.get("live_url")),
                stream.get("live_url"),
                stream.get("state", "unknown"),
                int(stream.get("viewers") or 0),
            )
            for stream in streams
        ]
        self._conn.executemany(
            "INSERT OR REPLACE INTO observations "
            "(observed_at, path, host, url, state, viewers) VALUES (?,?,?,?,?,?)",
            rows,
        )
        self._conn.commit()

    def observations_since(self, since: datetime) -> list[sqlite3.Row]:
        stamp = since.astimezone(timezone.utc).isoformat(timespec="seconds")
        return list(
            self._conn.execute(
                "SELECT * FROM observations WHERE observed_at >= ? ORDER BY observed_at, path",
                (stamp,),
            )
        )

    def transitions(self, since: datetime) -> list[dict]:
        """State changes per path within the window.

        Only the *first* transition of each kind per path is reported, so a
        feed that flaps does not drown out real changes.
        """
        rows = self.observations_since(since)
        seen: set[tuple[str, str]] = set()
        changes: list[dict] = []
        last: dict[str, str] = {}
        for row in rows:
            path = row["path"]
            state = row["state"]
            previous = last.get(path)
            if previous is not None and previous != state and (path, state) not in seen:
                seen.add((path, state))
                changes.append(
                    {
                        "observed_at": row["observed_at"],
                        "path": path,
                        "from": previous,
                        "to": state,
                        "url": row["url"],
                    }
                )
            last[path] = state
        return changes

    def latest_observations(self) -> dict[str, dict]:
        """The most recent observation per path."""
        rows = self._conn.execute(
            "SELECT o.* FROM observations o "
            "JOIN (SELECT path, MAX(observed_at) AS ts FROM observations GROUP BY path) m "
            "ON o.path = m.path AND o.observed_at = m.ts"
        )
        return {row["path"]: dict(row) for row in rows}

    # ------------------------------------------------------------ site state

    def save_state(self, observed_at: datetime, state: SiteState) -> None:
        stamp = observed_at.astimezone(timezone.utc).isoformat(timespec="seconds")
        self._conn.execute(
            "INSERT OR REPLACE INTO site_states (observed_at, payload) VALUES (?,?)",
            (stamp, state.to_json()),
        )
        self._conn.commit()

    def last_state(self) -> SiteState | None:
        row = self._conn.execute(
            "SELECT payload FROM site_states ORDER BY observed_at DESC LIMIT 1"
        ).fetchone()
        return SiteState.from_json(row["payload"]) if row else None
