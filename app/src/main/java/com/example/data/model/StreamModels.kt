package com.example.data.model

import com.example.BuildConfig

object SportDisplayFormatter {
    private val SOCCER_WORD_REGEX = Regex("""\bsoccer\b""", RegexOption.IGNORE_CASE)

    private val KNOWN_TITLES = mapOf(
        "soccer" to "Football",
        "football" to "Football",
        "nfl" to "NFL",
        "ncaa" to "College Sports",
        "ncaaf" to "College Football",
        "ncaab" to "College Basketball",
        "nba" to "NBA",
        "wnba" to "WNBA",
        "nhl" to "NHL",
        "mlb" to "MLB",
        "ufc" to "UFC",
        "mma" to "MMA",
        "aew" to "AEW",
        "wwe" to "WWE",
        "boxing" to "Boxing",
        "fighting" to "Fighting",
        "motorsports" to "Motorsports",
        "f1" to "Formula 1",
        "tennis" to "Tennis",
        "golf" to "Golf",
        "darts" to "Darts",
        "cricket" to "Cricket",
        "rugby" to "Rugby"
    )

    fun formatSportName(raw: String?): String {
        if (raw.isNullOrBlank()) return "Live TV"
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        KNOWN_TITLES[lower]?.let { return it }
        if (lower.startsWith("ppv-streams")) {
            val num = lower.substringAfterLast('-', "").takeIf { it.all(Char::isDigit) }
            return if (!num.isNullOrEmpty()) "Special Event $num" else "Special Events"
        }
        // Replace any occurrence of "Soccer" with "Football"
        val normalized = SOCCER_WORD_REGEX.replace(trimmed) { "Football" }
        if (normalized != trimmed) return normalized

        return trimmed.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                KNOWN_TITLES[part.lowercase()] ?: part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
            }
    }

    fun cleanConsumerTitle(label: String?, group: String?, sport: String?, path: String?): String {
        val sportLabel = formatSportName(sport)
        val cleanGroup = group?.trim()
            ?.let { SOCCER_WORD_REGEX.replace(it, "Football") }
            ?.takeIf { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }

        val cleanLabel = label?.trim()
            ?.removePrefix("default:")
            ?.let { SOCCER_WORD_REGEX.replace(it, "Football") }
            ?.takeIf {
                it.isNotEmpty() &&
                    !it.endsWith(".m3u8", ignoreCase = true) &&
                    !it.endsWith(".mpd", ignoreCase = true) &&
                    !it.startsWith("http://", ignoreCase = true) &&
                    !it.startsWith("https://", ignoreCase = true)
            }

        return when {
            cleanGroup != null && cleanLabel != null && !cleanGroup.equals(cleanLabel, ignoreCase = true) ->
                "$cleanGroup • $cleanLabel"
            cleanGroup != null -> cleanGroup
            cleanLabel != null -> cleanLabel
            !path.isNullOrBlank() -> {
                val base = path.substringBeforeLast('.')
                    .replace(Regex("""[-_]+"""), " ")
                    .trim()
                val trailingDigits = Regex("""(\d+)$""").find(base)?.groupValues?.get(1)
                if (trailingDigits != null) {
                    "$sportLabel Channel $trailingDigits"
                } else {
                    "$sportLabel Live Broadcast"
                }
            }
            else -> "$sportLabel Live Broadcast"
        }
    }
}

enum class ProbeState(val wireValue: String, val rank: Int) {
    LIVE("live", 0),
    IDLE("idle", 1),
    FORBIDDEN("forbidden", 2),
    DEAD("dead", 3),
    HOST_DOWN("host-down", 4),
    ERROR("error", 5);

    val isPlayable: Boolean
        get() = this == LIVE || this == IDLE

    companion object {
        fun fromWire(value: String): ProbeState =
            entries.firstOrNull { it.wireValue == value } ?: ERROR
    }
}

enum class EventStatus(val wireValue: String) {
    LIVE("live"),
    UPCOMING("upcoming"),
    ENDED("ended"),
    UNKNOWN("unknown")
}

