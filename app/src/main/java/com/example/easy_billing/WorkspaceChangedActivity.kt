package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WorkspaceChangedActivity
 *
 * Shown when the backend returns HTTP 409 WORKSPACE_CHANGED.
 * This means the current JWT's workspace_version no longer matches
 * the DB — the workspace was either factory-reset from another device
 * or restored by an admin.
 *
 * The ONLY valid action is "Reload Workspace":
 *   1. Cancel any in-flight sync.
 *   2. Wipe the local Room database (clearAllTables).
 *   3. Clear auth SharedPreferences (TOKEN, SHOP_ID, workspace_version).
 *   4. Navigate to MainActivity (login screen).
 *
 * Per spec: forceLogout() is NOT called here — it clears prefs which
 * is what we do manually below, but we do NOT want it to run in other
 * scenarios (offline timeout) and wipe data incorrectly.
 *
 * This screen is also deliberately EXEMPT from SessionTimeoutGuard/
 * BaseActivity's offline-session-timeout, same as MainActivity — this is
 * a confirmed decision, not an oversight (flagged and reviewed in the
 * offline-session-timeout audit). Wiring the guard in here would let its
 * forceLogout() race against reloadWorkspace()'s own manual DB-clear +
 * prefs-clear + navigation sequence above — exactly the double-wipe/
 * double-navigation scenario this class's own forceLogout() ban already
 * exists to prevent. A user is only ever on this screen briefly, with a
 * single forced action (Reload Workspace) and no way to navigate away,
 * so there is no real 12-hour-offline exposure window here to protect
 * against in the first place.
 */
class WorkspaceChangedActivity : AppCompatActivity() {

    // Deliberately NOT a BaseActivity (avoids BaseActivity's forced landscape
    // re-orientation), so the system bars are hidden locally here instead —
    // same immersive treatment every other screen in the app gets.
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
        setContentView(R.layout.activity_workspace_changed)
        com.example.easy_billing.util.UserEventLogger.logAction("WorkspaceChanged", "opened")

        // Prevent back-navigation — user must reload.
        // (FLAG_ACTIVITY_CLEAR_TASK already handles the back stack via interceptor.)

        val btnReload = findViewById<Button>(R.id.btnReloadWorkspace)
        btnReload.setOnClickListener { reloadWorkspace() }
    }

    @Deprecated("Disabled — user must reload workspace, not navigate back.")
    override fun onBackPressed() {
        // Intentionally block back. User must press the reload button.
    }

    private fun reloadWorkspace() {
        lifecycleScope.launch {
            val coordinator = com.example.easy_billing.sync.SyncCoordinator.get(applicationContext)

            // ── 1. Pause + cancel sync ────────────────────────────────────────
            // Suspend all background sync so nothing writes rows back into the
            // DB while we clear it. Resumed in the finally — never stuck off.
            try { coordinator.pauseSync() } catch (_: Exception) {}

            try {
                withContext(Dispatchers.IO) {
                    // ── 2. Clear all local Room tables (DB stays OPEN) ────────
                    // clearAllTables() empties every table on the same open
                    // connection. We do NOT close the database (no destroyInstance),
                    // so no screen / repository / sync keeps a dead reference —
                    // that's what caused "connection pool has been closed" and the
                    // close+reopen caused "database is locked".
                    try {
                        AppDatabase.getDatabase(applicationContext).clearAllTables()
                    } catch (_: Exception) {}

                    // ── 3. Clear auth state ───────────────────────────────────
                    getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply()

                    // Drop delta-pull cursors — the workspace changed, so a stale
                    // cursor could skip rows in the new/restored data set (R6).
                    getSharedPreferences("sync_cursors", MODE_PRIVATE).edit().clear().apply()

                    // Clear app settings cache (language, currency) so next launch
                    // re-fetches from the fresh workspace.
                    getSharedPreferences("app_settings", MODE_PRIVATE).edit().apply {
                        remove("ai_reset")
                    }.apply()
                }
            } finally {
                // Re-enable sync (it no-ops until the user logs in again since the
                // token was just cleared) so the flag is never left paused.
                coordinator.resumeSync()
            }

            // ── 4. Go to login ────────────────────────────────────────────────
            val intent = Intent(this@WorkspaceChangedActivity, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
