package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The actual "can we reach OUR server" probe — extracted out of
 * NetworkReceiver so it can be run from more than one trigger.
 *
 * Why this had to be pulled out: NetworkReceiver only ever calls this check
 * from inside a ConnectivityManager.NetworkCallback.onAvailable() — i.e. only
 * when the DEVICE's network reconnects. Turning the backend server off while
 * the device stays connected to Wi-Fi/data (the exact "I turned off my
 * server and the app just kept working" scenario) never fires onAvailable,
 * so the check never runs and nothing gets reported. This function now also
 * gets called periodically while DashboardActivity is in the foreground (see
 * DashboardActivity.startBackendHealthPolling()), which is what actually
 * catches a mid-session outage instead of only catching it on the next
 * network flap or app cold start.
 */
object BackendReachabilityChecker {

    /**
     * Distinguishes:
     *  - a real auth failure (→ caller should log out)
     *  - the DEVICE having no internet (→ not our server's fault at all;
     *    SessionTimeoutGuard/BaseActivity's offline-session-timeout already
     *    owns this case)
     *  - OUR server specifically being unreachable despite the device being
     *    online (→ retry later, surfaced via BackendHealthStatus)
     */
    enum class Status { OK, UNREACHABLE, UNAUTHORIZED, NO_INTERNET }

    suspend fun check(context: Context): Status {
        // Device-level connectivity checked FIRST, before even looking at
        // whether a token exists — NO_INTERNET should always win over
        // UNAUTHORIZED when both are true, since "no internet" is the more
        // fundamental fact and the token check below can't mean anything
        // reliable without connectivity anyway. The old ordering checked for
        // a missing token first, so a caller invoking this with no token
        // stored while the device was genuinely offline got a misleading
        // UNAUTHORIZED instead of NO_INTERNET.
        if (!NetworkUtils.isOnline(context)) {
            return Status.NO_INTERNET
        }

        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return Status.UNAUTHORIZED

        // Up to 2 attempts, 5s each, with a short gap, before concluding the
        // SERVER specifically is unreachable — avoids a false positive on a
        // merely slow-but-alive server.
        repeat(2) { attempt ->
            try {
                withTimeout(5000) {
                    RetrofitClient.api.getSubscription(token)
                }
                BackendHealthStatus.report(true)
                return Status.OK
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401 || e.code() == 403) {
                    // Server responded — it's reachable, it just rejected us.
                    BackendHealthStatus.report(true)
                    return Status.UNAUTHORIZED
                }
                // Other HTTP errors (5xx etc.) fall through to the retry/give-up below.
            } catch (e: Exception) {
                // Timeout, connection refused, parse error, etc. — fall through.
            }
            if (attempt == 0) delay(1000)
        }

        BackendHealthStatus.report(false)
        return Status.UNREACHABLE
    }
}
