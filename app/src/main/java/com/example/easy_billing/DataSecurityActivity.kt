package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.ChangePasswordRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DataSecurityActivity : BaseActivity() {

    private lateinit var btnClearBills: View
    private lateinit var btnFactoryReset: View
    private lateinit var btnChangePassword: View

    private lateinit var icChangePassword: ImageView
    private lateinit var icClearBills: ImageView
    private lateinit var icFactoryReset: ImageView

    private lateinit var btnUnlock: View
    private lateinit var tvUnlock: TextView
    private lateinit var icUnlock: ImageView

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_security)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        btnClearBills     = findViewById(R.id.btnClearBills)
        btnFactoryReset   = findViewById(R.id.btnFactoryReset)
        btnChangePassword = findViewById(R.id.btnChangePassword)

        icChangePassword = findViewById(R.id.icChangePassword)
        icClearBills     = findViewById(R.id.icClearBills)
        icFactoryReset   = findViewById(R.id.icFactoryReset)

        btnUnlock = findViewById(R.id.btnUnlock)
        tvUnlock  = findViewById(R.id.tvUnlock)
        icUnlock  = findViewById(R.id.icUnlock)

        setLocked(true)

        btnUnlock.setOnClickListener { toggleLock() }

        // Each action stays gated: locked guard + per-action password verification.
        btnChangePassword.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            showPasswordVerificationDialog { showChangePinDialog() }
        }
        btnClearBills.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            showPasswordVerificationDialog { clearBills() }
        }
        btnFactoryReset.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            showPasswordVerificationDialog { performFactoryReset() }
        }
    }

    // ================= LOCK / UNLOCK =================

    private fun toggleLock() {
        isEditMode = !isEditMode
        setLocked(!isEditMode)
    }

    /** locked = actions disabled (default); unlocked = actions tappable. */
    private fun setLocked(locked: Boolean) {
        val rows = listOf(btnChangePassword, btnClearBills, btnFactoryReset)
        rows.forEach {
            it.isEnabled = !locked
            it.isClickable = !locked
            it.alpha = if (locked) 0.55f else 1f
        }

        // Trailing glyph: lock when locked, chevron when unlocked.
        val trailing = if (locked) R.drawable.ic_si_lock else R.drawable.ic_chevron_right
        listOf(icChangePassword, icClearBills, icFactoryReset).forEach {
            it.setImageResource(trailing)
        }

        // Pill reflects the action the user can take next.
        tvUnlock.text = if (locked) "Unlock" else "Lock"
        icUnlock.setImageResource(if (locked) R.drawable.ic_si_lock else R.drawable.ic_si_unlock)
    }

    // ================= LOGIC =================

    private fun clearBills() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                RetrofitClient.api.clearBills(token)

                val db = AppDatabase.getDatabase(this@DataSecurityActivity)
                db.billDao().deleteAllItems()
                db.billDao().deleteAllBills()

                Toast.makeText(
                    this@DataSecurityActivity,
                    "Bills archived successfully",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@DataSecurityActivity,
                    "Failed to clear bills",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performFactoryReset() {

        lifecycleScope.launch(Dispatchers.IO) {

            val authPrefs = getSharedPreferences("auth", MODE_PRIVATE)
            val token     = authPrefs.getString("TOKEN", null)

            if (token == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DataSecurityActivity, "Not logged in.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // ── STEP 1: Pause + cancel sync ────────────────────────────────────
            // Suspend ALL background sync (in-flight job, 5-min retry loop,
            // WorkManager, network-regain) so nothing writes rows back into the
            // DB while we wipe it. Resumed in the finally below — never stuck off.
            val coordinator = com.example.easy_billing.sync.SyncCoordinator
                .get(applicationContext)
            coordinator.pauseSync()

            try {
                // ── STEP 2: Workspace Rotation on the backend ──────────────────
                // Archives the current Shop and provisions a clean new Shop.
                // Migrates the active Subscription. Returns a fresh JWT.
                // Business tables (bills, purchases, inventory, credit notes, etc.)
                // are NEVER touched — they remain linked to the archived shop.
                val resetResponse = RetrofitClient.api.factoryReset(token)
                val newToken      = resetResponse.access_token
                val newShopId     = resetResponse.new_shop_id

                // ── STEP 3: Wipe local data WITHOUT closing the DB ─────────────
                // clearAllTables() empties every table on the SAME open
                // connection. The database object is never closed, so no screen,
                // repository, or sync coroutine is left holding a dead instance
                // (which used to throw "connection pool has been closed", and the
                // close+reopen used to throw "database is locked"). The file is
                // kept. Corruption fallback ONLY: if clearing throws, fall back to
                // close+delete — the next getDatabase() rebuilds a fresh file.
                // Also load-bearing for isolation: import_services carries no
                // shopId, so this clear (or the delete fallback below) is what
                // stops the archived shop's records showing up under the new
                // one. See db/ImportService.kt.
                try {
                    AppDatabase.getDatabase(applicationContext).clearAllTables()
                } catch (clearError: Exception) {
                    clearError.printStackTrace()
                    AppDatabase.destroyInstance()
                    applicationContext.deleteDatabase("easy_billing_db")
                }

                // ── STEP 4: Write new workspace identity to SharedPrefs ─────────
                authPrefs.edit {
                    putString("TOKEN",   newToken)
                    putInt("SHOP_ID",    newShopId)
                }

                // ── STEP 5: Reset app-level settings ───────────────────────────
                getSharedPreferences("app_settings", MODE_PRIVATE).edit {
                    clear()
                    putString("app_language",      "en")
                    putString("app_language_name", "English")
                    putString("app_currency",      "₹")
                    putBoolean("ai_reset",         true)
                }

                // Drop delta-pull cursors — the DB was wiped and a new workspace
                // provisioned, so stale cursors must not carry over (R6).
                getSharedPreferences("sync_cursors", MODE_PRIVATE).edit().clear().apply()

                // ── STEP 6: Restart into fresh workspace ────────────────────────
                // SplashActivity validates the new token, then routes to Dashboard.
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Factory reset complete. Starting fresh workspace.",
                        Toast.LENGTH_LONG
                    ).show()

                    val intent = Intent(this@DataSecurityActivity, SplashActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Reset failed: ${e.message ?: "Check internet connection"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                // Always re-enable sync — against the fresh workspace on success,
                // or the existing one if the reset failed. Never leave it paused.
                coordinator.resumeSync()
            }
        }
    }

    private fun showChangePinDialog() {

        val dialogView = layoutInflater.inflate(R.layout.dialog_change_pin, null)

        val etNewPin = dialogView.findViewById<EditText>(R.id.etNewPin)
        val etConfirmPin = dialogView.findViewById<EditText>(R.id.etConfirmPin)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSavePin)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {

            val newPin = etNewPin.text.toString().trim()
            val confirmPin = etConfirmPin.text.toString().trim()

            if (newPin.length < 6) {
                etNewPin.error = "Password must be at least 6 characters"
                return@setOnClickListener
            }

            if (newPin != confirmPin) {
                etConfirmPin.error = "Passwords do not match"
                return@setOnClickListener
            }

            lifecycleScope.launch {

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null) ?: return@launch

                try {

                    RetrofitClient.api.changePassword(
                        token,
                        ChangePasswordRequest(newPin)
                    )

                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Password changed. Please login again.",
                        Toast.LENGTH_LONG
                    ).show()

                    // logout user
                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    val intent = Intent(this@DataSecurityActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)

                    dialog.dismiss()

                    dialog.dismiss()

                } catch (e: Exception) {

                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Failed to change password",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {

            val password = etPassword.text.toString().trim()

            if (password.isEmpty()) {
                etPassword.error = "Enter password"
                return@setOnClickListener
            }

            verifyPassword(password) {
                dialog.dismiss()
                onVerified()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}