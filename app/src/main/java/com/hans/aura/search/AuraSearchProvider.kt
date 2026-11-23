package com.hans.aura.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import kotlinx.coroutines.runBlocking

/**
 * Android TV Global Search provider.
 * Queries the active Jellyfin session when the system launcher searches Aura.
 */
class AuraSearchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val rawQuery = selectionArgs?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it != "search_suggest_query" && it.isNotBlank() }
            ?: return emptyCursor()

        val app = context?.applicationContext as? AuraApp ?: return emptyCursor()
        val session = app.container.jellyfin.auth.session ?: return emptyCursor()
        val items = runCatching {
            runBlocking {
                app.container.jellyfin.api.getItems(
                    userId = session.userId,
                    searchTerm = rawQuery.trim(),
                    includeItemTypes = "Audio,MusicAlbum,MusicArtist",
                    recursive = true,
                    limit = 15
                ).items
            }
        }.getOrDefault(emptyList())

        return buildCursor(items, session.serverUrl)
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA
        )

        fun emptyCursor(): MatrixCursor = MatrixCursor(COLUMNS)

        fun buildCursor(items: List<JellyfinItem>, serverUrl: String): MatrixCursor {
            val cursor = MatrixCursor(COLUMNS)
            items.forEachIndexed { index, item ->
                val subtitle = when (item.type) {
                    "MusicAlbum" -> item.albumArtist ?: item.artistNames.ifBlank { "Album" }
                    "MusicArtist" -> "Artist"
                    "Audio" -> listOfNotNull(item.artistNames.takeIf { it.isNotBlank() }, item.album)
                        .joinToString(" \u00B7 ").ifBlank { "Track" }
                    else -> item.type
                }
                val artId = item.albumId?.takeIf { item.primaryImageTag == null } ?: item.id
                val artTag = item.primaryImageTag ?: item.albumPrimaryImageTag
                val imageUrl = if (artTag != null && serverUrl.isNotBlank()) {
                    AuthStore.imageUrl(serverUrl, artId, artTag, maxWidth = 300)
                } else null
                cursor.addRow(
                    arrayOf<Any?>(
                        index.toLong(),
                        item.name,
                        subtitle,
                        imageUrl,
                        "android.intent.action.VIEW",
                        Uri.Builder()
                            .scheme("content")
                            .authority("com.hans.aura.search")
                            .appendPath("item")
                            .appendPath(item.type)
                            .appendPath(item.id)
                            .build()
                            .toString()
                    )
                )
            }
            return cursor
        }
    }
}
