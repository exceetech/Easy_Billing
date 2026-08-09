package com.example.easy_billing

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.edit
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VerifyPasswordRequest
import com.example.easy_billing.util.AppClock
import com.example.easy_billing.util.BackendHealthStatus
import com.example.easy_billing.util.NtpClient
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class BaseActivity : AppCompatActivity() {

    private var warningShown = false
    private var sessionLoopStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Orientation is no longer forced here — this used to override the
        // manifest's android:screenOrientation on every Activity extending
        // BaseActivity, which is why changing the manifest to "unspecified"
        // (phone compatibility work) had no visible effect: this line ran
        // after the manifest was read and silently reset it back to
        // landscape every time. The manifest value is now the only source
        // of truth for orientation.

        // Removed hideSystemUI() from here to prevent NullPointerException on some devices
        // where the DecorView is not yet initialized. It is handled in onWindowFocusChanged.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                // Hide both status bar and navigation bar
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                // Use sticky immersive mode (swipe to show temporarily)
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Backward compatibility for older Android versions
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // ---------------- TOOLBAR ----------------

    protected fun setupToolbar(toolbarId: Int, showBack: Boolean = true) {
        val toolbar = findViewById<Toolbar>(toolbarId)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowHomeEnabled(false)
            setDisplayHomeAsUpEnabled(showBack)
        }

        // Optional: custom back icon (more premium)
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------- LANGUAGE ----------------

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "en") ?: "en"
        val context = LocaleHelper.setLocale(newBase, lang)
        super.attachBaseContext(context)
    }

    // ---------------- VERIFY PASSWORD ----------------

    protected fun verifyPassword(password: String, onSuccess: () -> Unit) {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {
                val response = RetrofitClient.api.verifyPassword(
                    token,
                    VerifyPasswordRequest(password)
                )

                if (response.isSuccessful) {
                    updateLastOnlineTime()
                    onSuccess()
                } else {
                    Toast.makeText(this@BaseActivity, R.string.incorrect_password, Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@BaseActivity, R.string.verification_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- CLOCK GATE (Phase 0) ----------------

    /** Result of verifying the device clock against internet time. */
    sealed class ClockCheck {
        /** Internet time confirmed (and anchored); drift within tolerance. */
        object Ok : ClockCheck()
        /** Device clock is off by [driftMs]; billing must be blocked. */
        data class Skewed(val driftMs: Long) : ClockCheck()
        /** Offline but a previously-verified anchor exists; safe to proceed. */
        object OfflineVerified : ClockCheck()
        /** Offline and never verified — cannot trust the clock at all. */
        object OfflineUnverified : ClockCheck()
    }

    /**
     * Fetch internet (NTP) time and reconcile it with the device clock.
     *
     *  • Online + drift ≤ tolerance → anchor & return [ClockCheck.Ok].
     *  • Online + drift > tolerance → return [ClockCheck.Skewed] (caller blocks).
     *  • Offline → [OfflineVerified] if we have a cached anchor, else
     *    [OfflineUnverified].
     *
     * Runs the blocking UDP call off the main thread. Call from a coroutine.
     */
    suspend fun verifyDeviceClock(): ClockCheck {
        if (!isInternetAvailable()) {
            return if (AppClock.isVerified()) ClockCheck.OfflineVerified
                   else ClockCheck.OfflineUnverified
        }
        val ntp = withContext(Dispatchers.IO) { NtpClient.fetch() }
            ?: return if (AppClock.isVerified()) ClockCheck.OfflineVerified
                      else ClockCheck.OfflineUnverified

        val drift = kotlin.math.abs(ntp - System.currentTimeMillis())
        return if (drift > CLOCK_TOLERANCE_MS) {
            ClockCheck.Skewed(drift)
        } else {
            AppClock.anchor(ntp)
            ClockCheck.Ok
        }
    }

    /** Blocking dialog telling the user to fix their device clock. */
    fun showClockBlockedDialog(driftMs: Long, onRetry: () -> Unit) {
        if (isFinishing) return
        val mins = driftMs / 60000
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.wrong_device_time)
            .setMessage(
                "Your device clock is off by about $mins minute(s).\n\n" +
                "Please set it to automatic / correct date & time, then retry. " +
                "Billing is paused until the time is correct."
            )
            .setPositiveButton(R.string.open_date_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
                }
            }
            .setNegativeButton(R.string.retry) { _, _ -> onRetry() }
            .show()
    }

    // ---------------- SESSION ----------------

    fun updateLastOnlineTime() {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        prefs.edit { putLong("LAST_ONLINE", System.currentTimeMillis()) }
    }

    suspend fun checkOfflineSession() {

        // Pre-login / account-recovery screens never hold a valid session
        // TOKEN (forgot-password uses a separate one-time RESET_TOKEN, see
        // ChangePasswordActivity). Probing backend health here with no/stale
        // TOKEN always comes back UNAUTHORIZED, and the UNAUTHORIZED branch
        // below calls forceLogout() unconditionally (it was only ever gated
        // against the offline-timeout branch, not this one) — that's what
        // was kicking users back to MainActivity with a "Session expired"
        // toast mid-way through forgot-password/OTP/reset. None of these
        // screens have a session to expire, so skip the check entirely.
        val isAuthScreen = this is MainActivity ||
                this is RegisterActivity ||
                this is ForgotPasswordActivity ||
                this is OtpVerificationActivity ||
                this is ChangePasswordActivity
        if (isAuthScreen) return

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)

        val lastOnline = prefs.getLong("LAST_ONLINE", 0L)
        val now = System.currentTimeMillis()

        val diff = now - lastOnline

        val limit = SESSION_OFFLINE_LIMIT_MS

        val warningTime = limit - (15 * 1000L)

        // Two-stage check, as originally intended: device internet FIRST,
        // THEN the actual server — only when BOTH are confirmed do we treat
        // the session as alive and reset the offline countdown. Previously
        // this only checked device internet and refreshed LAST_ONLINE off
        // that alone, so a permanently-dead backend never actually expired a
        // session as long as the device had generic internet from any
        // source (NET_CAPABILITY_VALIDATED just confirms Android's own
        // connectivity check, not that OUR server is reachable) — someone
        // could keep the app "logged in" forever with the backend gone.
        // BackendHealthStatus.checkIfDue() is throttled app-wide (at most
        // one real server probe every 20s across every guarded screen), so
        // this 5-second tick doesn't turn into a server-hammering loop.
        // Which case is driving this countdown — device has no internet at
        // all, or the device is online but OUR server specifically isn't
        // answering. Defaults to NO_INTERNET (the isInternetAvailable()
        // check below failing is the common case); only flipped to
        // SERVER_UNREACHABLE when the device passed the internet check but
        // the server probe came back UNREACHABLE. Threaded through to
        // showSessionWarning() so the two situations render visually
        // distinct dialogs instead of one generic "Session Expiring" card
        // regardless of which one is actually true.
        var offlineReason = OfflineReason.NO_INTERNET

        if (isInternetAvailable()) {
            when (BackendHealthStatus.checkIfDue(this)) {
                com.example.easy_billing.util.BackendReachabilityChecker.Status.OK -> {
                    warningShown = false
                    updateLastOnlineTime()   // 🔥 KEEP SESSION ALIVE
                    return
                }
                com.example.easy_billing.util.BackendReachabilityChecker.Status.UNAUTHORIZED -> {
                    // The server responded and rejected the token — this is
                    // not a reachability problem, it's a real "you are not
                    // logged in anymore." Checking this as a plain reachable
                    // boolean used to treat a revoked token exactly like a
                    // healthy session and never expire it. Log out now,
                    // don't wait for the offline countdown.
                    forceLogout()
                    return
                }
                else -> {
                    // UNREACHABLE (or NO_INTERNET, which shouldn't occur
                    // here since isInternetAvailable() already passed) — the
                    // server specifically isn't answering even though the
                    // device is online. Fall through to the same
                    // warning/logout timer as "no internet at all," but tag
                    // it so the warning dialog can say the right thing.
                    offlineReason = OfflineReason.SERVER_UNREACHABLE
                }
            }
        }

        // ⚠️ SHOW WARNING ONCE
        if (!warningShown && diff in warningTime until limit) {
            showSessionWarning((limit - diff) / 1000, offlineReason)
            warningShown = true
        }

        // ❌ LOGOUT (isAuthScreen already returned early above for the
        // pre-login/recovery screens, so reaching here means this is a real
        // logged-in session)
        if (lastOnline > 0 && diff > limit) {
            forceLogout()
        }
    }

    fun forceLogout() {

        // DashboardActivity runs TWO independent loops on the same instance
        // (BaseActivity's own 5s checkOfflineSession() loop and its own 20s
        // startBackendHealthPolling() loop), and both can independently reach
        // an UNAUTHORIZED result and both call this function. SessionClearGate
        // is a JVM-monitor-backed choke point (not just a coroutine Mutex),
        // so it stays correct even against SessionTimeoutWorker's background
        // executor thread, not just other Main-dispatcher callers. If it
        // returns false, someone else already cleared the session — nothing
        // left to do (DEVICE_ID preservation is handled inside the gate).
        if (!com.example.easy_billing.util.SessionClearGate.clearIfNeeded(this)) return

        Toast.makeText(this, R.string.session_expired_login_again, Toast.LENGTH_LONG).show()

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    // ---------------- INTERNET ----------------

    fun isInternetAvailable(): Boolean {

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ---------------- WARNING ----------------

    /** Which condition is driving the offline-timeout countdown — used to
     *  pick between dialog_session_expiring.xml (no internet at all) and
     *  dialog_server_unavailable.xml (device online, our backend down). */
    enum class OfflineReason { NO_INTERNET, SERVER_UNREACHABLE }

    private fun showSessionWarning(seconds: Long, reason: OfflineReason = OfflineReason.NO_INTERNET) {

        if (isFinishing) return

        // Custom card dialog matching the app's design language (same shell
        // as dialog_sign_out.xml), instead of the plain default AlertDialog.
        // Which layout gets inflated depends on WHY the countdown started —
        // see OfflineReason above.
        val layoutRes: Int
        val countdownId: Int
        val okButtonId: Int
        when (reason) {
            OfflineReason.NO_INTERNET -> {
                layoutRes = R.layout.dialog_session_expiring
                countdownId = R.id.tvSessionExpiringCountdown
                okButtonId = R.id.btnSessionExpiringOk
            }
            OfflineReason.SERVER_UNREACHABLE -> {
                layoutRes = R.layout.dialog_server_unavailable
                countdownId = R.id.tvServerUnavailableCountdown
                okButtonId = R.id.btnServerUnavailableOk
            }
        }

        val view = layoutInflater.inflate(layoutRes, null)
        view.findViewById<android.widget.TextView>(countdownId).text =
            "Logging out in ${seconds}s"

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(okButtonId).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ---------------- LIFECYCLE ----------------

    override fun onResume() {
        super.onResume()
        startSessionLoop()
    }

    /**
     * Replaces the old Handler.postDelayed loop — checkOfflineSession() is
     * now suspend (it can make a real network call via
     * BackendHealthStatus.checkIfDue()), so the tick needs a coroutine.
     * Guarded by sessionLoopStarted so onResume firing more than once
     * doesn't stack duplicate loops; repeatOnLifecycle(STARTED) means the
     * loop body naturally stops running once the Activity drops below
     * STARTED (e.g. onStop) and this single long-lived coroutine resumes it
     * automatically next time the Activity re-enters STARTED, instead of
     * needing a matching manual onPause() call the way the old Handler did.
     */
    private fun startSessionLoop() {
        if (sessionLoopStarted) return
        sessionLoopStarted = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    checkOfflineSession()
                    delay(5000)
                }
            }
        }
    }

    companion object {
        /** Max allowed device-clock vs internet-time drift before billing is blocked. */
        const val CLOCK_TOLERANCE_MS = 5 * 60 * 1000L        // 5 minutes
        /** How long an offline session is allowed before forced logout. */
        // PROD value. See SessionTimeoutGuard.kt and SessionTimeoutWorker.kt
        // for the other two places this same constant is duplicated and must
        // be changed together.
        const val SESSION_OFFLINE_LIMIT_MS = 12 * 60 * 60 * 1000L // 12 hours
    }
}