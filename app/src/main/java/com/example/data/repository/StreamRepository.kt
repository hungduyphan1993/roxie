package com.example.data.repository

import com.example.data.local.ObservationEntity
import com.example.data.local.PrismDao
import com.example.data.local.SavedStreamEntity
import com.example.data.local.SiteStateEntity
import com.example.data.local.WhitelabelPreferences
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.Catalog
import com.example.data.model.LastPlaybackSession
import com.example.data.model.ResolvedStream
import com.example.data.model.SiteStateSnapshot
import com.example.data.model.SiteStatus
import com.example.data.model.StateTransition
import com.example.data.model.StreamEvent
import com.example.data.model.StreamRef
import com.example.data.model.WhitelabelConfig
import com.example.data.network.MirrorHttpClient
import com.example.data.resolver.SiteParser
import com.example.data.resolver.StreamResolverEngine
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SweepReport(
    val catalog: Catalog,
    val resolvedStreams: List<ResolvedStream>,
    val siteStatus: SiteStatus,
    val rotationAlerts: List<String>,
    val elapsedMs: Long
)

class StreamRepository(
    private val dao: PrismDao,
    private val preferences: WhitelabelPreferences,
    private val engine: StreamResolverEngine = StreamResolverEngine()
) {
    val whitelabelConfig: WhitelabelConfig = preferences.envConfig
    val savedStreams: Flow<List<SavedStreamEntity>> = dao.observeSavedStreams()

    suspend fun runFullSweep(
        config: WhitelabelConfig = whitelabelConfig,
        onProgress: (String) -> Unit
    ): SweepReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val client = buildClient(config)
        val clientId = preferences.getOrCreateViewerClientId()

        onProgress("Updating live channel guide…")
        val rawCatalog = engine.enumerateCatalog(
            client = client,
            mirrorHosts = config.allMirrorHosts,
            includeMultiview = true,
            concurrency = config.concurrency,
            onProgress = {
                onProgress("Scanning live sports & events…")
            }
        )

        val viewerCounts = engine.collectViewers(
            client = client,
            events = rawCatalog.events,
            clientId = clientId,
            concurrency = config.concurrency
        )

        val enrichedEvents = rawCatalog.events.map { event ->
            val slugKey = event.route.trim('/')
            event.copy(viewers = viewerCounts[slugKey] ?: 0)
        }
        val catalog = rawCatalog.copy(events = enrichedEvents)

        onProgress("Verifying active HD broadcast feeds…")
        val resolvedRaw = engine.resolveStreams(
            refs = catalog.streams,
            pool = catalog.pool,
            client = client,
            extraHosts = config.extraSubdomains,
            fallbackDomain = config.fallbackDomain,
            includeFallback = config.includeFallback,
            concurrency = config.concurrency,
            probeTimeoutSec = config.probeTimeoutSec,
            onProgress = {
                onProgress("Optimizing live stream connections…")
            }
        )

        val bestViewersByPath = mutableMapOf<String, Int>()
        for (ev in catalog.events) {
            if (ev.route.isBlank()) continue
            for (path in catalog.eventPaths[ev.route].orEmpty()) {
                if (ev.viewers > (bestViewersByPath[path] ?: 0)) {
                    bestViewersByPath[path] = ev.viewers
                }
            }
        }
        val resolved = resolvedRaw.map { item ->
            val count = item.ref.path?.let { bestViewersByPath[it] ?: 0 } ?: 0
            item.copy(viewers = count)
        }

        val status = engine.fetchSiteStatus(client, baseUrl = config.statusBaseUrl)
        val rotationAlerts = recordSweepHistory(catalog, resolved, config)

        SweepReport(
            catalog = catalog,
            resolvedStreams = resolved,
            siteStatus = status,
            rotationAlerts = rotationAlerts,
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    suspend fun resolveSingleEventStreams(
        event: StreamEvent,
        knownPool: List<String>,
        config: WhitelabelConfig = whitelabelConfig
    ): List<ResolvedStream> = withContext(Dispatchers.IO) {
        val client = buildClient(config)
        val fetched = client.fetchRoute(event.route) ?: return@withContext emptyList()
        val buttons = SiteParser.parseStreamButtons(fetched.second)
            .ifEmpty { SiteParser.parseDefaultStreams(fetched.second) }
            .map { it.copy(sport = it.sport ?: event.category, group = it.group ?: event.label) }
        if (buttons.isEmpty()) return@withContext emptyList()

        val poolFile = buttons.firstNotNullOfOrNull { it.poolFile } ?: "domainsz77.txt"
        val pool = knownPool.ifEmpty { engine.fetchPool(client, poolFile) }
        engine.resolveStreams(
            refs = SiteParser.dedupeRefs(buttons),
            pool = pool,
            client = client,
            extraHosts = config.extraSubdomains,
            fallbackDomain = config.fallbackDomain,
            includeFallback = config.includeFallback,
            concurrency = config.concurrency,
            probeTimeoutSec = config.probeTimeoutSec
        ).map { it.copy(viewers = event.viewers) }
    }

    suspend fun pingWatchingEvent(
        eventRoute: String?,
        leave: Boolean,
        config: WhitelabelConfig = whitelabelConfig
    ) {
        if (eventRoute.isNullOrBlank()) return
        val clientId = preferences.getOrCreateViewerClientId()
        engine.pingViewerPresence(buildClient(config), eventRoute, clientId, leave = leave)
    }

    suspend fun addFavoriteStream(
        title: String,
        groupName: String,
        streamUrl: String,
        streamPath: String? = null,
        subdomain: String? = null
    ) {
        dao.insertSavedStream(
            SavedStreamEntity(
                title = title.trim().ifEmpty { "Live Channel" },
                groupName = groupName.trim().ifEmpty { "Live TV" },
                streamUrl = streamUrl.trim(),
                streamPath = streamPath,
                subdomain = subdomain
            )
        )
    }

    suspend fun deleteSavedStream(id: Int) {
        dao.deleteSavedStreamById(id)
    }

    suspend fun savePlaybackSession(session: LastPlaybackSession?) {
        preferences.savePlaybackSession(session)
    }

    suspend fun loadPlaybackSession(): LastPlaybackSession? {
        return preferences.loadPlaybackSession()
    }

    /**
     * Silently re-resolves fresh edge URLs for an active feed when upstream hosts rotate mid-broadcast.
     */
    suspend fun refreshFeedCandidates(
        feed: ActivePlayableFeed,
        knownPool: List<String>,
        config: WhitelabelConfig = whitelabelConfig
    ): List<String> = withContext(Dispatchers.IO) {
        val client = buildClient(config)
        val freshUrls = mutableListOf<String>()

        if (!feed.eventRoute.isNullOrBlank()) {
            val event = StreamEvent(
                category = feed.category,
                label = feed.title,
                route = feed.eventRoute
            )
            val resolved = resolveSingleEventStreams(event, emptyList(), config)
            resolved.forEach { item ->
                item.liveUrl?.let { freshUrls.add(it) }
                freshUrls.addAll(item.candidates)
            }
        }

        if (!feed.streamPath.isNullOrBlank()) {
            val freshPool = engine.fetchPool(client, "domainsz77.txt").ifEmpty { knownPool }
            if (freshPool.isNotEmpty()) {
                val ref = StreamRef(
                    label = feed.title,
                    subdomain = feed.subdomain ?: config.extraSubdomains.firstOrNull(),
                    path = feed.streamPath,
                    sport = feed.category,
                    customHeaders = feed.customHeaders
                )
                val resolved = engine.resolveStreams(
                    refs = listOf(ref),
                    pool = freshPool,
                    client = client,
                    extraHosts = config.extraSubdomains,
                    fallbackDomain = config.fallbackDomain,
                    includeFallback = config.includeFallback,
                    concurrency = config.concurrency,
                    probeTimeoutSec = config.probeTimeoutSec
                )
                resolved.forEach { item ->
                    item.liveUrl?.let { freshUrls.add(it) }
                    freshUrls.addAll(item.candidates)
                }
            }
        }

        freshUrls.distinct()
    }

    private suspend fun recordSweepHistory(
        catalog: Catalog,
        resolved: List<ResolvedStream>,
        config: WhitelabelConfig
    ): List<String> {
        val nowMs = System.currentTimeMillis()
        val iso = formatIsoUtc(nowMs)

        val hosts = catalog.streams.mapNotNull { it.subdomain }.flatMap { sub ->
            catalog.pool.map { domain -> "$sub.$domain" }
        }.distinct().sorted()
        val subdomains = catalog.streams.mapNotNull { it.subdomain }.distinct().sorted()

        val currentSnapshot = SiteStateSnapshot(
            observedAtIso = iso,
            pool = catalog.pool.sorted(),
            hosts = hosts,
            subdomains = subdomains,
            mirrors = config.mirrors.sorted()
        )
        val previousEntity = dao.getLatestSiteState()
        val diff = if (previousEntity != null) {
            val prev = decodeSiteState(previousEntity.observedAtIso, previousEntity.payloadJson)
            diffSiteStates(prev, currentSnapshot)
        } else {
            emptyList()
        }

        dao.insertSiteState(
            SiteStateEntity(
                observedAtMillis = nowMs,
                observedAtIso = iso,
                payloadJson = encodeSiteState(currentSnapshot)
            )
        )

        val observationRows = resolved.mapNotNull { item ->
            val pathKey = item.ref.path ?: item.ref.hardcoded ?: return@mapNotNull null
            val host = item.liveUrl?.let { extractHost(it) }
            ObservationEntity(
                observedAtMillis = nowMs,
                observedAtIso = iso,
                path = pathKey,
                host = host,
                url = item.liveUrl,
                state = item.probe.state.wireValue,
                viewers = item.viewers,
                latencyMs = item.probe.elapsedMs
            )
        }
        if (observationRows.isNotEmpty()) {
            dao.insertObservations(observationRows)
        }
        return diff
    }

    private fun buildClient(config: WhitelabelConfig): MirrorHttpClient =
        MirrorHttpClient(
            mirrors = config.mirrors,
            userAgent = config.customUserAgent
        )

    companion object {
        fun diffSiteStates(old: SiteStateSnapshot, new: SiteStateSnapshot): List<String> {
            val changes = mutableListOf<String>()
            fun compareField(label: String, before: List<String>, after: List<String>) {
                val bSet = before.toSet()
                val aSet = after.toSet()
                (aSet - bSet).sorted().forEach { changes.add("$label added: $it") }
                (bSet - aSet).sorted().forEach { changes.add("$label removed: $it") }
            }
            compareField("pool", old.pool, new.pool)
            compareField("hosts", old.hosts, new.hosts)
            compareField("subdomains", old.subdomains, new.subdomains)
            compareField("mirrors", old.mirrors, new.mirrors)
            return changes
        }

        fun computeTransitions(rows: List<ObservationEntity>): List<StateTransition> {
            val seen = mutableSetOf<Pair<String, String>>()
            val changes = mutableListOf<StateTransition>()
            val lastStateByPath = mutableMapOf<String, String>()
            for (row in rows) {
                val previous = lastStateByPath[row.path]
                if (previous != null && previous != row.state && seen.add(row.path to row.state)) {
                    changes.add(
                        StateTransition(
                            observedAtIso = row.observedAtIso,
                            path = row.path,
                            fromState = previous,
                            toState = row.state,
                            url = row.url
                        )
                    )
                }
                lastStateByPath[row.path] = row.state
            }
            return changes.reversed()
        }

        private fun encodeSiteState(state: SiteStateSnapshot): String {
            val obj = JSONObject()
            obj.put("pool", JSONArray(state.pool))
            obj.put("hosts", JSONArray(state.hosts))
            obj.put("subdomains", JSONArray(state.subdomains))
            obj.put("mirrors", JSONArray(state.mirrors))
            return obj.toString()
        }

        private fun decodeSiteState(observedAtIso: String, payload: String): SiteStateSnapshot {
            return try {
                val obj = JSONObject(payload)
                fun arr(key: String): List<String> {
                    val a = obj.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
                }
                SiteStateSnapshot(
                    observedAtIso = observedAtIso,
                    pool = arr("pool"),
                    hosts = arr("hosts"),
                    subdomains = arr("subdomains"),
                    mirrors = arr("mirrors")
                )
            } catch (_: Exception) {
                SiteStateSnapshot(observedAtIso = observedAtIso)
            }
        }

        private fun extractHost(url: String): String? = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }

        private fun formatIsoUtc(millis: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(millis))
        }
    }
}
