package com.example.easy_billing.network

import android.content.Context
import com.example.easy_billing.util.AccountSuspension
import com.example.easy_billing.util.BackendHealthStatus
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches every response for two admin-controlled states:
 *
 *  - HTTP 403 ACCOUNT_SUSPENDED: the shop was suspended in the admin console.
 *    Opens the blocking [com.example.easy_billing.AccountSuspendedActivity]
 *    (data is kept; nothing is cleared).
 *  - HTTP 503 with `"maintenance": true`: the operator turned on maintenance
 *    mode. Flags it so the Dashboard banner says "maintenance" instead of the
 *    generic "can't reach our servers". Any normal answer clears the flag.
 *
 * The body is only peeked, so Retrofit still reads it afterwards.
 */
class AccountStatusInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        when {
            response.code == 403 -> {
                val message = AccountSuspension.messageFrom(peek(response))
                if (message != null) AccountSuspension.show(context, message)
            }
            response.code == 503 -> {
                val body = peek(response)
                if (body != null && body.contains("\"maintenance\"") && body.contains("true")) {
                    BackendHealthStatus.reportMaintenance(true)
                }
            }
            response.isSuccessful -> BackendHealthStatus.reportMaintenance(false)
        }
        return response
    }

    private fun peek(response: Response): String? = try {
        response.peekBody(8 * 1024).string()
    } catch (_: Exception) {
        null
    }
}
