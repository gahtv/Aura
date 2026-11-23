package com.hans.aura.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    private const val KEY_AMBIENT_NP = "ambient_now_playing"
    private const val KEY_NP_DIM_MODE = "now_playing_dim_mode"
    private const val KEY_SEARCH_TIP_DISMISSED = "search_remote_tip_dismissed"
    private const val KEY_TV_CHANNEL_ID = "tv_channel_id"
    private const val KEY_TV_BROWSABLE_REQUESTED_ID = "tv_browsable_requested_id"

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

    /** Fade out Now Playing chrome after ~6s of D-pad inactivity while playing. */
    var ambientNowPlaying: Boolean
        get() = p()?.getBoolean(KEY_AMBIENT_NP, true) ?: true
        set(value) {
            p()?.edit()?.putBoolean(KEY_AMBIENT_NP, value)?.apply()
        }

    /** Drop dynamic color wash to near-black (#0E0C0A) in Now Playing. */
    var nowPlayingDimMode: Boolean
        get() = p()?.getBoolean(KEY_NP_DIM_MODE, false) ?: false
        set(value) {
            p()?.edit()?.putBoolean(KEY_NP_DIM_MODE, value)?.apply()
        }

    /** Whether the Aura Remote search tip coachmark was dismissed. */
    var searchRemoteTipDismissed: Boolean
        get() = p()?.getBoolean(KEY_SEARCH_TIP_DISMISSED, false) ?: false
        set(value) {
            p()?.edit()?.putBoolean(KEY_SEARCH_TIP_DISMISSED, value)?.apply()
        }

    /** Android TV Home Screen Channel id (-1L when not yet published). */
    var tvChannelId: Long
        get() = p()?.getLong(KEY_TV_CHANNEL_ID, -1L) ?: -1L
        set(value) {
            p()?.edit()?.putLong(KEY_TV_CHANNEL_ID, value)?.apply()
        }

    var tvBrowsableRequestedId: Long
        get() = p()?.getLong(KEY_TV_BROWSABLE_REQUESTED_ID, -1L) ?: -1L
        set(value) {
            p()?.edit()?.putLong(KEY_TV_BROWSABLE_REQUESTED_ID, value)?.apply()
        }

    // ---- Atmos filter ------------------------------------------------------
    // Global "only show Atmos content": hides anything that is not E-AC-3 JOC
    // from every browse list. Intended for the case where one library holds both
    // the standard and the Atmos copy of the same release. Per-view filtering
    // uses the icon pill instead (session-scoped, does not touch this).
    private const val KEY_ATMOS_ONLY = "atmos_only"

    var atmosOnly: Boolean
        get() = p()?.getBoolean(KEY_ATMOS_ONLY, false) ?: false
        set(value) {
            p()?.edit()?.putBoolean(KEY_ATMOS_ONLY, value)?.apply()
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

    // ---- Recent searches -------------------------------------------------
    // Appended at the end of the object on purpose: another branch also adds
    // keys here, and end-of-object keeps the merge additive.
    private const val KEY_SEARCH_RECENT = "search_recent_terms"
    private const val SEARCH_RECENT_LIMIT = 8
    private val stringListSerializer = ListSerializer(String.serializer())

    /**
     * Last [SEARCH_RECENT_LIMIT] submitted search terms, newest first.
     * Encoded as a JSON array: terms may contain commas, so a comma-joined
     * string would silently split a term like "AC/DC, live".
     */
    fun recentSearches(): List<String> {
        val raw = p()?.getString(KEY_SEARCH_RECENT, null) ?: return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, raw) }
            .onFailure { Log.w(TAG, "recent search decode failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun writeRecentSearches(list: List<String>) {
        p()?.edit()?.putString(KEY_SEARCH_RECENT, json.encodeToString(stringListSerializer, list))?.apply()
    }

    /**
     * Record a submitted term. Case-insensitive dedupe (a re-run replaces the
     * older entry rather than duplicating it), newest first, capped at
     * [SEARCH_RECENT_LIMIT]. Returns the resulting list.
     */
    fun addRecentSearch(term: String): List<String> {
        val t = term.trim()
        if (t.length < 2) return recentSearches()
        val merged = ArrayList<String>(SEARCH_RECENT_LIMIT)
        merged.add(t)
        recentSearches().forEach { old ->
            if (!old.equals(t, ignoreCase = true) && merged.size < SEARCH_RECENT_LIMIT) merged.add(old)
        }
        writeRecentSearches(merged)
        return merged
    }

    /** Drop one entry (long-press on its chip). Returns the resulting list. */
    fun removeRecentSearch(term: String): List<String> {
        val remaining = recentSearches().filterNot { it.equals(term.trim(), ignoreCase = true) }
        writeRecentSearches(remaining)
        return remaining
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
 * Stored as a map keyed by server, user and item, pruned to the newest
 * [POSITION_LIMIT] entries and expired after [POSITION_TTL_MS].
 */
object ResumePoints {
    private const val TAG = "AURA_RESUME"
    private const val KEY = "resume_points_by_profile"
    private const val LEGACY_KEY = "resume_points"
    private const val POSITION_LIMIT = 400
    private const val POSITION_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    private fun prefs() = Prefs.file()

    private fun key(session: Session, itemId: String): String =
        "${Uri.encode(session.serverUrl.trimEnd('/'))}/${Uri.encode(session.userId)}/${Uri.encode(itemId)}"

    private fun load(): MutableMap<String, String> = synchronized(lock) {
        cache?.let { return it }
        val prefs = prefs()
        // Legacy bookmarks have no owner and cannot be safely assigned.
        if (prefs?.contains(LEGACY_KEY) == true) prefs.edit().remove(LEGACY_KEY).apply()
        val raw = prefs?.getString(KEY, null)
        // Entries are "key:positionMs:epochMs"; the encoded key has no colons or pipes.
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
    fun save(session: Session, itemId: String, positionMs: Long) {
        if (itemId.isBlank() || positionMs < 5_000L) return
        synchronized(lock) {
            val map = load()
            map[key(session, itemId)] = "$positionMs:${System.currentTimeMillis()}"
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
    fun position(session: Session, itemId: String): Long? = synchronized(lock) {
        val entry = load()[key(session, itemId)] ?: return null
        val ms = entry.substringBefore(':').toLongOrNull() ?: return null
        val at = entry.substringAfter(':').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > POSITION_TTL_MS) return null
        ms
    }

    fun forget(session: Session, itemId: String) {
        synchronized(lock) {
            val map = load()
            if (map.remove(key(session, itemId)) != null) {
                prefs()?.edit()?.putString(KEY, map.entries.joinToString("|") { "${it.key}:${it.value}" })?.apply()
            }
        }
    }
}
