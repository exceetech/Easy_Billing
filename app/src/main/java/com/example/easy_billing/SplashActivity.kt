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
                    // markVerifiedNow(), not just report(true) — this call just
                    // proved the token is genuinely valid AND the server is
                    // reachable, so it should also reset the shared
                    // checkIfDue() throttle clock. Otherwise a stale
                    // UNAUTHORIZED/UNREACHABLE verdict cached from before this
                    // cold start (e.g. the app was killed and reopened shortly
                    // after a forced logout) could get handed back out by the
                    // very next offline-timeout tick on Dashboard, immediately
                    // logging the user right back out despite this successful
                    // check. See BackendHealthStatus.markVerifiedNow().
                    com.example.easy_billing.util.BackendHealthStatus.markVerifiedNow()
                    SessionCheckResult.VALID
                } catch (e: HttpException) {
                    when (e.code()) {
                        401 -> SessionCheckResult.INVALID_TOKEN
                        409 -> SessionCheckResult.WORKSPACE_CHANGED
                        else -> {
                            // Server responded — it's reachable, just errored (5xx etc.).
                            // Still let the user through to cached Dashboard; only the
                            // banner state changes here, not the routing decision.
                            com.example.easy_billing.util.BackendHealthStatus.report(true)
                            SessionCheckResult.VALID
                        }
                    }
                } catch (e: Exception) {
                    // Could be "device offline" (SessionTimeoutGuard/BaseActivity's
                    // concern, not ours) or "our server specifically didn't respond."
                    // Only report the latter — checking device connectivity here keeps
                    // this consistent with NetworkReceiver.checkBackend()'s same split.
                    if (com.example.easy_billing.util.NetworkUtils.isOnline(this@SplashActivity)) {
                        com.example.easy_billing.util.BackendHealthStatus.report(false)
                    }
                    SessionCheckResult.VALID // offline or server hiccup → let user into cached Dashboard
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
