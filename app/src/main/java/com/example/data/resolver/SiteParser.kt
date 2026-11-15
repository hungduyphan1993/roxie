package com.example.data.resolver

import com.example.data.model.Category
import com.example.data.model.StreamRef
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.jsoup.Jsoup

object SiteParser {
    val SITE_TIME_ZONE: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")

    private val STATIC_SUFFIXES = listOf(
        ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp",
        ".txt", ".xml", ".json", ".mp4", ".woff", ".woff2"
    )

    private val TIME_PATTERNS = listOf(
        "MMM d, yyyy hh:mm a",
        "MMMM d, yyyy hh:mm a",
        "MMM d, yyyy hh:mma",
        "MMMM d, yyyy hh:mma",
        "MMM d, yyyy HH:mm",
        "MMMM d, yyyy HH:mm"
    )

    private val QUOTED_JS_KV = Regex("""(\w+)\s*:\s*['"]([^'"]*)['"]""")
    private val GET_RANDOM_STREAM = Regex(
        """getRandomStream\(\s*['"]([^'"]+)['"]\s*(?:,\s*['"]([^'"]*)['"]\s*)?\)"""
    )
    private val SHOW_PLAYER_URL = Regex(
        """showPlayer\(\s*['"][^'"]*['"]\s*,\s*['"](https?://[^'"]+)['"]"""
    )
    private val FETCH_TXT = Regex("""fetch\(\s*['"]([^'"]+\.txt)['"]""")
    private val EXTINF_ATTR = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    data class ParsedEventRow(
        val label: String,
        val href: String?,
        val startTimeText: String?
    )

    // --------------------------------------------------------- JS Literals

