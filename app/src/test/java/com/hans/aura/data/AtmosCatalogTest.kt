package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AtmosCatalogTest {
    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.hiddenLibraries = emptySet()
        AtmosCatalog.clear()
    }

    @After
    fun tearDown() {
        AtmosCatalog.clear()
    }

    @Test
    fun `concurrent cold callers share one scan and cache empty success`() = runTest {
        val response = CompletableDeferred<ItemsResponse>()
        var queries = 0
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
                queries++
                return response.await()
            }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { AtmosCatalog.catalog(api, "user") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { AtmosCatalog.catalog(api, "user") }
        assertEquals(1, queries)
        response.complete(ItemsResponse())
        assertEquals(AtmosCatalog.Catalog(), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(first.await(), AtmosCatalog.catalog(api, "user"))
        assertEquals(1, queries)
    }

    @Test
    fun `cached lookup does not wait for another user scan`() = runTest {
        val cached = AtmosCatalog.catalog(FakeJellyfinApi(), "cached")
        val response = CompletableDeferred<ItemsResponse>()
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
                return response.await()
            }
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { AtmosCatalog.catalog(api, "other") }
        assertEquals(cached, AtmosCatalog.catalog(api, "cached"))
        pending.cancel()
    }

    @Test
    fun `failed scan is not cached and can be retried`() = runTest {
        val failing = FakeJellyfinApi(failingParents = setOf(null))
        val result = runCatching { AtmosCatalog.catalog(failing, "user") }
        org.junit.Assert.assertTrue(result.isFailure)
        assertEquals(0, AtmosCatalog.size)
        assertEquals(AtmosCatalog.Catalog(), AtmosCatalog.catalog(FakeJellyfinApi(), "user"))
        assertEquals(1, AtmosCatalog.size)
    }
}
