package com.hans.aura.data

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

const val APP_NAME = "Aura"
const val APP_VERSION = "0.1.0"

/** Retrofit-backed Jellyfin client; URL + auth headers swap when the session changes. */
class JellyfinClient(context: Context) {

    private val deviceId = AuthStore.newDeviceId(context)
    val auth = AuthStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        // Fields with defaults (e.g. CanSeek on playback reports) must still be
        // serialized: the server resets session PlayState.CanSeek when the field
        // is absent, which makes Jellyfin Web refuse remote seeks.
        encodeDefaults = true
    }

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val session = auth.session
        val req = chain.request().newBuilder().apply {
            // Jellyfin 12 requires the standard Authorization header (not X-Emby-Authorization).
            header("Authorization", AuthStore.authHeader(session, deviceId, APP_NAME, APP_VERSION))
            if (session != null) {
                header("X-MediaBrowser-Token", session.accessToken)
            }
        }.build()
        chain.proceed(req)
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(60))
        .build()

    @Volatile
    private var currentBaseUrl: String? = null

    val api: JellyfinApi by lazy { buildApi() }

    val imageCacheBust: Long get() = 0

    private fun buildApi(): JellyfinApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(resolveBaseUrl() ?: "http://localhost/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(JellyfinApi::class.java)
    }

    private fun resolveBaseUrl(): String? =
        auth.session?.serverUrl?.trimEnd('/')?.plus("/")

    /** Called after first authentication, before any API call, so baseUrl is set. */
    fun initWithServer(serverUrl: String) {
        currentBaseUrl = serverUrl.trimEnd('/')
    }

    suspend fun signIn(serverUrlRaw: String, username: String, password: String): Session {
        val serverUrl = serverUrlRaw.trimEnd('/')
        val retrofit = Retrofit.Builder()
            .baseUrl("$serverUrl/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(JellyfinApi::class.java)
        val resp = api.authenticateByName(AuthRequest(username, password))
        val session = Session(
            serverUrl = serverUrl,
            userId = resp.user?.id ?: error("Login response missing user id"),
            userName = resp.user?.name ?: username,
            accessToken = resp.accessToken ?: error("Login response missing token"),
            deviceId = deviceId
        )
        auth.save(session)
        initWithServer(serverUrl)
        return session
    }
}
