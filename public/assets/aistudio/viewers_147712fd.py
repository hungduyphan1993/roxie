"""Live viewer counts from the site's public ``/api/viewers`` endpoint.

The counter is keyed by page slug and a client id; the site keeps a viewer in
the pool until it times out (45 s) or explicitly leaves. A stable id keeps this
tool counted as a single viewer instead of inflating the number on every sweep.
"""

from __future__ import annotations

import secrets
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote

VIEWERS_PATH = "/api/viewers"


def load_client_id(path: Path) -> str:
    """Return a stable per-installation viewer id, creating it if needed."""
    try:
        existing = path.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    except OSError:
        pass
    client_id = secrets.token_hex(8)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(client_id, encoding="utf-8")
    except OSError:
        pass
    return client_id


def viewer_url(mirror: str, page: str, client_id: str) -> str:
    return (
        f"{mirror.rstrip('/')}{VIEWERS_PATH}"
        f"?page={quote(page, safe='')}&id={quote(client_id, safe='')}"
    )


def fetch_viewers(
    client, pages: list[str], *, client_id: str, timeout: float = 10.0, concurrency: int = 12
) -> dict[str, int]:
    """Current watcher count per page slug.

    Every page is released afterwards so this tool does not linger in the pool.
    """
    if not pages:
        return {}
    mirror = client.preferred_mirror

    def read(page: str) -> tuple[str, int | None]:
        text = client.get_text(viewer_url(mirror, page, client_id), timeout=timeout)
        if not text:
            return page, None
        try:
            import json

            payload = json.loads(text)
        except ValueError:
            return page, None
        count = payload.get("count")
        return page, count if isinstance(count, int) else None

    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        results = list(pool.map(read, pages))

    counts = {page: count for page, count in results if count is not None}
    leave(mirror, pages, client_id, timeout=timeout, concurrency=concurrency)
    return counts


def leave(
    mirror: str,
    pages: list[str],
    client_id: str,
    *,
    timeout: float = 10.0,
    concurrency: int = 12,
) -> None:
    """Drop this client out of the viewer pool for each page."""
    import requests

    def send(page: str) -> None:
        url = viewer_url(mirror, page, client_id) + "&leave=1"
        try:
            requests.get(url, timeout=timeout)
        except requests.RequestException:
            return

    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        list(pool.map(send, pages))
