"""Turn stream descriptors into concrete URLs and verify which ones are live."""

from __future__ import annotations

import threading
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from .http import HttpClient
from .model import Probe, ProbeState, ResolvedStream, StreamRef

# The site's own hardcoded fallback host, used when the pool file cannot load.
FALLBACK_DOMAIN = "shadow-ran.online"

_STATE_RANK = {
    ProbeState.LIVE: 0,
    ProbeState.IDLE: 1,
    ProbeState.FORBIDDEN: 2,
    ProbeState.DEAD: 3,
    ProbeState.HOST_DOWN: 4,
    ProbeState.ERROR: 5,
}


@dataclass(frozen=True)
class Candidate:
    """A concrete URL a descriptor could be served from."""

    url: str
    host: str
    origin: str  # declared | alternate | fallback | hardcoded


def _host_of(url: str) -> str:
    return url.split("//", 1)[-1].split("/", 1)[0]


class HostHealth:
    """Caches which stream hosts answer at all.

    A host that is unreachable costs a full timeout on every path, so it is
    probed once and then skipped. The canary must hit a *stream* path: some
    hosts answer the root instantly but hang on every real path, and a root
    canary would wrongly mark them healthy.
    """

    def __init__(
        self,
        client: HttpClient,
        timeout: float = 10.0,
        canary_timeout: float = 4.0,
        concurrency: int = 8,
        canary_path: str = "",
    ) -> None:
        self.client = client
        self.timeout = timeout
        self.canary_timeout = canary_timeout
        self.concurrency = concurrency
        self.canary_path = canary_path
        self._cache: dict[str, bool] = {}
        self._lock = threading.Lock()

    def is_up(self, host: str) -> bool:
        with self._lock:
            if host in self._cache:
                return self._cache[host]
        target = f"https://{host}/{self.canary_path}" if self.canary_path else f"https://{host}/"
        probe = self.client.probe(target, timeout=self.canary_timeout)
        up = probe.state not in (ProbeState.HOST_DOWN, ProbeState.ERROR)
        with self._lock:
            self._cache[host] = up
        return up

    def reachable(self, hosts: set[str]) -> set[str]:
        """Subset of ``hosts`` that answered."""
        ordered = sorted(hosts)
        if not ordered:
            return set()
        with ThreadPoolExecutor(max_workers=max(1, self.concurrency)) as pool:
            flags = list(pool.map(self.is_up, ordered))
        return {host for host, up in zip(ordered, flags) if up}


def _host_variants(
    ref: StreamRef,
    pool: list[str],
    extra_hosts: tuple[str, ...],
    include_fallback: bool,
) -> tuple[list[str], set[str]]:
    """Subdomains to try, and the hosts they expand to."""
    subdomains: list[str] = []
    for subdomain in (ref.subdomain, *extra_hosts):
        if subdomain and subdomain not in subdomains:
            subdomains.append(subdomain)

    hosts: set[str] = set()
    for subdomain in subdomains:
        for domain in pool:
            hosts.add(f"{subdomain}.{domain}")
        if include_fallback:
            hosts.add(f"{subdomain}.{FALLBACK_DOMAIN}")
    return subdomains, hosts


def build_candidates(
    ref: StreamRef,
    pool: list[str],
    *,
    extra_hosts: tuple[str, ...] = (),
    include_fallback: bool = False,
    allowed_hosts: set[str] | None = None,
) -> list[Candidate]:
    """Candidate URLs for one descriptor, most-authoritative first.

    The site's own markup pins a ``subdomain`` per stream, so that pairing is
    tried first; other known subdomains are fallbacks for when the pinned one
    is down.
    """
    if ref.hardcoded:
        return [Candidate(ref.hardcoded, _host_of(ref.hardcoded), "hardcoded")]

    subdomains, _ = _host_variants(ref, pool, extra_hosts, include_fallback)
    if not subdomains:
        return []

    candidates: list[Candidate] = []
    for subdomain in subdomains:
        for domain in pool:
            host = f"{subdomain}.{domain}"
            if allowed_hosts is not None and host not in allowed_hosts:
                continue
            origin = "declared" if subdomain == ref.subdomain else "alternate"
            candidates.append(Candidate(f"https://{host}/{ref.path}", host, origin))
    if include_fallback:
        for subdomain in subdomains:
            host = f"{subdomain}.{FALLBACK_DOMAIN}"
            if allowed_hosts is not None and host not in allowed_hosts:
                continue
            candidates.append(
                Candidate(f"https://{host}/{ref.path}", host, "fallback")
            )
    return candidates


def _probe_many(
    candidates: list[Candidate],
    client: HttpClient,
    concurrency: int,
    timeout: float,
) -> dict[str, Probe]:
    """Probe every candidate concurrently, keyed by URL."""
    unique = {candidate.url: candidate for candidate in candidates}
    results: dict[str, Probe] = {}
    if not unique:
        return results

    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        probes = list(
            pool.map(lambda url: client.probe(url, timeout=timeout), unique.keys())
        )
    for url, probe in zip(unique.keys(), probes):
        results[url] = probe
    return results


