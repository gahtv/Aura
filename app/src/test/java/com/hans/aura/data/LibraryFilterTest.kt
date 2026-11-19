package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        LibraryFilter.clearCaches()
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
    fun `Title paging shows every visible track exactly once in server SortName order`() = runTest {
        Prefs.knownLibraries = Fixtures.allLibraries.filter { it.id in setOf("lib-music", "lib-atmos", "lib-mixed") }
        hide("lib-mixed")
        val rows: Map<String?, List<JellyfinItem>> = listOf("lib-music", "lib-atmos").mapIndexed { lib, id ->
            id to (0 until 180).map { index ->
                val album = index / 2
                val track = index % 2 + 1
                val name = "Album ${album.toString().padStart(3, '0')} Track $track Library $lib"
                Fixtures.item("$lib-$index", name = name, sortName = "000$track - $name")
            }
        }.toMap()
        val expected = rows.values.flatten().sortedBy { it.sortName }.map { it.id }
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true, honorSortNameField = true)
        val actual = (0 until 6).flatMap { page ->
            LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
                sortBy = "SortName", startIndex = page * 60, limit = 60)).items.map { it.id }
        }
        val duplicates = actual.groupingBy { it }.eachCount().count { it.value > 1 }
        val skipped = (expected.toSet() - actual.toSet()).size
        assertEquals("duplicated IDs=$duplicates, skipped IDs=$skipped", expected, actual)
        assertTrue(api.browseFields.all { "SortName" in it.split(',') })
    }

    @Test
    fun `cursor requests SortName for library artist and playlist sources only once`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        for (fields in listOf("", "Path,UserData", "SortName,Path")) {
            val api = FakeJellyfinApi(pagedBrowse = true)
            LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(
                includeItemTypes = "Audio,Playlist", fields = fields))
            LibraryFilter.queryAlbumArtists(api, "u", fields = fields)
            assertEquals(5, api.browseFields.size)
            api.browseFields.forEach { requested ->
                assertEquals(1, requested.split(',').count { it == "SortName" })
                assertTrue(requested.split(',').containsAll(fields.split(',').filter { it.isNotEmpty() }))
            }
        }
    }

    @Test
    fun `different cursor shapes fetch concurrently`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeJellyfinApi(
            itemsByParent = mapOf("lib-music" to listOf(Fixtures.item("a"))),
            pagedBrowse = true,
            beforeItems = { filters ->
                if (filters == "IsFavorite") {
                    started.complete(Unit)
                    release.await()
                }
            }
        )
        val q = LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 1)
        val blocked = async { LibraryFilter.queryItems(api, "u", q.copy(filters = "IsFavorite")) }
        started.await()
        try {
            val independent = async { LibraryFilter.queryItems(api, "u", q.copy(filters = "IsPlayed")) }
            assertEquals(listOf("a"), withTimeout(1000) { independent.await() }.items.map { it.id })
            assertFalse(blocked.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertEquals(listOf("a"), blocked.await().items.map { it.id })
    }

    @Test
    fun `successive pages fetch only new rows`() = runTest {
        hide("lib-movies", "lib-playlists")
        val rows: Map<String?, List<JellyfinItem>> = listOf("lib-music", "lib-atmos", "lib-mixed").mapIndexed { lib, id ->
            id to (0 until 30).map { Fixtures.item("${it * 3 + lib}".padStart(3, '0')) }
        }.toMap()
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        repeat(6) { page ->
            LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
                limit = 6, startIndex = page * 6))
        }
        assertTrue("must not refetch prefixes: ${api.browsePages}", api.browsePages.all { it.limit == 6 })
        assertTrue("six pages should transfer at most 54 rows, got ${api.browsePages.sumOf { it.rows }}",
            api.browsePages.sumOf { it.rows } <= 54)
        api.browsePages.groupBy { it.parent }.values.forEach { pages ->
            assertEquals(pages.indices.map { it * 6 }, pages.map { it.start })
        }
    }

    @Test
    fun `paging matches old stable merge for supported sorts`() = runTest {
        hide("lib-movies", "lib-playlists")
        val base = (0 until 36).map { i ->
            Fixtures.item("id-$i", name = "name-${i / 3}", sortName = "sort-${i / 4}",
                playCount = i % 4).copy(dateCreated = "2025-01-${10 + i % 5}", album = "album-${i % 3}")
        }
        val libraries = listOf("lib-music", "lib-atmos", "lib-mixed")
        val rows: Map<String?, List<JellyfinItem>> = libraries.mapIndexed { index, id ->
            id to base.filterIndexed { i, _ -> i % 3 == index || i % 7 == 0 }
        }.toMap()
        for ((sort, order) in listOf("SortName" to "Ascending", "DateCreated" to "Descending",
            "Album" to "Ascending", "PlayCount" to "Ascending")) {
            val comparator = when (sort) {
                "DateCreated" -> compareBy<JellyfinItem> { it.dateCreated }.thenBy { it.name.lowercase() }
                "Album" -> compareBy<JellyfinItem> { it.album?.lowercase() }.thenBy { it.name.lowercase() }
                "PlayCount" -> compareBy<JellyfinItem> { it.userData?.playCount }.thenBy { it.name.lowercase() }
                else -> compareBy<JellyfinItem> { it.sortName?.lowercase() }
            }.let { if (order == "Descending") it.reversed() else it }
            val sorted = rows.mapValues { (_, items) -> items.sortedWith(comparator) }
            val api = FakeJellyfinApi(itemsByParent = sorted, pagedBrowse = true)
            for (start in 0..40 step 5) {
                val expected = libraries.flatMap { sorted.getValue(it).take(start + 5) }
                    .distinctBy { it.id }.sortedWith(comparator).drop(start).take(5)
                val actual = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio", sortBy = sort, sortOrder = order, limit = 5, startIndex = start))
                assertEquals("$sort page $start", expected, actual.items)
                assertEquals(rows.values.sumOf { it.size }, actual.totalRecordCount)
            }
        }
    }

    @Test
    fun `cold deep page and eviction merge forward correctly`() = runTest {
        hide("lib-movies", "lib-playlists")
        val rows: Map<String?, List<JellyfinItem>> = listOf("lib-music", "lib-atmos", "lib-mixed")
            .mapIndexed { lib, id -> id to (0 until 12).map {
                Fixtures.item("${it * 3 + lib}".padStart(3, '0'))
            } }.toMap()
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        val query = LibraryFilter.ItemsQuery(includeItemTypes = "Audio", startIndex = 17, limit = 4)
        val expected = (17..20).map { "$it".padStart(3, '0') }
        assertEquals(expected, LibraryFilter.queryItems(api, "u", query).items.map { it.id })
        repeat(5) { LibraryFilter.queryItems(api, "u", query.copy(filters = "shape-$it")) }
        val before = api.browsePages.size
        assertEquals(expected, LibraryFilter.queryItems(api, "u", query).items.map { it.id })
        assertTrue(api.browsePages.drop(before).any { it.start == 0 })
        LibraryFilter.clearCaches()
        assertEquals(expected, LibraryFilter.queryItems(api, "u", query).items.map { it.id })
    }

    @Test
    fun `partial failure is not cached and retry recovers`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val failing = mutableSetOf<String?>("lib-music")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to (0..5).map { Fixtures.item("a$it") },
            "lib-atmos" to (0..5).map { Fixtures.item("b$it") })
        val api = FakeJellyfinApi(itemsByParent = rows, failingParents = failing, pagedBrowse = true)
        val query = LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 2, startIndex = 2)
        assertEquals(listOf("b2", "b3"), LibraryFilter.queryItems(api, "u", query).items.map { it.id })
        failing.clear()
        assertEquals(listOf("a2", "a3"), LibraryFilter.queryItems(api, "u", query).items.map { it.id })
    }

    @Test
    fun `non allow-listed sorts retain prefix requests for items and artists`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to (0..9).map { Fixtures.item("a$it") },
            "lib-atmos" to (0..9).map { Fixtures.item("b$it") })
        for (sort in listOf("DateCreated", "Album", "AlbumArtist", "Runtime", "PlayCount",
            "DatePlayed", "ProductionYear", "Random", "Unknown", "SortName,DateCreated")) {
            val api = FakeJellyfinApi(itemsByParent = rows, artistsByParent = rows, pagedBrowse = true)
            for (start in listOf(0, 2, 4)) {
                val before = api.browsePages.size
                LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
                    sortBy = sort, sortOrder = "Descending", startIndex = start, limit = 2))
                LibraryFilter.queryAlbumArtists(api, "u", sortBy = sort, sortOrder = "Descending",
                    startIndex = start, limit = 2)
                val requests = api.browsePages.drop(before)
                assertEquals("$sort page $start", 4, requests.size)
                assertTrue("$sort page $start: $requests", requests.all { it.start == 0 && it.limit == start + 2 })
                assertTrue(api.browseFields.none { "SortName" in it.split(',') })
            }
        }
    }

    @Test
    fun `count-only requests retain visible totals for items and artists`() = runTest {
        Prefs.knownLibraries = Fixtures.allLibraries.filter { it.id in setOf("lib-music", "lib-atmos", "lib-mixed") }
        hide("lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to listOf(Fixtures.item("a"), Fixtures.item("b")),
            "lib-atmos" to listOf(Fixtures.item("c"), Fixtures.item("d"), Fixtures.item("e")),
            "lib-mixed" to listOf(Fixtures.item("hidden")))
        val totals = listOf(rows, emptyMap()).flatMap { fixture ->
            val api = FakeJellyfinApi(itemsByParent = fixture, artistsByParent = fixture, pagedBrowse = true)
            val items = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 0))
            val artists = LibraryFilter.queryAlbumArtists(api, "u", limit = 0)
            assertTrue(items.items.isEmpty())
            assertTrue(artists.items.isEmpty())
            listOf(items.totalRecordCount, artists.totalRecordCount)
        }
        assertEquals(listOf(5, 5, 0, 0), totals)
    }

    @Test
    fun `SortName search retains prefix requests`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to (0..9).map { Fixtures.item("a$it") },
            "lib-atmos" to (0..9).map { Fixtures.item("b$it") })
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        for (start in listOf(0, 2, 4)) {
            val before = api.browsePages.size
            LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
                searchTerm = "Track", sortBy = "SortName", startIndex = start, limit = 2))
            val requests = api.browsePages.drop(before)
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.start == 0 && it.limit == start + 2 })
            assertTrue(api.browseFields.none { "SortName" in it.split(',') })
        }
    }

    @Test
    fun `Random retains prefix fetching and concatenation order`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to listOf(Fixtures.item("z"), Fixtures.item("a"), Fixtures.item("b")),
            "lib-atmos" to listOf(Fixtures.item("c")))
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        val page = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
            sortBy = "Random", startIndex = 2, limit = 2))
        assertEquals(listOf("b", "c"), page.items.map { it.id })
        assertTrue(api.browsePages.all { it.start == 0 && it.limit == 4 })
    }

    @Test
    fun `artists and mixed playlists use incremental sources`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>(
            "lib-music" to listOf(Fixtures.item("a"), Fixtures.item("d"), Fixtures.item("g")),
            "lib-atmos" to listOf(Fixtures.item("b"), Fixtures.item("e"), Fixtures.item("h")),
            null to listOf(Fixtures.item("c", type = "Playlist"), Fixtures.item("f", type = "Playlist")))
        val api = FakeJellyfinApi(itemsByParent = rows, artistsByParent = rows, pagedBrowse = true)
        val query = LibraryFilter.ItemsQuery(includeItemTypes = "Audio,Playlist", limit = 2)
        val mixed = (0..6 step 2).flatMap {
            LibraryFilter.queryItems(api, "u", query.copy(startIndex = it)).items.map { item -> item.id }
        }
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), mixed)
        val artists = (0..4 step 2).flatMap {
            LibraryFilter.queryAlbumArtists(api, "u", limit = 2, startIndex = it).items.map { item -> item.id }
        }
        assertEquals(listOf("a", "b", "d", "e", "g", "h"), artists)
        assertTrue(api.browsePages.all { it.limit == 2 })
    }

    @Test
    fun `cursor isolates users and APIs and resets on first page`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val rows = mapOf<String?, List<JellyfinItem>>("lib-music" to
            (0..8).map { Fixtures.item("a$it") })
        val api = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        val q = LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 2)
        LibraryFilter.queryItems(api, "u", q)
        var before = api.browsePages.size
        LibraryFilter.queryItems(api, "v", q.copy(startIndex = 2))
        assertTrue(api.browsePages.drop(before).any { it.parent == "lib-music" && it.start == 0 })
        before = api.browsePages.size
        LibraryFilter.queryItems(api, "u", q)
        assertTrue(api.browsePages.drop(before).any { it.parent == "lib-music" && it.start == 0 })
        val other = FakeJellyfinApi(itemsByParent = rows, pagedBrowse = true)
        assertEquals(listOf("a2", "a3"), LibraryFilter.queryItems(other, "u", q.copy(startIndex = 2)).items.map { it.id })
        assertTrue(other.browsePages.any { it.parent == "lib-music" && it.start == 0 })
    }

    @Test
    fun `cancellation propagates and retry starts cleanly`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val cancelled = mutableSetOf<String?>("lib-atmos")
        val api = FakeJellyfinApi(itemsByParent = mapOf("lib-music" to listOf(Fixtures.item("a"))),
            pagedBrowse = true, cancelledParents = cancelled)
        val q = LibraryFilter.ItemsQuery(includeItemTypes = "Audio", limit = 1)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { LibraryFilter.queryItems(api, "u", q) }
        }
        cancelled.clear()
        assertEquals(listOf("a"), LibraryFilter.queryItems(api, "u", q).items.map { it.id })
    }

    @Test
    fun `deep pages retain cumulative per library ceiling`() = runTest {
        hide("lib-movies", "lib-playlists", "lib-mixed")
        val api = FakeJellyfinApi(itemsByParent = mapOf("lib-music" to
            (0 until 5100).map { Fixtures.item("$it".padStart(5, '0')) }), pagedBrowse = true)
        val result = LibraryFilter.queryItems(api, "u", LibraryFilter.ItemsQuery(includeItemTypes = "Audio",
            startIndex = 4998, limit = 64))
        assertEquals(listOf("04998", "04999"), result.items.map { it.id })
        assertEquals(5100, result.totalRecordCount)
        assertEquals(5000, api.browsePages.filter { it.parent == "lib-music" }.sumOf { it.rows })
        assertTrue(api.browsePages.all { it.start + it.rows <= 5000 })
    }

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
    fun `date added merge requests dates and interleaves library pages`() = runTest {
        hide("lib-movies")
        val tracks = mapOf(
            "lib-music" to listOf(
                Fixtures.item("newest", name = "Alpha").copy(dateCreated = "2025-01-04T12:00:00Z"),
                Fixtures.item("third", name = "Mike").copy(dateCreated = "2025-01-02T12:00:00Z")
            ),
            "lib-atmos" to listOf(
                Fixtures.item("second", name = "Bravo").copy(dateCreated = "2025-01-03T12:00:00Z"),
                Fixtures.item("oldest", name = "Zulu").copy(dateCreated = "2025-01-01T12:00:00Z")
            )
        )
        val requestedFields = mutableListOf<List<String>>()
        val api = object : JellyfinApi by FakeJellyfinApi() {
            override suspend fun getItems(
                userId: String,
                includeItemTypes: String?,
                excludeItemTypes: String?,
                genres: String?,
                filters: String?,
                enableUserData: Boolean,
                recursive: Boolean,
                parentId: String?,
                artistIds: String?,
                albumArtistIds: String?,
                searchTerm: String?,
                ids: String?,
                sortBy: String,
                sortOrder: String,
                limit: Int,
                startIndex: Int,
                fields: String,
                imageTypeLimit: Int,
                nameStartsWithOrGreater: String?
            ): ItemsResponse {
                assertEquals("Audio", includeItemTypes)
                assertEquals("DateCreated", sortBy)
                assertEquals("Descending", sortOrder)
                assertEquals(0, startIndex)
                val projection = fields.split(',').map { it.trim() }
                requestedFields += projection
                val all = tracks[parentId].orEmpty()
                val items = all.take(limit).map {
                    if (projection.any { field -> field.equals("DateCreated", ignoreCase = true) }) it
                    else it.copy(dateCreated = null)
                }
                return ItemsResponse(items = items, totalRecordCount = all.size)
            }
        }
        val query = LibraryFilter.ItemsQuery(
            includeItemTypes = "Audio", sortBy = "DateCreated", sortOrder = "Descending",
            limit = 2, fields = "PrimaryImageAspectRatio,Path"
        )

        val first = LibraryFilter.queryItems(api, "u", query)
        val second = LibraryFilter.queryItems(api, "u", query.copy(startIndex = 2))

        assertEquals(listOf("newest", "second"), first.items.map { it.id })
        assertEquals(listOf("third", "oldest"), second.items.map { it.id })
        assertEquals(4, first.totalRecordCount)
        assertEquals(2, second.startIndex)
        assertTrue(requestedFields.isNotEmpty())
        requestedFields.forEach {
            assertTrue(it.containsAll(listOf("PrimaryImageAspectRatio", "Path", "DateCreated")))
        }
        requestedFields.clear()
        LibraryFilter.queryItems(api, "u", query.copy(fields = "Path,datecreated"))
        requestedFields.forEach {
            assertEquals(1, it.count { field -> field.equals("DateCreated", ignoreCase = true) })
            assertTrue(it.contains("Path"))
        }
    }

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
