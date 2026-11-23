package com.hans.aura.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface AppIntentRequest {
    data class OpenSearch(val query: String) : AppIntentRequest
    data class OpenItem(val type: String, val itemId: String) : AppIntentRequest
}

/**
 * Inbound system intents (Android TV Global Search + Home Screen Channel deep links).
 */
object AppIntents {
    val request = MutableStateFlow<AppIntentRequest?>(null)
    var pendingSearchQuery: String? = null

    fun postFromIntent(intent: Intent?) {
        val parsed = parseIntent(intent) ?: return
        if (parsed is AppIntentRequest.OpenSearch) {
            pendingSearchQuery = parsed.query
        }
        request.value = parsed
    }

    fun consumeSearchQuery(): String? {
        val q = pendingSearchQuery
        pendingSearchQuery = null
        return q
    }

    internal fun parseIntent(intent: Intent?): AppIntentRequest? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                if (query.isNotBlank()) AppIntentRequest.OpenSearch(query) else null
            }
            Intent.ACTION_VIEW -> parseViewUri(intent.data)
            else -> null
        }
    }

    internal fun parseViewUri(uri: Uri?): AppIntentRequest? {
        if (uri == null) return null
        // aura://item/<type>/<id>
        if (uri.scheme == "aura" && uri.host == "item") {
            val segments = uri.pathSegments
            val type = segments.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val id = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            return AppIntentRequest.OpenItem(type, id)
        }
        // content://com.hans.aura.search/item/<type>/<id>
        if (uri.authority == "com.hans.aura.search") {
            val segments = uri.pathSegments
            val offset = if (segments.firstOrNull() == "item") 1 else 0
            val type = segments.getOrNull(offset)?.takeIf { it.isNotBlank() } ?: return null
            val id = segments.getOrNull(offset + 1)?.takeIf { it.isNotBlank() } ?: return null
            return AppIntentRequest.OpenItem(type, id)
        }
        return null
    }
}
