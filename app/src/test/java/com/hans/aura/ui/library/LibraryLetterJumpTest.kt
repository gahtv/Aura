package com.hans.aura.ui.library

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.LibraryRef
import com.hans.aura.data.PagedLibrary
import com.hans.aura.data.Prefs
import com.hans.aura.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryLetterJumpTest {
    private val store = ViewModelStore()
    private lateinit var app: AuraApp

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        AtmosFilter.setEnabled(false)
        clearCaches()
        Prefs.knownLibraries = (VISIBLE + HIDDEN).map { LibraryRef(it, it, "music") }
        Prefs.hiddenLibraries = setOf(HIDDEN)
    }

    @After
    fun tearDown() {
        store.clear()
        app.jellyfin.auth.clear()
        Prefs.hiddenLibraries = emptySet()
        Prefs.knownLibraries = emptyList()
        clearCaches()
        Dispatchers.resetMain()
    }

    @Test
    fun `album letter jump with a hidden library lists every album once in SortName order`() =
        assertJumpListsEveryRowOnce("Albums")

    @Test
    fun `artist letter jump with a hidden library lists every artist once in SortName order`() =
        assertJumpListsEveryRowOnce("Artists")

    private fun assertJumpListsEveryRowOnce(kind: String) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = JellyfinRows
            app.jellyfin.auth.installSession(Session(server.url("/").toString(), "user", "Test", "token", "device"))
            val model = model(kind)
            model.selectLetter(LETTER)

            var state = withTimeout(5000) {
                model.state.first { it.letter == LETTER && !it.loading && it.items.isNotEmpty() }
            }
            while (state.items.size < state.total) {
                val shown = state.items.size
                model.loadNextPage()
                state = withTimeout(5000) {
                    model.state.first { !it.loading && (it.items.size > shown || it.total == shown) }
                }
            }

            val expected = ROWS.filterKeys { it in VISIBLE }.values.flatten()
                .filter { it.sortName!! >= LETTER.lowercase() }
                .sortedBy { it.sortName }
                .map { it.id }
            val actual = state.items.map { it.id }
            val duplicated = actual.size - actual.toSet().size
            val skipped = (expected - actual.toSet()).size
            assertEquals("duplicated=$duplicated skipped=$skipped", expected, actual)
        }
    }

    private fun clearCaches() {
        LibraryCache.clear()
        PagedLibrary.clear()
        LibraryFilter.clearCaches()
    }

    private fun model(kind: String): LibraryViewModel = ViewModelProvider(
        object : ViewModelStoreOwner { override val viewModelStore = store },
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(app, kind) as T
        }
    )[LibraryViewModel::class.java]

    /**
     * Jellyfin 12.4.0's paging for Items and AlbumArtists: SortName order and
     * `nameStartsWithOrGreater` on the server, SortName returned only when it
     * is in Fields. Rows mirror the emulator library ("The Atlas 19" sorts as
     * "atlas 0000000019"), so Name order differs from SortName order.
     */
    private object JellyfinRows : Dispatcher() {
        private val json = Json { encodeDefaults = false }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val path = url.encodedPath
            if (!path.endsWith("/Items") && !path.endsWith("/Artists/AlbumArtists")) {
                return MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}""")
            }
            val letter = url.queryParameter("nameStartsWithOrGreater")?.lowercase()
            val withSortName = url.queryParameter("Fields").orEmpty().split(',').any { it == "SortName" }
            val matching = url.queryParameter("ParentId")
                ?.let { ROWS[it].orEmpty() }
                ?: ROWS.filterKeys { it in VISIBLE }.values.flatten()
            val rows = matching
                .filter { letter == null || it.sortName!! >= letter }
                .sortedBy { it.sortName }
            val start = url.queryParameter("StartIndex")?.toInt() ?: 0
            val limit = url.queryParameter("Limit")?.toInt() ?: rows.size
            val type = if (path.endsWith("/Artists/AlbumArtists")) "MusicArtist" else "MusicAlbum"
            val page = rows.drop(start).take(limit).map {
                it.copy(type = type, sortName = it.sortName.takeIf { withSortName })
            }
            return MockResponse().setBody(
                json.encodeToString(ItemsResponse(items = page, totalRecordCount = rows.size, startIndex = start))
            )
        }
    }

    private companion object {
        val VISIBLE = listOf("lib-a", "lib-b")
        const val HIDDEN = "lib-hidden"
        const val LETTER = "A"
        val WORDS = listOf("Atlas", "Cinder", "Copper", "Drift", "Echo", "Ember", "Fable", "Glacier", "Velvet")
        val ARTICLES = listOf("The ", "An ", "")

        val ROWS: Map<String, List<JellyfinItem>> = (VISIBLE + HIDDEN).mapIndexed { lib, id ->
            id to (0 until 90).map { index ->
                val number = lib * 100 + index
                val word = WORDS[index % WORDS.size]
                JellyfinItem(
                    id = "$id-$index",
                    name = "${ARTICLES[index % ARTICLES.size]}$word $number",
                    sortName = "${word.lowercase()} ${number.toString().padStart(10, '0')}"
                )
            }
        }.toMap()
    }
}
