package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AccountSuspension
import com.example.easy_billing.util.QuickUnlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AccountSuspendedActivity
 *
 * Shown when the backend answers 403 ACCOUNT_SUSPENDED: an administrator
 * suspended this shop. The shop is blocked from everything until it is
 * reactivated, so this screen replaces the app and cannot be dismissed with Back.
 *
 * Unlike WorkspaceChangedActivity it deliberately does NOT clear anything:
 * a suspension is temporary, and bills that have not synced yet must survive it.
 *
 *  - "Check again" asks the server whether the shop is active now and, if so,
 *    restarts the normal start-up flow (SplashActivity).
 *  - "Sign out" only removes the login token and goes to the login screen, the
 *    same as an expired token does.
 *
 * Not a BaseActivity (same reasons as WorkspaceChangedActivity): no forced
 * orientation, and the offline-session-timeout must not log the user out from
 * under this screen.
 */
class AccountSuspendedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE = "message"

        /** True while the screen is showing, so a second launch is skipped. */
        @Volatile
        var isVisible: Boolean = false
            private set
    }

    private lateinit var tvMessage: TextView
    private lateinit var btnCheck: Button

    override fun onStart() {
        super.onStart()
        isVisible = true
    }

    override fun onStop() {
        isVisible = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_suspended)
        com.example.easy_billing.util.UserEventLogger.logAction("AccountSuspended", "opened")

        tvMessage = findViewById(R.id.tvSuspendedMessage)
        btnCheck = findViewById(R.id.btnCheckAgain)
        showMessage(intent.getStringExtra(EXTRA_MESSAGE))

        btnCheck.setOnClickListener { checkAgain() }
        findViewById<Button>(R.id.btnSuspendedSignOut).setOnClickListener { signOut() }
    }

    @Deprecated("Disabled — the shop is suspended; the only ways forward are the two buttons.")
    override fun onBackPressed() {
        // Intentionally blocked.
    }

    private fun showMessage(message: String?) {
        tvMessage.text = message?.takeIf { it.isNotBlank() } ?: getString(R.string.account_suspended_default_message)
    }

    private fun checkAgain() {
        val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
        if (token == null) {
            // Logged out meanwhile: nothing to check, go to the normal start-up flow.
            restartApp()
            return
        }
        btnCheck.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RetrofitClient.api.getProfile(token) }
                // Active again — run the usual start-up checks.
                restartApp()
            } catch (e: Exception) {
                btnCheck.isEnabled = true
                if (AccountSuspension.isSuspended(e)) {
                    showMessage(AccountSuspension.lastMessage)
                    Toast.makeText(this@AccountSuspendedActivity, R.string.account_suspended_still, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@AccountSuspendedActivity, R.string.account_suspended_check_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restartApp() {
        val intent = Intent(this, SplashActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun signOut() {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val username = prefs.getString("USERNAME", null)
        // Only the token goes. Local data and Quick Unlock stay, so the owner can
        // sign straight back in once the shop is reactivated.
        prefs.edit().remove("TOKEN").apply()
        val intent = QuickUnlockManager.buildLoginIntent(this, username)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