def resolve_streams(
    refs: list[StreamRef],
    pool: list[str],
    client: HttpClient,
    *,
    extra_hosts: tuple[str, ...] = (),
    include_fallback: bool = False,
    concurrency: int = 16,
    timeout: float = 10.0,
    canary_timeout: float = 4.0,
) -> list[ResolvedStream]:
    """Resolve every descriptor to a live URL, if one exists.

    Probing runs in phases: the pinned subdomain first, then the other known
    subdomains, then the site's fallback host. A descriptor stops advancing as
    soon as one of its candidates answers with a playlist.
    """
    probes: dict[str, list[Probe]] = defaultdict(list)
    candidate_urls: dict[str, list[str]] = defaultdict(list)

    all_hosts: set[str] = set()
    for ref in refs:
        if ref.hardcoded:
            continue
        _, hosts = _host_variants(ref, pool, extra_hosts, include_fallback)
        all_hosts |= hosts
    canary_path = next((r.path for r in refs if r.path), "")
    reachable = HostHealth(
        client, timeout, canary_timeout, canary_path=canary_path
    ).reachable(all_hosts)

    pending = list(refs)
    for phase in ("declared", "alternate", "fallback"):
        if not pending:
            break
        pairs: list[tuple[StreamRef, Candidate]] = []
        for ref in pending:
            if phase == "declared":
                cands = build_candidates(ref, pool, allowed_hosts=reachable)
            elif phase == "alternate":
                cands = build_candidates(
                    ref, pool, extra_hosts=extra_hosts, allowed_hosts=reachable
                )
            else:
                cands = build_candidates(
                    ref, [],
                    extra_hosts=extra_hosts,
                    include_fallback=True,
                    allowed_hosts=reachable,
                )
            pairs.extend((ref, cand) for cand in cands)
        if not pairs:
            continue

        results = _probe_many([cand for _, cand in pairs], client, concurrency, timeout)
        for ref, candidate in pairs:
            probe = results.get(candidate.url)
            if probe is None:
                continue
            probes[ref.key].append(probe)
            candidate_urls[ref.key].append(candidate.url)

        pending = [ref for ref in pending if not any(p.ok for p in probes[ref.key])]

    resolved: list[ResolvedStream] = []
    seen_urls: set[str] = set()
    seen_dead_paths: set[str] = set()
    for ref in refs:
        found = probes.get(ref.key, [])
        best = pick_best(found)
        live_url = best.url if best and best.ok else None
        if live_url is not None:
            if live_url in seen_urls:
                continue
            seen_urls.add(live_url)
        elif ref.path and ref.path in seen_dead_paths:
            continue
        elif ref.path:
            seen_dead_paths.add(ref.path)
        resolved.append(
            ResolvedStream(
                ref=ref,
                probe=best or Probe("", ProbeState.ERROR, error="no candidates"),
                candidates=candidate_urls.get(ref.key, []),
                live_url=live_url,
            )
        )
    return resolved


def pick_best(probes: list[Probe]) -> Probe | None:
    """Choose the most useful probe result: live beats idle beats dead."""
    if not probes:
        return None
    return min(
        probes,
        key=lambda p: (_STATE_RANK.get(p.state, 9), p.elapsed_ms),
    )


def probe_matrix(
    paths: list[str],
    hosts: list[str],
    client: HttpClient,
    *,
    concurrency: int = 16,
    timeout: float = 10.0,
    canary_timeout: float = 4.0,
) -> dict[str, dict[str, Probe]]:
    """Probe every ``path`` against every ``host`` to build a coverage matrix.

    Hosts that do not answer at all are recorded as ``host-down`` rather than
    being probed once per path.
    """
    canary_path = paths[0] if paths else ""
    health = HostHealth(client, timeout, canary_timeout, canary_path=canary_path)
    reachable = health.reachable(set(hosts))

    results: dict[str, dict[str, Probe]] = defaultdict(dict)
    pairs: list[tuple[str, str, str]] = []
    for path in paths:
        for host in hosts:
            if host in reachable:
                pairs.append((path, host, f"https://{host}/{path}"))

    unique = {url: (path, host) for path, host, url in pairs}
    if unique:
        with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
            probes = list(
                pool.map(lambda url: client.probe(url, timeout=timeout), unique.keys())
            )
        for url, probe in zip(unique.keys(), probes):
            path, host = unique[url]
            results[path][host] = probe

    for path in paths:
        for host in hosts:
            if host not in reachable:
                results[path][host] = Probe(
                    f"https://{host}/{path}",
                    ProbeState.HOST_DOWN,
                    error="host unreachable",
                )
    return dict(results)
