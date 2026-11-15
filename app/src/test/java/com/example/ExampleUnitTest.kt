package com.example

import com.example.data.local.ObservationEntity
import com.example.data.model.ProbeResult
import com.example.data.model.ProbeState
import com.example.data.model.SiteStateSnapshot
import com.example.data.model.SportDisplayFormatter
import com.example.data.model.StreamRef
import com.example.data.model.WhitelabelConfig
import com.example.data.repository.StreamRepository
import com.example.data.resolver.SiteParser
import com.example.data.resolver.StreamResolverEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun soccerCategoryAndConsumerTitles_mapToFootballAndHideRawUrls() {
        assertEquals("Football", SportDisplayFormatter.formatSportName("soccer"))
        assertEquals("Football", SportDisplayFormatter.formatSportName("Soccer"))
        assertEquals("Football Live", SportDisplayFormatter.formatSportName("Soccer Live"))
        assertEquals("NFL", SportDisplayFormatter.formatSportName("nfl"))

        val html = """
            <html>
            <body>
              <nav>
                <a class="nav-link" href="/soccer">Soccer</a>
                <a class="nav-link" href="/nba">NBA</a>
              </nav>
            </body>
            </html>
        """.trimIndent()
        val categories = SiteParser.parseNavCategories(html, setOf("roxiestreams.info"))
        assertEquals(2, categories.size)
        assertEquals("soccer", categories[0].slug)
        assertEquals("Football", categories[0].title)
        assertEquals("Football", categories[0].displayTitle)

        // Verify raw .m3u8 filenames and URLs are never shown to home users
        val rawRef = StreamRef(
            label = "default:soccer2.m3u8",
            path = "soccer2.m3u8",
            sport = "soccer"
        )
        assertFalse(rawRef.displayTitle.contains(".m3u8"))
        assertEquals("Football Channel 2", rawRef.displayTitle)
        assertEquals("Football", rawRef.displaySport)
    }

    @Test
    fun envWhitelabelDefaults_arePopulatedFromBuildConfig() {
        val cfg = WhitelabelConfig.fromEnvBuildConfig()
        assertEquals("PrismCast", cfg.brandName)
        assertEquals("PC", cfg.monogram)
        assertTrue(cfg.mirrors.isNotEmpty())
        assertTrue(cfg.extraSubdomains.contains("admin2"))
        assertTrue(cfg.autoFailover)
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36",
            cfg.customUserAgent
        )

        val streamHeaders = cfg.buildStreamRequestHeaders("https://roxiestreams.info")
        assertEquals(cfg.customUserAgent, streamHeaders["User-Agent"])
        assertEquals("https://roxiestreams.info", streamHeaders["Origin"])
        assertEquals("https://roxiestreams.info/", streamHeaders["Referer"])
        assertEquals("cross-site", streamHeaders["Sec-Fetch-Site"])
        assertEquals("cors", streamHeaders["Sec-Fetch-Mode"])
        assertEquals("empty", streamHeaders["Sec-Fetch-Dest"])

        val httpClient = com.example.data.network.MirrorHttpClient(
            mirrors = cfg.mirrors,
            userAgent = cfg.customUserAgent
        )
        val edgeHeaders = httpClient.buildHeadersForUrl("https://admin2.formaturamaxi.com.br/ncaa3.m3u8")
        assertEquals("https://roxiestreams.info", edgeHeaders["Origin"])
        assertEquals("https://roxiestreams.info/", edgeHeaders["Referer"])
        assertEquals("cross-site", edgeHeaders["Sec-Fetch-Site"])

        val sameSiteHeaders = httpClient.buildHeadersForUrl("https://roxiestreams.info/multiview")
        assertEquals("https://roxiestreams.info", sameSiteHeaders["Origin"])
        assertEquals("https://roxiestreams.info/", sameSiteHeaders["Referer"])
        assertEquals("same-origin", sameSiteHeaders["Sec-Fetch-Site"])
    }

    @Test
    fun parseMultiviewAndButtons_extractsBaseAltsAndHardcodedStreams() {
        val html = """
            <html>
            <body>
              <table>
                <tr>
                  <td><a href="/nba-streams-1">Lakers vs Celtics</a></td>
                  <td class="event-start-time">Nov 14, 2026 07:30 PM</td>
                </tr>
              </table>
              <button onclick="showPlayer('clappr', getRandomStream('nba1.m3u8', 'admin2'))">HD 1</button>
              <button onclick="showPlayer('clappr', 'https://cdn.example.net/backup.m3u8')">Backup</button>
              <script>
                fetch('domainsz77.txt');
                const STREAMS = [
                  {
                    label: 'Main Event',
                    subdomain: 'admin2',
                    path: 'ufcmain.m3u8',
                    sport: 'ufc',
                    alts: [
                      { label: 'Alt Feed', subdomain: 'tedesco', path: 'ufcalt.m3u8' }
                    ]
                  }
                ];
              </script>
            </body>
            </html>
        """.trimIndent()

        val events = SiteParser.parseEventRows(html)
        assertEquals(1, events.size)
        assertEquals("Lakers vs Celtics", events[0].label)
        assertNotNull(SiteParser.parseStartTimeUtcMillis(events[0].startTimeText))

        val buttons = SiteParser.parseStreamButtons(html)
        assertEquals(2, buttons.size)
        assertEquals("nba1.m3u8", buttons[0].path)
        assertEquals("admin2", buttons[0].subdomain)
        assertEquals("domainsz77.txt", buttons[0].poolFile)
        assertEquals("https://cdn.example.net/backup.m3u8", buttons[1].hardcoded)

        val multiview = SiteParser.parseMultiviewStreams(html)
        assertEquals(2, multiview.size)
        assertEquals("Main Event", multiview[0].label)
        assertEquals("Main Event", multiview[1].group)
        assertEquals("tedesco", multiview[1].subdomain)
    }

    @Test
    fun buildCandidatesAndPickBest_prioritizesDeclaredLiveWithLowestLatency() {
        val engine = StreamResolverEngine()
        val ref = StreamRef(label = "Game 1", subdomain = "admin2", path = "game1.m3u8")
        val candidates = engine.buildCandidates(
            ref = ref,
            pool = listOf("edge-a.net", "edge-b.net"),
            extraHosts = listOf("admin", "tedesco"),
            fallbackDomain = "shadow-ran.online",
            includeFallback = true
        )
        assertEquals("https://admin2.edge-a.net/game1.m3u8", candidates.first().url)
        assertEquals("declared", candidates.first().origin)
        assertTrue(candidates.any { it.origin == "alternate" && it.host == "tedesco.edge-b.net" })
        assertTrue(candidates.any { it.origin == "fallback" && it.host == "admin2.shadow-ran.online" })

        val best = engine.pickBest(
            listOf(
                ProbeResult("https://a/1.m3u8", ProbeState.IDLE, statusCode = 200, elapsedMs = 40),
                ProbeResult("https://b/1.m3u8", ProbeState.LIVE, statusCode = 200, elapsedMs = 110),
                ProbeResult("https://c/1.m3u8", ProbeState.LIVE, statusCode = 200, elapsedMs = 55)
            )
        )
        assertNotNull(best)
        assertEquals("https://c/1.m3u8", best?.url)
    }

    @Test
    fun historyDiff_detectsRotationsAndTransitions() {
        val oldState = SiteStateSnapshot(pool = listOf("old-pool.com"), subdomains = listOf("admin"))
        val newState = SiteStateSnapshot(pool = listOf("new-pool.com"), subdomains = listOf("admin", "admin2"))
        val diffs = StreamRepository.diffSiteStates(oldState, newState)
        assertTrue(diffs.contains("pool added: new-pool.com"))
        assertTrue(diffs.contains("pool removed: old-pool.com"))
        assertTrue(diffs.contains("subdomains added: admin2"))

        val transitions = StreamRepository.computeTransitions(
            listOf(
                ObservationEntity(1000L, "2026-11-14T10:00:00Z", "nba1.m3u8", "h1", null, "idle"),
                ObservationEntity(2000L, "2026-11-14T10:05:00Z", "nba1.m3u8", "h1", "https://h1/nba1.m3u8", "live")
            )
        )
        assertEquals(1, transitions.size)
        assertEquals("idle", transitions[0].fromState)
        assertEquals("live", transitions[0].toState)
    }

    @Test
    fun autoRetryBackoffSchedule_escalatesAndCapsCleanly() {
        assertEquals(1_200L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(0))
        assertEquals(1_200L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(1))
        assertEquals(2_400L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(2))
        assertEquals(4_000L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(3))
        assertEquals(6_000L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(4))
        assertEquals(8_000L, com.example.util.RetryBackoffPolicy.computeRetryDelayMs(99))
    }
}
