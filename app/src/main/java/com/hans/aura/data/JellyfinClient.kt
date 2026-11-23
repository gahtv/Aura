package com.hans.aura.data

import android.content.Context
import com.hans.aura.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

const val APP_NAME = "Aura"

/**
 * Reported to the server as the client version.
 *
 * Was a hardcoded "0.1.0" while the app was 0.4.0, so every session/capability
 * report announced a version the build never had. Always the build's own value.
 */
val APP_VERSION: String get() = BuildConfig.VERSION_NAME

/** Retrofit-backed Jellyfin client; URL + auth headers swap when the session changes. */
class JellyfinClient(context: Context) {

    /** This install's Jellyfin device id — the player reports the SAME id. */
    val deviceId = AuthStore.newDeviceId(context)
    val auth = AuthStore(context)

    private val json = jellyfinJson

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

    // The Retrofit instance is bound to a baseUrl, so it has to be rebuilt
    // when the session's server changes (change server / sign in elsewhere).
    // Headers alone are not enough: the old host would keep receiving calls.
    @Volatile
    private var apiCache: JellyfinApi? = null
    @Volatile
    private var apiBaseUrl: String? = null

    val api: JellyfinApi
        get() = apiFor(resolveBaseUrl() ?: "http://localhost/")

    private fun apiFor(base: String): JellyfinApi {
        apiCache?.let { if (apiBaseUrl == base) return it }
        synchronized(this) {
            apiCache?.let { if (apiBaseUrl == base) return it }
            val built = buildApi(base)
            apiCache = built
            apiBaseUrl = base
            return built
        }
    }

    private fun buildApi(baseUrl: String): JellyfinApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
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

    /**
     * An api bound to an explicit server, for calls made BEFORE there is a
     * session (the login screen: Quick Connect check/initiate/poll). The auth
     * interceptor tolerates a null session, so no token is sent.
     */
    fun apiForServer(serverUrlRaw: String): JellyfinApi {
        val base = serverUrlRaw.trim().trimEnd('/') + "/"
        return apiFor(base)
    }

    /**
     * Quick Connect: authorise this device from another Jellyfin client.
     * The secret was authorised by the user in Jellyfin Web/another app, so the
     * server hands back an access token without a password ever being typed
     * here — the reason this exists on a TV.
     */
    suspend fun signInWithQuickConnect(serverUrlRaw: String, secret: String): Session {
        val serverUrl = serverUrlRaw.trim().trimEnd('/')
        val resp = apiForServer(serverUrl).authenticateWithQuickConnect(QuickConnectAuthRequest(secret))
        val session = Session(
            serverUrl = serverUrl,
            userId = resp.user?.id ?: error("Quick Connect response missing user id"),
            userName = resp.user?.name ?: "unknown",
            accessToken = resp.accessToken ?: error("Quick Connect response missing token"),
            deviceId = deviceId
        )
        auth.save(session)
        initWithServer(serverUrl)
        return session
    }

    suspend fun signIn(serverUrlRaw: String, username: String, password: String): Session {
        val serverUrl = serverUrlRaw.trimEnd('/')
        // Reuse the cached api builder: signing in twice (or to the same server
        // again) must not rebuild a duplicate Retrofit/OkHttp stack each time.
        val api = apiForServer(serverUrl)
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
