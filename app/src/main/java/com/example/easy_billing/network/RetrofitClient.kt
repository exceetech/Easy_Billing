package com.example.easy_billing.network

import android.content.Context
import com.example.easy_billing.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Configured per build type in app/build.gradle.kts (buildConfigField "API_BASE_URL").
    private val BASE_URL = BuildConfig.API_BASE_URL

    // Use lateinit instead of nullable → avoids repeated null checks
    private lateinit var appContext: Context

    fun setContext(ctx: Context) {
        // Always store application context to prevent leaks
        if (!::appContext.isInitialized) {
            appContext = ctx.applicationContext
        }
    }

    private val client: OkHttpClient by lazy {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("RetrofitClient not initialized. Call setContext() in Application class.")
        }

        // A release build still pointing at the placeholder host means the
        // real production API_BASE_URL was never set before shipping — fail
        // loudly here instead of silently making every network call in the
        // app fail against a host that doesn't exist. Crash on first use is
        // the intended behavior: it's much easier to catch during your own
        // pre-release smoke test than to have this discovered by users.
        if (!BuildConfig.DEBUG && BASE_URL.contains("api.example.com")) {
            throw IllegalStateException(
                "API_BASE_URL is still the placeholder \"$BASE_URL\" in a release " +
                    "build. Set the real production HTTPS endpoint in " +
                    "app/build.gradle.kts (release buildType's buildConfigField) " +
                    "before shipping."
            )
        }

        OkHttpClient.Builder()
            // Bound every request so a bad host/network fails fast instead of hanging.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(appContext))
            .addInterceptor(WorkspaceInterceptor(appContext)) // 409 → WorkspaceChangedActivity
            // Passive disk cache — OkHttp only stores/reuses a response here
            // if the server's own Cache-Control/Expires headers say it's
            // cacheable (standard HTTP semantics). Nothing changes for
            // responses without those headers, so this can't make billing/
            // stock/credit data look stale unless the backend explicitly
            // opts a response in.
            .cache(Cache(File(appContext.cacheDir, "http_cache"), 10L * 1024 * 1024))
            .build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client) // uses safe client
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // Deliberately its OWN client, not the shared `client` above: the main
    // client's AuthInterceptor attaches this app's own backend auth token
    // and device_id to every request with no host check (see
    // AuthInterceptor.kt) — sharing it here would leak that token to
    // Google's translation API on every call. Give this one real timeouts
    // (the old code had none, silently falling back to OkHttp's 10s
    // defaults) without pulling in any interceptor.
    private val translateClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val googleTranslateApi: GoogleTranslateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://translation.googleapis.com/")
            .client(translateClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleTranslateApi::class.java)
    }
}