package com.example.easy_billing.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.easy_billing.MainActivity
import com.example.easy_billing.util.BackendHealthStatus
import com.example.easy_billing.util.BackendReachabilityChecker

/**
 * Background enforcement of the offline-session-timeout while the app is
 * minimized (not force-killed). BaseActivity/SessionTimeoutGuard's 5-second
 * timer only runs while a screen is actually on-screen (onResume/onPause) —
 * if the app sits backgrounded offline for the full 12-hour window it was
 * never re-checked until the user reopened it. This worker closes that gap
 * the same way SyncWorker already closes the equivalent gap for pending
 * syncs: a periodic WorkManager job that survives the app being backgrounded
 * (though not a full process kill without a scheduled re-enqueue, same
 * caveat SyncWorker already has).
 *
 * Deliberately does NOT show the "Session Expiring" warning dialog — there's
 * no foreground UI to show it on. It only does the check-and-clear; the user
 * simply finds themselves logged out next time they open the app.
 */
class SessionTimeoutWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val lastOnline = prefs.getLong("LAST_ONLINE", 0L)
        val diff = System.currentTimeMillis() - lastOnline

        // No session to expire (never logged in / already logged out).
        if (lastOnline <= 0L) return Result.success()

        // Same two-stage check as BaseActivity/SessionTimeoutGuard: device
        // internet first, then the actual server. This worker used to only
        // check device internet and refresh LAST_ONLINE off that alone — the
        // exact bug the two-stage redesign fixed everywhere else, just
        // missed here. Without this, a backgrounded session with a
        // permanently-dead server would never time out via this path even
        // though the identical scenario correctly times out in the
        // foreground, an inconsistency between the two enforcement paths.
        if (isInternetAvailable()) {
            when (BackendHealthStatus.checkIfDue(applicationContext)) {
                BackendReachabilityChecker.Status.OK -> {
                    prefs.edit { putLong("LAST_ONLINE", System.currentTimeMillis()) }
                    return Result.success()
                }
                BackendReachabilityChecker.Status.UNAUTHORIZED -> {
                    // Server responded and rejected the token — log out now,
                    // same as the foreground paths, rather than waiting for
                    // the offline countdown below.
                    clearSessionAndRedirect(prefs)
                    return Result.success()
                }
                else -> {
                    // UNREACHABLE (or NO_INTERNET, shouldn't occur here) —
                    // fall through to the offline-countdown check below.
                }
            }
        }

        // PROD value. Same limit as SessionTimeoutGuard/BaseActivity.
        val limit = 12 * 60 * 60 * 1000L // 12 hours
        if (diff > limit) {
            clearSessionAndRedirect(prefs)
        }

        return Result.success()
    }

    private fun isInternetAvailable(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Shared by both the offline-countdown expiry and the UNAUTHORIZED
     *  short-circuit above — same DEVICE_ID-preserving clear, same
     *  best-effort background-launch caveat either way. */
    private fun clearSessionAndRedirect(prefs: android.content.SharedPreferences) {
        // This worker runs on WorkManager's own background executor thread —
        // NOT the Main dispatcher a foreground Activity's forceLogout() runs
        // on. A plain "TOKEN == null" boolean guard checked independently on
        // two different real OS threads is NOT atomic (unlike two Main-
        // dispatcher callers, which can't truly interleave). SessionClearGate
        // uses a JVM monitor (synchronized), which enforces mutual exclusion
        // across actual threads, not just coroutines on one dispatcher — the
        // only choke point here that's genuinely safe against a foreground
        // Activity's forceLogout() racing this Worker.
        if (!com.example.easy_billing.util.SessionClearGate.clearIfNeeded(applicationContext)) return

        // No toast here (nothing is foregrounded to show it on) — just
        // pre-clear the session so the next app open goes straight to
        // MainActivity's login screen instead of a stale Dashboard.
        val intent = Intent(applicationContext, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Only launch if nothing is already handling it in the foreground;
        // starting an Activity from the background on modern Android is
        // restricted anyway, so this is best-effort — the prefs clear above
        // is what actually matters. If the launch is blocked by the OS,
        // the user still lands on the login screen the next time they
        // manually open the app, because TOKEN is already gone.
        runCatching { applicationContext.startActivity(intent) }
    }
}
