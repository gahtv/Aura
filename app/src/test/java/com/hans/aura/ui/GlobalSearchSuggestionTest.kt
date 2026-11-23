package com.hans.aura.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import com.hans.aura.data.JellyfinItem
import com.hans.aura.search.AuraSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalSearchSuggestionTest {
    @Test
    fun selectingSuggestionsOpensTheirItems() {
        val types = listOf("MusicAlbum", "MusicArtist", "Audio")
        val items = types.map { JellyfinItem(id = "result-$it", name = "Hit", type = it) }
        AuraSearchProvider.buildCursor(items, "").use { cursor ->
            for (item in items) {
                check(cursor.moveToNext())
                val action = cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_ACTION))
                val dataColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
                val idColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID)
                val base = if (dataColumn >= 0) cursor.getString(dataColumn) else "content://com.hans.aura.search/item"
                val data = if (idColumn >= 0) "$base/${Uri.encode(cursor.getString(idColumn))}" else base
                val intent = Intent(action, Uri.parse(data))

                assertEquals(AppIntentRequest.OpenItem(item.type, item.id), AppIntents.parseIntent(intent))
            }
        }
    }
}
