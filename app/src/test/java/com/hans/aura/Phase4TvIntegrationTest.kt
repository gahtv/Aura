package com.hans.aura

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.data.AuthStore
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Session
import com.hans.aura.search.AuraSearchProvider
import com.hans.aura.tvchannel.HomeChannelPublisher
import com.hans.aura.ui.AppIntentRequest
import com.hans.aura.ui.AppIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase4TvIntegrationTest {

    private lateinit var auth: AuthStore

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        auth = AuthStore(app)
        auth.clear()
    }

    @Test
    fun authStoreMultiProfileSaveSwitchRemoveAndClearServer() {
        val s1 = Session("http://192.168.1.10:8096", "u1", "Hans", "tok1", "dev1")
        val s2 = Session("http://192.168.1.10:8096", "u2", "Guest", "tok2", "dev1")
        auth.save(s1)
        auth.save(s2)

        assertEquals(2, auth.profilesForServer("http://192.168.1.10:8096").size)
        assertEquals("u2", auth.session?.userId)

        // clearSession drops active token but keeps saved profiles for quick re-entry.
        auth.clearSession()
        assertNull(auth.session)
        assertEquals(2, auth.profilesForServer("http://192.168.1.10:8096").size)

        val switched = auth.switchProfile("http://192.168.1.10:8096", "u1")
        assertNotNull(switched)
        assertEquals("u1", auth.session?.userId)

        auth.removeProfile("http://192.168.1.10:8096", "u2")
        assertEquals(1, auth.profilesForServer("http://192.168.1.10:8096").size)

        auth.clearServer()
        assertTrue(auth.profilesForServer("http://192.168.1.10:8096").isEmpty())
    }

    @Test
    fun searchProviderBuildsExpectedSuggestionCursor() {
        val album = JellyfinItem(
            id = "alb1",
            name = "Brat",
            type = "MusicAlbum",
            albumArtist = "Charli xcx",
            imageTags = mapOf("Primary" to "tag1")
        )
        val cursor = AuraSearchProvider.buildCursor(listOf(album), "http://192.168.1.10:8096")
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals("Brat", cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1)))
        assertEquals("Charli xcx", cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_2)))
        assertEquals("MusicAlbum/alb1", cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID)))
        cursor.close()
    }

    @Test
    fun homeChannelBuildsPreviewProgramValues() {
        val album = Fixtures.domainAlbum("alb42").copy(
            albumArtist = "boygenius",
            artwork = com.hans.aura.model.Artwork(itemId = "alb42", tag = "tag42")
        )
        val values = HomeChannelPublisher.buildProgramValues(
            channelId = 7L,
            item = album,
            serverUrl = "http://192.168.1.10:8096",
            weight = 10
        )
        assertEquals(7L, values.getAsLong("channel_id"))
        assertEquals("alb42", values.getAsString("title"))
        assertEquals("aura://item/MusicAlbum/alb42", values.getAsString("intent_uri"))
        assertTrue(values.getAsString("poster_art_uri").contains("/Items/alb42/Images/Primary"))
    }

    @Test
    fun appIntentsParsesSearchAndDeepLinkUris() {
        val searchIntent = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "atmos")
        assertEquals(AppIntentRequest.OpenSearch("atmos"), AppIntents.parseIntent(searchIntent))

        val channelIntent = Intent(Intent.ACTION_VIEW, Uri.parse("aura://item/MusicAlbum/alb9"))
        assertEquals(AppIntentRequest.OpenItem("MusicAlbum", "alb9"), AppIntents.parseIntent(channelIntent))

        val suggestIntent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.hans.aura.search/item/MusicArtist/art3"))
        assertEquals(AppIntentRequest.OpenItem("MusicArtist", "art3"), AppIntents.parseIntent(suggestIntent))
    }
}
