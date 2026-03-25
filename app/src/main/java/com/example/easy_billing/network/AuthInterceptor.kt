package com.example.easy_billing.network

import android.content.Context
import com.example.easy_billing.util.DeviceUtils
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

        val token = prefs.getString("TOKEN", null)
        val deviceId = DeviceUtils.getDeviceId(context)

        val requestBuilder = chain.request().newBuilder()

        // 🔥 Add token
        token?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        // 🔥 Add device id
        requestBuilder.addHeader("device_id", deviceId)

        val request = requestBuilder.build()

        return chain.proceed(request)
    }
}