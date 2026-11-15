package com.hans.aura.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A Jellyfin library ("UserView") as offered in Settings > Libraries shown in Aura. */
@Serializable
data class LibraryRef(
    val id: String,
    val name: String,
    /** e.g. "music", "movies", "playlists", "boxsets" (BaseItemDto.CollectionType). */
    val collectionType: String? = null
)

/**
 * App preferences — the one SharedPreferences store for Aura's own settings
 * (file "aura_prefs"). Currently holds library selection: the ids of libraries
 * the user toggled OFF, plus the last library list fetched from the server so
 * the data layer can turn "hidden ids" into "visible parents to query".
 *
 * The session lives in AuthStore ("aura_session") and cache stamps in
 * LibraryCache ("aura_cache"); do not add a third store for either.
 */
object Prefs {
    private const val TAG = "AURA_LIBS"
    private const val FILE = "aura_prefs"
    private const val KEY_HIDDEN = "hidden_library_ids"
    private const val KEY_LIBRARIES = "known_libraries"
    private const val KEY_LIBRARIES_AT = "known_libraries_at_ms"
    private const val KEY_AUTOPLAY = "autoplay_music"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(LibraryRef.serializer())

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Called once from AuraApp.onCreate. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences? = prefs

    /** The aura_prefs file, for the small stores that live beside the toggles. */
    internal fun file(): SharedPreferences? = prefs

    /**
     * Libraries the user switched OFF. Empty = show everything (and the data
     * layer keeps its single-query path).
     */
    var hiddenLibraries: Set<String>
        get() = (p()?.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toSet()
        set(value) {
            // toSet() again: SharedPreferences must never receive a set it may
            // later hand back by reference.
            p()?.edit()?.putStringSet(KEY_HIDDEN, value.toSet())?.apply()
        }

    /**
     * Last library list seen from GET /UserViews. Needed by the query layer:
     * without the full list, "everything except these ids" cannot be turned
     * into the per-library ParentId queries that make hiding real.
     */
    var knownLibraries: List<LibraryRef>
        get() {
            val raw = p()?.getString(KEY_LIBRARIES, null) ?: return emptyList()
            return runCatching { json.decodeFromString(listSerializer, raw) }
                .onFailure { Log.w(TAG, "known library list decode failed: ${it.message}") }
                .getOrDefault(emptyList())
        }
        set(value) {
            p()?.edit()
                ?.putString(KEY_LIBRARIES, json.encodeToString(listSerializer, value))
                ?.putLong(KEY_LIBRARIES_AT, System.currentTimeMillis())
                ?.apply()
        }

    val knownLibrariesAtMs: Long get() = p()?.getLong(KEY_LIBRARIES_AT, 0L) ?: 0L

    /**
     * "Keep the music going": when the queue reaches its last track with repeat
     * off, PlayerConnection appends an Instant Mix seeded from that track
     * instead of ending. On by default — this is the behaviour an endless queue
     * is expected to have; the Settings toggle exists to turn it off.
     */
    var autoplayMusic: Boolean
        get() = p()?.getBoolean(KEY_AUTOPLAY, true) ?: true
        set(value) {
            p()?.edit()?.putBoolean(KEY_AUTOPLAY, value)?.apply()
        }

    // ---- Lyrics (B10) ------------------------------------------------------
    // Appended at the END of the object on purpose: another branch adds its own
    // keys here in parallel, and both sides are purely additive.

    private const val KEY_LYRICS_WEB = "lyrics_web_fallback"
    private const val KEY_LYRICS_OFFSET = "lyrics_offset_"

    /** Largest nudge a track may carry in either direction. */
    const val LYRICS_OFFSET_MAX_MS: Long = 10_000L

    /**
     * Settings > "Look up lyrics online": when Jellyfin has no lyrics for a
     * track, ask LRCLIB. OFF by default — it is a request to a third party.
     */
    var lyricsWebFallback: Boolean
        get() = p()?.getBoolean(KEY_LYRICS_WEB, false) ?: false
        set(value) {
            p()?.edit()?.putBoolean(KEY_LYRICS_WEB, value)?.apply()
        }

    /**
     * Per-track lyric timing nudge in milliseconds, key `lyrics_offset_<trackId>`.
     * Positive = the lyric line is shown EARLIER than the audio timestamp.
     * A zero offset removes the key instead of storing 0.
     */
    fun lyricsOffsetMs(trackId: String): Long {
        if (trackId.isBlank()) return 0L
        return (p()?.getLong(KEY_LYRICS_OFFSET + trackId, 0L) ?: 0L)
            .coerceIn(-LYRICS_OFFSET_MAX_MS, LYRICS_OFFSET_MAX_MS)
    }

    fun setLyricsOffsetMs(trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        val clamped = offsetMs.coerceIn(-LYRICS_OFFSET_MAX_MS, LYRICS_OFFSET_MAX_MS)
        val edit = p()?.edit() ?: return
        if (clamped == 0L) edit.remove(KEY_LYRICS_OFFSET + trackId).apply()
        else edit.putLong(KEY_LYRICS_OFFSET + trackId, clamped).apply()
    }
}

/**
 * Resume points, kept LOCALLY.
 *
 * Jellyfin 12.2.0 does not write UserData.PlaybackPositionTicks from
 * /Sessions/Playing/Progress or /Stopped — verified against the live server by
 * posting spec-shaped reports with a 70 s position and reading the item back
 * (still 0). So "resume where you left off" has to be remembered by Aura.
 *
 * Stored as one JSON map itemId -> "positionMs:epochMs", pruned to the newest
 * [POSITION_LIMIT] entries and expired after [POSITION_TTL_MS].
 */
object ResumePoints {
    private const val TAG = "AURA_RESUME"
    private const val KEY = "resume_points"
    private const val POSITION_LIMIT = 400
    private const val POSITION_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    private fun prefs() = Prefs.file()