data class Category(
    val slug: String,
    val title: String,
    val route: String,
    val kind: String = "nav"
) {
    val isMultiview: Boolean
        get() = slug == "multiview"

    val displayTitle: String
        get() = SportDisplayFormatter.formatSportName(
            if (slug.equals("soccer", ignoreCase = true)) "soccer" else title
        )
}

data class StreamEvent(
    val label: String,
    val route: String,
    val category: String,
    val startTimeText: String? = null,
    val startUtcMillis: Long? = null,
    val status: EventStatus = EventStatus.UNKNOWN,
    val viewers: Int = 0,
    val sources: List<String> = emptyList()
) {
    val displayCategory: String
        get() = SportDisplayFormatter.formatSportName(category)

    val displayLabel: String
        get() = SportDisplayFormatter.cleanConsumerTitle(label, null, category, null)
}

data class StreamRef(
    val label: String,
    val subdomain: String? = null,
    val path: String? = null,
    val poolFile: String? = null,
    val hardcoded: String? = null,
    val sport: String? = null,
    val group: String? = null,
    val customHeaders: Map<String, String> = emptyMap()
) {
    val isHardcoded: Boolean
        get() = !hardcoded.isNullOrBlank()

    val key: String
        get() = when {
            !hardcoded.isNullOrBlank() -> hardcoded
            !subdomain.isNullOrBlank() -> "$subdomain/${path.orEmpty()}"
            else -> path.orEmpty()
        }

    val displayTitle: String
        get() = SportDisplayFormatter.cleanConsumerTitle(label, group, sport, path)

    val displaySport: String
        get() = SportDisplayFormatter.formatSportName(sport)
}

data class CandidateUrl(
    val url: String,
    val host: String,
    val origin: String
)

data class ProbeResult(
    val url: String,
    val state: ProbeState,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val sizeBytes: Int = 0,
    val elapsedMs: Long = 0L,
    val error: String? = null
) {
    val ok: Boolean
        get() = state.isPlayable
}

data class ResolvedStream(
    val ref: StreamRef,
    val probe: ProbeResult,
    val candidates: List<String> = emptyList(),
    val liveUrl: String? = null,
    val viewers: Int = 0
) {
    val state: ProbeState
        get() = probe.state
}

data class MonitorRecord(
    val id: Int,
    val name: String,
    val group: String,
    val type: String,
    val up: Boolean?,
    val pingMs: Int?,
    val lastCheck: String?,
    val message: String,
    val samples: Int
)

data class SiteStatus(
    val title: String = "",
    val description: String = "",
    val monitors: List<MonitorRecord> = emptyList(),
    val incidentCount: Int = 0,
    val maintenanceCount: Int = 0,
    val error: String? = null
) {
    val allUp: Boolean
        get() = monitors.isNotEmpty() && monitors.all { it.up == true }
}

data class Catalog(
    val categories: List<Category> = emptyList(),
    val events: List<StreamEvent> = emptyList(),
    val streams: List<StreamRef> = emptyList(),
    val pool: List<String> = emptyList(),
    val poolFile: String = "domainsz77.txt",
    val routes: Set<String> = emptySet(),
    val eventPaths: Map<String, List<String>> = emptyMap(),
    val activeMirror: String = ""
)

data class StateTransition(
    val observedAtIso: String,
    val path: String,
    val fromState: String,
    val toState: String,
    val url: String?
)

data class SiteStateSnapshot(
    val observedAtIso: String = "",
    val pool: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val subdomains: List<String> = emptyList(),
    val mirrors: List<String> = emptyList()
)

