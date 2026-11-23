package com.hans.aura.ui.nav

import com.hans.aura.data.JellyfinItem

/**
 * Every destination the app can show. Screens carry only their payload; all
 * per-screen metadata (saveable-state key, remote vocabulary, debug alias,
 * library kind) lives in [ScreenMeta] so no mapping is duplicated.
 */
internal sealed interface Screen {
    val meta: ScreenMeta

    data object Home : Screen {
        override val meta = ScreenMeta.HOME
    }

    data object Artists : Screen {
        override val meta = ScreenMeta.ARTISTS
    }

    data object Albums : Screen {
        override val meta = ScreenMeta.ALBUMS
    }

    data object Tracks : Screen {
        override val meta = ScreenMeta.TRACKS
    }

    data object Liked : Screen {
        override val meta = ScreenMeta.LIKED
    }

    data object Playlists : Screen {
        override val meta = ScreenMeta.PLAYLISTS
    }

    data object Search : Screen {
        override val meta = ScreenMeta.SEARCH
    }

    data object Settings : Screen {
        override val meta = ScreenMeta.SETTINGS
    }

    data object NowPlaying : Screen {
        override val meta = ScreenMeta.NOWPLAYING
    }

    data object Queue : Screen {
        override val meta = ScreenMeta.QUEUE
    }

    data object Genres : Screen {
        override val meta = ScreenMeta.GENRES
    }

    data class Genre(val name: String) : Screen {
        override val meta = ScreenMeta.GENRE
    }

    data class Collection(val item: JellyfinItem) : Screen {
        override val meta = ScreenMeta.COLLECTION
    }

    data class Artist(val item: JellyfinItem) : Screen {
        override val meta = ScreenMeta.ARTIST
    }
}

/**
 * The single source of truth for screen metadata.
 *
 *  - [keyPrefix]    first half of the SaveableStateProvider key; item/screen
 *                   screens append `:name` / `:id` (see [NavSpec.key]).
 *  - [mirrorKind]   the name a paired remote understands this screen by. The
 *                   remote's vocabulary is its own (no `col:` internals), and
 *                   Genres publish `albums` because a remote only knows
 *                   "browsing albums".
 *  - [routeName]    the route a remote may navigate TO (null = not addressable).
 *  - [debugAlias]   the name accepted by `--es aura_debug_screen` (null = none).
 *  - [libraryKind]  LibraryScreen kind when this screen lists a library.
 *  - [takesItem]    the screen wraps a JellyfinItem that must be resolved first.
 */
internal enum class ScreenMeta(
    val keyPrefix: String,
    val mirrorKind: String,
    val routeName: String? = null,
    val debugAlias: String? = null,
    val libraryKind: String? = null,
    val takesItem: Boolean = false
) {
    HOME("home", "home", "home", "home"),
    ARTISTS("artists", "artists", "artists", "artists", libraryKind = "Artists"),
    ALBUMS("albums", "albums", "albums", "albums", libraryKind = "Albums"),
    TRACKS("tracks", "tracks", "tracks", "tracks", libraryKind = "Tracks"),
    LIKED("liked", "tracks", debugAlias = "liked", libraryKind = "Tracks"),
    PLAYLISTS("playlists", "playlists", "playlists", "playlists", libraryKind = "Playlists"),
    SEARCH("search", "search", "search", "search"),
    SETTINGS("settings", "settings", "settings", "settings"),
    NOWPLAYING("nowplaying", "nowplaying", "nowplaying", "nowplaying"),
    QUEUE("queue", "queue", "queue", "queue"),
    GENRES("genres", "albums", debugAlias = "genres"),
    GENRE("genre", "albums"),
    COLLECTION("col", "collection", "collection", takesItem = true),
    ARTIST("artist", "artist", "artist", takesItem = true);
}

/** Derived lookups — every screen↔string mapping in the app comes from here. */
internal object NavSpec {

    /** Screens with no payload, in one place so meta lookups stay data-driven. */
    private val simpleScreens: List<Screen> = listOf(
        Screen.Home, Screen.Artists, Screen.Albums, Screen.Tracks, Screen.Playlists,
        Screen.Search, Screen.Settings, Screen.NowPlaying, Screen.Queue, Screen.Genres, Screen.Liked
    )

    fun isLibraryScreen(screen: Screen): Boolean =
        screen is Screen.Albums || screen is Screen.Artists ||
            screen is Screen.Tracks || screen is Screen.Playlists

    /** Remote routes that replace the stack instead of pushing onto it. */
    private val rootRoutes = setOf(
        ScreenMeta.HOME, ScreenMeta.ARTISTS, ScreenMeta.ALBUMS, ScreenMeta.TRACKS,
        ScreenMeta.PLAYLISTS, ScreenMeta.SEARCH, ScreenMeta.SETTINGS
    )

    /**
     * Saveable-state key. Format is byte-identical to the pre-refactor keys:
     * `home`, `artists`, `albums`, `tracks`, `playlists`, `search`, `settings`,
     * `nowplaying`, `queue`, `genres`, `genre:NAME`, `col:ID`, `artist:ID`.
     */
    fun key(screen: Screen): String = when (screen) {
        is Screen.Genre -> "${screen.meta.keyPrefix}:${screen.name}"
        is Screen.Collection -> "${screen.meta.keyPrefix}:${screen.item.id}"
        is Screen.Artist -> "${screen.meta.keyPrefix}:${screen.item.id}"
        else -> screen.meta.keyPrefix
    }

    fun libraryKind(screen: Screen): String? = screen.meta.libraryKind

    /** What a paired remote is told we are showing: name to item id. */
    fun mirrorPublish(screen: Screen): Pair<String, String?> = screen.meta.mirrorKind to when (screen) {
        is Screen.Collection -> screen.item.id
        is Screen.Artist -> screen.item.id
        else -> null
    }

    fun isRootRoute(screen: Screen): Boolean = screen.meta in rootRoutes

    /** True when a remote route needs an item fetched before it can be shown. */
    fun routeNeedsItem(route: String?): Boolean =
        route != null && ScreenMeta.entries.any { it.routeName == route && it.takesItem }

    /** Screen for a remote route; item screens need [resolvedItem] for [itemId]. */
    fun fromMirrorRoute(route: String?, itemId: String?, resolvedItem: JellyfinItem?): Screen? {
        if (route == null) return null
        val meta = ScreenMeta.entries.firstOrNull { it.routeName == route } ?: return null
        if (!meta.takesItem) return meta.resolve(null)
        // Ignore a resolved item that is not the one the remote asked for.
        if (itemId == null || resolvedItem == null || resolvedItem.id != itemId) return null
        return meta.resolve(resolvedItem)
    }

    /** Screen for an `--es aura_debug_screen <alias>` alias. */
    fun fromDebugScreen(alias: String?): Screen? {
        if (alias == null) return null
        val meta = ScreenMeta.entries.firstOrNull { it.debugAlias == alias } ?: return null
        return meta.resolve(null)
    }

    private fun ScreenMeta.resolve(item: JellyfinItem?): Screen? = when (this) {
        ScreenMeta.COLLECTION -> item?.let { Screen.Collection(it) }
        ScreenMeta.ARTIST -> item?.let { Screen.Artist(it) }
        else -> simpleScreens.firstOrNull { it.meta == this }
    }
}
