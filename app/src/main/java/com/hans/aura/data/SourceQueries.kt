package com.hans.aura.data

import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.CollectionItem
import com.hans.aura.model.Genre
import com.hans.aura.model.MediaItem
import com.hans.aura.model.MediaPage
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track

/**
 * Browse facade used by screens and ViewModels. The installed [MusicSource]
 * continues to work in wire DTOs; every public answer here is a domain model.
 */
object SourceQueries {

    private fun source(): MusicSource? = MusicSources.current

    suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
        checkNotNull(source()) { "No music source available" }.removeFromPlaylist(playlistId, itemId, occurrence)
    }

    private suspend fun cache(key: String?, items: List<JellyfinItem>) {
        if (key != null) source()?.cache(key, items)
    }

    suspend fun cachedItems(prefix: String): List<MediaItem>? = source()?.cached(prefix)?.toDomainItems()
    suspend fun cachedTracks(prefix: String): List<Track>? = source()?.cached(prefix)?.toTracks()
    suspend fun cachedAlbums(prefix: String): List<Album>? = source()?.cached(prefix)?.toAlbums()
    suspend fun cachedArtists(prefix: String): List<Artist>? = source()?.cached(prefix)?.toArtists()
    suspend fun cachedPlaylists(prefix: String): List<Playlist>? = source()?.cached(prefix)?.toPlaylists()

    suspend fun recentlyPlayed(limit: Int = 20, cacheKey: String? = null): List<MediaItem> {
        val raw = source()?.recentlyPlayed(limit).orEmpty()
        cache(cacheKey, raw)
        return raw.toDomainItems()
    }

    suspend fun recentArtists(limit: Int = 20, cacheKey: String? = null): List<Artist> {
        val raw = source()?.recentArtists(limit).orEmpty()
        cache(cacheKey, raw)
        return raw.toArtists()
    }

    private suspend fun rawItems(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): ItemsResponse = source()?.browse(query, cachePrefix) ?: ItemsResponse()

    /** Mixed results (search/debug); use a typed call when the query is single-type. */
    suspend fun items(query: LibraryFilter.ItemsQuery, cachePrefix: String? = null): MediaPage<MediaItem> =
        rawItems(query, cachePrefix).toDomainPage()

    suspend fun tracks(query: LibraryFilter.ItemsQuery, cacheKey: String? = null): MediaPage<Track> {
        val raw = rawItems(query)
        cache(cacheKey, raw.items)
        return raw.toDomainPage().typed()
    }

    suspend fun albums(query: LibraryFilter.ItemsQuery, cacheKey: String? = null): MediaPage<Album> {
        val raw = rawItems(query)
        cache(cacheKey, raw.items)
        return raw.toDomainPage().typed()
    }

    suspend fun playlists(query: LibraryFilter.ItemsQuery, cacheKey: String? = null): MediaPage<Playlist> {
        val raw = rawItems(query)
        cache(cacheKey, raw.items)
        return raw.toDomainPage().typed()
    }

    /** Named-argument form matching the original `api.getItems(...)` call sites. */
    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    suspend fun items(
        userId: String? = null,
        includeItemTypes: String? = null,
        excludeItemTypes: String? = null,
        genres: String? = null,
        filters: String? = null,
        enableUserData: Boolean = true,
        recursive: Boolean = true,
        parentId: String? = null,
        artistIds: String? = null,
        albumArtistIds: String? = null,
        searchTerm: String? = null,
        ids: String? = null,
        fields: String? = null,
        imageTypeLimit: Int? = null,
        nameStartsWithOrGreater: String? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0
    ): MediaPage<MediaItem> = items(
        LibraryFilter.ItemsQuery(
            includeItemTypes = includeItemTypes,
            excludeItemTypes = excludeItemTypes,
            genres = genres,
            filters = filters,
            enableUserData = enableUserData,
            fields = fields ?: LibraryFilter.ItemsQuery.DEFAULT_FIELDS,
            imageTypeLimit = imageTypeLimit ?: 1,
            nameStartsWithOrGreater = nameStartsWithOrGreater,
            ids = ids,
            recursive = recursive,
            parentId = parentId,
            artistIds = artistIds,
            albumArtistIds = albumArtistIds,
            searchTerm = searchTerm,
            sortBy = sortBy,
            sortOrder = sortOrder,
            limit = limit,
            startIndex = startIndex
        )
    )

    suspend fun artists(
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0,
        cacheKey: String? = null
    ): MediaPage<Artist> {
        val raw = source()?.artists(sortBy, sortOrder, limit, startIndex) ?: ItemsResponse()
        cache(cacheKey, raw.items)
        return raw.toDomainPage().typed()
    }

    suspend fun item(itemId: String): MediaItem? = source()?.item(itemId)?.toDomainItem()

    suspend fun artistAlbums(artistId: String, limit: Int = 100, cacheKey: String? = null): List<Album> {
        val raw = source()?.artistAlbums(artistId, limit).orEmpty()
        cache(cacheKey, raw)
        return raw.toAlbums()
    }

    suspend fun artistTopTracks(artistId: String, limit: Int = 10, cacheKey: String? = null): List<Track> {
        val raw = source()?.artistTopTracks(artistId, limit).orEmpty()
        cache(cacheKey, raw)
        return raw.toTracks()
    }

    suspend fun similar(artistId: String, limit: Int = 20, cacheKey: String? = null): List<Artist> {
        val raw = source()?.similarArtists(artistId, limit).orEmpty()
        cache(cacheKey, raw)
        return raw.toArtists()
    }

    /** Playlist or album tracks in their existing server order; every success updates col:<id>. */
    suspend fun collectionTracks(collection: CollectionItem): List<Track> {
        val raw = when (collection) {
            is Playlist -> source()?.playlistItems(collection.id).orEmpty()
            else -> rawItems(LibraryFilter.ItemsQuery(
                parentId = collection.id,
                includeItemTypes = "Audio",
                sortBy = "ParentIndexNumber,IndexNumber,SortName",
                limit = 500
            )).items
        }
        cache("col:${collection.id}", raw)
        return raw.toTracks()
    }

    /** Home's Atmos shelf, including the zero-extra-query match against cached recent albums. */
    suspend fun homeAtmosAlbums(
        atmosIds: Set<String>,
        recentAlbumsCacheKey: String,
        cacheKey: String
    ): List<Album> {
        val raw = if (atmosIds.isEmpty()) {
            emptyList()
        } else {
            val recent = source()?.cached(recentAlbumsCacheKey).orEmpty()
                .filter { it.id in atmosIds }.take(20)
            recent.ifEmpty {
                rawItems(LibraryFilter.ItemsQuery(
                    includeItemTypes = "MusicAlbum", recursive = true,
                    sortBy = "DateCreated", sortOrder = "Descending", limit = 120
                )).items.filter { it.id in atmosIds }.take(20)
            }
        }
        cache(cacheKey, raw)
        return raw.toAlbums()
    }

    suspend fun genres(): List<Genre> = source()?.genres().orEmpty().toGenres()

    suspend fun atmosAlbums(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): MediaPage<Album> = (source()?.atmosAlbums(sortBy, desc, favoritesOnly, startIndex, limit)
        ?: ItemsResponse()).toDomainPage().typed()

    suspend fun atmosTracks(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): MediaPage<Track> = (source()?.atmosTracks(sortBy, desc, favoritesOnly, startIndex, limit)
        ?: ItemsResponse()).toDomainPage().typed()

    suspend fun atmosAlbumIds(): Set<String> = source()?.atmosAlbumIds() ?: emptySet()
}
