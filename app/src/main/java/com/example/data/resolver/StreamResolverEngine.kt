package com.example.data.resolver

import com.example.data.model.CandidateUrl
import com.example.data.model.Catalog
import com.example.data.model.Category
import com.example.data.model.EventStatus
import com.example.data.model.MonitorRecord
import com.example.data.model.ProbeResult
import com.example.data.model.ProbeState
import com.example.data.model.ResolvedStream
import com.example.data.model.SiteStatus
import com.example.data.model.StreamEvent
import com.example.data.model.StreamRef
import com.example.data.network.MirrorHttpClient
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class StreamResolverEngine {

    suspend fun enumerateCatalog(
        client: MirrorHttpClient,
        mirrorHosts: Set<String>,
        includeMultiview: Boolean = true,
        concurrency: Int = 12,
        onProgress: (String) -> Unit = {}
    ): Catalog = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(concurrency.coerceIn(2, 24))
        onProgress("Connecting to mirror ${client.preferredMirror}…")

        val homePair = client.fetchRoute("/")
            ?: throw IllegalStateException(
                "Mirrors unreachable (${client.mirrors.joinToString(", ")})"
            )
        val (_, homeHtml) = homePair
        val navCategories = SiteParser.parseNavCategories(homeHtml, mirrorHosts)
        val navSlugs = navCategories.map { it.slug }.toSet()
        val navRoutes = navCategories.map { it.route }.toSet()

        val featuredEvents = SiteParser.parseEventRows(homeHtml).mapNotNull { row ->
            val route = SiteParser.routeFromHref(row.href, mirrorHosts) ?: return@mapNotNull null
            val startUtc = SiteParser.parseStartTimeUtcMillis(row.startTimeText)
            StreamEvent(
                label = row.label,
                route = route,
                category = slugOf(route),
                startTimeText = row.startTimeText,
                startUtcMillis = startUtc,
                status = classifyEventStatus(startUtc),
                sources = listOf("featured")
            )
        }

        val pageCategories = LinkedHashMap<String, Category>()
        for (event in featuredEvents) {
            if (event.category !in navSlugs && !pageCategories.containsKey(event.category)) {
                pageCategories[event.category] = Category(
                    slug = event.category,
                    title = titleForSlug(event.category),
                    route = event.route,
                    kind = "page"
                )
            }
        }
        val allCategories = navCategories + pageCategories.values

        onProgress("Scanning ${navCategories.size} sport schedules…")
        val categoryEvents = coroutineScope {
            navCategories.filter { it.kind == "nav" }.map { category ->
                async {
                    semaphore.withPermit {
                        val fetched = client.fetchRoute(category.route) ?: return@withPermit emptyList()
                        val seenLocal = mutableSetOf<Triple<String, String, String>>()
                        SiteParser.parseEventRows(fetched.second).mapNotNull { row ->
                            val route = SiteParser.routeFromHref(row.href, mirrorHosts).orEmpty()
                            val key = Triple(category.slug, row.label, route)
                            if (!seenLocal.add(key)) return@mapNotNull null
                            val startUtc = SiteParser.parseStartTimeUtcMillis(row.startTimeText)
                            StreamEvent(
                                label = row.label,
                                route = route,
                                category = category.slug,
                                startTimeText = row.startTimeText,
                                startUtcMillis = startUtc,
                                status = classifyEventStatus(startUtc),
                                sources = listOf("category")
                            )
                        }
                    }
                }
            }.awaitAll().flatten()
        }

        val mergedEvents = mergeEvents(featuredEvents, categoryEvents)
        val uniqueEventRoutes = mergedEvents
            .filter { it.route.isNotBlank() && it.route !in navRoutes }
            .distinctBy { it.route }

        onProgress("Inspecting ${uniqueEventRoutes.size} event pages & multiview inventory…")
        val eventPaths = ConcurrentHashMap<String, List<String>>()

        val (multiviewRefs, eventStreamRefs) = coroutineScope {
            val mvDeferred = async {
                if (!includeMultiview) emptyList()
                else semaphore.withPermit {
                    val mv = client.fetchRoute("/multiview")
                    if (mv != null) SiteParser.dedupeRefs(SiteParser.parseMultiviewStreams(mv.second))
                    else emptyList()
                }
            }

            val evDeferred = uniqueEventRoutes.map { event ->
                async {
                    semaphore.withPermit {
                        val page = client.fetchRoute(event.route) ?: return@withPermit emptyList()
                        val buttons = SiteParser.parseStreamButtons(page.second)
                            .ifEmpty { SiteParser.parseDefaultStreams(page.second) }
                        val paths = buttons.mapNotNull { it.path }
                        if (paths.isNotEmpty()) {
                            eventPaths[event.route] = paths
                        }
                        buttons.map { ref ->
                            ref.copy(
                                sport = ref.sport ?: event.category,
                                group = ref.group ?: event.label
                            )
                        }
                    }
                }
            }

            mvDeferred.await() to SiteParser.dedupeRefs(evDeferred.awaitAll().flatten())
        }

        val allStreams = SiteParser.dedupeRefs(multiviewRefs + eventStreamRefs)
        val poolFile = allStreams.firstNotNullOfOrNull { it.poolFile } ?: "domainsz77.txt"

        onProgress("Fetching active domain rotation pool ($poolFile)…")
        val pool = fetchPool(client, poolFile)

        val discoveredRoutes = mutableSetOf("/")
        discoveredRoutes.addAll(SiteParser.parseInternalRoutes(homeHtml, mirrorHosts))
        allCategories.forEach { discoveredRoutes.add(it.route) }
        mergedEvents.forEach { if (it.route.isNotBlank()) discoveredRoutes.add(it.route) }

        Catalog(
            categories = allCategories,
            events = mergedEvents,
            streams = allStreams,
            pool = pool,
            poolFile = poolFile,
            routes = discoveredRoutes,
            eventPaths = eventPaths.toMap(),
            activeMirror = client.preferredMirror
        )
    }

    fun fetchPool(client: MirrorHttpClient, poolFile: String = "domainsz77.txt"): List<String> {
        val cleanFile = poolFile.trimStart('/')
        for (mirror in client.mirrorOrder()) {
            val text = client.fetchAbsoluteWithMirrorFailover("${mirror.trimEnd('/')}/$cleanFile")
                ?: continue
            val domains = SiteParser.parsePoolDomains(text)
            if (domains.isNotEmpty()) {
                client.prefer(mirror)
                return domains
            }
        }
        return emptyList()
    }

    suspend fun collectViewers(
        client: MirrorHttpClient,
        events: List<StreamEvent>,
        clientId: String,
        concurrency: Int = 12
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val pages = events.mapNotNull { e ->
            e.route.trim('/').takeIf { it.isNotEmpty() }
        }.distinct()
        if (pages.isEmpty()) return@withContext emptyMap()

        val mirror = client.preferredMirror.trimEnd('/')
        val semaphore = Semaphore(concurrency.coerceIn(2, 20))

        val pairs = coroutineScope {
            pages.map { page ->
                async {
                    semaphore.withPermit {
                        val encodedPage = URLEncoder.encode(page, "UTF-8")
                        val encodedId = URLEncoder.encode(clientId, "UTF-8")
                        val baseUrl = "$mirror/api/viewers?page=$encodedPage&id=$encodedId"
                        val text = client.getText(baseUrl, timeoutSec = 6L)
                        // Release viewer slot immediately after reading sweep count
                        client.getText("$baseUrl&leave=1", timeoutSec = 4L)
                        val count = try {
                            if (text != null) {
                                val json = JSONObject(text)
                                if (json.has("count")) json.optInt("count", 0) else null
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                        if (count != null) page to count else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        pairs.toMap()
    }

    suspend fun pingViewerPresence(
        client: MirrorHttpClient,
        eventRoute: String,
        clientId: String,
        leave: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val page = eventRoute.trim('/').takeIf { it.isNotBlank() } ?: return@withContext
        val mirror = client.preferredMirror.trimEnd('/')
        val encodedPage = URLEncoder.encode(page, "UTF-8")
        val encodedId = URLEncoder.encode(clientId, "UTF-8")
        val suffix = if (leave) "&leave=1" else ""
        client.getText("$mirror/api/viewers?page=$encodedPage&id=$encodedId$suffix", timeoutSec = 5L)
    }

    // --------------------------------------------------------- Multi-Phase Stream Resolution

    fun buildCandidates(
        ref: StreamRef,
        pool: List<String>,
        extraHosts: List<String> = emptyList(),
        fallbackDomain: String = "shadow-ran.online",
        includeFallback: Boolean = false,
        allowedHosts: Set<String>? = null
    ): List<CandidateUrl> {
        if (!ref.hardcoded.isNullOrBlank()) {
            return listOf(CandidateUrl(ref.hardcoded, hostOf(ref.hardcoded), "hardcoded"))
        }
        val path = ref.path?.trimStart('/') ?: return emptyList()
        val subdomains = buildList {
            if (!ref.subdomain.isNullOrBlank()) add(ref.subdomain)
            for (extra in extraHosts) {
                if (extra.isNotBlank() && extra !in this) add(extra)
            }
        }
        if (subdomains.isEmpty()) return emptyList()

        val candidates = mutableListOf<CandidateUrl>()
        for (sub in subdomains) {
            for (domain in pool) {
                val host = "$sub.$domain"
                if (allowedHosts != null && host !in allowedHosts) continue
                val origin = if (sub == ref.subdomain) "declared" else "alternate"
                candidates.add(CandidateUrl("https://$host/$path", host, origin))
            }
        }
        if (includeFallback && fallbackDomain.isNotBlank()) {
            for (sub in subdomains) {
                val host = "$sub.$fallbackDomain"
                if (allowedHosts != null && host !in allowedHosts) continue
                candidates.add(CandidateUrl("https://$host/$path", host, "fallback"))
            }
        }
        return candidates
    }

    suspend fun resolveStreams(
        refs: List<StreamRef>,
        pool: List<String>,
        client: MirrorHttpClient,
        extraHosts: List<String> = listOf("admin2", "admin", "tedesco", "temp"),
        fallbackDomain: String = "shadow-ran.online",
        includeFallback: Boolean = true,
        concurrency: Int = 14,
        probeTimeoutSec: Int = 7,
        canaryTimeoutSec: Int = 4,
        onProgress: (String) -> Unit = {}
    ): List<ResolvedStream> = withContext(Dispatchers.IO) {
        if (refs.isEmpty()) return@withContext emptyList()
        val semaphore = Semaphore(concurrency.coerceIn(2, 32))

        // 1. Collect all candidate hosts for fast canary reachability check
        val allHosts = mutableSetOf<String>()
        for (ref in refs) {
            if (ref.isHardcoded) continue
            val subs = buildList {
                if (!ref.subdomain.isNullOrBlank()) add(ref.subdomain)
                extraHosts.forEach { if (it.isNotBlank() && it !in this) add(it) }
            }
            for (sub in subs) {
                pool.forEach { domain -> allHosts.add("$sub.$domain") }
                if (includeFallback && fallbackDomain.isNotBlank()) {
                    allHosts.add("$sub.$fallbackDomain")
                }
            }
        }

        val canaryPath = refs.firstNotNullOfOrNull { it.path?.trimStart('/') }.orEmpty()
        val reachableHosts = if (allHosts.isEmpty()) {
            emptySet()
        } else {
            onProgress("Checking ${allHosts.size} edge hosts via canary probe…")
            coroutineScope {
                allHosts.sorted().map { host ->
                    async {
                        semaphore.withPermit {
                            val target = if (canaryPath.isNotEmpty()) {
                                "https://$host/$canaryPath"
                            } else {
                                "https://$host/"
                            }
                            val probe = client.probe(target, timeoutSec = canaryTimeoutSec)
                            val up = probe.state != ProbeState.HOST_DOWN &&
                                probe.state != ProbeState.ERROR
                            if (up) host else null
                        }
                    }
                }.awaitAll().filterNotNull().toSet()
            }
        }

        val probesByKey = ConcurrentHashMap<String, MutableList<ProbeResult>>()
        val candidateUrlsByKey = ConcurrentHashMap<String, MutableList<String>>()
        var pending = refs.toList()

        val phases = listOf("declared", "alternate", "fallback")
        for (phase in phases) {
            if (pending.isEmpty()) break
            val pairs = mutableListOf<Pair<StreamRef, CandidateUrl>>()
            for (ref in pending) {
                val cands = when (phase) {
                    "declared" -> buildCandidates(
                        ref = ref,
                        pool = pool,
                        allowedHosts = if (ref.isHardcoded) null else reachableHosts
                    )
                    "alternate" -> buildCandidates(
                        ref = ref,
                        pool = pool,
                        extraHosts = extraHosts,
                        allowedHosts = if (ref.isHardcoded) null else reachableHosts
                    )
                    else -> buildCandidates(
                        ref = ref,
                        pool = emptyList(),
                        extraHosts = extraHosts,
                        fallbackDomain = fallbackDomain,
                        includeFallback = includeFallback,
                        allowedHosts = if (ref.isHardcoded) null else reachableHosts
                    )
                }
                for (cand in cands) {
                    val alreadyTested = candidateUrlsByKey[ref.key]?.contains(cand.url) == true
                    if (!alreadyTested) {
                        pairs.add(ref to cand)
                    }
                }
            }
            if (pairs.isEmpty()) continue

            val uniqueUrls = pairs.map { it.second }.distinctBy { it.url }
            onProgress("Probing ${uniqueUrls.size} $phase stream candidates…")

            val resultMap = coroutineScope {
                uniqueUrls.map { cand ->
                    async {
                        semaphore.withPermit {
                            val refHeaders = pairs.firstOrNull { it.second.url == cand.url }
                                ?.first?.customHeaders.orEmpty()
                            cand.url to client.probe(
                                cand.url,
                                timeoutSec = probeTimeoutSec,
                                headers = refHeaders
                            )
                        }
                    }
                }.awaitAll().toMap()
            }

            for ((ref, cand) in pairs) {
                val probe = resultMap[cand.url] ?: continue
                probesByKey.getOrPut(ref.key) { mutableListOf() }.add(probe)
                val urlList = candidateUrlsByKey.getOrPut(ref.key) { mutableListOf() }
                if (cand.url !in urlList) urlList.add(cand.url)
            }

            pending = pending.filter { ref ->
                probesByKey[ref.key]?.none { it.ok } ?: true
            }
        }

        val resolved = mutableListOf<ResolvedStream>()
        val seenLiveUrls = mutableSetOf<String>()
        val seenDeadPaths = mutableSetOf<String>()

        for (ref in refs) {
            val found = probesByKey[ref.key].orEmpty()
            val best = pickBest(found)
            val liveUrl = if (best != null && best.ok) best.url else null

            if (liveUrl != null) {
                if (!seenLiveUrls.add(liveUrl)) continue
            } else if (!ref.path.isNullOrBlank()) {
                if (!seenDeadPaths.add(ref.path)) continue
            }

            // Order verified playable candidates first for instant player failover
            val orderedPlayableCandidates = found
                .filter { it.ok }
                .sortedWith(compareBy({ it.state.rank }, { it.elapsedMs }))
                .map { it.url }
                .ifEmpty { candidateUrlsByKey[ref.key].orEmpty() }

            resolved.add(
                ResolvedStream(
                    ref = ref,
                    probe = best ?: ProbeResult(
                        url = "",
                        state = ProbeState.ERROR,
                        error = "No reachable host candidates"
                    ),
                    candidates = orderedPlayableCandidates,
                    liveUrl = liveUrl
                )
            )
        }
        resolved
    }

    suspend fun probeMatrix(
        paths: List<String>,
        hosts: List<String>,
        client: MirrorHttpClient,
        concurrency: Int = 14,
        probeTimeoutSec: Int = 7,
        canaryTimeoutSec: Int = 4
    ): Map<String, Map<String, ProbeResult>> = withContext(Dispatchers.IO) {
        if (paths.isEmpty() || hosts.isEmpty()) return@withContext emptyMap()
        val semaphore = Semaphore(concurrency.coerceIn(2, 32))
        val canaryPath = paths.first().trimStart('/')

        val reachable = coroutineScope {
            hosts.distinct().map { host ->
                async {
                    semaphore.withPermit {
                        val probe = client.probe("https://$host/$canaryPath", canaryTimeoutSec)
                        val up = probe.state != ProbeState.HOST_DOWN &&
                            probe.state != ProbeState.ERROR
                        if (up) host else null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }

        val results = mutableMapOf<String, MutableMap<String, ProbeResult>>()
        val activePairs = mutableListOf<Triple<String, String, String>>()
        for (path in paths) {
            val cleanPath = path.trimStart('/')
            for (host in hosts) {
                if (host in reachable) {
                    activePairs.add(Triple(path, host, "https://$host/$cleanPath"))
                } else {
                    results.getOrPut(path) { mutableMapOf() }[host] = ProbeResult(
                        url = "https://$host/$cleanPath",
                        state = ProbeState.HOST_DOWN,
                        error = "host unreachable"
                    )
                }
            }
        }

        val probed = coroutineScope {
            activePairs.map { (path, host, url) ->
                async {
                    semaphore.withPermit {
                        Triple(path, host, client.probe(url, probeTimeoutSec))
                    }
                }
            }.awaitAll()
        }

        for ((path, host, probe) in probed) {
            results.getOrPut(path) { mutableMapOf() }[host] = probe
        }
        results
    }

    // --------------------------------------------------------- Uptime Kuma Status Reader

    suspend fun fetchSiteStatus(
        client: MirrorHttpClient,
        baseUrl: String = "https://roxiestreams.cc",
        slug: String = "default"
    ): SiteStatus = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        val configText = client.getText("$base/api/status-page/$slug", timeoutSec = 8L)
            ?: return@withContext SiteStatus(error = "Status endpoint unreachable ($base)")

        return@withContext try {
            val root = JSONObject(configText)
            val cfg = root.optJSONObject("config")
            val title = cfg?.optString("title").orEmpty()
            val description = cfg?.optString("description").orEmpty()
            val incidentCount = when (val inc = root.opt("incident")) {
                is JSONArray -> inc.length()
                is JSONObject -> 1
                else -> 0
            }
            val maintenanceCount = root.optJSONArray("maintenanceList")?.length() ?: 0

            val hbText = client.getText("$base/api/status-page/heartbeat/$slug", timeoutSec = 8L)
            val heartbeats = if (hbText != null) {
                try {
                    JSONObject(hbText).optJSONObject("heartbeatList") ?: JSONObject()
                } catch (_: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            val monitors = mutableListOf<MonitorRecord>()
            val groups = root.optJSONArray("publicGroupList") ?: JSONArray()
            for (g in 0 until groups.length()) {
                val groupObj = groups.optJSONObject(g) ?: continue
                val groupName = groupObj.optString("name", "Core")
                val monitorList = groupObj.optJSONArray("monitorList") ?: continue
                for (m in 0 until monitorList.length()) {
                    val mon = monitorList.optJSONObject(m) ?: continue
                    val id = mon.optInt("id", m)
                    val points = heartbeats.optJSONArray(id.toString())
                    val latest = if (points != null && points.length() > 0) {
                        points.optJSONObject(points.length() - 1)
                    } else null
                    val up = if (latest != null && latest.has("status")) {
                        latest.optInt("status", 0) == 1
                    } else null
                    val ping = if (latest != null && !latest.isNull("ping")) {
                        latest.optInt("ping")
                    } else null

                    monitors.add(
                        MonitorRecord(
                            id = id,
                            name = mon.optString("name", "Monitor $id"),
                            group = groupName,
                            type = mon.optString("type", "http"),
                            up = up,
                            pingMs = ping,
                            lastCheck = latest?.optString("time")?.takeIf { it.isNotBlank() },
                            message = latest?.optString("msg").orEmpty(),
                            samples = points?.length() ?: 0
                        )
                    )
                }
            }

            SiteStatus(
                title = title,
                description = description,
                monitors = monitors,
                incidentCount = incidentCount,
                maintenanceCount = maintenanceCount
            )
        } catch (e: Exception) {
            SiteStatus(error = "Invalid status JSON: ${e.message}")
        }
    }

    fun pickBest(probes: List<ProbeResult>): ProbeResult? =
        probes.minWithOrNull(compareBy({ it.state.rank }, { it.elapsedMs }))

    fun classifyEventStatus(startUtcMillis: Long?, nowMillis: Long = System.currentTimeMillis()): EventStatus {
        if (startUtcMillis == null) return EventStatus.UNKNOWN
        return when {
            nowMillis < startUtcMillis -> EventStatus.UPCOMING
            nowMillis <= startUtcMillis + LIVE_WINDOW_MS -> EventStatus.LIVE
            else -> EventStatus.ENDED
        }
    }

    private fun mergeEvents(
        featured: List<StreamEvent>,
        fromCategories: List<StreamEvent>
    ): List<StreamEvent> {
        val scheduledRoutes = fromCategories.mapNotNull { it.route.takeIf(String::isNotBlank) }.toSet()
        val merged = fromCategories.toMutableList()
        val seen = fromCategories.map { it.label to it.route }.toMutableSet()
        for (event in featured) {
            if ((event.label to event.route) in seen) continue
            if (event.route.isNotBlank() && event.route in scheduledRoutes) continue
            merged.add(event)
            seen.add(event.label to event.route)
        }
        return merged
    }

    private fun slugOf(route: String): String {
        val stripped = route.trim('/')
        return if (stripped.isEmpty()) "index" else stripped.substringBefore('/')
    }

    private fun titleForSlug(slug: String): String =
        com.example.data.model.SportDisplayFormatter.formatSportName(slug)

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/')

    companion object {
        private const val LIVE_WINDOW_MS = 180L * 60L * 1000L
    }
}
