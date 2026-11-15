"""Command line interface for the Roxiestreams enumerator."""

from __future__ import annotations

import argparse
import dataclasses
import json
import sys
import time
import unicodedata
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence

from .discover import Enumerator
from .http import HttpClient
from .model import EventStatus, Probe, ProbeState, ResolvedStream
from .parse import SITE_TZ
from .resolve import build_candidates, probe_matrix, resolve_streams
from .status import DEFAULT_STATUS_BASE, fetch_status, status_summary
from .store import HistoryStore, SiteState, diff_states
from .viewers import load_client_id

MIRRORS = {
    "info": ("https://roxiestreams.info",),
    "su": ("https://roxiestreams.su",),
    "both": ("https://roxiestreams.info", "https://roxiestreams.su"),
}

DEFAULT_EXTRA_HOSTS = ("admin2", "admin", "tedesco", "temp")

_STATE_ICON = {
    ProbeState.LIVE: "LIVE",
    ProbeState.IDLE: "idle",
    ProbeState.DEAD: "dead",
    ProbeState.HOST_DOWN: "down",
    ProbeState.FORBIDDEN: "403",
    ProbeState.ERROR: "err",
}

_STATUS_ICON = {
    EventStatus.LIVE: "LIVE",
    EventStatus.UPCOMING: "soon",
    EventStatus.ENDED: "end",
    EventStatus.UNKNOWN: "?",
}


# ----------------------------------------------------------------- helpers


def display_width(text: str) -> int:
    """Terminal column width, accounting for wide and combining characters."""
    width = 0
    for char in text:
        if unicodedata.combining(char):
            continue
        width += 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
    return width


def pad(text: str, width: int) -> str:
    return text + " " * max(0, width - display_width(text))


def render_table(headers: Sequence[str], rows: Iterable[Sequence[Any]]) -> str:
    """Render a fixed-width table."""
    materialised = [[("" if cell is None else str(cell)) for cell in row] for row in rows]
    widths = [display_width(h) for h in headers]
    for row in materialised:
        for index, cell in enumerate(row):
            if index < len(widths):
                widths[index] = max(widths[index], display_width(cell))
    lines = ["  ".join(pad(h, widths[i]) for i, h in enumerate(headers)).rstrip()]
    lines.append("  ".join("-" * widths[i] for i in range(len(headers))))
    for row in materialised:
        lines.append(
            "  ".join(pad(cell, widths[i]) for i, cell in enumerate(row)).rstrip()
        )
    return "\n".join(lines)


def _shorten(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _fmt_utc(moment: datetime | None) -> str:
    if moment is None:
        return "-"
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def _iso(moment: datetime | None) -> str | None:
    """ISO 8601 for machine consumers."""
    return moment.isoformat() if moment is not None else None


def _local_iso(local: datetime | None) -> str | None:
    """ISO 8601 for a site-local timestamp, tagged with its Pacific offset."""
    if local is None:
        return None
    return local.replace(tzinfo=SITE_TZ).isoformat()


def events_by_group(catalog) -> dict[str, list]:
    """Bucket events under the group that lists them.

    Navbar groups own their schedule, so an event belongs to the sport that
    lists it. Standalone pages (``/ufc``, ``/aew``) are not sport schedules —
    they host whichever events point at them.
    """
    buckets: dict[str, list] = defaultdict(list)
    for category in catalog.categories:
        if category.kind == "nav":
            buckets[category.slug] = [
                e for e in catalog.events if e.category == category.slug
            ]
        else:
            buckets[category.slug] = [
                e for e in catalog.events if e.route == category.route
            ]
    return buckets


# ------------------------------------------------------------------- setup


def build_client(args: argparse.Namespace) -> HttpClient:
    return HttpClient(
        mirrors=MIRRORS[args.site],
        timeout=args.timeout,
        retries=args.retries,
    )


def hosts_tuple(args: argparse.Namespace) -> tuple[str, ...]:
    return MIRRORS[args.site] + tuple(
        url.split("//", 1)[-1].split("/", 1)[0] for url in MIRRORS[args.site]
    )


# ---------------------------------------------------------------- commands


def cmd_categories(args: argparse.Namespace) -> int:
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))
    catalog = enumerator.enumerate_all(include_multiview=False)

    if args.json:
        print(json.dumps(
            {"categories": [dataclasses.asdict(c) for c in catalog.categories]}, indent=2
        ))
        return 0

    counts = defaultdict(int)
    for category, events in events_by_group(catalog).items():
        counts[category] = len(events)

    print(f"\nCATEGORIES ({len(catalog.categories)})")
    print(render_table(
        ["slug", "title", "route", "kind", "events"],
        [
            [c.slug, c.title, c.route, c.kind, counts.get(c.slug, 0)]
            for c in catalog.categories
        ],
    ))
    print(f"\n  routes discovered: {len(catalog.routes)}")
    print(f"  events collected : {len(catalog.events)}")
    return 0


