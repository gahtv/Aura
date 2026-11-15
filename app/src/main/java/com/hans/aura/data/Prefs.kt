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
}
