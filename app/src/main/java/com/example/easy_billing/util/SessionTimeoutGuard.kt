package com.example.easy_billing.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Standalone offline-session-timeout check, extracted so screens that
 * shouldn't inherit BaseActivity's orientation-lock / immersive-fullscreen /
 * toolbar / locale bundle can still get the same "log out after 12 hours
 * offline" protection.
 *
 * Why this exists as a separate object instead of just making every screen
 * extend BaseActivity: InvoiceActivity, ProfitActivity, BillDetailsActivity,
 * GstReportsActivity, SalesReturnActivity, CustomerTransactionsActivity and
 * ProfitChartActivity already have their OWN hand-written copies of the
 * orientation-lock + immersive-UI logic that BaseActivity also provides.
 * Making them extend BaseActivity on top of that gives every one of those
 * screens two competing copies of the same "hide status/nav bars" logic
 * firing on every onResume/onWindowFocusChanged — which is what broke
 * printing and other system-dialog-driven features. This object carries
 * ONLY the session-timeout piece, so a screen can opt in with two calls in
 * its existing onResume()/onPause() without touching anything else about
 * how it's already built.
 *
 * Mirrors BaseActivity's checkOfflineSession()/forceLogout()/
 * isInternetAvailable() logic exactly, including the two-stage "check
 * internet, THEN check server" gate (see BackendHealthStatus.checkIfDue()),
 * with one deliberate improvement: forceLogout() here preserves DEVICE_ID
 * across the clear (matching NetworkReceiver's forceLogout and now
 * BaseActivity's own forceLogout(), which was brought in line separately).
 *
 * Implementation note: checking the server means an actual suspend network
 * call, so this switched from a plain Handler.postDelayed loop to one
 * coroutine per guarded Activity, launched from start() via that Activity's
 * own lifecycleScope and cancelled by stop() — same "start in onResume, stop
 * in onPause" bracketing every call site already does, just coroutine-based
 * underneath instead of Handler-based.
 */
object SessionTimeoutGuard {

    /** How long an offline session is allowed before forced logout. Matches BaseActivity. */
    // PROD value. Matches BaseActivity.kt and SessionTimeoutWorker.kt.
    const val SESSION_OFFLINE_LIMIT_MS = 12 * 60 * 60 * 1000L // 12 hours

    private val jobs = WeakHashMap<Activity, Job>()

    /** Call from the screen's onResume(). Starts the 5-second recheck loop. */
    fun start(activity: AppCompatActivity) {
        if (jobs[activity]?.isActive == true) return // already running
        val job = activity.lifecycleScope.launch {
            var warningShown = false
            while (true) {
                warningShown = check(activity, warningShown)
                delay(5000)
            }
        }
        jobs[activity] = job
    }

    /** Call from the screen's onPause(). Stops the recheck loop for this screen. */
    fun stop(activity: Activity) {
        jobs.remove(activity)?.cancel()
    }

    private fun updateLastOnlineTime(activity: Activity) {
        val prefs = activity.getSharedPreferences("auth", Context.MODE_PRIVATE)
        prefs.edit { putLong("LAST_ONLINE", System.currentTimeMillis()) }
    }

    /** Same real-reachability check as BaseActivity.isInternetAvailable(). */
    private fun isInternetAvailable(activity: Activity): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Mirrors BaseActivity.OfflineReason — which condition triggered the
     *  countdown, so the right dialog (no-internet vs. server-down) shows. */
    enum class OfflineReason { NO_INTERNET, SERVER_UNREACHABLE }

    private fun showWarning(activity: Activity, secondsLeft: Long, reason: OfflineReason) {
        if (activity.isFinishing) return

        // Same custom card dialogs BaseActivity uses, instead of the plain
        // default AlertDialog, so the warning looks identical regardless of
        // which of the two mechanisms triggers it. Which layout gets
        // inflated depends on WHY the countdown started.
        val layoutRes: Int
        val countdownId: Int
        val okButtonId: Int
        when (reason) {
            OfflineReason.NO_INTERNET -> {
                layoutRes = com.example.easy_billing.R.layout.dialog_session_expiring
                countdownId = com.example.easy_billing.R.id.tvSessionExpiringCountdown
                okButtonId = com.example.easy_billing.R.id.btnSessionExpiringOk
            }
            OfflineReason.SERVER_UNREACHABLE -> {
                layoutRes = com.example.easy_billing.R.layout.dialog_server_unavailable
                countdownId = com.example.easy_billing.R.id.tvServerUnavailableCountdown
                okButtonId = com.example.easy_billing.R.id.btnServerUnavailableOk
            }
        }

        val inflater = android.view.LayoutInflater.from(activity)
        val view = inflater.inflate(layoutRes, null)
        view.findViewById<android.widget.TextView>(countdownId).text =
            "Logging out in ${secondsLeft}s"

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<android.view.View>(okButtonId).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun forceLogout(activity: Activity) {
        // SessionClearGate is a JVM-monitor-backed choke point, correct even
        // against SessionTimeoutWorker's separate background-executor thread
        // (a plain "TOKEN == null" guard checked independently on two
        // different real OS threads isn't atomic — see SessionClearGate's
        // doc comment). If it returns false, someone else already cleared
        // the session — nothing left to do.
        if (!SessionClearGate.clearIfNeeded(activity)) return

        Toast.makeText(activity, "Session expired. Please login again.", Toast.LENGTH_LONG).show()

        val intent = Intent(activity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        activity.startActivity(intent)
    }

    /** Returns the updated warningShown state (this object has no per-Activity
     *  field storage of its own for it — each start() call's coroutine keeps
     *  its own local copy across ticks). */
    private suspend fun check(activity: Activity, warningShownIn: Boolean): Boolean {
        var warningShown = warningShownIn

        val prefs = activity.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val lastOnline = prefs.getLong("LAST_ONLINE", 0L)
        val now = System.currentTimeMillis()
        val diff = now - lastOnline
        val limit = SESSION_OFFLINE_LIMIT_MS
        val warningTime = limit - (15 * 1000L)

        // Two-stage check, as originally intended: device internet FIRST,
        // THEN the actual server. BackendHealthStatus.checkIfDue() is
        // throttled app-wide (shared with BaseActivity's identical gate), so
        // this doesn't turn every guarded screen's 5-second tick into its
        // own server-hammering loop.
        var offlineReason = OfflineReason.NO_INTERNET

        if (isInternetAvailable(activity)) {
            when (BackendHealthStatus.checkIfDue(activity)) {
                BackendReachabilityChecker.Status.OK -> {
                    updateLastOnlineTime(activity)
                    return false
                }
                BackendReachabilityChecker.Status.UNAUTHORIZED -> {
                    // Server responded and rejected the token — a real "not
                    // logged in anymore," not a reachability problem. A plain
                    // reachable boolean used to mask this as "session alive"
                    // and never expire it. Log out now.
                    forceLogout(activity)
                    return false
                }
                else -> {
                    // UNREACHABLE (or NO_INTERNET, shouldn't occur here) —
                    // fall through, same as the no-internet-at-all path
                    // below, but tagged so the right dialog shows.
                    offlineReason = OfflineReason.SERVER_UNREACHABLE
                }
            }
        }

        if (!warningShown && diff in warningTime until limit) {
            showWarning(activity, (limit - diff) / 1000, offlineReason)
            warningShown = true
        }

        // The login screen itself is exempt — can't log yourself out of login.
        val isAuthScreen = activity is MainActivity
        if (!isAuthScreen && lastOnline > 0 && diff > limit) {
            forceLogout(activity)
        }

        return warningShown
    }
}