data class WhitelabelConfig(
    val brandName: String = "PrismCast",
    val brandTagline: String = "Live Sports & Broadcast Television",
    val monogram: String = "PC",
    val paletteKey: String = "signal_mint",
    val darkTheme: Boolean = true,
    val showPlayerWatermark: Boolean = false,
    val autoFailover: Boolean = true,
    val customUserAgent: String = DEFAULT_BROWSER_USER_AGENT,
    val mirrors: List<String> = listOf("https://roxiestreams.info", "https://roxiestreams.su"),
    val statusBaseUrl: String = "https://roxiestreams.cc",
    val extraSubdomains: List<String> = listOf("admin2", "admin", "tedesco", "temp"),
    val fallbackDomain: String = "shadow-ran.online",
    val includeFallback: Boolean = true,
    val probeTimeoutSec: Int = 7,
    val concurrency: Int = 14
) {
    val allMirrorHosts: Set<String>
        get() {
            val result = mutableSetOf<String>()
            mirrors.forEach { raw ->
                val trimmed = raw.trim().trimEnd('/')
                if (trimmed.isNotEmpty()) {
                    result.add(trimmed.lowercase())
                    val hostOnly = trimmed.substringAfter("://").substringBefore('/')
                    if (hostOnly.isNotEmpty()) result.add(hostOnly.lowercase())
                }
            }
            return result
        }

    companion object {
        const val DEFAULT_BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        fun fromEnvBuildConfig(): WhitelabelConfig {
            val brand = BuildConfig.BRAND_NAME.trim().ifEmpty { "PrismCast" }
            val tagline = BuildConfig.BRAND_TAGLINE.trim()
                .ifEmpty { "Live Sports & Broadcast Television" }
            val monogram = BuildConfig.BRAND_MONOGRAM.trim().take(3).uppercase()
                .ifEmpty { "PC" }
            val palette = BuildConfig.BRAND_PALETTE.trim().lowercase()
                .ifEmpty { "signal_mint" }
            val dark = BuildConfig.BRAND_DARK_THEME.trim()
                .lowercase().let { it.isEmpty() || it == "true" || it == "1" }
            val watermark = BuildConfig.SHOW_PLAYER_WATERMARK.trim()
                .lowercase().let { it == "true" || it == "1" }
            val failover = BuildConfig.AUTO_FAILOVER.trim()
                .lowercase().let { it.isEmpty() || it == "true" || it == "1" }
            val mirrors = BuildConfig.STREAM_MIRRORS.split(',')
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .ifEmpty { listOf("https://roxiestreams.info", "https://roxiestreams.su") }
            val statusUrl = BuildConfig.STATUS_BASE_URL.trim().trimEnd('/')
                .ifEmpty { "https://roxiestreams.cc" }
            val subs = BuildConfig.RESOLVER_SUBDOMAINS.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("admin2", "admin", "tedesco", "temp") }
            val fallback = BuildConfig.FALLBACK_DOMAIN.trim()
                .ifEmpty { "shadow-ran.online" }
            val incFallback = BuildConfig.INCLUDE_FALLBACK.trim()
                .lowercase().let { it.isEmpty() || it == "true" || it == "1" }
            val timeout = BuildConfig.PROBE_TIMEOUT_SEC.trim().toIntOrNull()
                ?.coerceIn(3, 20) ?: 7
            val workers = BuildConfig.RESOLVER_CONCURRENCY.trim().toIntOrNull()
                ?.coerceIn(4, 28) ?: 14

            return WhitelabelConfig(
                brandName = brand,
                brandTagline = tagline,
                monogram = monogram,
                paletteKey = palette,
                darkTheme = dark,
                showPlayerWatermark = watermark,
                autoFailover = failover,
                mirrors = mirrors,
                statusBaseUrl = statusUrl,
                extraSubdomains = subs,
                fallbackDomain = fallback,
                includeFallback = incFallback,
                probeTimeoutSec = timeout,
                concurrency = workers
            )
        }
    }
}

data class ActivePlayableFeed(
    val id: String,
    val title: String,
    val subtitle: String,
    val primaryUrl: String,
    val candidateUrls: List<String> = listOf(primaryUrl),
    val currentCandidateIndex: Int = 0,
    val category: String = "Live TV",
    val viewers: Int = 0,
    val eventRoute: String? = null,
    val streamPath: String? = null,
    val subdomain: String? = null,
    val retryNonce: Int = 0,
    val customHeaders: Map<String, String> = emptyMap()
) {
    val activeUrl: String
        get() = candidateUrls.getOrElse(currentCandidateIndex) { primaryUrl }
}

data class LastPlaybackSession(
    val mode: String, // "SINGLE" or "MULTIVIEW"
    val activeFeed: ActivePlayableFeed? = null,
    val multiViewSlots: List<ActivePlayableFeed?> = listOf(null, null, null, null),
    val focusedMultiViewSlot: Int = 0,
    val isQuadMode: Boolean = true,
    val selectedCategorySlug: String? = null
)
