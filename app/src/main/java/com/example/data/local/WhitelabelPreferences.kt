package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.LastPlaybackSession
import com.example.data.model.WhitelabelConfig
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.viewerSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prismcast_session_prefs"
)

class WhitelabelPreferences(private val context: Context) {

    val envConfig: WhitelabelConfig = WhitelabelConfig.fromEnvBuildConfig()

    suspend fun getOrCreateViewerClientId(): String {
        val prefs = context.viewerSessionDataStore.data.first()
        val existing = prefs[KEY_CLIENT_ID]
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        context.viewerSessionDataStore.edit { it[KEY_CLIENT_ID] = generated }
        return generated
    }

    suspend fun savePlaybackSession(session: LastPlaybackSession?) {
        context.viewerSessionDataStore.edit { prefs ->
            if (session == null) {
                prefs.remove(KEY_LAST_SESSION)
            } else {
                prefs[KEY_LAST_SESSION] = encodeSession(session)
            }
        }
    }

    suspend fun loadPlaybackSession(): LastPlaybackSession? {
        return runCatching {
            val prefs = context.viewerSessionDataStore.data.first()
            val raw = prefs[KEY_LAST_SESSION]
            if (raw.isNullOrBlank()) null else decodeSession(raw)
        }.getOrNull()
    }

    companion object {
        private val KEY_CLIENT_ID = stringPreferencesKey("viewer_client_id")
        private val KEY_LAST_SESSION = stringPreferencesKey("last_playback_session_json")

        internal fun encodeSession(session: LastPlaybackSession): String {
            val root = JSONObject()
            root.put("mode", session.mode)
            root.put("focusedMultiViewSlot", session.focusedMultiViewSlot)
            root.put("isQuadMode", session.isQuadMode)
            if (session.selectedCategorySlug != null) {
                root.put("selectedCategorySlug", session.selectedCategorySlug)
            }
            if (session.activeFeed != null) {
                root.put("activeFeed", encodeFeed(session.activeFeed))
            }
            val slotsArr = JSONArray()
            session.multiViewSlots.take(4).forEach { feed ->
                slotsArr.put(if (feed != null) encodeFeed(feed) else JSONObject.NULL)
            }
            root.put("multiViewSlots", slotsArr)
            return root.toString()
        }

        internal fun decodeSession(raw: String): LastPlaybackSession? {
            val root = JSONObject(raw)
            val mode = root.optString("mode", "")
            if (mode != "SINGLE" && mode != "MULTIVIEW") return null

            val activeFeed = root.optJSONObject("activeFeed")?.let(::decodeFeed)
            val slotsArr = root.optJSONArray("multiViewSlots")
            val slots = List(4) { idx ->
                if (slotsArr == null || slotsArr.isNull(idx)) {
                    null
                } else {
                    slotsArr.optJSONObject(idx)?.let(::decodeFeed)
                }
            }

            if (mode == "SINGLE" && activeFeed == null) return null
            if (mode == "MULTIVIEW" && slots.all { it == null }) return null

            val catSlug = if (root.has("selectedCategorySlug") && !root.isNull("selectedCategorySlug")) {
                root.optString("selectedCategorySlug").takeIf { it.isNotBlank() }
            } else {
                null
            }

            return LastPlaybackSession(
                mode = mode,
                activeFeed = activeFeed,
                multiViewSlots = slots,
                focusedMultiViewSlot = root.optInt("focusedMultiViewSlot", 0).coerceIn(0, 3),
                isQuadMode = root.optBoolean("isQuadMode", true),
                selectedCategorySlug = catSlug
            )
        }

        private fun encodeFeed(feed: ActivePlayableFeed): JSONObject {
            val obj = JSONObject()
            obj.put("id", feed.id)
            obj.put("title", feed.title)
            obj.put("subtitle", feed.subtitle)
            obj.put("primaryUrl", feed.primaryUrl)
            val cands = JSONArray()
            feed.candidateUrls.forEach { cands.put(it) }
            obj.put("candidateUrls", cands)
            obj.put("currentCandidateIndex", feed.currentCandidateIndex)
            obj.put("category", feed.category)
            obj.put("viewers", feed.viewers)
            if (feed.eventRoute != null) obj.put("eventRoute", feed.eventRoute)
            if (feed.streamPath != null) obj.put("streamPath", feed.streamPath)
            if (feed.subdomain != null) obj.put("subdomain", feed.subdomain)
            if (feed.customHeaders.isNotEmpty()) {
                val hdrs = JSONObject()
                feed.customHeaders.forEach { (k, v) -> hdrs.put(k, v) }
                obj.put("customHeaders", hdrs)
            }
            return obj
        }

        private fun decodeFeed(obj: JSONObject): ActivePlayableFeed? {
            val primaryUrl = obj.optString("primaryUrl", "").trim()
            if (primaryUrl.isEmpty()) return null
            val candsArr = obj.optJSONArray("candidateUrls")
            val candidates = buildList {
                if (candsArr != null) {
                    for (i in 0 until candsArr.length()) {
                        val u = candsArr.optString(i, "").trim()
                        if (u.isNotEmpty()) add(u)
                    }
                }
                if (isEmpty()) add(primaryUrl)
            }.distinct()

            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("customHeaders")?.let { hdrObj ->
                val keys = hdrObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = hdrObj.optString(key, "")
                }
            }

            return ActivePlayableFeed(
                id = obj.optString("id", primaryUrl),
                title = obj.optString("title", "Live Broadcast"),
                subtitle = obj.optString("subtitle", "Live TV"),
                primaryUrl = primaryUrl,
                candidateUrls = candidates,
                currentCandidateIndex = obj.optInt("currentCandidateIndex", 0)
                    .coerceIn(0, (candidates.size - 1).coerceAtLeast(0)),
                category = obj.optString("category", "Live TV"),
                viewers = obj.optInt("viewers", 0),
                eventRoute = obj.optString("eventRoute", "").takeIf { it.isNotBlank() },
                streamPath = obj.optString("streamPath", "").takeIf { it.isNotBlank() },
                subdomain = obj.optString("subdomain", "").takeIf { it.isNotBlank() },
                customHeaders = headers
            )
        }
    }
}
