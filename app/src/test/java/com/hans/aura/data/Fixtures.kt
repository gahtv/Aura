package com.hans.aura.data

/** Builders for the model objects the unit tests need, with sane defaults. */
object Fixtures {

    fun item(
        id: String,
        name: String = id,
        type: String = "Audio",
        sortName: String? = null,
        playCount: Int? = null,
        lastPlayedDate: String? = null,
        codec: String = "eac3",
        profile: String? = "JOC",
        container: String? = "m4a"
    ): JellyfinItem = JellyfinItem(
        id = id,
        name = name,
        type = type,
        sortName = sortName,
        container = container,
        userData = if (playCount == null && lastPlayedDate == null) null else UserData(
            playCount = playCount,
            lastPlayedDate = lastPlayedDate
        ),
        mediaSources = listOf(
            MediaSource(
                id = "src-$id",
                container = container,
                mediaStreams = listOf(MediaStream(codec = codec, profile = profile, channels = 6))
            )
        )
    )

    fun track(id: String) = item(id)
    fun album(id: String) = item(id, name = id, type = "MusicAlbum")
    fun artist(id: String) = item(id, name = id, type = "MusicArtist")

    val musicLib = LibraryRef(id = "lib-music", name = "Music", collectionType = "music")
    val atmosLib = LibraryRef(id = "lib-atmos", name = "Atmos Content", collectionType = "music")
    val moviesLib = LibraryRef(id = "lib-movies", name = "Movies", collectionType = "movies")
    val playlistsLib = LibraryRef(id = "lib-playlists", name = "Playlists", collectionType = "playlists")
    val mixedLib = LibraryRef(id = "lib-mixed", name = "Mixed", collectionType = null)

    val allLibraries = listOf(musicLib, atmosLib, moviesLib, playlistsLib, mixedLib)
}