def cmd_events(args: argparse.Namespace) -> int:
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))
    catalog = enumerator.enumerate_all(include_multiview=False)

    events = catalog.events
    if not args.no_viewers:
        client_id = load_client_id(Path(args.state).parent / "viewer-id")
        enumerator.collect_viewers(catalog, client_id)
    if args.category:
        wanted = {c.strip() for c in args.category.split(",")}
        events = [e for e in events if e.category in wanted]
    if args.min_viewers:
        events = [e for e in events if e.viewers >= args.min_viewers]
    if not args.all_events:
        events = [
            e for e in events if e.status in (EventStatus.LIVE, EventStatus.UPCOMING)
        ]

    if args.json:
        print(json.dumps(
            {
                "events": [
                    {
                        **dataclasses.asdict(e),
                        "status": e.status.value,
                        "start_local": _local_iso(e.start_local),
                        "start_utc": _iso(e.start_utc),
                    }
                    for e in events
                ]
            },
            indent=2,
            default=str,
        ))
        return 0

    print(f"\nEVENTS ({len(events)})")
    print(render_table(
        ["status", "watchers", "category", "event", "start (UTC)", "route"],
        [
            [
                _STATUS_ICON.get(e.status, "?"),
                e.viewers or "-",
                e.category,
                _shorten(e.label, 38),
                _fmt_utc(e.start_utc),
                e.route or "-",
            ]
            for e in sorted(
                events,
                key=lambda e: (-e.viewers, e.start_utc or datetime.max.replace(tzinfo=timezone.utc)),
            )
        ],
    ))
    return 0


def _in_category(ref, catalog, wanted: set[str]) -> bool:
    """Whether a stream belongs to one of the named categories.

    Category slugs and the site's own sport labels do not line up — the
    ``/nfl`` page carries college football, which ``/multiview`` labels
    ``ncaa``. Membership is therefore resolved through the events: a stream
    belongs to a category when one of that category's events serves it.
    """
    if ref.sport in wanted:
        return True
    if not ref.path:
        return False
    grouped = events_by_group(catalog)
    for slug in wanted:
        for event in grouped.get(slug, []):
            if ref.path in catalog.event_paths.get(event.route, []):
                return True
    return False


def cmd_streams(args: argparse.Namespace) -> int:
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))
    catalog = enumerator.enumerate_all(include_multiview=args.source in ("multiview", "all"))

    refs = catalog.streams
    if args.source == "multiview":
        refs = [r for r in refs if r.group is None]
    if args.category:
        wanted = {c.strip() for c in args.category.split(",")}
        refs = [r for r in refs if _in_category(r, catalog, wanted)]

    if not args.no_viewers:
        client_id = load_client_id(Path(args.state).parent / "viewer-id")
        enumerator.collect_viewers(catalog, client_id)

    if not args.no_probe:
        resolved = resolve_streams(
            refs,
            catalog.pool,
            client,
            extra_hosts=DEFAULT_EXTRA_HOSTS,
            include_fallback=args.include_fallback,
            concurrency=args.concurrency,
            timeout=args.probe_timeout,
            canary_timeout=args.canary_timeout,
        )
        _attach_viewers(catalog, resolved)
    else:
        resolved = [
            ResolvedStream(
                ref=ref,
                probe=Probe("", ProbeState.ERROR, error="probing skipped"),
                candidates=[
                    c.url
                    for c in build_candidates(
                        ref, catalog.pool, extra_hosts=DEFAULT_EXTRA_HOSTS
                    )
                ],
            )
            for ref in refs
        ]

    if args.json:
        print(json.dumps(
            {
                "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "pool": catalog.pool,
                "streams": _streams_payload(resolved),
            },
            indent=2,
            default=str,
        ))
        return 0

    live = [r for r in resolved if r.live_url]
    print(f"\nSTREAMS  pool={catalog.pool or '-'}  resolved={len(resolved)}  live={len(live)}")
    if not live:
        print("  no live streams found")
    else:
        print(render_table(
            ["state", "viewers", "sport", "label", "path", "url"],
            [
                [
                    _STATE_ICON.get(r.probe.state, "?"),
                    r.viewers or "-",
                    r.ref.sport or "-",
                    _shorten(r.ref.group or r.ref.label, 34),
                    r.ref.path or _shorten(r.ref.hardcoded or "", 26),
                    r.live_url,
                ]
                for r in sorted(live, key=lambda r: (-r.viewers, r.ref.sport or "", r.ref.label))
            ],
        ))
    return 0


