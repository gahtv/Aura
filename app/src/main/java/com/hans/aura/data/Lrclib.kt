package com.hans.aura.data

import android.util.Log
import com.hans.aura.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * LRCLIB (https://lrclib.net) lyrics fallback.
 *
 * Jellyfin only returns lyrics for tracks whose library has them. When the
 * server has nothing AND the user switched on Settings > "Look up lyrics
 * online", the lyrics pane asks LRCLIB instead. Everything here is
 * best-effort: no exception escapes, a timeout or 5xx is logged and treated as
 * "no lyrics", and playback is never touched.
 *
 * Requests (plain HTTPS GET, no key in the query string):
 *   /api/get?artist_name=&track_name=&album_name=&duration=
 *   /api/search?track_name=&artist_name=        (when /api/get has no match)
 *
 * Headers: `Lrclib-App-Id` (BuildConfig.LRCLIB_APP_ID, omitted when a build has
 * no .env) and `Lrclib-Client: Aura <version> (https://github.com/gahtv/Aura)`.
 */
object Lrclib {

    private const val TAG = "AURA_LRCLIB"
    private const val HOST = "lrclib.net"
    private const val CLIENT = "Aura ${BuildConfig.VERSION_NAME} (https://github.com/gahtv/Aura)"
    private const val APP_ID = BuildConfig.LRCLIB_APP_ID

    /** Result selection: a candidate may miss by this much before it is treated as a different recording. */
    private const val DURATION_TOLERANCE_S = 2.0

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Per-track in-process cache. An EMPTY list is a remembered miss (no re-query per recomposition). */
    private val cache = ConcurrentHashMap<String, List<LyricLine>>()

    private val trackListSerializer = ListSerializer(Track.serializer())

    @Serializable
    private data class Track(
        val id: Long = 0,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )

    /** Last known result for [itemId], or null when nothing has been looked up yet. */
    fun cached(itemId: String): List<LyricLine>? = cache[itemId]

    /**
     * Look [item] up. Returns empty on a miss, an error, an instrumental match,
     * or a result with no usable text. Never throws.
     */
    suspend fun lookup(item: JellyfinItem): List<LyricLine> {
        cache[item.id]?.let { return logAndReturn(item, it, "cached") }
        return withContext(Dispatchers.IO) {
            val targetSec = (item.durationMs / 1000.0).roundToLong()
            val artist = primaryArtist(item)
            val track = item.name.trim()
            if (track.isBlank()) {
                cache[item.id] = emptyList()
                return@withContext logAndReturn(item, emptyList(), "miss")
            }

            val parsed = try {
                val exact = fetchGet(
                    artist = artist,
                    track = track,
                    album = item.album.orEmpty(),
                    durationSec = targetSec
                )?.let { listOf(it) }

                // /api/get is an exact-match endpoint: a 404 there means "not the
                // same recording", so widen with a search before giving up.
                val candidates = exact ?: fetchSearch(artist = artist, track = track)
                val picked = pick(candidates, targetSec)
                picked?.let { toLines(it) }.orEmpty()
            } catch (t: Throwable) {
                // Never cache an error: a transient network failure should be
                // retryable the next time this track is opened.
                Log.w(TAG, "AURA_LRCLIB item=${item.id} result=error dur=${item.durationMs / 1000} ${t.javaClass.simpleName}: ${t.message}")
                return@withContext emptyList()
            }

            // Only an authoritative "nothing found" is remembered.
            cache[item.id] = parsed
            logAndReturn(item, parsed, if (parsed.isEmpty()) "miss" else "hit")
        }
    }

    private fun logAndReturn(item: JellyfinItem, lines: List<LyricLine>, result: String): List<LyricLine> {
        Log.i(TAG, "AURA_LRCLIB item=${item.id} result=$result dur=${item.durationMs / 1000} lines=${lines.size}")
        return lines
    }

    /** LRCLIB matches on ONE artist: prefer the album artist, then the first credited artist. */
    private fun primaryArtist(item: JellyfinItem): String {
        item.albumArtists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        item.artists?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return item.artistNames.substringBefore(',').trim()
    }

    /** GET /api/get — one exact recording. Null on 404 / no body. */
    private fun fetchGet(artist: String, track: String, album: String, durationSec: Long): Track? {
        val url = "https://$HOST/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", track)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "get ${resp.code} artist=\"$artist\" track=\"$track\"")
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return runCatching { json.decodeFromString(Track.serializer(), body) }.getOrNull()
        }
    }

    /** GET /api/search — candidates by track + artist. Empty on any failure. */
    private fun fetchSearch(artist: String, track: String): List<Track> {
        val url = "https://$HOST/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track)
            .addQueryParameter("artist_name", artist)
            .build()
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "search ${resp.code} artist=\"$artist\" track=\"$track\"")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            return runCatching { json.decodeFromString(trackListSerializer, body) }.getOrDefault(emptyList())
        }
    }

    private fun request(url: okhttp3.HttpUrl): Request =
        Request.Builder()
            .url(url)
            .get()
            // A blank app id (a checkout with no .env) simply omits the header.
            .apply { if (APP_ID.isNotBlank()) header("Lrclib-App-Id", APP_ID) }
            .header("Lrclib-Client", CLIENT)
            .build()

    /**
     * Choose the recording that is most likely the one playing:
     * synced + duration within 2 s  >  synced  >  duration match  >  first.
     * Instrumental entries and entries with no text at all are skipped.
     */
    private fun pick(candidates: List<Track>, targetSec: Long): Track? {
        val usable = candidates.filter {
            !it.instrumental &&
                (!it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank())
        }
        if (usable.isEmpty()) return null
        fun close(t: Track) = t.duration != null && abs(t.duration - targetSec.toDouble()) <= DURATION_TOLERANCE_S
        fun synced(t: Track) = !t.syncedLyrics.isNullOrBlank()
        return usable.firstOrNull { close(it) && synced(it) }
            ?: usable.firstOrNull { synced(it) }
            ?: usable.firstOrNull { close(it) }
            ?: usable.first()
    }

    /** LRC text through the SAME parser the Jellyfin path uses; plain text as untimed lines. */
    private fun toLines(t: Track): List<LyricLine> {
        t.syncedLyrics?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = LyricsStore.parseLyricsText(raw)
            if (parsed.any { it.isSynced }) return parsed
        }
        t.plainLyrics?.takeIf { it.isNotBlank() }?.let { raw ->
            return LyricsStore.parseLyricsText(raw)
        }
        return emptyList()
    }
}
