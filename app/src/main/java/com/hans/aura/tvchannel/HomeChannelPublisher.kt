package com.hans.aura.tvchannel

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs

/**
 * Publishes an Android TV Home Screen preview channel ("Aura — Recent & Atmos")
 * via the platform TvContract ContentProvider URI contract.
 * Wrapped in runCatching so non-TV devices / Robolectric tests fail silently.
 */
object HomeChannelPublisher {
    private const val TAG = "AURA_CHANNEL"
    private val CHANNEL_URI = Uri.parse("content://android.media.tv/channel")
    private val PROGRAM_URI = Uri.parse("content://android.media.tv/preview_program")

    fun publish(context: Context, items: List<JellyfinItem>, serverUrl: String) {
        if (items.isEmpty() || serverUrl.isBlank()) return
        runCatching {
            val resolver = context.applicationContext.contentResolver
            val channelId = ensureChannel(context, resolver)
            if (channelId <= 0L) return

            deletePrograms(resolver, channelId)
            val distinct = items.distinctBy { it.id }.take(12)
            distinct.forEachIndexed { idx, item ->
                val values = buildProgramValues(channelId, item, serverUrl, weight = distinct.size - idx)
                checkNotNull(resolver.insert(PROGRAM_URI, values))
            }
            Log.i(TAG, "published ${distinct.size} programs to channelId=$channelId")
            if (Prefs.tvBrowsableRequestedId != channelId) {
                Prefs.tvBrowsableRequestedId = channelId
                runCatching {
                    TvContract.requestChannelBrowsable(context.applicationContext, channelId)
                    Log.i(TAG, "requested browsability for channelId=$channelId")
                }.onFailure { e ->
                    Log.w(TAG, "channel browsability request failed for channelId=$channelId: ${e.message}")
                }
            }
        }.onFailure { e ->
            Log.d(TAG, "channel publish skipped: ${e.message}")
        }
    }

    fun clear(context: Context) {
        runCatching {
            val channelId = Prefs.tvChannelId
            if (channelId > 0L) {
                deletePrograms(context.applicationContext.contentResolver, channelId)
                Log.i(TAG, "cleared programs for channelId=$channelId")
            }
        }
    }

    private fun deletePrograms(resolver: ContentResolver, channelId: Long) {
        val uri = TvContract.buildPreviewProgramsUriForChannel(channelId)
        checkNotNull(resolver.query(uri, arrayOf("_id"), null, null, null)).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("_id")
            while (cursor.moveToNext()) {
                resolver.delete(TvContract.buildPreviewProgramUri(cursor.getLong(idColumn)), null, null)
            }
        }
    }

    private fun ensureChannel(context: Context, resolver: android.content.ContentResolver): Long {
        val existing = Prefs.tvChannelId
        if (existing > 0L) return existing
        val values = ContentValues().apply {
            put("type", "TYPE_PREVIEW")
            put("display_name", "Aura \u2014 Recent & Atmos")
            put("app_link_intent_uri", "aura://item/Home/home")
            put("internal_provider_id", "aura_recent_atmos")
            put("package_name", context.packageName)
        }
        val inserted = resolver.insert(CHANNEL_URI, values) ?: return -1L
        val id = runCatching { ContentUris.parseId(inserted) }.getOrDefault(-1L)
        if (id > 0L) {
            Prefs.tvChannelId = id
        }
        return id
    }

    internal fun buildProgramValues(
        channelId: Long,
        item: JellyfinItem,
        serverUrl: String,
        weight: Int
    ): ContentValues = ContentValues().apply {
        put("channel_id", channelId)
        put("type", 0) // TYPE_ALBUM
        put("title", item.name)
        val author = item.albumArtist ?: item.artistNames
        if (author.isNotBlank()) put("short_description", author)
        val artTag = item.primaryImageTag ?: item.albumPrimaryImageTag
        if (artTag != null) {
            AuthStore.imageUrl(serverUrl, item.id, artTag, maxWidth = 480)?.let {
                put("poster_art_uri", it)
            }
        }
        put("intent_uri", "aura://item/${item.type}/${item.id}")
        put("internal_provider_id", item.id)
        put("weight", weight)
    }
}
