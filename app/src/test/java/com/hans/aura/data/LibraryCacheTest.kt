package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the 24h TTL, the library-selection key suffix (the same list must never
 * be served under a different hidden-set), and the family eviction the
 * favourite toggle relies on.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryCacheTest {

    private val items = listOf(Fixtures.item("a"), Fixtures.item("b"))

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.knownLibraries = Fixtures.allLibraries
        Prefs.hiddenLibraries = emptySet()
        LibraryCache.clear()
        PagedLibrary.clear()
        LibraryCache.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Prefs.hiddenLibraries = emptySet()
        LibraryCache.clear()
        PagedLibrary.clear()
    }

    // ---- TTL ---------------------------------------------------------------

    @Test
    fun `fresh entry is served`() {
        LibraryCache.put("Tracks", items)
        assertEquals(items, LibraryCache.get("Tracks"))
    }

    @Test
    fun `unknown key misses`() {
        assertNull(LibraryCache.get("never-stored"))
    }

    @Test
    fun `entry older than the TTL misses`() {
        LibraryCache.put("Tracks", items)
        // The clock has no seam, so age the stored entry by reflection instead
        // of sleeping for a day.
        ageEntry("Tracks", 25L * 60 * 60 * 1000)

        assertNull("a >24h old entry must be refetched", LibraryCache.get("Tracks"))
    }

    @Test
    fun `entry just inside the TTL still hits`() {
        LibraryCache.put("Tracks", items)
        ageEntry("Tracks", 23L * 60 * 60 * 1000)

        assertEquals(items, LibraryCache.get("Tracks"))
    }

    @Test
    fun `evict drops one key only`() {
        LibraryCache.put("a", items)
        LibraryCache.put("b", items)

        LibraryCache.evict("a")

        assertNull(LibraryCache.get("a"))
        assertNotNull(LibraryCache.get("b"))
    }

    // ---- evictWhere --------------------------------------------------------

    @Test
    fun `evictWhere drops a whole family of keys`() {
        LibraryCache.put("Tracks|SortName|asc|0", items)
        LibraryCache.put("Tracks|DateCreated|desc|0", items)
        LibraryCache.put("Albums|SortName|asc|0", items)

        LibraryCache.evictWhere { it.startsWith("Tracks") }

        assertNull(LibraryCache.get("Tracks|SortName|asc|0"))
        assertNull(LibraryCache.get("Tracks|DateCreated|desc|0"))
        assertNotNull("other families must survive", LibraryCache.get("Albums|SortName|asc|0"))
    }

    @Test
    fun `PagedLibrary evictWhere drops a whole family of keys`() {
        PagedLibrary.put("Tracks|SortName|asc", items, total = 400)
        PagedLibrary.put("Albums|SortName|asc", items, total = 10)

        PagedLibrary.evictWhere { it.startsWith("Tracks") }

        assertNull(PagedLibrary.get("Tracks|SortName|asc"))
        assertEquals(10, PagedLibrary.get("Albums|SortName|asc")?.total)
    }

    @Test
    fun `PagedLibrary keeps the accumulated total`() {
        PagedLibrary.put("Albums", items, total = 365)
        val page = PagedLibrary.get("Albums")
        assertEquals(365, page?.total)
        assertEquals(items, page?.items)
    }

    // ---- library-selection isolation --------------------------------------

    @Test
    fun `cache entries are isolated per hidden library set`() {
        LibraryCache.put("Tracks", items)
        assertEquals(items, LibraryCache.get("Tracks"))

        Prefs.hiddenLibraries = setOf("lib-atmos")
        assertNull("must not serve the unfiltered list", LibraryCache.get("Tracks"))

        val filtered = listOf(Fixtures.item("only-atmos-survivor"))
        LibraryCache.put("Tracks", filtered)
        assertEquals(filtered, LibraryCache.get("Tracks"))

        // Back to "nothing hidden": the original list is still there, untouched.
        Prefs.hiddenLibraries = emptySet()
        assertEquals(items, LibraryCache.get("Tracks"))
    }

    @Test
    fun `paged lists are isolated per hidden library set`() {
        PagedLibrary.put("Tracks", items, total = 2)

        Prefs.hiddenLibraries = setOf("lib-movies")
        assertNull(PagedLibrary.get("Tracks"))

        PagedLibrary.put("Tracks", emptyList(), total = 0)
        assertEquals(0, PagedLibrary.get("Tracks")?.total)

        Prefs.hiddenLibraries = emptySet()
        assertEquals(2, PagedLibrary.get("Tracks")?.total)
    }

    @Test
    fun `clear wipes lists but keeps the freshness stamps`() {
        LibraryCache.put("Tracks", items)
        LibraryCache.markManualRefresh()
        val manualAt = LibraryCache.lastManualRefreshAtMs
        assertTrue(manualAt > 0)

        LibraryCache.clear()

        assertEquals(0, LibraryCache.size)
        assertEquals(manualAt, LibraryCache.lastManualRefreshAtMs)
    }

    @Test
    fun `init restores persisted stamps`() {
        LibraryCache.markManualRefresh()
        val manualAt = LibraryCache.lastManualRefreshAtMs
        val loadedAt = LibraryCache.lastLoadedAtMs

        LibraryCache.init(ApplicationProvider.getApplicationContext())

        assertEquals(manualAt, LibraryCache.lastManualRefreshAtMs)
        assertEquals(loadedAt, LibraryCache.lastLoadedAtMs)
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Re-writes the cache entry under [key] with a `savedAtMs` [ageMs] in the
     * past. The store and its Entry are private on purpose (callers must not
     * reach into them), so the test pokes at them deliberately.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ageEntry(key: String, ageMs: Long) {
        val storeField = LibraryCache::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(LibraryCache) as ConcurrentHashMap<String, Any>

        val entryClass = Class.forName("com.hans.aura.data.LibraryCache\$Entry")
        val ctor = entryClass.getDeclaredConstructor(Long::class.java, List::class.java)
        ctor.isAccessible = true

        val fullKey = key + LibraryFilter.cacheSuffix()
        if (!store.containsKey(fullKey)) error("no cached entry for $fullKey")
        store[fullKey] = ctor.newInstance(System.currentTimeMillis() - ageMs, items)
    }
}