def cmd_matrix(args: argparse.Namespace) -> int:
    """Probe every known path against every known host."""
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))
    catalog = enumerator.enumerate_all()

    paths = sorted({r.path for r in catalog.streams if r.path})
    hosts = [f"{sub}.{domain}" for domain in catalog.pool for sub in DEFAULT_EXTRA_HOSTS]
    if args.include_fallback:
        hosts += [f"{sub}.shadow-ran.online" for sub in DEFAULT_EXTRA_HOSTS]

    matrix = probe_matrix(
        paths, hosts, client,
        concurrency=args.concurrency,
        timeout=args.probe_timeout,
        canary_timeout=args.canary_timeout,
    )

    if args.json:
        print(json.dumps(
            {
                path: {
                    host: {"state": p.state.value, "code": p.status_code, "size": p.size}
                    for host, p in probes.items()
                }
                for path, probes in matrix.items()
            },
            indent=2,
        ))
        return 0

    _render_matrix(matrix, paths, hosts)
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    client = build_client(args)
    status = fetch_status(client, base_url=args.status_url)

    if args.json:
        print(json.dumps(dataclasses.asdict(status), indent=2, default=str))
        return 0

    print(f"\nSTATUS  {status.title or args.status_url}")
    if status.description:
        print(f"  {status.description}")
    print(f"  {status_summary(status)}")
    if status.monitors:
        print(render_table(
            ["id", "monitor", "group", "up", "ping", "last check"],
            [
                [
                    m["id"], m["name"], m["group"],
                    "yes" if m["up"] else ("no" if m["up"] is False else "?"),
                    f"{m['ping_ms']}ms" if m["ping_ms"] is not None else "-",
                    m["last_check"] or "-",
                ]
                for m in status.monitors
            ],
        ))
    return 0


def _render_categories(catalog, live: list[ResolvedStream]) -> None:
    """The categories-and-groups table."""
    grouped = events_by_group(catalog)
    live_paths = {item.ref.path for item in live if item.ref.path}

    def live_for(slug: str) -> int:
        paths = {
            path
            for event in grouped.get(slug, [])
            for path in catalog.event_paths.get(event.route, [])
        }
        return len(paths & live_paths)

    print(f"\nCATEGORIES & GROUPS ({len(catalog.categories)})")
    print(render_table(
        ["category", "title", "kind", "events", "live", "soon"],
        [
            [
                c.slug,
                c.title,
                c.kind,
                len(grouped.get(c.slug, [])),
                live_for(c.slug),
                sum(
                    1 for e in grouped.get(c.slug, [])
                    if e.status == EventStatus.UPCOMING
                ),
            ]
            for c in catalog.categories
        ],
    ))


def _render_events(events: list) -> None:
    """The upcoming/live events table, busiest stream first."""
    soon = sorted(
        (e for e in events if e.status in (EventStatus.LIVE, EventStatus.UPCOMING)),
        key=lambda e: (-e.viewers, e.start_utc or datetime.max.replace(tzinfo=timezone.utc)),
    )
    print(f"\nEVENTS ({len(soon)} upcoming/live of {len(events)})")
    if not soon:
        print("  (none scheduled)")
        return
    print(render_table(
        ["status", "watchers", "category", "event", "start (UTC)", "route"],
        [
            [
                _STATUS_ICON.get(e.status, "?"),
                e.viewers or "-",
                e.category,
                _shorten(e.label, 38),
                _fmt_utc(e.start_utc),
                e.route or "-",
            ]
            for e in soon
        ],
    ))


