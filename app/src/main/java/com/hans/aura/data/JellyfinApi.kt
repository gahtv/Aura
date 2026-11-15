package com.hans.aura.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface JellyfinApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthRequest): AuthResponse

    // ---- Quick Connect (sign-in without typing a password on a TV) ----
    // All of these are called BEFORE there is a session, so the caller must
    // build the api for an explicit server URL (JellyfinClient.apiForServer).

    @GET("QuickConnect/Enabled")
    suspend fun quickConnectEnabled(): Boolean

    @POST("QuickConnect/Initiate")
    suspend fun quickConnectInitiate(): QuickConnectResult

    @GET("QuickConnect/Connect")
    suspend fun quickConnectConnect(@Query("secret") secret: String): QuickConnectResult

    @POST("Users/AuthenticateWithQuickConnect")
    suspend fun authenticateWithQuickConnect(@Body body: QuickConnectAuthRequest): AuthResponse


    // ---- Browse ----

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @retrofit2.http.Path("userId") userId: String,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("ExcludeItemTypes") excludeItemTypes: String? = null,
        @Query("Genres") genres: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("ParentId") parentId: String? = null,
        @Query("ArtistIds") artistIds: String? = null,
        @Query("AlbumArtistIds") albumArtistIds: String? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Limit") limit: Int = 100,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,MediaSources,ChildCount,Path",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1,
        // A-Z jump. Spec (12.4.0, GET /Items): `nameStartsWithOrGreater`, a string
        // compared against SortName, so it only makes sense with SortBy=SortName.
        @Query("nameStartsWithOrGreater") nameStartsWithOrGreater: String? = null
    ): ItemsResponse

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @retrofit2.http.Path("userId") userId: String,
        @retrofit2.http.Path("itemId") itemId: String
    ): JellyfinItem

    /** The user's libraries (Settings > Libraries shown in Aura, and the list the
     *  per-library query strategy filters). */
    @GET("UserViews")
    suspend fun getUserViews(
        @Query("userId") userId: String
    ): ItemsResponse

    @GET("Artists/AlbumArtists")
    suspend fun getAlbumArtists(
        @Query("userId") userId: String,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Limit") limit: Int = 200,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1,
        // Per-library filtering for library selection (see data/LibraryFilter.kt).
        @Query("ParentId") parentId: String? = null,
        // A-Z jump on the Artists grid (same param as /Items).
        @Query("nameStartsWithOrGreater") nameStartsWithOrGreater: String? = null
    ): ItemsResponse

    // ---- Discovery (radio / similar / genres) ----
    // All verified against references/jellyfin-openapi-stable.json (12.4.0):
    //   GET /Items/{itemId}/InstantMix     - userId, limit, fields
    //   GET /Artists/{itemId}/InstantMix   - same query params
    //   GET /Songs/{itemId}/InstantMix     - same (songs are Items; /Items covers tracks)
    //   GET /MusicGenres/{name}/InstantMix - path is the genre NAME, not an id
    //   GET /Artists/{itemId}/Similar      - excludeArtistIds, userId, limit, fields
    // Responses are ItemsResponse (QueryResult<BaseItemDto>).

    @GET("Items/{itemId}/InstantMix")
    suspend fun instantMix(
        @retrofit2.http.Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Query("limit") limit: Int = 50,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,MediaSources,ChildCount",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1
    ): ItemsResponse

    @GET("Artists/{itemId}/InstantMix")
    suspend fun artistInstantMix(
        @retrofit2.http.Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Query("limit") limit: Int = 50,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,MediaSources,ChildCount",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1
    ): ItemsResponse

    @GET("MusicGenres/{name}/InstantMix")
    suspend fun genreInstantMix(
        @retrofit2.http.Path("name") name: String,
        @Query("userId") userId: String,
        @Query("limit") limit: Int = 50,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,MediaSources,ChildCount",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1
    ): ItemsResponse

    @GET("Artists/{itemId}/Similar")
    suspend fun getSimilarArtists(
        @retrofit2.http.Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Query("limit") limit: Int = 20,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio"
    ): ItemsResponse

    // ---- Playlists ----

    @GET("Playlists/{playlistId}/Items")
    suspend fun getPlaylistItems(
        @retrofit2.http.Path("playlistId") playlistId: String,
        @Query("userId") userId: String
    ): ItemsResponse

    @POST("Playlists")
    suspend fun createPlaylist(
        @Query("Name") name: String,
        @Query("Ids") ids: String? = null,
        @Query("UserId") userId: String,
        @Body body: PlaylistRequest
    ): JellyfinItem

    @POST("Playlists/{playlistId}/Items")
    suspend fun addToPlaylist(
        @retrofit2.http.Path("playlistId") playlistId: String,
        @Query("Ids") ids: String,
        // Passed explicitly: Jellyfin resolves playlist permissions from this,
        // and omitting it made some adds silently no-op.
        @Query("UserId") userId: String? = null
    ): retrofit2.Response<Unit>

    /**
     * Moves a playlist entry to [newIndex]. Verified against the bundled
     * OpenAPI spec (operationId MoveItem): POST, all three params in the path,
     * no query/body, 204 on success. Returns 4xx/5xx as a Response, never throws.
     */
    @POST("Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}")
    suspend fun movePlaylistItem(
        @retrofit2.http.Path("playlistId") playlistId: String,
        @retrofit2.http.Path("itemId") itemId: String,
        @retrofit2.http.Path("newIndex") newIndex: Int
    ): retrofit2.Response<Unit>

    @DELETE("Playlists/{playlistId}/Items")
    suspend fun removeFromPlaylist(
        @retrofit2.http.Path("playlistId") playlistId: String,
        @Query("Ids") ids: String
    ): retrofit2.Response<Unit>

    @DELETE("Items/{itemId}")
    suspend fun deletePlaylist(
        @retrofit2.http.Path("itemId") itemId: String
    ): retrofit2.Response<Unit>

    // ---- Lyrics (Jellyfin >= 10.9) ----

    @GET("Audio/{itemId}/lyrics")
    suspend fun getLyrics(
        @retrofit2.http.Path("itemId") itemId: String
    ): LyricsResponse

    // ---- Playback reporting ----

    @POST("Sessions/Capabilities/Full")
    suspend fun setCapabilities(
        @Body body: CapabilitiesDto
    ): retrofit2.Response<Unit>

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(
        @Body body: PlaybackStartInfo
    ): retrofit2.Response<Unit>

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(
        @Body body: PlaybackProgressInfo
    ): retrofit2.Response<Unit>

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(
        @Body body: PlaybackStopInfo
    ): retrofit2.Response<Unit>
}