    private fun scanQuoted(text: String, start: Int): Int {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\\') {
                index += 2
                continue
            }
            if (ch == quote) {
                return index + 1
            }
            index++
        }
        return text.length
    }

    fun splitJsObjects(block: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var index = 0
        while (index < block.length) {
            val ch = block[index]
            if (ch == '\'' || ch == '"' || ch == '`') {
                index = scanQuoted(block, index)
                continue
            }
            if (ch == '{') {
                if (depth == 0) start = index
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0 && start >= 0) {
                    objects.add(block.substring(start, index + 1))
                    start = -1
                }
            }
            index++
        }
        return objects
    }

    private fun matchBracket(text: String, start: Int, opener: Char, closer: Char): Int {
        var depth = 1
        var index = start
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\'' || ch == '"' || ch == '`') {
                index = scanQuoted(text, index)
                continue
            }
            if (ch == opener) {
                depth++
            } else if (ch == closer) {
                depth--
                if (depth == 0) return index
            }
            index++
        }
        return -1
    }

    fun extractBracketed(
        html: String,
        name: String,
        opener: Char = '[',
        closer: Char = ']'
    ): String? {
        val regex = Regex("""\b${Regex.escape(name)}\s*[:=]\s*${Regex.escape(opener.toString())}""")
        val match = regex.find(html) ?: return null
        val start = match.range.last + 1
        val end = matchBracket(html, start, opener, closer)
        return if (end != -1) html.substring(start, end) else null
    }

    private fun splitPropArray(raw: String, name: String): Pair<String, String?> {
        val regex = Regex("""\b${Regex.escape(name)}\s*:\s*\[""")
        val match = regex.find(raw) ?: return raw to null
        val start = match.range.last + 1
        val end = matchBracket(raw, start, '[', ']')
        if (end == -1) return raw to null
        val stripped = raw.substring(0, match.range.first) + raw.substring(end + 1)
        return stripped to raw.substring(start, end)
    }

    // --------------------------------------------------------- Routing

    fun routeFromHref(href: String?, hosts: Set<String>): String? {
        if (href.isNullOrBlank()) return null
        val clean = href.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty() ||
            clean.startsWith("mailto:", ignoreCase = true) ||
            clean.startsWith("javascript:", ignoreCase = true) ||
            clean.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }

        val normalizedHosts = hosts.map {
            it.lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        }.toSet()

        val path = when {
            clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) -> {
                val uri = try {
                    URI(clean)
                } catch (_: Exception) {
                    return null
                }
                val host = uri.host?.lowercase() ?: return null
                if (host !in normalizedHosts) return null
                uri.path ?: "/"
            }
            clean.startsWith("/") -> clean
            else -> return null
        }

        val lowerPath = path.lowercase()
        if (STATIC_SUFFIXES.any { lowerPath.endsWith(it) }) return null
        return path.ifEmpty { "/" }
    }

    fun parseNavCategories(html: String, hosts: Set<String>): List<Category> {
        val doc = Jsoup.parse(html)
        val nav = doc.selectFirst("nav") ?: doc
        val seen = LinkedHashMap<String, Category>()
        for (anchor in nav.select("a.nav-link")) {
            val route = routeFromHref(anchor.attr("href"), hosts) ?: continue
            val slug = if (route == "/") {
                "index"
            } else {
                route.trim('/').substringBefore('/')
            }
            if (slug.isEmpty() || seen.containsKey(slug)) continue
            val rawTitle = anchor.text().trim().ifEmpty { slug.uppercase() }
            val displayTitle = if (slug.equals("soccer", ignoreCase = true) ||
                rawTitle.contains("soccer", ignoreCase = true)
            ) {
                com.example.data.model.SportDisplayFormatter.formatSportName(rawTitle)
            } else {
                rawTitle
            }
            seen[slug] = Category(
                slug = slug,
                title = displayTitle,
                route = route,
                kind = "nav"
            )
        }
        return seen.values.toList()
    }

    fun parseInternalRoutes(html: String, hosts: Set<String>): Set<String> {
        val doc = Jsoup.parse(html)
        val routes = mutableSetOf<String>()
        for (anchor in doc.select("a[href]")) {
            routeFromHref(anchor.attr("href"), hosts)?.let { routes.add(it) }
        }
        return routes
    }

    // --------------------------------------------------------- Events

    fun parseEventRows(html: String): List<ParsedEventRow> {
        val doc = Jsoup.parse(html)
        val rows = mutableListOf<ParsedEventRow>()
        for (table in doc.select("table")) {
            for (tr in table.select("tr")) {
                val cells = tr.select("td")
                if (cells.isEmpty()) continue
                val labelCell = cells[0]
                val anchor = labelCell.selectFirst("a")
                val label = (anchor?.text() ?: labelCell.text()).trim()
                if (label.isEmpty()) continue
                val href = anchor?.attr("href")?.takeIf { it.isNotBlank() }
                var startText: String? = null
                for (i in 1 until cells.size) {
                    if (cells[i].hasClass("event-start-time")) {
                        startText = cells[i].text().trim()
                        break
                    }
                }
                rows.add(ParsedEventRow(label = label, href = href, startTimeText = startText))
            }
        }
        return rows
    }

    fun parseStartTimeUtcMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.trim().replace(Regex("""\s+"""), " ")
        for (pattern in TIME_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = SITE_TIME_ZONE
                    isLenient = false
                }
                val parsed = sdf.parse(cleaned)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return null
    }

    // --------------------------------------------------------- Streams

    private fun parseStreamObject(
        body: String,
        fallbackSport: String? = null,
        group: String? = null
    ): StreamRef {
        val fields = mutableMapOf<String, String>()
        for (m in QUOTED_JS_KV.findAll(body)) {
            fields[m.groupValues[1]] = m.groupValues[2]
        }
        return StreamRef(
            label = fields["label"].orEmpty(),
            subdomain = fields["subdomain"]?.takeIf { it.isNotBlank() },
            path = fields["path"]?.takeIf { it.isNotBlank() },
            poolFile = fields["txt"]?.takeIf { it.isNotBlank() },
            hardcoded = fields["hardcoded"]?.takeIf { it.isNotBlank() },
            sport = fields["sport"]?.takeIf { it.isNotBlank() } ?: fallbackSport,
            group = group
        )
    }

    fun parseMultiviewStreams(html: String): List<StreamRef> {
        val block = extractBracketed(html, "STREAMS", '[', ']') ?: return emptyList()
        val refs = mutableListOf<StreamRef>()
        for (raw in splitJsObjects(block)) {
            val (baseBody, altsBody) = splitPropArray(raw, "alts")
            val base = parseStreamObject(baseBody)
            if (base.path == null && base.hardcoded == null) continue
            refs.add(base)
            if (altsBody != null) {
                for (altRaw in splitJsObjects(altsBody)) {
                    val alt = parseStreamObject(altRaw, fallbackSport = base.sport, group = base.label)
                    if (alt.path != null || alt.hardcoded != null) {
                        refs.add(alt)
                    }
                }
            }
        }
        return refs
    }

    fun parseSportLabels(html: String): Map<String, String> {
        val block = extractBracketed(html, "SPORT_LABELS", '{', '}') ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (m in QUOTED_JS_KV.findAll(block)) {
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    fun parseStreamButtons(html: String): List<StreamRef> {
        val doc = Jsoup.parse(html)
        val poolFile = parsePoolFile(html)
        val refs = mutableListOf<StreamRef>()
        for (button in doc.select("button[onclick]")) {
            val onclick = button.attr("onclick")
            val label = button.text().trim()

            val randomMatch = GET_RANDOM_STREAM.find(onclick)
            if (randomMatch != null) {
                val path = randomMatch.groupValues[1]
                val subdomain = randomMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                refs.add(
                    StreamRef(
                        label = label.ifEmpty { path },
                        subdomain = subdomain,
                        path = path,
                        poolFile = poolFile
                    )
                )
                continue
            }

            val hardcodedMatch = SHOW_PLAYER_URL.find(onclick)
            if (hardcodedMatch != null) {
                val url = hardcodedMatch.groupValues[1]
                val tail = url.substringAfterLast('/')
                refs.add(
                    StreamRef(
                        label = label.ifEmpty { tail },
                        path = tail.takeIf { it.endsWith(".m3u8", ignoreCase = true) },
                        hardcoded = url,
                        poolFile = poolFile
                    )
                )
            }
        }
        return refs
    }

    fun parseDefaultStreams(html: String): List<StreamRef> {
        val poolFile = parsePoolFile(html)
        return GET_RANDOM_STREAM.findAll(html).map { m ->
            val path = m.groupValues[1]
            val subdomain = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            StreamRef(
                label = "default:$path",
                subdomain = subdomain,
                path = path,
                poolFile = poolFile
            )
        }.toList()
    }

    fun parsePoolFile(html: String): String? =
        FETCH_TXT.find(html)?.groupValues?.getOrNull(1)

    fun parsePoolDomains(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '.' in it }
            .distinct()
            .toList()

    fun dedupeRefs(refs: List<StreamRef>): List<StreamRef> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<StreamRef>()
        for (ref in refs) {
            if (ref.key.isNotEmpty() && seen.add(ref.key)) {
                unique.add(ref)
            }
        }
        return unique
    }

    // --------------------------------------------------------- M3U / IPTV Playlist Parser

    fun parseM3uPlaylist(content: String, defaultGroup: String = "Imported M3U"): List<StreamRef> {
        val refs = mutableListOf<StreamRef>()
        var pendingTitle: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val attrs = EXTINF_ATTR.findAll(line).associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: defaultGroup
                    val commaTitle = line.substringAfter(',', "").trim()
                    pendingTitle = commaTitle.ifEmpty {
                        attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: "Channel ${refs.size + 1}"
                    }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.removePrefix("#EXTVLCOPT:").trim()
                    val key = opt.substringBefore('=').lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                            "http-origin" -> pendingHeaders["Origin"] = value
                        }
                    }
                }
                !line.startsWith("#") &&
                    (line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true)) -> {
                    val title = pendingTitle ?: line.substringAfterLast('/').substringBefore('?').ifEmpty {
                        "Stream ${refs.size + 1}"
                    }
                    refs.add(
                        StreamRef(
                            label = title,
                            hardcoded = line,
                            sport = (pendingGroup ?: defaultGroup).lowercase().replace(' ', '-'),
                            group = pendingGroup ?: defaultGroup,
                            customHeaders = pendingHeaders.toMap()
                        )
                    )
                    pendingTitle = null
                    pendingGroup = null
                    pendingHeaders.clear()
                }
            }
        }
        return dedupeRefs(refs)
    }
}
