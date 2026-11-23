package com.hans.aura

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.data.Fixtures
import com.hans.aura.data.Prefs
import com.hans.aura.tvchannel.HomeChannelPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class HomeChannelPublisherTest {
    private val app = ApplicationProvider.getApplicationContext<AuraApp>()
    private lateinit var provider: PreviewProvider

    @Before
    fun setUp() {
        Prefs.init(app)
        Prefs.tvChannelId = 7L
        provider = PreviewProvider()
        ShadowContentResolver.registerProviderInternal("android.media.tv", provider)
    }

    @Test
    fun publishReplacesOnlyThisChannelsProgramsAndDoesNotDuplicate() {
        val albums = listOf(Fixtures.album("first"), Fixtures.album("second"))
        HomeChannelPublisher.publish(app, albums + albums.first(), "http://example.test")

        assertEquals(listOf(TvContract.buildPreviewProgramUri(10), TvContract.buildPreviewProgramUri(11)), provider.deletes)
        assertEquals(listOf("first", "second"), provider.inserted.map { it.getAsString("internal_provider_id") })
        assertEquals(2, provider.programs.values.count { it == 7L })
        assertEquals(8L, provider.programs[12L])

        HomeChannelPublisher.publish(app, albums, "http://example.test")
        assertEquals(2, provider.programs.values.count { it == 7L })
        assertEquals(8L, provider.programs[12L])
    }

    @Test
    fun clearDeletesEachProgramByUriWithoutTouchingOtherChannels() {
        HomeChannelPublisher.clear(app)
        assertEquals(listOf(TvContract.buildPreviewProgramUri(10), TvContract.buildPreviewProgramUri(11)), provider.deletes)
        assertEquals(mapOf(12L to 8L), provider.programs)
    }

    @Test
    fun publishIntoEmptyChannelInsertsAndClearEmptyChannelDoesNothing() {
        provider.programs.clear()
        HomeChannelPublisher.clear(app)
        assertTrue(provider.deletes.isEmpty())
        HomeChannelPublisher.publish(app, listOf(Fixtures.album("first")), "http://example.test")
        assertEquals(1, provider.inserted.size)
    }

    @Test
    fun failedQueryDoesNotInsertDuplicatesAndClearDoesNotThrow() {
        provider.failQuery = true
        HomeChannelPublisher.publish(app, listOf(Fixtures.album("first")), "http://example.test")
        HomeChannelPublisher.clear(app)
        assertTrue(provider.inserted.isEmpty())
        assertTrue(provider.deletes.isEmpty())
        assertEquals(3, provider.programs.size)
    }

    class PreviewProvider : ContentProvider() {
        val programs = linkedMapOf(10L to 7L, 11L to 7L, 12L to 8L)
        val deletes = mutableListOf<Uri>()
        val inserted = mutableListOf<ContentValues>()
        var failQuery = false
        private var nextId = 20L

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(!failQuery) { "Provider unavailable" }
            assertEquals(TvContract.buildPreviewProgramsUriForChannel(7), uri)
            assertEquals(null, selection)
            return MatrixCursor(arrayOf("_id")).apply {
                programs.filterValues { it == 7L }.keys.forEach { addRow(arrayOf(it)) }
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            require(selection == null) { "Selection not allowed for $uri" }
            require(uri.pathSegments.size == 2)
            deletes.add(uri)
            return if (programs.remove(ContentUris.parseId(uri)) != null) 1 else 0
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            assertEquals(TvContract.PreviewPrograms.CONTENT_URI, uri)
            val program = ContentValues(requireNotNull(values))
            inserted.add(program)
            val id = nextId++
            programs[id] = program.getAsLong("channel_id")
            return TvContract.buildPreviewProgramUri(id)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
