"""Reader for the Uptime Kuma status page at roxiestreams.cc."""

from __future__ import annotations

from typing import Any

from .http import HttpClient
from .model import SiteStatus

DEFAULT_STATUS_BASE = "https://roxiestreams.cc"
DEFAULT_SLUG = "default"

# Uptime Kuma heartbeat status codes.
_STATUS_UP = 1


def fetch_status(
    client: HttpClient,
    *,
    base_url: str = DEFAULT_STATUS_BASE,
    slug: str = DEFAULT_SLUG,
) -> SiteStatus:
    """Fetch monitor configuration and recent heartbeats."""
    base = base_url.rstrip("/")
    status = SiteStatus()

    config_text = client.get_text(f"{base}/api/status-page/{slug}")
    if not config_text:
        status.error = f"status page config unreachable: {base}/api/status-page/{slug}"
        return status

    try:
        import json

        config = json.loads(config_text)
    except ValueError as exc:
        status.error = f"invalid status page JSON: {exc}"
        return status

    status.title = (config.get("config") or {}).get("title", "")
    status.description = (config.get("config") or {}).get("description", "")
    status.incidents = config.get("incident") or []
    status.maintenance = config.get("maintenanceList") or []

    heartbeat_text = client.get_text(f"{base}/api/status-page/heartbeat/{slug}")
    heartbeats: dict[str, Any] = {}
    if heartbeat_text:
        try:
            import json

            heartbeats = (json.loads(heartbeat_text) or {}).get("heartbeatList") or {}
        except ValueError:
            heartbeats = {}
    status.heartbeat = heartbeats

    for group in config.get("publicGroupList") or []:
        for monitor in group.get("monitorList") or []:
            status.monitors.append(_describe(monitor, group.get("name", ""), heartbeats))
    return status


def _describe(monitor: dict[str, Any], group: str, heartbeats: dict[str, Any]) -> dict[str, Any]:
    """Flatten one monitor plus its latest heartbeat into a single record."""
    monitor_id = str(monitor.get("id", ""))
    points = heartbeats.get(monitor_id) or []
    latest = points[-1] if points else {}

    up: bool | None = None
    if latest:
        up = latest.get("status") == _STATUS_UP

    return {
        "id": monitor.get("id"),
        "name": monitor.get("name", ""),
        "group": group,
        "type": monitor.get("type", ""),
        "up": up,
        "ping_ms": latest.get("ping"),
        "last_check": latest.get("time"),
        "message": latest.get("msg", ""),
        "samples": len(points),
    }


def status_summary(status: SiteStatus) -> str:
    """One-line human summary of monitor health."""
    if status.error:
        return f"error: {status.error}"
    if not status.monitors:
        return "no monitors published"
    up = sum(1 for m in status.monitors if m["up"])
    return f"{up}/{len(status.monitors)} monitors up"
