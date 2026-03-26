package com.example.easy_billing

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.launch

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
    }

    // ---------------- TOOLBAR ----------------

    protected fun setupToolbar(toolbarId: Int, showBack: Boolean = true) {
        val toolbar = findViewById<Toolbar>(toolbarId)
        setSupportActionBar(toolbar)

        if (showBack) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
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
                    "Bearer $token",
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

        val limit = 1 * 60 * 1000L          // 🔴 TEST (1 min)
        //val limit = 12 * 60 * 60 * 1000L // ✅ PROD

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
}