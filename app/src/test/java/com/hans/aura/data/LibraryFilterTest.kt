package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LibraryFilter is the guard between "libraries the user kept" and every browse
 * query. The regression it exists for: a music query fanned out to a Playlists
 * or Movies root DOES return items of the requested type, so albums stayed on
 * the grid with every music library switched off.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFilterTest {

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.knownLibraries = Fixtures.allLibraries
        Prefs.hiddenLibraries = emptySet()
        LibraryCache.clear()
        PagedLibrary.clear()
    }

    private fun hide(vararg ids: String) {
        Prefs.hiddenLibraries = ids.toSet()
    }

    // ---- music item types are confined to music libraries ------------------

    @Test
    fun `music album query only fans out to music or unknown libraries`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi(itemsByParent = emptyMap())

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "MusicAlbum"))

        assertEquals(
            "music query must skip playlists/movies roots",
            setOf("lib-atmos", "lib-mixed"),
            api.queriedParents.toSet()
        )
        assertFalse(api.queriedParents.contains("lib-playlists"))
        assertFalse(api.queriedParents.contains("lib-movies"))
    }

    @Test
    fun `audio query is also confined to music libraries`() = runTest {
        hide("lib-atmos")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio"))

        assertEquals(setOf("lib-music", "lib-mixed"), api.queriedParents.toSet())
    }

    @Test
    fun `music artist query is confined to music libraries`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "MusicArtist"))

        assertEquals(setOf("lib-atmos", "lib-mixed"), api.queriedParents.toSet())
    }

    @Test
    fun `album artist endpoint is confined to music libraries`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryAlbumArtists(api, "u")

        assertEquals(setOf("lib-atmos", "lib-mixed"), api.artistQueries.toSet())
    }

    @Test
    fun `non-music query keeps every visible library`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Movie"))

        assertEquals(
            setOf("lib-atmos", "lib-movies", "lib-playlists", "lib-mixed"),
            api.queriedParents.toSet()
        )
    }

    @Test
    fun `query without item types keeps every visible library`() = runTest {
        hide("lib-movies")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = null))

        assertEquals(
            setOf("lib-music", "lib-atmos", "lib-playlists", "lib-mixed"),
            api.queriedParents.toSet()
        )
    }

    // ---- pass-through paths ------------------------------------------------

    @Test
    fun `nothing hidden means a single unfiltered query`() = runTest {
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "MusicAlbum"))

        assertFalse(LibraryFilter.isActive())
        assertEquals(listOf<String?>(null), api.queriedParents)
    }

    @Test
    fun `parent scoped query is never split`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(
            api, "u",
            LibraryFilter.ItemsQuery(includeItemTypes = "Audio", parentId = "album-1")
        )

        assertEquals(listOf<String?>("album-1"), api.queriedParents)
    }

    @Test
    fun `playlist query is passed through untouched`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Playlist"))

        assertEquals(listOf<String?>(null), api.queriedParents)
    }

    // ---- filters / id hydration -------------------------------------------

    @Test
    fun `item filter is forwarded on the single-query path`() = runTest {
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(
            api, "u",
            LibraryFilter.ItemsQuery(includeItemTypes = "Audio", filters = "IsFavorite")
        )

        assertEquals(listOf<String?>(null), api.queriedParents)
        assertEquals(listOf<String?>("IsFavorite"), api.itemFilters)
    }

    @Test
    fun `item filter reaches every library of a fan-out`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(
            api, "u",
            LibraryFilter.ItemsQuery(includeItemTypes = "Audio", filters = "IsFavorite")
        )

        assertEquals(setOf("lib-atmos", "lib-mixed"), api.queriedParents.toSet())
        assertEquals(
            "a filtered browse must still not read a hidden library",
            listOf<String?>("IsFavorite", "IsFavorite"),
            api.itemFilters
        )
    }

    @Test
    fun `id hydration bypasses the per-library fan-out`() = runTest {
        hide("lib-music")
        val api = FakeJellyfinApi()

        LibraryFilter.queryItems(
            api, "u",
            LibraryFilter.ItemsQuery(includeItemTypes = "MusicAlbum", ids = "a,b,c")
        )

        assertEquals(listOf<String?>(null), api.queriedParents)
        assertEquals(listOf<String?>("a,b,c"), api.itemIds)
    }

    // ---- merging -----------------------------------------------------------

    @Test
    fun `merge sorts by SortName across libraries`() = runTest {
        hide("lib-movies")
        val api = FakeJellyfinApi(
            itemsByParent = mapOf(
                "lib-music" to listOf(
                    Fixtures.item("b", name = "Bravo", sortName = "Bravo"),
                    Fixtures.item("d", name = "Delta", sortName = "Delta")
                ),
                "lib-atmos" to listOf(
                    Fixtures.item("c", name = "Charlie", sortName = "Charlie"),
                    Fixtures.item("a", name = "Alpha", sortName = "Alpha")
                )
            )
        )

        val res = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio"))

        assertEquals(listOf("a", "b", "c", "d"), res.items.map { it.id })
        assertEquals(4, res.totalRecordCount)
    }

    @Test
    fun `merge aggregates totalRecordCount and applies the page window`() = runTest {
        hide("lib-movies")
        val api = FakeJellyfinApi(
            itemsByParent = mapOf(
                "lib-music" to listOf(
                    Fixtures.item("b", sortName = "B"),
                    Fixtures.item("d", sortName = "D"),
                    Fixtures.item("f", sortName = "F")
                ),
                "lib-atmos" to listOf(
                    Fixtures.item("a", sortName = "A"),
                    Fixtures.item("c", sortName = "C"),
                    Fixtures.item("e", sortName = "E")
                )
            )
        )

        val first = LibraryFilter.queryItems(
            api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 2, startIndex = 0)
        )
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertEquals(6, first.totalRecordCount)

        val second = LibraryFilter.queryItems(
            api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 2, startIndex = 2)
        )
        assertEquals(listOf("c", "d"), second.items.map { it.id })
        assertEquals(2, second.startIndex)
    }

    @Test
    fun `merge de-duplicates items returned by more than one library`() = runTest {
        hide("lib-movies")
        val shared = Fixtures.item("dup", sortName = "Same")
        val api = FakeJellyfinApi(
            itemsByParent = mapOf(
                "lib-music" to listOf(shared),
                "lib-atmos" to listOf(shared)
            )
        )

        val res = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio"))

        assertEquals(listOf("dup"), res.items.map { it.id })
    }

    @Test
    fun `descending sort order is honoured on the merged page`() = runTest {
        hide("lib-movies")
        val api = FakeJellyfinApi(
            itemsByParent = mapOf(
                "lib-music" to listOf(Fixtures.item("a", sortName = "A"), Fixtures.item("c", sortName = "C")),
                "lib-atmos" to listOf(Fixtures.item("b", sortName = "B"))
            )
        )

        val res = LibraryFilter.queryItems(
            api, "u",
            LibraryFilter.ItemsQuery(includeItemTypes = "Audio", sortOrder = "Descending")
        )

        assertEquals(listOf("c", "b", "a"), res.items.map { it.id })
    }

    // ---- emptiness / failure handling --------------------------------------

    @Test
    fun `all libraries hidden returns an empty result without querying`() = runTest {
        hide("lib-music", "lib-atmos", "lib-movies", "lib-playlists", "lib-mixed")
        val api = FakeJellyfinApi()

        val res = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "MusicAlbum"))

        assertTrue(res.items.isEmpty())
        assertEquals(0, res.totalRecordCount)
        assertTrue("nothing should have been queried", api.itemQueries.isEmpty())
    }

    @Test
    fun `one failing library does not kill the page`() = runTest {
        hide("lib-movies")
        val api = FakeJellyfinApi(
            itemsByParent = mapOf("lib-atmos" to listOf(Fixtures.item("a", sortName = "A"))),
            failingParents = setOf("lib-music")
        )

        val res = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio"))

        assertEquals(listOf("a"), res.items.map { it.id })
    }

    @Test
    fun `all failed library requests throw`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val api = FakeJellyfinApi(failingParents = setOf("lib-music", "lib-atmos"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio")) }
        }
        assertEquals(2, api.itemQueries.size)
    }

    @Test
    fun `artist fan out keeps partial results but throws when all requests fail`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val api = FakeJellyfinApi(
            artistsByParent = mapOf("lib-atmos" to listOf(Fixtures.artist("a"))),
            failingParents = setOf("lib-music")
        )
        assertEquals(listOf("a"), LibraryFilter.queryAlbumArtists(api, "u").items.map { it.id })

        val failed = FakeJellyfinApi(failingParents = setOf("lib-music", "lib-atmos"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { LibraryFilter.queryAlbumArtists(failed, "u") }
        }
    }

    @Test
    fun `mixed query tolerates failures if libraries or playlists answer`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val query = LibraryFilter.ItemsQuery(includeItemTypes = "Audio,Playlist")
        val libraries = FakeJellyfinApi(
            itemsByParent = mapOf("lib-music" to listOf(Fixtures.track("track"))),
            failingParents = setOf(null)
        )
        assertEquals(listOf("track"), LibraryFilter.queryItems(libraries, "u", query).items.map { it.id })

        val playlists = FakeJellyfinApi(
            itemsByParent = mapOf(null to listOf(Fixtures.item("playlist", type = "Playlist"))),
            failingParents = setOf("lib-music", "lib-atmos")
        )
        assertEquals(listOf("playlist"), LibraryFilter.queryItems(playlists, "u", query).items.map { it.id })

        val failed = FakeJellyfinApi(failingParents = setOf("lib-music", "lib-atmos", null))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { LibraryFilter.queryItems(failed, "u", query) }
        }
    }

    @Test
    fun `empty successful library response is not a total failure`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val api = FakeJellyfinApi(failingParents = setOf("lib-music"))

        assertTrue(LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio")).items.isEmpty())
        assertTrue(LibraryFilter.queryAlbumArtists(api, "u").items.isEmpty())
    }

    @Test
    fun `failed Atmos scan publishes no partial catalog and can retry`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        AtmosCatalog.clear()
        val tracks = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to listOf(Fixtures.track("track").copy(albumId = "album"))
        )
        val failed = FakeJellyfinApi(itemsByParent = tracks, failingParents = setOf("lib-atmos"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { AtmosCatalog.albumIds(failed, "u") }
        }
        assertEquals(0, AtmosCatalog.size)
        assertTrue(AtmosFilter.albumIds().isEmpty())

        val recovered = FakeJellyfinApi(itemsByParent = tracks)
        assertEquals(setOf("album"), AtmosCatalog.albumIds(recovered, "u"))
        assertEquals(1, AtmosCatalog.size)
        assertEquals(setOf("track"), AtmosFilter.trackIds())
        AtmosCatalog.clear()
    }

    // ---- cache suffix -------------------------------------------------------

    @Test
    fun `cacheSuffix is empty when nothing is hidden`() {
        assertEquals("", LibraryFilter.cacheSuffix())
    }

    @Test
    fun `cacheSuffix is sorted and stable regardless of set order`() {
        Prefs.hiddenLibraries = linkedSetOf("lib-z", "lib-a")
        val first = LibraryFilter.cacheSuffix()
        Prefs.hiddenLibraries = linkedSetOf("lib-a", "lib-z")
        val second = LibraryFilter.cacheSuffix()

        assertEquals(first, second)
        assertEquals("#h=lib-a,lib-z", first)
    }

    @Test
    fun `refreshLibraryList drops hidden ids that no longer exist`() = runTest {
        Prefs.hiddenLibraries = setOf("lib-gone", "lib-music")
        val api = FakeJellyfinApi(
            views = listOf(
                Fixtures.item("lib-music", name = "Music", type = "CollectionFolder").copy(collectionType = "music"),
                Fixtures.item("lib-atmos", name = "Atmos Content", type = "CollectionFolder")
                    .copy(collectionType = "music")
            )
        )

        val libs = LibraryFilter.refreshLibraryList(api, "u")

        assertEquals(listOf("lib-music", "lib-atmos"), libs.map { it.id })
        assertEquals(setOf("lib-music"), Prefs.hiddenLibraries)
    }
}