def _render_live_streams(live: list[ResolvedStream], total: int) -> None:
    """The live stream table, URL first so it is never the cut-off column."""
    print(f"\nLIVE STREAMS ({len(live)} of {total} resolved)")
    if not live:
        print("  (none live)")
        return
    print(render_table(
        ["live url", "viewers", "sport", "label"],
        [
            [
                r.live_url,
                r.viewers or "-",
                r.ref.sport or "-",
                _shorten(r.ref.group or r.ref.label, 28),
            ]
            for r in sorted(
                live, key=lambda r: (-r.viewers, r.ref.sport or "", r.ref.label)
            )
        ],
    ))
    print("\n  (copy one URL at a time with: roxie.py urls)")


def _render_matrix(matrix: dict, paths: list[str], hosts: list[str]) -> None:
    """The path-by-host coverage grid."""
    short = [
        h.replace(".formaturamaxi.com.br", "").replace(".shadow-ran.online", "*")
        for h in hosts
    ]
    rows = []
    for path in paths:
        cells = []
        for host in hosts:
            probe = matrix.get(path, {}).get(host)
            if probe is None:
                cells.append("-")
            elif probe.state == ProbeState.LIVE:
                cells.append("LIVE")
            elif probe.state == ProbeState.IDLE:
                cells.append("idle")
            elif probe.state == ProbeState.HOST_DOWN:
                cells.append("down")
            elif probe.state == ProbeState.FORBIDDEN:
                cells.append("403")
            else:
                cells.append("dead")
        rows.append([path, *cells])
    print(f"\nCOVERAGE MATRIX ({len(paths)} paths x {len(hosts)} hosts)")
    print(render_table(["path", *short], rows))


def cmd_urls(args: argparse.Namespace) -> int:
    """Print full stream URLs, one per line, ready to copy.

    The tables are for scanning; this is for taking a URL away. Nothing is
    truncated and no columns are involved.
    """
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))
    catalog = enumerator.enumerate_all()

    refs = catalog.streams
    if args.category:
        wanted = {c.strip() for c in args.category.split(",")}
        refs = [r for r in refs if _in_category(r, catalog, wanted)]

    if not args.no_viewers:
        client_id = load_client_id(Path(args.state).parent / "viewer-id")
        enumerator.collect_viewers(catalog, client_id)

    resolved = resolve_streams(
        refs,
        catalog.pool,
        client,
        extra_hosts=DEFAULT_EXTRA_HOSTS,
        include_fallback=args.include_fallback,
        concurrency=args.concurrency,
        timeout=args.probe_timeout,
        canary_timeout=args.canary_timeout,
    )
    _attach_viewers(catalog, resolved)

    live = [r for r in resolved if r.live_url]
    if args.min_viewers:
        live = [r for r in live if r.viewers >= args.min_viewers]
    live.sort(key=lambda r: (-r.viewers, r.ref.sport or "", r.ref.label))

    if args.json:
        print(json.dumps(
            {
                "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "count": len(live),
                "urls": [
                    {
                        "url": r.live_url,
                        "viewers": r.viewers,
                        "sport": r.ref.sport,
                        "label": r.ref.group or r.ref.label,
                        "path": r.ref.path,
                    }
                    for r in live
                ],
            },
            indent=2,
        ))
        return 0

    for item in live:
        print(item.live_url)
    return 0


