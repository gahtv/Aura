package com.hans.aura.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Library selection enforcement, at the DATA layer.
 *
 * Jellyfin's /Items has no "exclude this library subtree" filter — `excludeItemIds`
 * (per the OpenAPI spec) drops the listed ids themselves, not their descendants,
 * so it cannot express "hide this library". The supported filter that does is
 * `parentId`, which localises a query to one folder. So when the user has hidden
 * libraries we issue one recursive query per VISIBLE library and merge the
 * results here: hidden libraries are never queried, therefore nothing from them
 * can reach LibraryCache / PagedLibrary / AtmosCatalog.
 *
 * With no hidden libraries every call goes through the original single-query
 * path, so users who hide nothing see byte-identical behaviour and cost.
 *
 * EXEMPT (never filtered): direct item lookups (getItem, /Items?Ids=, playlist
 * items by id) and anything the remote "Play On" path fetches. Filtering those
 * empties detail screens and breaks remote control.
 */
object LibraryFilter {
    private const val TAG = "AURA_LIBS"

    /** Per-library page cap: one library may have to answer a deep page. */
    private const val MAX_FETCH_PER_LIBRARY = 5000
    private const val MAX_CURSOR_SHAPES = 4
    // Only SortName browses reproduce server ordering; other sorts and search retain prefix merging.
    private val INCREMENTAL_CURSOR_SORTS = setOf("SortName")
    private val cursorCacheLock = Any()
    private var cursors = newCursorCache()

    private fun newCursorCache() = LinkedHashMap<CursorKey, CursorState>(MAX_CURSOR_SHAPES, 0.75f, true)

    private class ApiIdentity(val api: JellyfinApi) {
        override fun equals(other: Any?): Boolean = other is ApiIdentity && api === other.api
        override fun hashCode(): Int = System.identityHashCode(api)
    }

    private data class CursorKey(
        val api: ApiIdentity,
        val userId: String,
        val query: ItemsQuery,
        val libraries: List<LibraryRef>,
        val suffix: String,
        val artists: Boolean
    )

    private class SourceState {
        val buffer = ArrayDeque<JellyfinItem>()
        var fetched = 0
        var total = 0
        var exhausted = false
    }

    private class CursorState(sourceCount: Int) {
        val mutex = Mutex()
        var sources = List(sourceCount) { SourceState() }
            private set
        val merged = mutableListOf<JellyfinItem>()
        val seen = HashSet<String>()

        fun reset() {
            sources = List(sources.size) { SourceState() }
            merged.clear()
            seen.clear()
        }

        fun response(q: ItemsQuery, failed: Set<Int>): ItemsResponse = ItemsResponse(
            items = merged.drop(q.startIndex).take(q.limit),
            totalRecordCount = sources.filterIndexed { index, _ -> index !in failed }.sumOf { it.total },
            startIndex = q.startIndex
        )

        fun nextItem(comparator: Comparator<JellyfinItem>, failed: Set<Int>): JellyfinItem? {
            var smallest: Int? = null
            for ((index, source) in sources.withIndex()) {
                if (index in failed || source.buffer.isEmpty()) continue
                val previous = smallest
                if (previous == null || comparator.compare(source.buffer.first(),
                        sources[previous].buffer.first()) < 0) smallest = index
            }
            return smallest?.let { sources[it].buffer.removeFirst() }
        }
    }

    private suspend fun cursorPage(
        api: JellyfinApi,
        userId: String,
        q: ItemsQuery,
        libs: List<LibraryRef>,
        artists: Boolean = false,
        sourceCount: Int = libs.size,
        fetch: suspend (source: Int, offset: Int, limit: Int) -> ItemsResponse
    ): ItemsResponse {
        val key = CursorKey(ApiIdentity(api), userId, q.copy(startIndex = 0, limit = 0),
            libs.toList(), cacheSuffix(), artists)
        val state = synchronized(cursorCacheLock) {
            cursors.getOrPut(key) { CursorState(sourceCount) }.also {
                while (cursors.size > MAX_CURSOR_SHAPES) cursors.remove(cursors.keys.first())
            }
        }
        return state.mutex.withLock {
            try {
                if (q.startIndex == 0) state.reset()
                val failed = mutableMapOf<Int, Throwable>()
                mergeForward(state, q, failed, fetch)
                state.response(q, failed.keys).also {
                    if (failed.isNotEmpty()) discardCursor(key, state)
                }
            } catch (e: Throwable) {
                discardCursor(key, state)
                throw e
            }
        }
    }

    private fun discardCursor(key: CursorKey, state: CursorState) {
        synchronized(cursorCacheLock) {
            if (cursors[key] === state) cursors.remove(key)
        }
        state.reset()
    }

