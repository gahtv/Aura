package com.hans.aura.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface JellyfinApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthRequest): AuthResponse

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
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1
    ): ItemsResponse

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @retrofit2.http.Path("userId") userId: String,
        @retrofit2.http.Path("itemId") itemId: String
    ): JellyfinItem

    @GET("Artists/AlbumArtists")
    suspend fun getAlbumArtists(
        @Query("userId") userId: String,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Limit") limit: Int = 200,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
        @Query("ImageTypeLimit") imageTypeLimit: Int = 1
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
        @Query("Ids") ids: String
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
