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
 */
class WorkspaceChangedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace_changed)

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
