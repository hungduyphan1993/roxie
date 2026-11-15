package com.example.data.network

import android.os.SystemClock
import com.example.data.model.ProbeResult
import com.example.data.model.ProbeState
import com.example.data.model.WhitelabelConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

class MirrorHttpClient(
    mirrors: List<String>,
    private val userAgent: String = WhitelabelConfig.DEFAULT_BROWSER_USER_AGENT,
    private val pageTimeoutSec: Long = 12L
) {
    val mirrors: List<String> = mirrors
        .map { it.trim().trimEnd('/') }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .ifEmpty { listOf("https://roxiestreams.info", "https://roxiestreams.su") }

    private val preferredIndex = AtomicInteger(0)

    private val sharedPool = ConnectionPool(24, 2, TimeUnit.MINUTES)
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 48
        maxRequestsPerHost = 16
    }

    private val browserHeaderInterceptor = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", userAgent)
        if (original.header("Accept") == null) {
            builder.header("Accept", "*/*")
        }
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        val defaultHeaders = buildHeadersForUrl(original.url.toString())
        for ((key, value) in defaultHeaders) {
            if (!key.equals("User-Agent", ignoreCase = true) && original.header(key) == null) {
                builder.header(key, value)
            }
        }
        chain.proceed(builder.build())
    }

    private val routeClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedPool)
        .dispatcher(sharedDispatcher)
        .addNetworkInterceptor(browserHeaderInterceptor)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(pageTimeoutSec, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedPool)
        .dispatcher(sharedDispatcher)
        .addNetworkInterceptor(browserHeaderInterceptor)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    val preferredMirror: String
        get() = mirrors[preferredIndex.get().coerceIn(mirrors.indices)]

    fun mirrorOrder(): List<String> {
        val start = preferredIndex.get().coerceIn(mirrors.indices)
        return mirrors.subList(start, mirrors.size) + mirrors.subList(0, start)
    }

    fun prefer(mirror: String) {
        val clean = mirror.trim().trimEnd('/')
        val idx = mirrors.indexOf(clean)
        if (idx >= 0) {
            preferredIndex.set(idx)
        }
    }

    fun buildHeadersForUrl(url: String): Map<String, String> {
        val matchingMirror = mirrors.firstOrNull { mirror ->
            val clean = mirror.trimEnd('/')
            url.equals(clean, ignoreCase = true) || url.startsWith("$clean/", ignoreCase = true)
        }
        val originMirror = (matchingMirror ?: preferredMirror).trimEnd('/')
        val isSameOrigin = matchingMirror != null
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to originMirror,
            "Referer" to "$originMirror/",
            "Sec-Fetch-Site" to if (isSameOrigin) "same-origin" else "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )
    }

    fun getText(url: String, timeoutSec: Long = pageTimeoutSec): String? {
        val client = if (timeoutSec == pageTimeoutSec) {
            routeClient
        } else {
            routeClient.newBuilder()
                .readTimeout(timeoutSec.coerceAtLeast(2L), TimeUnit.SECONDS)
                .connectTimeout(timeoutSec.coerceAtMost(6L).coerceAtLeast(2L), TimeUnit.SECONDS)
                .build()
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
        buildHeadersForUrl(url).forEach { (k, v) -> reqBuilder.header(k, v) }
        val request = reqBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code >= 400) return null
                val bytes = response.body?.bytes() ?: return null
                decodeBody(bytes, response.header("Content-Type"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchRoute(route: String, timeoutSec: Long = pageTimeoutSec): Pair<String, String>? {
        val normalized = if (route.startsWith("/")) route else "/$route"
        for (mirror in mirrorOrder()) {
            val fullUrl = "${mirror.trimEnd('/')}$normalized"
            val html = getText(fullUrl, timeoutSec)
            if (html != null) {
                prefer(mirror)
                return fullUrl to html
            }
        }
        return null
    }

    fun fetchAbsoluteWithMirrorFailover(url: String, timeoutSec: Long = pageTimeoutSec): String? {
        getText(url, timeoutSec)?.let { return it }
        for (mirror in mirrorOrder()) {
            val base = mirror.trimEnd('/')
            if (url.startsWith(base)) continue
            val matchingOrigin = mirrors.firstOrNull { url.startsWith(it.trimEnd('/')) }
            val pathPart = if (matchingOrigin != null) {
                url.removePrefix(matchingOrigin.trimEnd('/'))
            } else {
                "/" + url.substringAfter("://").substringAfter('/', "")
            }
            val candidate = "$base$pathPart"
            val text = getText(candidate, timeoutSec)
            if (text != null) {
                prefer(mirror)
                return text
            }
        }
        return null
    }

    fun probe(
        url: String,
        timeoutSec: Int = 7,
        headers: Map<String, String> = emptyMap()
    ): ProbeResult {
        val started = monotonicNowMs()
        val effectiveTimeoutMs = (timeoutSec.coerceIn(2, 20) * 1000L)
        val connectTimeoutMs = effectiveTimeoutMs.coerceAtMost(4500L)

        val client = probeClient.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(effectiveTimeoutMs + 1500L, TimeUnit.MILLISECONDS)
            .build()

        val reqBuilder = Request.Builder()
            .url(url)
            .get()
        buildHeadersForUrl(url).forEach { (k, v) -> reqBuilder.header(k, v) }
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true) && v.isNotBlank()) {
                reqBuilder.header(k, v)
            }
        }

        val request = try {
            reqBuilder.build()
        } catch (e: IllegalArgumentException) {
            return ProbeResult(
                url = url,
                state = ProbeState.ERROR,
                elapsedMs = monotonicNowMs() - started,
                error = e.message ?: "Invalid URL"
            )
        }

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                val stream = response.body?.byteStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                if (stream != null) {
                    while (totalRead < buffer.size) {
                        val read = stream.read(buffer, totalRead, buffer.size - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                }
                val snippet = String(buffer, 0, totalRead, Charsets.UTF_8).trimStart()
                val isMpegUrl = MPEGURL_HINTS.any { contentType.contains(it) } ||
                    snippet.startsWith("#EXTM3U", ignoreCase = true) ||
                    contentType.contains("dash+xml") ||
                    snippet.startsWith("<MPD", ignoreCase = true)

                val state = when {
                    code == 401 || code == 403 -> ProbeState.FORBIDDEN
                    code in 521..524 -> ProbeState.HOST_DOWN
                    code == 404 || code == 410 -> ProbeState.DEAD
                    code >= 400 -> ProbeState.DEAD
                    isMpegUrl -> if (totalRead > 0) ProbeState.LIVE else ProbeState.IDLE
                    else -> ProbeState.DEAD
                }

                ProbeResult(
                    url = url,
                    state = state,
                    statusCode = code,
                    contentType = contentType.substringBefore(';').ifBlank { null },
                    sizeBytes = totalRead,
                    elapsedMs = monotonicNowMs() - started
                )
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(
                url = url,
                state = ProbeState.HOST_DOWN,
                elapsedMs = monotonicNowMs() - started,
                error = "timeout"
            )
        } catch (e: IOException) {
            ProbeResult(
                url = url,
                state = ProbeState.HOST_DOWN,
                elapsedMs = monotonicNowMs() - started,
                error = e.message?.take(160) ?: "connection error"
            )
        } catch (e: Exception) {
            ProbeResult(
                url = url,
                state = ProbeState.ERROR,
                elapsedMs = monotonicNowMs() - started,
                error = e.message?.take(160) ?: e.javaClass.simpleName
            )
        }
    }

    private fun decodeBody(bytes: ByteArray, contentTypeHeader: String?): String {
        var encoding: String? = null
        val ctype = contentTypeHeader?.lowercase().orEmpty()
        if ("charset=" in ctype) {
            encoding = ctype.substringAfter("charset=")
                .substringBefore(';')
                .trim()
                .trim('"', '\'')
        }
        if (encoding.isNullOrBlank() || encoding == "iso-8859-1") {
            val headAscii = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            val match = META_CHARSET_REGEX.find(headAscii)
            if (match != null) {
                encoding = match.groupValues[1]
            }
        }
        val charset = try {
            if (!encoding.isNullOrBlank()) Charset.forName(encoding) else Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private fun monotonicNowMs(): Long = try {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed > 0L) elapsed else System.nanoTime() / 1_000_000L
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L
    }

    companion object {
        private val MPEGURL_HINTS = listOf("mpegurl", "x-mpegurl", "vnd.apple.mpegurl")
        private val META_CHARSET_REGEX =
            Regex("""charset\s*=\s*["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)
    }
}