def cmd_all(args: argparse.Namespace) -> int:
    """Everything at once: status, categories, events, live URLs, host coverage."""
    started = time.monotonic()
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))

    catalog = enumerator.enumerate_all()
    site_status = fetch_status(client, base_url=args.status_url)

    if not args.no_viewers:
        client_id = load_client_id(Path(args.state).parent / "viewer-id")
        enumerator.collect_viewers(catalog, client_id)

    resolved = resolve_streams(
        catalog.streams,
        catalog.pool,
        client,
        extra_hosts=DEFAULT_EXTRA_HOSTS,
        include_fallback=args.include_fallback,
        concurrency=args.concurrency,
        timeout=args.probe_timeout,
        canary_timeout=args.canary_timeout,
    )
    _attach_viewers(catalog, resolved)
    live = [r for r in resolved if r.live_url]

    if args.min_viewers:
        catalog.events = [e for e in catalog.events if e.viewers >= args.min_viewers]
        live = [r for r in live if r.viewers >= args.min_viewers]

    rotations = _record_history(args, catalog, resolved)

    paths = sorted({r.ref.path for r in resolved if r.ref.path})
    hosts = [f"{sub}.{domain}" for domain in catalog.pool for sub in DEFAULT_EXTRA_HOSTS]
    if args.include_fallback:
        hosts += [f"{sub}.shadow-ran.online" for sub in DEFAULT_EXTRA_HOSTS]
    matrix = probe_matrix(
        paths, hosts, client,
        concurrency=args.concurrency,
        timeout=args.probe_timeout,
        canary_timeout=args.canary_timeout,
    )

    if args.json:
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "site": {
                "mirrors": list(MIRRORS[args.site]),
                "pool": catalog.pool,
                "pool_file": catalog.pool_file,
                "status": dataclasses.asdict(site_status),
            },
            "rotations": rotations,
            "categories": [dataclasses.asdict(c) for c in catalog.categories],
            "events": [
                {
                    **dataclasses.asdict(e),
                    "status": e.status.value,
                    "start_local": _local_iso(e.start_local),
                    "start_utc": _iso(e.start_utc),
                }
                for e in catalog.events
            ],
            "event_paths": catalog.event_paths,
            "groups": _groups_payload(catalog, resolved),
            "streams": _streams_payload(resolved),
            "matrix": {
                path: {
                    host: {"state": p.state.value, "code": p.status_code, "size": p.size}
                    for host, p in probes.items()
                }
                for path, probes in matrix.items()
            },
        }
        _emit(payload, args)
        return 0

    print(f"\n{'=' * 72}")
    print(f"ROXIESTREAMS FULL REPORT   {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"{'=' * 72}")
    print(f"  mirror        : {client.preferred_mirror}")
    print(f"  pool          : {catalog.pool or '-'} ({catalog.pool_file})")
    print(f"  status page   : {status_summary(site_status)}")
    if rotations:
        print("  ROTATIONS     :")
        for change in rotations:
            print(f"    ! {change}")

    if site_status.monitors:
        print("\nMONITORS")
        print(render_table(
            ["id", "monitor", "group", "up", "ping", "last check"],
            [
                [
                    m["id"], m["name"], m["group"],
                    "yes" if m["up"] else ("no" if m["up"] is False else "?"),
                    f"{m['ping_ms']}ms" if m["ping_ms"] is not None else "-",
                    m["last_check"] or "-",
                ]
                for m in site_status.monitors
            ],
        ))

    _render_categories(catalog, live)
    _render_events(catalog.events)
    _render_live_streams(live, len(resolved))
    _render_matrix(matrix, paths, hosts)

    elapsed = time.monotonic() - started
    print(f"\n  elapsed {elapsed:.1f}s")
    return 0


def cmd_sweep(args: argparse.Namespace) -> int:
    """Full pipeline: categories -> events -> resolved live stream URLs."""
    started = time.monotonic()
    client = build_client(args)
    enumerator = Enumerator(client, hosts_tuple(args))

    catalog = enumerator.enumerate_all()
    site_status = fetch_status(client, base_url=args.status_url)

    if not args.no_viewers:
        client_id = load_client_id(Path(args.state).parent / "viewer-id")
        enumerator.collect_viewers(catalog, client_id)

    resolved = resolve_streams(
        catalog.streams,
        catalog.pool,
        client,
        extra_hosts=DEFAULT_EXTRA_HOSTS,
        include_fallback=args.include_fallback,
        concurrency=args.concurrency,
        timeout=args.probe_timeout,
        canary_timeout=args.canary_timeout,
    )
    _attach_viewers(catalog, resolved)
    live = [r for r in resolved if r.live_url]

    if args.min_viewers:
        catalog.events = [e for e in catalog.events if e.viewers >= args.min_viewers]
        live = [r for r in live if r.viewers >= args.min_viewers]

    rotations = _record_history(args, catalog, resolved)

    if args.json:
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "site": {
                "mirrors": list(MIRRORS[args.site]),
                "pool": catalog.pool,
                "pool_file": catalog.pool_file,
                "status": dataclasses.asdict(site_status),
            },
            "rotations": rotations,
            "categories": [dataclasses.asdict(c) for c in catalog.categories],
            "events": [
                {
                    **dataclasses.asdict(e),
                    "status": e.status.value,
                    "start_local": _local_iso(e.start_local),
                    "start_utc": _iso(e.start_utc),
                }
                for e in catalog.events
            ],
            "event_paths": catalog.event_paths,
            "groups": _groups_payload(catalog, resolved),
            "streams": _streams_payload(resolved),
        }
        _emit(payload, args)
        return 0

    print(f"\n{'=' * 72}")
    print(f"ROXIESTREAMS SWEEP   {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
    print(f"{'=' * 72}")
    print(f"  mirror        : {client.preferred_mirror}")
    print(f"  pool          : {catalog.pool or '-'} ({catalog.pool_file})")
    print(f"  status page   : {status_summary(site_status)}")
    if rotations:
        print("  ROTATIONS     :")
        for change in rotations:
            print(f"    ! {change}")

    _render_categories(catalog, live)
    _render_events(catalog.events)
    _render_live_streams(live, len(resolved))

    elapsed = time.monotonic() - started
    print(f"\n  elapsed {elapsed:.1f}s")
    return 0


def cmd_watch(args: argparse.Namespace) -> int:
    """Re-run the sweep on an interval and report what changed."""
    previous: set[str] | None = None
    try:
        while True:
            client = build_client(args)
            enumerator = Enumerator(client, hosts_tuple(args))
            catalog = enumerator.enumerate_all()
            resolved = resolve_streams(
                catalog.streams,
                catalog.pool,
                client,
                extra_hosts=DEFAULT_EXTRA_HOSTS,
                include_fallback=args.include_fallback,
                concurrency=args.concurrency,
                timeout=args.probe_timeout,
                canary_timeout=args.canary_timeout,
            )
            live = {r.live_url for r in resolved if r.live_url}
            stamp = datetime.now(timezone.utc).strftime("%H:%M:%S")
            if previous is None:
                print(f"[{stamp}] baseline: {len(live)} live streams", flush=True)
            else:
                gained = sorted(live - previous)
                lost = sorted(previous - live)
                for url in gained:
                    print(f"[{stamp}] + {url}", flush=True)
                for url in lost:
                    print(f"[{stamp}] - {url}", flush=True)
                if not gained and not lost:
                    print(f"[{stamp}] no change ({len(live)} live)", flush=True)
            previous = live
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nstopped")
    return 0


# ----------------------------------------------------------------- payload


def _attach_viewers(catalog, resolved: list[ResolvedStream]) -> None:
    """Propagate per-event watcher counts onto the streams they serve."""
    best: dict[str, int] = {}
    for event in catalog.events:
        if not event.route:
            continue
        for path in catalog.event_paths.get(event.route, []):
            if event.viewers > best.get(path, 0):
                best[path] = event.viewers
    for item in resolved:
        if item.ref.path:
            item.viewers = best.get(item.ref.path, 0)


def _record_history(
    args: argparse.Namespace, catalog, resolved: list[ResolvedStream]
) -> list[str]:
    """Persist this sweep and report any change in the site's shape."""
    hosts = {
        f"{ref.subdomain}.{domain}"
        for ref in catalog.streams
        if ref.subdomain
        for domain in catalog.pool
    }
    subdomains = {ref.subdomain for ref in catalog.streams if ref.subdomain}
    state = SiteState(
        pool=list(catalog.pool),
        hosts=sorted(hosts),
        subdomains=sorted(subdomains),
        mirrors=list(MIRRORS[args.site]),
    )

    with HistoryStore(args.state) as store:
        previous = store.last_state()
        store.save_state(datetime.now(timezone.utc), state)
        store.record(
            datetime.now(timezone.utc),
            [
                {
                    "path": item.ref.path,
                    "hardcoded": item.ref.hardcoded,
                    "live_url": item.live_url,
                    "state": item.probe.state.value,
                    "viewers": item.viewers,
                }
                for item in resolved
            ],
        )
    return diff_states(previous, state) if previous else []


def _parse_duration(text: str) -> timedelta:
    """Parse ``30m`` / ``2h`` / ``3d`` into a timedelta."""
    units = {"m": "minutes", "h": "hours", "d": "days"}
    cleaned = text.strip().lower()
    if cleaned.isdigit():
        return timedelta(minutes=int(cleaned))
    if len(cleaned) > 1 and cleaned[-1] in units:
        return timedelta(**{units[cleaned[-1]]: int(cleaned[:-1])})
    raise ValueError(f"unrecognised duration: {text!r} (use 30m, 2h, 3d)")


def cmd_history(args: argparse.Namespace) -> int:
    """Show state transitions recorded in the history database."""
    since = datetime.now(timezone.utc) - _parse_duration(args.since)
    with HistoryStore(args.state) as store:
        changes = store.transitions(since)
        latest = store.latest_observations()

    if args.json:
        print(json.dumps({"since": since.isoformat(), "changes": changes}, indent=2))
        return 0

    print(f"\nSTATE CHANGES (last {args.since})")
    if not changes:
        print("  no recorded changes")
        return 0
    print(render_table(
        ["when (UTC)", "path", "from", "to", "url"],
        [
            [
                c["observed_at"],
                c["path"],
                c["from"],
                c["to"],
                c["url"] or "-",
            ]
            for c in changes
        ],
    ))
    print(f"\n  paths tracked: {len(latest)}")
    return 0


def cmd_rotations(args: argparse.Namespace) -> int:
    """Show how the site's pool, hosts and mirrors changed over time."""
    since = datetime.now(timezone.utc) - _parse_duration(args.since)
    with HistoryStore(args.state) as store:
        rows = list(
            store._conn.execute(  # noqa: SLF001 - intentional internal read
                "SELECT observed_at, payload FROM site_states WHERE observed_at >= ? "
                "ORDER BY observed_at",
                (since.isoformat(timespec="seconds"),),
            )
        )

    if args.json:
        print(json.dumps(
            {
                "since": since.isoformat(),
                "states": [
                    {"observed_at": r["observed_at"], **json.loads(r["payload"])}
                    for r in rows
                ],
            },
            indent=2,
        ))
        return 0

    print(f"\nSITE STATE (last {args.since})")
    if not rows:
        print("  no state recorded yet — run 'roxie.py sweep' first")
        return 0
    for index, row in enumerate(rows):
        payload = json.loads(row["payload"])
        print(f"\n  {row['observed_at']}")
        print(f"    pool       : {', '.join(payload['pool']) or '-'}")
        print(f"    hosts      : {', '.join(payload['hosts']) or '-'}")
        print(f"    subdomains : {', '.join(payload['subdomains']) or '-'}")
        print(f"    mirrors    : {', '.join(payload['mirrors']) or '-'}")
        if index + 1 < len(rows):
            later = json.loads(rows[index + 1]["payload"])
            changes = diff_states(SiteState.from_json(row["payload"]), SiteState.from_json(json.dumps(later)))
            for change in changes:
                print(f"      -> {change}")
    return 0


def _streams_payload(resolved: list[ResolvedStream]) -> list[dict[str, Any]]:
    return [
        {
            "sport": r.ref.sport,
            "label": r.ref.label,
            "group": r.ref.group,
            "path": r.ref.path,
            "subdomain": r.ref.subdomain,
            "hardcoded": r.ref.hardcoded,
            "state": r.probe.state.value,
            "status_code": r.probe.status_code,
            "viewers": r.viewers,
            "live_url": r.live_url,
            "candidates": r.candidates,
        }
        for r in resolved
    ]


def _groups_payload(catalog, resolved: list[ResolvedStream]) -> list[dict[str, Any]]:
    """Per-group rollup: events, their states, and the live URLs behind them."""
    live_paths = {r.ref.path for r in resolved if r.live_url and r.ref.path}
    grouped = events_by_group(catalog)
    rows: list[dict[str, Any]] = []
    for category in catalog.categories:
        events = grouped.get(category.slug, [])
        paths = {
            path
            for event in events
            for path in catalog.event_paths.get(event.route, [])
        }
        rows.append(
            {
                "slug": category.slug,
                "title": category.title,
                "route": category.route,
                "kind": category.kind,
                "events": len(events),
                "live": sum(1 for e in events if e.status == EventStatus.LIVE),
                "upcoming": sum(1 for e in events if e.status == EventStatus.UPCOMING),
                "ended": sum(1 for e in events if e.status == EventStatus.ENDED),
                "live_streams": len(paths & live_paths),
            }
        )
    return rows


def _emit(payload: dict[str, Any], args: argparse.Namespace) -> None:
    text = json.dumps(payload, indent=2, default=str)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
        print(f"wrote {args.output} ({len(text)} bytes)")
    else:
        print(text)


# -------------------------------------------------------------------- main


DEFAULTS: dict[str, Any] = {
    "site": "both",
    "timeout": 15.0,
    "probe_timeout": 10.0,
    "canary_timeout": 4.0,
    "retries": 1,
    "concurrency": 16,
    "status_url": DEFAULT_STATUS_BASE,
    "json": False,
    "output": None,
    "state": "roxie-history.db",
    "min_viewers": 0,
    "no_viewers": False,
}


def _add_common(parser: argparse.ArgumentParser) -> None:
    """Register the options accepted both before and after the subcommand."""
    parser.add_argument("--site", choices=list(MIRRORS), default=argparse.SUPPRESS,
                        help="which mirror(s) to use (default: both)")
    parser.add_argument("--timeout", type=float, default=argparse.SUPPRESS,
                        help="page fetch timeout")
    parser.add_argument("--probe-timeout", type=float, default=argparse.SUPPRESS,
                        help="stream probe timeout")
    parser.add_argument("--canary-timeout", type=float, default=argparse.SUPPRESS,
                        help="host liveness canary timeout")
    parser.add_argument("--retries", type=int, default=argparse.SUPPRESS,
                        help="http retry attempts")
    parser.add_argument("--concurrency", type=int, default=argparse.SUPPRESS,
                        help="parallel probes")
    parser.add_argument("--status-url", default=argparse.SUPPRESS,
                        help="status page base url")
    parser.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                        help="emit JSON")
    parser.add_argument("--output", default=argparse.SUPPRESS,
                        help="write JSON output to a file")
    parser.add_argument("--state", default=argparse.SUPPRESS,
                        help="sqlite history database path")
    parser.add_argument("--min-viewers", type=int, default=argparse.SUPPRESS,
                        help="only report events with at least this many watchers")
    parser.add_argument("--no-viewers", action="store_true", default=argparse.SUPPRESS,
                        help="skip the viewer counter")


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    _add_common(common)

    parser = argparse.ArgumentParser(
        prog="roxie",
        parents=[common],
        description="Enumerate Roxiestreams categories, events and live stream URLs.",
    )

    sub = parser.add_subparsers(dest="command", required=True)

    categories = sub.add_parser("categories", parents=[common], help="list categories/groups")
    categories.set_defaults(func=cmd_categories)

    events = sub.add_parser("events", parents=[common], help="list scheduled events")
    events.add_argument("--category", help="comma-separated category filter")
    events.add_argument("--all-events", action="store_true", help="include ended events")
    events.set_defaults(func=cmd_events)

    streams = sub.add_parser("streams", parents=[common],
                              help="resolve stream descriptors to live URLs")
    streams.add_argument("--source", choices=("multiview", "events", "all"), default="all")
    streams.add_argument("--category", help="comma-separated sport filter")
    streams.add_argument("--no-probe", action="store_true",
                         help="build candidate URLs without probing them")
    streams.add_argument("--include-fallback", action="store_true",
                         help="also try the site's shadow-ran.online fallback host")
    streams.set_defaults(func=cmd_streams)

    matrix = sub.add_parser("matrix", parents=[common],
                             help="probe every path against every host")
    matrix.add_argument("--include-fallback", action="store_true",
                        help="also probe the parked shadow-ran.online fallback host")
    matrix.set_defaults(func=cmd_matrix)

    status = sub.add_parser("status", parents=[common],
                            help="read the roxiestreams.cc status page")
    status.set_defaults(func=cmd_status)

    history = sub.add_parser("history", parents=[common],
                              help="show recorded state transitions")
    history.add_argument("--since", default="6h", help="window to report (30m, 2h, 3d)")
    history.set_defaults(func=cmd_history)

    rotations = sub.add_parser("rotations", parents=[common],
                                help="show changes to the site's pool, hosts and mirrors")
    rotations.add_argument("--since", default="7d", help="window to report (30m, 2h, 3d)")
    rotations.set_defaults(func=cmd_rotations)

    urls = sub.add_parser("urls", parents=[common],
                           help="print full stream URLs, one per line")
    urls.add_argument("--category", help="comma-separated category filter")
    urls.add_argument("--include-fallback", action="store_true",
                      help="also try the site's shadow-ran.online fallback host")
    urls.set_defaults(func=cmd_urls)

    every = sub.add_parser("all", parents=[common],
                            help="everything: status, categories, events, live URLs, coverage")
    every.add_argument("--include-fallback", action="store_true")
    every.set_defaults(func=cmd_all)

    sweep = sub.add_parser("sweep", parents=[common],
                            help="full pipeline: categories + events + live URLs")
    sweep.add_argument("--include-fallback", action="store_true")
    sweep.set_defaults(func=cmd_sweep)

    watch = sub.add_parser("watch", parents=[common], help="poll for live URL changes")
    watch.add_argument("--interval", type=float, default=300.0, help="seconds between polls")
    watch.add_argument("--include-fallback", action="store_true")
    watch.set_defaults(func=cmd_watch)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    for key, value in DEFAULTS.items():
        if not hasattr(args, key):
            setattr(args, key, value)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130
    except Exception as exc:  # noqa: BLE001 - CLI boundary
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
