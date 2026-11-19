package com.hans.aura.data

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

data class ProviderLyricMatch(
    val id: String,
    val providerName: String,
    val isSynced: Boolean,
    val preview: List<String>
)

sealed interface ProviderLyricsResult<out T> {
    data class Success<T>(val value: T) : ProviderLyricsResult<T>
    data object Forbidden : ProviderLyricsResult<Nothing>
    data object Unavailable : ProviderLyricsResult<Nothing>
    data object Failed : ProviderLyricsResult<Nothing>
}

internal suspend fun <T> providerLyricsRequest(request: suspend () -> T): ProviderLyricsResult<T> =
    try {
        ProviderLyricsResult.Success(request())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        when (e.code()) {
            403 -> ProviderLyricsResult.Forbidden
            404, 405 -> ProviderLyricsResult.Unavailable
            else -> ProviderLyricsResult.Failed
        }
    } catch (_: Exception) {
        ProviderLyricsResult.Failed
    }
