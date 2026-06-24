package com.example.easy_billing

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VerifyPasswordRequest
import com.example.easy_billing.util.AppClock
import com.example.easy_billing.util.NtpClient
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class BaseActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var warningShown = false

    private val sessionRunnable = object : Runnable {
        override fun run() {
            checkOfflineSession()
            handler.postDelayed(this, 5000) // every 5 sec
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
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
                    Toast.makeText(this@BaseActivity, "Incorrect password", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@BaseActivity, "Verification failed", Toast.LENGTH_SHORT).show()
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
            .setTitle("Wrong device time")
            .setMessage(
                "Your device clock is off by about $mins minute(s).\n\n" +
                "Please set it to automatic / correct date & time, then retry. " +
                "Billing is paused until the time is correct."
            )
            .setPositiveButton("Open date settings") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
                }
            }
            .setNegativeButton("Retry") { _, _ -> onRetry() }
            .show()
    }

    // ---------------- SESSION ----------------

    fun updateLastOnlineTime() {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        prefs.edit { putLong("LAST_ONLINE", System.currentTimeMillis()) }
    }

    fun checkOfflineSession() {

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)

        val lastOnline = prefs.getLong("LAST_ONLINE", 0L)
        val now = System.currentTimeMillis()

        val diff = now - lastOnline

        val limit = SESSION_OFFLINE_LIMIT_MS

        val warningTime = limit - (15 * 1000L)

        // 🔍 DEBUG (optional)
        android.util.Log.d("SESSION_DEBUG", "diff=$diff lastOnline=$lastOnline")

        // ✅ REAL INTERNET CHECK
        if (isInternetAvailable()) {
            warningShown = false
            updateLastOnlineTime()   // 🔥 KEEP SESSION ALIVE
            return
        }

        // ⚠️ SHOW WARNING ONCE
        if (!warningShown && diff in warningTime until limit) {
            showSessionWarning((limit - diff) / 1000)
            warningShown = true
        }

        // ❌ LOGOUT
        val isAuthScreen = this is MainActivity

        if (!isAuthScreen && lastOnline > 0 && diff > limit) {
            forceLogout()
        }
    }

    fun forceLogout() {

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        prefs.edit().clear().apply()
        // Drop delta-pull cursors so the next workspace starts fresh (R6).
        getSharedPreferences("sync_cursors", MODE_PRIVATE).edit().clear().apply()

        Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_LONG).show()

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

    private fun showSessionWarning(seconds: Long) {

        if (isFinishing) return

        AlertDialog.Builder(this)
            .setTitle("Session Expiring")
            .setMessage("No internet. You will be logged out in $seconds seconds.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------- LIFECYCLE ----------------

    override fun onResume() {
        super.onResume()
        handler.post(sessionRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(sessionRunnable)
    }

    companion object {
        /** Max allowed device-clock vs internet-time drift before billing is blocked. */
        const val CLOCK_TOLERANCE_MS = 5 * 60 * 1000L        // 5 minutes
        /** How long an offline session is allowed before forced logout. */
        const val SESSION_OFFLINE_LIMIT_MS = 12 * 60 * 60 * 1000L // 12 hours (PROD)
    }
}