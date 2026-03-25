package com.example.easy_billing.network

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "http://192.168.1.11:8080/"

    //private const val BASE_URL = "http://192.168.31.212:8080/"

    //private const val BASE_URL = "http://10.0.2.2:8080/"

    private var context: Context? = null

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    private val client: OkHttpClient by lazy {

        val ctx = context
            ?: throw IllegalStateException("Call setContext() first")

        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(ctx)) // 🔥 IMPORTANT
            .build()
    }

    val api: ApiService by lazy {

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client) // 🔥 THIS LINE IS CRITICAL
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