package com.example.easy_billing.network

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "http://192.168.31.212:8080/"

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
            .addInterceptor(AuthInterceptor(appContext)) // safe now
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