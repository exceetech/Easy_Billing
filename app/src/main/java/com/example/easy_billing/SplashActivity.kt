package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Launcher activity — opaque-themed so Android 12+ actually shows its
 * system splash (the app icon on a champagne background); MainActivity
 * can't hold the launcher slot itself because its translucent Auth theme
 * makes the OS skip the splash entirely.
 *
 * Does the session/token check itself and routes straight to Dashboard
 * (or WorkspaceChanged) when a valid session already exists, so a cold
 * start with an active session is a single hop (Splash → Dashboard)
 * instead of bouncing through MainActivity first. Each activity swap is
 * a full window transition, and since the app is landscape-locked while
 * the home screen is portrait, every extra hop re-triggers a rotation
 * transition — that's what read as "starting screen, then home screen,
 * then it loads again." Only falls through to MainActivity (login) when
 * there's no token or the token turned out to be invalid.
 */
class SplashActivity : BaseActivity() {

    private enum class SessionCheckResult { VALID, INVALID_TOKEN, WORKSPACE_CHANGED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)

        if (token.isNullOrEmpty()) {
            goToLogin()
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.api.getProfile(token)
                    SessionCheckResult.VALID
                } catch (e: HttpException) {
                    when (e.code()) {
                        401 -> SessionCheckResult.INVALID_TOKEN
                        409 -> SessionCheckResult.WORKSPACE_CHANGED
                        else -> SessionCheckResult.VALID // network/server hiccup → let through
                    }
                } catch (e: Exception) {
                    SessionCheckResult.VALID // offline → let user into cached Dashboard
                }
            }

            when (result) {
                SessionCheckResult.VALID -> {
                    startActivity(Intent(this@SplashActivity, DashboardActivity::class.java))
                    finish()
                }
                SessionCheckResult.INVALID_TOKEN -> {
                    prefs.edit().remove("TOKEN").apply()
                    goToLogin()
                }
                SessionCheckResult.WORKSPACE_CHANGED -> {
                    val intent = Intent(this@SplashActivity, WorkspaceChangedActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
