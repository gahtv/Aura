package com.hans.aura.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class AuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
data class AuthResponse(
    @SerialName("User") val user: JellyfinUser? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null
)

@Serializable
data class ItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameId>? = null,
    @SerialName("ArtistItems") val artistItems: List<NameId>? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("IndexNumber") val trackNumber: Int? = null,
    @SerialName("ParentIndexNumber") val discNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
    @SerialName("Overview") val overview: String? = null
) {
    val primaryImageTag: String? get() = imageTags?.get("Primary")
    val durationMs: Long get() = (runTimeTicks ?: 0L) / 10_000L
    val artistNames: String
        get() = artists?.joinToString(", ")?.ifBlank { null }
            ?: albumArtists?.joinToString(", ") { it.name } ?: ""
}

@Serializable
data class NameId(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = ""
)

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null
)

@Serializable
data class MediaStream(
    @SerialName("Codec") val codec: String = "",
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("BitRate") val bitRate: Int? = null
)

@Serializable
data class LyricsResponse(
    @SerialName("Lyrics") val lyrics: List<LyricLineDto>? = null,
    @SerialName("LyricText") val lyricText: String? = null
)

@Serializable
data class LyricLineDto(
    @SerialName("Text") val text: String = "",
    @SerialName("Start") val start: Long = 0L
)

@Serializable
data class PlaylistRequest(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String> = emptyList(),
    @SerialName("UserId") val userId: String? = null
)

/** Announced to the server so Jellyfin web's "Play On" can target this client. */
@Serializable
data class CapabilitiesDto(
    @SerialName("PlayableMediaTypes") val playableMediaTypes: List<String> = listOf("Audio"),
    @SerialName("SupportedCommands") val supportedCommands: List<String> = listOf(
        "Play", "Playstate", "SetVolume", "DisplayMessage"
    ),
    @SerialName("SupportsMediaControl") val supportsMediaControl: Boolean = true,
    @SerialName("SupportsPersistentValues") val supportsPersistentValues: Boolean = true,
    @SerialName("SupportsRemoteControl") val supportsRemoteControl: Boolean = true
)

/** Playback reporting bodies — Jellyfin 12 binds these from JSON, not query params. */
@Serializable
data class QueueItem(
    @SerialName("Id") val id: String,
    @SerialName("PlaylistItemId") val playlistItemId: String? = null
)

@Serializable
data class PlaybackStartInfo(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("NowPlayingQueue") val nowPlayingQueue: List<QueueItem> = emptyList(),
    @SerialName("RepeatMode") val repeatMode: String = "RepeatNone",
    @SerialName("PlaybackOrder") val playbackOrder: String = "Default"
)

@Serializable
data class PlaybackProgressInfo(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    // Must be present on every progress report: the server resets the
    // session's PlayState.CanSeek when the field is missing, which makes
    // Jellyfin Web refuse remote seeks ("nothing playing").
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("NowPlayingQueue") val nowPlayingQueue: List<QueueItem> = emptyList(),
    @SerialName("RepeatMode") val repeatMode: String = "RepeatNone",
    @SerialName("PlaybackOrder") val playbackOrder: String = "Default"
)

@Serializable
data class PlaybackStopInfo(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("MediaSourceId") val mediaSourceId: String
)
