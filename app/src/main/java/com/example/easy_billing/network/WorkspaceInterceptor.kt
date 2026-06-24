package com.example.easy_billing.network

import android.content.Context
import android.content.Intent
import com.example.easy_billing.WorkspaceChangedActivity
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp interceptor that catches HTTP 409 responses from the backend.
 *
 * A 409 means the workspace was rotated or restored (the JWT's
 * workspace_version no longer matches the DB). The correct response is
 * NOT to call forceLogout() — that would wipe unsynced data from an
 * unrelated scenario. Instead we launch WorkspaceChangedActivity which
 * informs the user and lets them choose to reload cleanly.
 *
 * The interceptor fires for EVERY API call, so the user is caught
 * regardless of which screen they happen to be on.
 */
class WorkspaceInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Several in-flight requests can each get a 409 at the same moment.
        // Without a guard, every one launches WorkspaceChangedActivity (a
        // "409 storm"). Debounce so only the first within the window launches.
        if (response.code == 409 && shouldLaunch()) {
            val intent = Intent(context, WorkspaceChangedActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }

        return response
    }

    /** True at most once per [DEBOUNCE_MS] window, across all interceptor calls. */
    private fun shouldLaunch(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastLaunchAt.get()
        return now - last > DEBOUNCE_MS && lastLaunchAt.compareAndSet(last, now)
    }

    private companion object {
        private val lastLaunchAt = AtomicLong(0L)
        private const val DEBOUNCE_MS = 10_000L
    }
}
