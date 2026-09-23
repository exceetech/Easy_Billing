package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.util.SessionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher activity — displays the brand splash screen with the center app logo
 * and the Powered by Scalancer emblem footer at the bottom before routing.
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        com.example.easy_billing.util.UserEventLogger.logAction("Splash", "opened")

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            if (token.isNullOrEmpty()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 2000) delay(2000 - elapsed)
                goToLogin()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                SessionCheck.run(this@SplashActivity, token)
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 2000) delay(2000 - elapsed)

            when (result) {
                SessionCheck.Result.VALID -> {
                    startActivity(Intent(this@SplashActivity, DashboardActivity::class.java))
                    finish()
                }
                SessionCheck.Result.ONBOARDING_INCOMPLETE -> {
                    startActivity(Intent(this@SplashActivity, OnboardingActivity::class.java))
                    finish()
                }
                SessionCheck.Result.INVALID_TOKEN -> {
                    prefs.edit().remove("TOKEN").apply()
                    goToLogin()
                }
                SessionCheck.Result.WORKSPACE_CHANGED -> {
                    val intent = Intent(this@SplashActivity, WorkspaceChangedActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun goToLogin() {
        // If any account has already set up Quick Unlock (PIN/fingerprint),
        // send the user there instead of the full email+password form —
        // straight to the PIN screen for a single saved account, or an
        // account picker first when more than one exists on this device.
        // See QuickUnlockManager.buildLoginIntent and QuickUnlockActivity's
        // doc comment for the full flow.
        startActivity(com.example.easy_billing.util.QuickUnlockManager.buildLoginIntent(this))
        finish()
    }
}