    private fun load(): MutableMap<String, String> = synchronized(lock) {
        cache?.let { return it }
        val raw = prefs()?.getString(KEY, null)
        // Entries are "itemId:positionMs:epochMs" — the id is everything before
        // the FIRST colon (a Guid cannot contain one), the value is the rest.
        val parsed = if (raw.isNullOrBlank()) mutableMapOf() else
            runCatching {
                raw.split('|').mapNotNull { entry ->
                    val id = entry.substringBefore(':', "")
                    val value = entry.substringAfter(':', "")
                    if (id.isBlank() || value.isBlank()) null else id to value
                }.toMap().toMutableMap()
            }.getOrElse { mutableMapOf() }
        cache = parsed
        parsed
    }

    /** Remember where [itemId] was left. Called from the reporter every ~10 s. */
    fun save(itemId: String, positionMs: Long) {
        if (itemId.isBlank() || positionMs < 5_000L) return
        synchronized(lock) {
            val map = load()
            map[itemId] = "$positionMs:${System.currentTimeMillis()}"
            if (map.size > POSITION_LIMIT) {
                // Drop the oldest by timestamp, not by insertion order.
                val keep = map.entries.sortedByDescending { it.value.substringAfter(':').toLongOrNull() ?: 0L }
                    .take(POSITION_LIMIT).associate { it.key to it.value }
                map.clear(); map.putAll(keep)
            }
            prefs()?.edit()?.putString(KEY, map.entries.joinToString("|") { "${it.key}:${it.value}" })?.apply()
        }
    }

    /** Saved position for [itemId], or null when stale/unknown. */
    fun position(itemId: String): Long? = synchronized(lock) {
        val entry = load()[itemId] ?: return null
        val ms = entry.substringBefore(':').toLongOrNull() ?: return null
        val at = entry.substringAfter(':').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > POSITION_TTL_MS) return null
        ms
    }

    fun forget(itemId: String) {
        synchronized(lock) {
            val map = load()
            if (map.remove(itemId) != null) {
                prefs()?.edit()?.putString(KEY, map.entries.joinToString("|") { "${it.key}:${it.value}" })?.apply()
            }
        }
    }
}
