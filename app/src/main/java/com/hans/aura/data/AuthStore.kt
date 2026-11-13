package com.hans.aura.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Session(
    val serverUrl: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val deviceId: String
)

/** Persists the Jellyfin session; also builds auth headers and media URLs. */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("aura_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var session: Session?
        get() = prefs.getString("session", null)?.let { json.decodeFromString<Session>(it) }
        private set(value) {
            prefs.edit().putString("session", value?.let { json.encodeToString(it) } ?: null).apply()
        }

    val isSignedIn: Boolean get() = session != null

    fun save(session: Session) { this.session = session }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        fun newDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("aura_device", Context.MODE_PRIVATE)
            return prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("id", it).apply()
            }
        }

        /**
         * Standard Authorization header value. Jellyfin 10.10+ requires this
         * scheme; the legacy X-Emby-Authorization header is rejected (HTTP 400).
         */
        fun authHeader(session: Session?, deviceId: String, appName: String, version: String): String {
            val base = "MediaBrowser Client=\"$appName\", Device=\"Android TV\", " +
                "DeviceId=\"$deviceId\", Version=\"$version\""
            return session?.accessToken?.let { "$base, Token=\"$it\"" } ?: base
        }

        /** Direct-play URL: /Audio/{id}/stream.{container}?static=true serves the original bitstream untouched. */
        fun audioStreamUrl(serverUrl: String, itemId: String, container: String?): String {
            val ext = container?.takeIf { it.isNotBlank() } ?: "mp4"
            return "$serverUrl/Audio/$itemId/stream.$ext?static=true"
        }

        fun imageUrl(
            serverUrl: String,
            itemId: String,
            tag: String?,
            maxWidth: Int = 600
        ): String? =
            "$serverUrl/Items/$itemId/Images/Primary?maxWidth=$maxWidth" +
                (tag?.let { "&tag=$it" } ?: "")
    }
}