    private suspend fun mergeForward(
        state: CursorState,
        q: ItemsQuery,
        failed: MutableMap<Int, Throwable>,
        fetch: suspend (source: Int, offset: Int, limit: Int) -> ItemsResponse
    ) {
        val comparator = requireNotNull(comparatorFor(q.sortBy, q.sortOrder))
        while (state.merged.size.toLong() < q.startIndex.toLong() + q.limit) {
            val restart = fillSources(state, q.limit, comparator, failed, fetch)
            if (failed.size == state.sources.size) throw failed.values.first()
            if (restart) {
                // Rebuild the page without failed sources; never retain a partial cursor.
                state.reset()
                continue
            }
            val item = state.nextItem(comparator, failed.keys) ?: break
            // Duplicate ids refer to the same item and carry identical comparator fields.
            if (state.seen.add(item.id)) state.merged.add(item)
        }
    }

    private suspend fun fillSources(
        state: CursorState,
        limit: Int,
        comparator: Comparator<JellyfinItem>,
        failed: MutableMap<Int, Throwable>,
        fetch: suspend (source: Int, offset: Int, limit: Int) -> ItemsResponse
    ): Boolean {
        var restart = false
        for ((index, source) in state.sources.withIndex()) {
            if (index in failed || source.exhausted || source.buffer.isNotEmpty()) continue
            val count = limit.coerceAtMost(MAX_FETCH_PER_LIBRARY - source.fetched)
            try {
                val response = fetch(index, source.fetched, count)
                val rows = response.items.take(count).sortedWith(comparator)
                source.buffer.addAll(rows)
                source.fetched += rows.size
                source.total = response.totalRecordCount
                source.exhausted = rows.isEmpty() || source.fetched >= source.total ||
                    source.fetched >= MAX_FETCH_PER_LIBRARY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "paging source $index query failed: ${e.message}")
                failed[index] = e
                restart = true
            }
        }
        return restart
    }

    /** Throttle: log the strategy once per distinct request shape, not per page. */
    @Volatile
    private var lastLogSignature: String? = null

    /** All parameters of a browse query, in the shape JellyfinApi.getItems takes. */
    data class ItemsQuery(
        val includeItemTypes: String? = null,
        val excludeItemTypes: String? = null,
        val genres: String? = null,
        /**
         * Server-side item filter (`IsFavorite`, `IsPlayed`, …). Passed through
         * to every query of a per-library fan-out, so a filtered browse still
         * never reads a hidden library.
         */
        val filters: String? = null,
        val recursive: Boolean = true,
        val parentId: String? = null,
        val artistIds: String? = null,
        val albumArtistIds: String? = null,
        val searchTerm: String? = null,
        /**
         * Hydrates a known id set (the Atmos browse path: ids come from the
         * whole-library scan, metadata is fetched in chunks). A direct lookup:
         * never fanned out per library, like [parentId].
         */
        val ids: String? = null,
        val sortBy: String = "SortName",
        val sortOrder: String = "Ascending",
        val limit: Int = 100,
        val startIndex: Int = 0,
        val fields: String = DEFAULT_FIELDS,
        val imageTypeLimit: Int = 1,
        val nameStartsWithOrGreater: String? = null,
        val enableUserData: Boolean = true
    ) {
        companion object {
            const val DEFAULT_FIELDS = "PrimaryImageAspectRatio,MediaSources,ChildCount,Path,UserData"
        }
    }

    /** Hidden library ids (empty = nothing hidden). */
    fun hidden(): Set<String> = Prefs.hiddenLibraries

    /** Libraries the user kept: the stored /UserViews list minus the hidden ids. */
    fun visibleLibraries(): List<LibraryRef> {
        val hiddenIds = Prefs.hiddenLibraries
        return Prefs.knownLibraries.filterNot { it.id in hiddenIds }
    }

    /** True when browse queries have to be split per visible library. */
    fun isActive(): Boolean = Prefs.hiddenLibraries.isNotEmpty() && Prefs.knownLibraries.isNotEmpty()

    /** Item types that can only live in a music library. */
    private val MUSIC_TYPES = setOf("audio", "musicalbum", "musicartist", "audiobook")

    /**
     * The libraries a query should fan out to.
     *
     * A music query must not be pointed at a movies/tvshows/playlists library:
     * those roots do hold stray items of the requested type (a playlist's
     * albums, for instance), so asking them for MusicAlbum makes albums appear
     * on the Albums tab even when every music library is switched off — seen on
     * the emulator. Unknown/absent collectionType is always included, because a
     * mixed-content library can legitimately hold music.
     */
    private fun librariesFor(includeItemTypes: String?): List<LibraryRef> {
        val libs = visibleLibraries()
        val wanted = includeItemTypes
            ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            .orEmpty()
        if (wanted.none { it in MUSIC_TYPES }) return libs
        return libs.filter {
            it.collectionType == null || it.collectionType.equals("music", ignoreCase = true)
        }
    }

    /**
     * Suffix appended to every cache key (LibraryCache + PagedLibrary) so data
     * fetched under one selection can never be served under another. Empty when
     * nothing is hidden, i.e. the historical key = the cache key.
     */
    fun cacheSuffix(): String {
        val hiddenIds = Prefs.hiddenLibraries
        if (hiddenIds.isEmpty()) return ""
        return "#h=" + hiddenIds.sorted().joinToString(",")
    }

    /** Pulls the user's library list and remembers it (and the hidden set stays). */
    suspend fun refreshLibraryList(api: JellyfinApi, userId: String): List<LibraryRef> {
        val views = api.getUserViews(userId = userId)
        val libs = views.items.mapNotNull { item ->
            item.id.takeIf { it.isNotBlank() }?.let {
                LibraryRef(id = it, name = item.name, collectionType = item.collectionType)
            }
        }
        Prefs.knownLibraries = libs
        // Ids that no longer exist on the server must not linger in the hidden
        // set — they would silently filter nothing and confuse the log line.
        val liveIds = libs.map { it.id }.toSet()
        val hidden = Prefs.hiddenLibraries
        if (hidden.any { it !in liveIds }) Prefs.hiddenLibraries = hidden.filter { it in liveIds }.toSet()
        logState()
        return libs
    }

    /**
     * Browse query (shelves, library tabs, search): honours the hidden set.
     * A query already scoped to a parent (album tracklist, playlist tracks) or
     * to a known id set (Atmos hydration) is passed through untouched — that is
     * a direct item lookup, not a browse.
     */
    suspend fun queryItems(api: JellyfinApi, userId: String, q: ItemsQuery): ItemsResponse {
        // Playlists are user-owned virtual content, not library content: hiding
        // a music library must not hide the playlists tab.
        if (!isActive() || q.parentId != null || q.ids != null || q.includeItemTypes == "Playlist") {
            return api.getItems(
                userId = userId,
                includeItemTypes = q.includeItemTypes,
                excludeItemTypes = q.excludeItemTypes,
                genres = q.genres,
                filters = q.filters,
                enableUserData = q.enableUserData,
                recursive = q.recursive,
                parentId = q.parentId,
                artistIds = q.artistIds,
                albumArtistIds = q.albumArtistIds,
                searchTerm = q.searchTerm,
                ids = q.ids,
                sortBy = q.sortBy,
                sortOrder = q.sortOrder,
                limit = q.limit,
                startIndex = q.startIndex,
                fields = q.fields,
                imageTypeLimit = q.imageTypeLimit,
                nameStartsWithOrGreater = q.nameStartsWithOrGreater
            )
        }
        val libs = librariesFor(q.includeItemTypes)
        if (libs.isEmpty()) {
            // Everything hidden: return nothing rather than falling back to an
            // unfiltered query that would show what the user just switched off.
            Log.w(TAG, "all libraries hidden — returning empty result for ${q.includeItemTypes}")
            return ItemsResponse()
        }
        if (q.limit > 0 && q.sortBy in INCREMENTAL_CURSOR_SORTS && q.searchTerm == null) {
            val cursorQuery = q.copy(fields = withSortName(q.fields))
            val playlists = q.includeItemTypes?.contains("Playlist", ignoreCase = true) == true
            logStrategy(q.includeItemTypes, libs)
            return cursorPage(api, userId, q, libs, sourceCount = libs.size + if (playlists) 1 else 0) { index, offset, count ->
                if (index < libs.size) {
                    queryItems(api, userId, cursorQuery.copy(parentId = libs[index].id, startIndex = offset, limit = count))
                } else {
                    queryItems(api, userId, ItemsQuery(includeItemTypes = "Playlist", filters = q.filters,
                        enableUserData = q.enableUserData, searchTerm = q.searchTerm, sortBy = q.sortBy,
                        sortOrder = q.sortOrder, limit = count, startIndex = offset, fields = cursorQuery.fields,
                        imageTypeLimit = q.imageTypeLimit, nameStartsWithOrGreater = q.nameStartsWithOrGreater))
                }
            }
        }
        val perLibraryLimit = (q.startIndex + q.limit).coerceAtMost(MAX_FETCH_PER_LIBRARY)
        val fields = fieldsForMerge(q.fields, q.sortBy)
        val responses = libs.map { lib ->
            runCatching {
                api.getItems(
                    userId = userId,
                    includeItemTypes = q.includeItemTypes,
                    excludeItemTypes = q.excludeItemTypes,
                    genres = q.genres,
                    filters = q.filters,
                    enableUserData = q.enableUserData,
                    recursive = q.recursive,
                    parentId = lib.id,
                    artistIds = q.artistIds,
                    albumArtistIds = q.albumArtistIds,
                    searchTerm = q.searchTerm,
                    ids = q.ids,
                    sortBy = q.sortBy,
                    sortOrder = q.sortOrder,
                    limit = perLibraryLimit,
                    startIndex = 0,
                    fields = fields,
                    imageTypeLimit = q.imageTypeLimit,
                    nameStartsWithOrGreater = q.nameStartsWithOrGreater
                )
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "library \"${lib.name}\" (${lib.id}) query failed: ${it.message}")
            }
        }
        // Mixed-type queries (search) also ask for Playlists, which live in the
        // server's virtual playlists view rather than inside a music library —
        // without this they would vanish from results once anything is hidden.
        val playlists = if (q.includeItemTypes?.contains("Playlist", ignoreCase = true) == true) {
            listOf(
                runCatching {
                    api.getItems(
                        userId = userId,
                        includeItemTypes = "Playlist",
                        filters = q.filters,
                        enableUserData = q.enableUserData,
                        recursive = true,
                        searchTerm = q.searchTerm,
                        sortBy = q.sortBy,
                        sortOrder = q.sortOrder,
                        limit = perLibraryLimit,
                        startIndex = 0,
                        fields = fields,
                        imageTypeLimit = q.imageTypeLimit,
                        nameStartsWithOrGreater = q.nameStartsWithOrGreater
                    )
                }.onFailure { if (it is CancellationException) throw it }
            )
        } else emptyList()
        logStrategy(q.includeItemTypes, libs)
        return merge(successfulResponses(responses + playlists), q)
    }

    /** Same treatment for the album-artist endpoint (Artists tab + Home shelf). */
    suspend fun queryAlbumArtists(
        api: JellyfinApi,
        userId: String,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 200,
        startIndex: Int = 0,
        fields: String = "PrimaryImageAspectRatio",
        imageTypeLimit: Int = 1
    ): ItemsResponse {
        if (!isActive()) {
            return api.getAlbumArtists(
                userId = userId, sortBy = sortBy, sortOrder = sortOrder,
                limit = limit, startIndex = startIndex,
                fields = fields, imageTypeLimit = imageTypeLimit
            )
        }
        val libs = librariesFor("MusicArtist")
        if (libs.isEmpty()) {
            Log.w(TAG, "all music libraries hidden — returning empty artist result")
            return ItemsResponse()
        }
        if (limit > 0 && sortBy in INCREMENTAL_CURSOR_SORTS) {
            logStrategy("MusicArtist", libs)
            return cursorPage(api, userId, ItemsQuery(sortBy = sortBy, sortOrder = sortOrder,
                limit = limit, startIndex = startIndex, fields = fields, imageTypeLimit = imageTypeLimit),
                libs, artists = true) { index, offset, count ->
                api.getAlbumArtists(userId = userId, sortBy = sortBy, sortOrder = sortOrder,
                    limit = count, startIndex = offset, fields = withSortName(fields), imageTypeLimit = imageTypeLimit,
                    parentId = libs[index].id)
            }
        }
        val perLibraryLimit = (startIndex + limit).coerceAtMost(MAX_FETCH_PER_LIBRARY)
        val mergeFields = fieldsForMerge(fields, sortBy)
        val responses = libs.map { lib ->
            runCatching {
                api.getAlbumArtists(
                    userId = userId, sortBy = sortBy, sortOrder = sortOrder,
                    limit = perLibraryLimit, startIndex = 0,
                    fields = mergeFields, imageTypeLimit = imageTypeLimit,
                    parentId = lib.id
                )
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "library \"${lib.name}\" artist query failed: ${it.message}")
            }
        }
        logStrategy("MusicArtist", libs)
        return merge(successfulResponses(responses), ItemsQuery(sortBy = sortBy, sortOrder = sortOrder,
            limit = limit, startIndex = startIndex))
    }

    private fun successfulResponses(results: List<Result<ItemsResponse>>): List<ItemsResponse> {
        // An unreadable library must not kill the whole screen. Keep any answered
        // page (including playlists); only a total failure must remain retryable.
        val responses = results.mapNotNull { it.getOrNull() }
        if (responses.isEmpty()) results.firstOrNull()?.getOrThrow()
        return responses
    }

    /** Jellyfin omits DateCreated unless requested; the merge comparator needs it. */
    private fun fieldsForMerge(fields: String, sortBy: String): String {
        if (!sortBy.contains("DateCreated", ignoreCase = true) ||
            fields.split(',').any { it.trim().equals("DateCreated", ignoreCase = true) }
        ) return fields
        return if (fields.isBlank()) "DateCreated" else "$fields,DateCreated"
    }

    /** Merge per-library pages: honour the requested sort, then the page window. */
    private fun merge(responses: List<ItemsResponse>, q: ItemsQuery): ItemsResponse {
        if (responses.isEmpty()) return ItemsResponse()
        val all = responses.flatMap { it.items }.distinctBy { it.id }
        val total = responses.sumOf { it.totalRecordCount }
        val ordered = comparatorFor(q.sortBy, q.sortOrder)?.let { all.sortedWith(it) } ?: all
        val page = ordered.drop(q.startIndex).take(q.limit)
        return ItemsResponse(items = page, totalRecordCount = total, startIndex = q.startIndex)
    }

    private fun withSortName(fields: String): String =
        if (fields.split(',').any { it.trim().equals("SortName", ignoreCase = true) }) fields
        else listOf(fields, "SortName").filter { it.isNotBlank() }.joinToString(",")

    /**
     * Re-creates the server's ordering for the sort the caller asked for. Each
     * per-library response is already sorted, so this only has to interleave
     * them — i.e. compare on the same field the server sorted on. Sort fields
     * we cannot reproduce (Random) return null and keep the server order.
     */
    private fun comparatorFor(sortBy: String, sortOrder: String): Comparator<JellyfinItem>? {
        val desc = sortOrder.equals("Descending", ignoreCase = true)
        val selectors: Array<(JellyfinItem) -> Comparable<*>?> = when {
            sortBy.contains("Random", ignoreCase = true) -> return null
            sortBy.contains("DatePlayed", ignoreCase = true) -> arrayOf({ it.userData?.lastPlayedDate }, { it.name.lowercase() })
            sortBy.contains("DateCreated", ignoreCase = true) -> arrayOf({ it.dateCreated }, { it.name.lowercase() })
            sortBy.contains("Runtime", ignoreCase = true) -> arrayOf({ it.runTimeTicks }, { it.name.lowercase() })
            sortBy.contains("PlayCount", ignoreCase = true) -> arrayOf({ it.userData?.playCount }, { it.name.lowercase() })
            sortBy.contains("AlbumArtist", ignoreCase = true) -> arrayOf({ it.albumArtist?.lowercase() ?: it.name.lowercase() }, { it.name.lowercase() })
            sortBy.contains("Album", ignoreCase = true) -> arrayOf({ it.album?.lowercase() ?: it.name.lowercase() }, { it.name.lowercase() })
            else -> arrayOf({ it.sortName?.lowercase() ?: it.name.lowercase() })
        }
        val cmp = Comparator<JellyfinItem> { a, b -> compareValuesBy(a, b, *selectors) }
        return if (desc) cmp.reversed() else cmp
    }

    /** One AURA_LIBS line per distinct query shape, listing ids + strategy. */
    private fun logStrategy(itemTypes: String?, libs: List<LibraryRef>) {
        val signature = "$itemTypes|${libs.joinToString(",") { it.id }}"
        if (signature == lastLogSignature) return
        lastLogSignature = signature
        Log.i(
            TAG,
            "strategy=per-library types=${itemTypes ?: "*"} visible=[${libs.joinToString(",") { it.id }}]" +
                " hidden=[${hidden().sorted().joinToString(",")}]"
        )
    }

    /** Startup / settings-time breadcrumb: who is visible and who is hidden. */
    fun logState() {
        val libs = Prefs.knownLibraries
        val hiddenIds = hidden()
        Log.i(
            TAG,
            if (hiddenIds.isEmpty()) {
                "strategy=single (no hidden libraries) known=[${libs.joinToString(",") { it.id }}]"
            } else {
                "known=[${libs.joinToString(",") { "${it.id}:${it.name}" }}]" +
                    " visible=[${libs.filterNot { it.id in hiddenIds }.joinToString(",") { it.id }}]" +
                    " hidden=[${hiddenIds.sorted().joinToString(",")}] strategy=per-library"
            }
        )
    }

    /** Call after any change of the hidden set / library list. */
    fun clearCaches() {
        synchronized(cursorCacheLock) { cursors = newCursorCache() }
        LibraryCache.clear()
        PagedLibrary.clear()
        AtmosCatalog.clear()
    }
}
