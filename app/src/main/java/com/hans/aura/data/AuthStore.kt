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

/**
 * Persists the Jellyfin session; also builds auth headers and media URLs.
 *
 * The server ADDRESS is stored separately from the session (key
 * [KEY_LAST_SERVER]) so it can outlive a sign-out: "Sign in again" drops only
 * the token and the login screen comes back with the old URL prefilled, while
 * "Change server" drops both (see [clearSession] / [clearServer]).
 */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("aura_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Decoded once and kept in memory: `session` is read on every API call
    // (JellyfinClient resolves its baseUrl from it), and a prefs read plus a
    // JSON decode each time is wasteful. Mutators below keep it in step.
    @Volatile
    private var cached: Session? = null
    @Volatile
    private var loaded = false

    var session: Session?
        get() {
            if (!loaded) {
                // Corrupt/older-shaped prefs must read as "signed out", not
                // crash the process on the first API call at startup.
                cached = prefs.getString(KEY_SESSION, null)?.let { raw ->
                    runCatching { json.decodeFromString<Session>(raw) }.getOrNull()
                }
                loaded = true
            }
            return cached
        }
        private set(value) {
            cached = value
            loaded = true
            prefs.edit().putString(KEY_SESSION, value?.let { json.encodeToString(it) }).apply()
        }

    val isSignedIn: Boolean get() = session != null

    /**
     * Installs a session the app did not obtain by logging in.
     *
     * A build whose backend needs no interactive sign-in (a packaged library, a token supplied at
     * build time) calls this once at startup so every screen sees a signed-in state.
     */
    fun installSession(session: Session) {
        this.session = session
    }

    /**
     * The last server this device signed in to, kept even when signed out so
     * the login screen can prefill the URL. Null after [clearServer].
     */
    val lastServerUrl: String? get() = prefs.getString(KEY_LAST_SERVER, null)

    fun save(session: Session) {
        this.session = session
        prefs.edit().putString(KEY_LAST_SERVER, session.serverUrl).apply()
    }

    /**
     * Sign out of THIS server, keeping its address: the token (and the cached
     * user) go, [lastServerUrl] stays so the login screen can prefill the URL.
     * Use for "Sign in again".
     */
    fun clearSession() {
        cached = null
        loaded = true
        prefs.edit().remove(KEY_SESSION).apply()
    }

    /**
     * Leave the current server entirely: no token AND no remembered address,
     * so the login screen starts with an empty URL field. Use for
     * "Change server".
     */
    fun clearServer() {
        cached = null
        loaded = true
        prefs.edit().remove(KEY_SESSION).remove(KEY_LAST_SERVER).apply()
    }

    /** Wholesale wipe of the prefs file (session + remembered server). */
    fun clear() {
        cached = null
        loaded = true
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SESSION = "session"
        private const val KEY_LAST_SERVER = "last_server_url"

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
            val base = "MediaBrowser Client=\"$appName\", Device=\"${com.hans.aura.data.DeviceNames.get()}\", " +
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
            MusicSources.current?.takeIf { it.servesArtwork }?.artworkUrl(itemId, tag, maxWidth)
                ?: jellyfinImageUrl(serverUrl, itemId, "Primary", tag, maxWidth)

        internal fun jellyfinImageUrl(
            serverUrl: String,
            itemId: String,
            type: String,
            tag: String?,
            maxWidth: Int
        ): String =
            "$serverUrl/Items/$itemId/Images/$type?maxWidth=$maxWidth" +
                (tag?.let { "&tag=$it" } ?: "")

        /** Short host form for logs: http://192.168.2.10:8096/ -> 192.168.2.10:8096 */
        fun host(url: String?): String = url
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.trimEnd('/')
            ?: "<none>"
    }
}
