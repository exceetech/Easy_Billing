package com.example.easy_billing.network

import android.content.Context
import com.example.easy_billing.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

        OkHttpClient.Builder()
            // Bound every request so a bad host/network fails fast instead of hanging.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(appContext))
            .addInterceptor(WorkspaceInterceptor(appContext)) // 409 → WorkspaceChangedActivity
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

    val googleTranslateApi: GoogleTranslateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://translation.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleTranslateApi::class.java)
    }
}