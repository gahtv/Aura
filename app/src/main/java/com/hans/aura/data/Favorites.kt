package com.hans.aura.data

import com.hans.aura.model.Album
import com.hans.aura.model.MediaItem
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Liked songs / favourite albums.
 *
 * The server is the source of truth (POST/DELETE UserFavoriteItems returns the
 * updated UserData), but the UI must not wait for a round trip on a TV remote:
 * a toggle flips an in-process override IMMEDIATELY, then either keeps it (2xx)
 * or reverts it (throw / 4xx / 5xx) with the HTTP code in the log.
 *
 * [overrides] is Compose state, so every TrackRow/ArtworkCard that reads
 * [isFavorite] re-renders its heart on the toggle frame. It is IN-PROCESS ONLY
 * (no persistence): data/Prefs.kt is owned by another branch, and favourites
 * survive restarts anyway because they live on the server and come back in
 * every item's UserData.
 */
object Favorites {
    private const val TAG = "AURA_FAV"

    /** Hard ceiling on the client-side favourite scan (library selection on). */
    private const val MAX_SCAN = 20_000

    /** id -> isFavorite, for items toggled this session. Wins over UserData. */
    private var overrides by mutableStateOf<Map<String, Boolean>>(emptyMap())

    fun clear() { overrides = emptyMap() }

    /** True when [item] is liked: session override first, then server UserData. */
    internal fun isFavoriteRaw(item: JellyfinItem): Boolean =
        overrides[item.id] ?: (item.userData?.isFavorite == true)

    fun isFavorite(item: MediaItem): Boolean =
        overrides[item.id] ?: when (item) {
            is Track -> item.isFavorite
            is Album -> item.isFavorite
            is Playlist -> item.isFavorite
            else -> false
        }

    /** Same, for code that only has the id (after a toggle it is always known). */
    fun isFavorite(itemId: String): Boolean = overrides[itemId] == true

    private fun set(itemId: String, value: Boolean) {
        overrides = overrides + (itemId to value)
    }

    /**
     * Flips the favourite state for [item] optimistically and persists it to
     * the server. Returns the NEW state on success, null when it was reverted.
     *
     * Retrofit's Response never throws on 4xx/5xx, so the code decides — and a
     * failed toggle must undo the heart rather than leave the UI lying.
     */
    suspend fun toggle(api: JellyfinApi, userId: String, item: MediaItem): Boolean? {
        val before = isFavorite(item)
        val now = !before
        set(item.id, now)
        val resp: retrofit2.Response<UserData> = try {
            if (now) api.markFavorite(item.id, userId) else api.unmarkFavorite(item.id, userId)
        } catch (t: Throwable) {
            set(item.id, before)
            Log.w(TAG, "item=${item.id} now=$before code=ERR error=${t.message}")
            return null
        }
        if (!resp.isSuccessful) {
            set(item.id, before)
            val body = runCatching { resp.errorBody()?.string()?.take(200) }.getOrNull()
            Log.w(TAG, "item=${item.id} now=$before code=${resp.code()} error=$body")
            return null
        }
        // Trust the server's echoed UserData when it arrives.
        resp.body()?.isFavorite?.let { set(item.id, it) }
        val after = isFavorite(item.id)
        Log.d(TAG, "item=${item.id} now=$after code=${resp.code()}")
        evictCaches(userId, item)
        LibraryCache.clearDiskLists()
        return after
    }

    /** Forces a known state (e.g. seeding from a fresh server read). */
    fun adopt(itemId: String, value: Boolean) = set(itemId, value)

    /**
     * A favourite change rewrites UserData inside cached lists. Without these
     * evictions the Home shelf and every album page keep serving the pre-toggle
     * heart for the 24h TTL.
     *
     * Keys (verified against the call sites): "home_recent:<userId>",
     * "col:<albumId>" for the album tracklist, and the library tab keys
     * for Tracks and Liked, including hydrated Atmos favorites. Each list can
     * be cached under multiple sorts and library selections.
     */
    private fun evictCaches(userId: String, item: MediaItem) {
        LibraryCache.evict("home_recent:$userId")
        (item as? Track)?.albumId?.let { LibraryCache.evict("col:$it") }
        fun isLikedAtmosKey(key: String) = key.substringBefore('#').endsWith(":fav")
        fun isAffectedPage(key: String): Boolean =
            key.startsWith("lib_tracks:$userId:") || key.startsWith("lib_liked:$userId:") ||
                (key.startsWith("lib_atmos_tracks:$userId:") && isLikedAtmosKey(key))

        LibraryCache.evictWhere { key ->
            isAffectedPage(key) ||
                (key.startsWith("atmos_audio:$userId:") && isLikedAtmosKey(key))
        }
        PagedLibrary.evictWhere(::isAffectedPage)
        Log.d(TAG, "evict item=${item.id} album=${(item as? Track)?.albumId ?: "-"} userId=$userId")
    }

    /**
     * One page of liked AUDIO, in the same 60-item shape the library tabs use.
     *
     * Nothing hidden: a single server-side query with Filters=IsFavorite (the
     * exact pass-through shape LibraryFilter itself issues in that state).
     *
     * Libraries hidden: /Items cannot exclude a library subtree, so the same
     * per-library fan-out runs and the favourite test happens here, on what
     * the fan-out returned. Slower than a server-side `Filters` query, but
     * hidden libraries can never leak into the list.
     */
    suspend fun browseLiked(
        api: JellyfinApi,
        userId: String,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse {
        if (!LibraryFilter.isActive()) {
            return api.getItems(
                userId = userId,
                includeItemTypes = "Audio",
                filters = "IsFavorite",
                enableUserData = true,
                recursive = true,
                sortBy = sortBy,
                sortOrder = sortOrder,
                limit = limit,
                startIndex = startIndex
            )
        }
        val liked = mutableListOf<JellyfinItem>()
        var total = 0
        var offset = 0
        while (offset < MAX_SCAN) {
            val page = LibraryFilter.queryItems(
                api, userId,
                LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio",
                    recursive = true,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    limit = 500,
                    startIndex = offset
                )
            )
            total = page.totalRecordCount
            liked += page.items.filter { isFavoriteRaw(it) }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= total) break
        }
        val all = liked.distinctBy { it.id }
        Log.d(TAG, "liked scan hidden-libs offset=$offset total=$total liked=${all.size}")
        return ItemsResponse(
            items = all.drop(startIndex).take(limit),
            totalRecordCount = all.size,
            startIndex = startIndex
        )
    }
}
