package com.hans.aura.ui.home

import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.Genre
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeMenuTest {
    @Test
    fun `collection rows keep their long press menu while tracks and artists do not use it`() {
        assertNotNull(collectionMenuTarget(Album(id = "album", name = "Album")))
        assertNotNull(collectionMenuTarget(Playlist(id = "playlist", name = "Playlist")))
        assertNotNull(collectionMenuTarget(Genre(id = "genre", name = "Genre")))
        assertNull(collectionMenuTarget(Track(id = "track", name = "Track")))
        assertNull(collectionMenuTarget(Artist(id = "artist", name = "Artist")))
    }
}
