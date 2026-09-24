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
    private lateinit var btnSendDiagnosticReport: View

    private lateinit var icChangePassword: ImageView
    private lateinit var icClearBills: ImageView
    private lateinit var icFactoryReset: ImageView
    private lateinit var icSendDiagnosticReport: ImageView

    private lateinit var btnUnlock: View
    private lateinit var tvUnlock: TextView
    private lateinit var icUnlock: ImageView

    private lateinit var btnQuickUnlock: View
    private lateinit var tvQuickUnlockSub: TextView
    private lateinit var chipQuickUnlockStatus: TextView
    private lateinit var icQuickUnlock: ImageView

    private var isEditMode = false

    companion object {
        const val EXTRA_ADMIN = "extra_admin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_security)
        com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "opened")

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        btnClearBills     = findViewById(R.id.btnClearBills)
        btnFactoryReset   = findViewById(R.id.btnFactoryReset)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnSendDiagnosticReport = findViewById(R.id.btnSendDiagnosticReport)

        icChangePassword = findViewById(R.id.icChangePassword)
        icClearBills     = findViewById(R.id.icClearBills)
        icFactoryReset   = findViewById(R.id.icFactoryReset)
        icSendDiagnosticReport = findViewById(R.id.icSendDiagnosticReport)

        btnUnlock = findViewById(R.id.btnUnlock)
        tvUnlock  = findViewById(R.id.tvUnlock)
        icUnlock  = findViewById(R.id.icUnlock)

        btnQuickUnlock = findViewById(R.id.btnQuickUnlock)
        tvQuickUnlockSub = findViewById(R.id.tvQuickUnlockSub)
        chipQuickUnlockStatus = findViewById(R.id.chipQuickUnlockStatus)
        icQuickUnlock = findViewById(R.id.icQuickUnlock)

        // Admin-only actions (clear bills, factory reset, diagnostics) only
        // show when opened from Settings' hidden "Admin tools" row.
        val isAdmin = com.example.easy_billing.util.AdminMode.enabled &&
            intent.getBooleanExtra(EXTRA_ADMIN, false)
        if (!isAdmin) {
            listOf(R.id.cardData, R.id.cardDiagnose, R.id.cardDanger,
                R.id.btnUnlock, R.id.helperUnlock, R.id.tvEyebrow)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            findViewById<TextView>(R.id.tvTitle1).setText(R.string.pu_title1)
            findViewById<TextView>(R.id.tvTitle2).setText(R.string.pu_title2)
        } else {
            // Admin page: only the admin tools, no password / quick unlock.
            findViewById<View>(R.id.cardSecurity).visibility = View.GONE
        }

        setLocked(true)
        icChangePassword.setImageResource(R.drawable.ic_chevron_right)
        icQuickUnlock.setImageResource(R.drawable.ic_chevron_right)
        refreshQuickUnlockRow()

        btnUnlock.setOnClickListener { toggleLock() }

        // Each action stays gated: locked guard + per-action password verification.
        btnChangePassword.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "change_password_clicked")
            showPasswordVerificationDialog { showChangePinDialog() }
        }
        btnQuickUnlock.setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "quick_unlock_clicked")
            handleQuickUnlockClick()
        }
        btnClearBills.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "clear_bills_clicked")
            showPasswordVerificationDialog { clearBills() }
        }
        btnFactoryReset.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "factory_reset_clicked")
            showPasswordVerificationDialog { performFactoryReset() }
        }

        // Now gated the same way as the other three actions — locked by
        // default, requires Unlock + a password re-check before it fires.
        // The report itself is harmless, but it does leave the device, so
        // it gets the same "confirm it's really you" friction as the rest.
        btnSendDiagnosticReport.setOnClickListener {
            if (!isEditMode) return@setOnClickListener
            com.example.easy_billing.util.UserEventLogger.logAction("DataSecurity", "send_diagnostic_report_clicked")
            showPasswordVerificationDialog {
                lifecycleScope.launch {
                    com.example.easy_billing.util.DiagnosticReportUploader.upload(this@DataSecurityActivity)
                }
            }
        }
    }

    // ================= LOCK / UNLOCK =================

    private fun toggleLock() {
        isEditMode = !isEditMode
        setLocked(!isEditMode)
    }

    /** locked = actions disabled (default); unlocked = actions tappable. */
    private fun setLocked(locked: Boolean) {
        val rows = listOf(btnClearBills, btnFactoryReset, btnSendDiagnosticReport)
        rows.forEach {
            it.isEnabled = !locked
            it.isClickable = !locked
            it.alpha = if (locked) 0.55f else 1f
        }

        // Trailing glyph: lock when locked, chevron when unlocked.
        val trailing = if (locked) R.drawable.ic_si_lock else R.drawable.ic_chevron_right
        listOf(icClearBills, icFactoryReset, icSendDiagnosticReport).forEach {
            it.setImageResource(trailing)
        }

        // Pill reflects the action the user can take next.
        tvUnlock.text = if (locked) getString(R.string.dialog_premium_upgrade_title_part1) else "Lock"
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
                    R.string.bills_archived_successfully,
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                com.example.easy_billing.util.UserEventLogger.logError(
                    "DataSecurity", "clear_bills_failed: ${e.javaClass.simpleName}"
                )
                Toast.makeText(
                    this@DataSecurityActivity,
                    R.string.failed_to_clear_bills,
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
                    Toast.makeText(this@DataSecurityActivity, R.string.not_logged_in_2, Toast.LENGTH_SHORT).show()
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
                // SplashActivity → MainActivity's checkExistingSession() validates
                // the new token, then routes to Dashboard.
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DataSecurityActivity,
                        R.string.factory_reset_complete,
                        Toast.LENGTH_LONG
                    ).show()

                    val intent = Intent(this@DataSecurityActivity, SplashActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }

            } catch (e: Exception) {
                com.example.easy_billing.util.UserEventLogger.logError(
                    "DataSecurity", "reset_failed: ${e.javaClass.simpleName}"
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DataSecurityActivity,
                        getString(R.string.something_went_wrong),
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
        val ivToggleNewPin = dialogView.findViewById<ImageView>(R.id.ivToggleNewPin)
        val ivToggleConfirmPin = dialogView.findViewById<ImageView>(R.id.ivToggleConfirmPin)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var isNewPinVisible = false
        ivToggleNewPin.setOnClickListener {
            isNewPinVisible = !isNewPinVisible
            if (isNewPinVisible) {
                etNewPin.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ivToggleNewPin.setImageResource(R.drawable.ic_lucide_eye_off)
            } else {
                etNewPin.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                ivToggleNewPin.setImageResource(R.drawable.ic_lucide_eye)
            }
            etNewPin.setSelection(etNewPin.text.length)
        }

        var isConfirmPinVisible = false
        ivToggleConfirmPin.setOnClickListener {
            isConfirmPinVisible = !isConfirmPinVisible
            if (isConfirmPinVisible) {
                etConfirmPin.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ivToggleConfirmPin.setImageResource(R.drawable.ic_lucide_eye_off)
            } else {
                etConfirmPin.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                ivToggleConfirmPin.setImageResource(R.drawable.ic_lucide_eye)
            }
            etConfirmPin.setSelection(etConfirmPin.text.length)
        }

        btnSave.setOnClickListener {

            val newPin = etNewPin.text.toString().trim()
            val confirmPin = etConfirmPin.text.toString().trim()

            if (newPin.length < 6) {
                etNewPin.error = getString(R.string.password_min_length)
                return@setOnClickListener
            }

            if (newPin != confirmPin) {
                etConfirmPin.error = getString(R.string.passwords_do_not_match)
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
                        R.string.password_changed_login_again,
                        Toast.LENGTH_LONG
                    ).show()

                    // If Quick Unlock is set up for this account, refresh its
                    // saved credentials with the new password NOW, while we
                    // still have it in hand — otherwise the account's PIN
                    // would silently stop working (replay would use the old,
                    // now-invalid password) until the user next typed the
                    // new one into the full login form. This keeps "just use
                    // your PIN" true even right after a password change.
                    val currentUsername = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("USERNAME", null)
                    if (currentUsername != null &&
                        com.example.easy_billing.util.QuickUnlockManager.isConfigured(this@DataSecurityActivity, currentUsername)
                    ) {
                        com.example.easy_billing.util.QuickUnlockManager
                            .updateCredentials(this@DataSecurityActivity, currentUsername, newPin)
                    }

                    // logout user (still required — the session token itself
                    // is invalidated server-side by a password change)
                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    val intent = com.example.easy_billing.util.QuickUnlockManager.buildLoginIntent(this@DataSecurityActivity, currentUsername)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)

                    dialog.dismiss()

                    dialog.dismiss()

                } catch (e: Exception) {

                    com.example.easy_billing.util.UserEventLogger.logError(
                        "DataSecurity", "password_change_failed: ${e.javaClass.simpleName}"
                    )
                    Toast.makeText(
                        this@DataSecurityActivity,
                        R.string.failed_to_change_password,
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

    private fun showPasswordVerificationDialog(onVerified: (String) -> Unit) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val ivTogglePassword = dialogView.findViewById<ImageView>(R.id.ivTogglePassword)

        var isPasswordVisible = false
        ivTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_lucide_eye_off)
            } else {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_lucide_eye)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {

            val password = etPassword.text.toString().trim()

            if (password.isEmpty()) {
                etPassword.error = getString(R.string.data_security_enter_password_error)
                return@setOnClickListener
            }

            verifyPassword(password) {
                dialog.dismiss()
                onVerified(password)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ================= QUICK UNLOCK SETTINGS =================

    private fun currentAccountUsername(): String? =
        getSharedPreferences("auth", MODE_PRIVATE).getString("USERNAME", null)

    private fun refreshQuickUnlockRow() {
        val username = currentAccountUsername()
        val configured = username != null &&
            com.example.easy_billing.util.QuickUnlockManager.isConfigured(this, username)

        if (configured) {
            tvQuickUnlockSub.text = getString(R.string.quick_unlock_change_sub)
            chipQuickUnlockStatus.text = getString(R.string.quick_unlock_status_on)
            chipQuickUnlockStatus.setBackgroundResource(R.drawable.bg_chip_low)
            chipQuickUnlockStatus.setTextColor(android.graphics.Color.parseColor("#3B6D11"))
        } else {
            tvQuickUnlockSub.text = getString(R.string.ds_quick_sub)
            chipQuickUnlockStatus.text = getString(R.string.quick_unlock_status_off)
            chipQuickUnlockStatus.setBackgroundResource(R.drawable.bg_chip_med)
            chipQuickUnlockStatus.setTextColor(android.graphics.Color.parseColor("#854F0B"))
        }
    }

    private fun handleQuickUnlockClick() {
        val username = currentAccountUsername() ?: return
        val configured = com.example.easy_billing.util.QuickUnlockManager.isConfigured(this, username)

        if (!configured) {
            // ENABLE: need the plaintext password to store for replay —
            // reuse the same password-verification dialog, now handed the
            // verified password instead of discarding it.
            showPasswordVerificationDialog { password ->
                launchQuickUnlockSetup(username, password, isChange = false)
            }
            return
        }

        // Already configured: offer either "change PIN" or "turn off".
        // Both are security-relevant, so — same as every other gated action
        // on this screen (change password, clear bills, factory reset) —
        // they each require a fresh password re-check, even though the
        // screen itself is already unlocked. Being unlocked only proves it
        // was really the account owner a moment ago; it shouldn't be enough
        // on its own to disable a security control or swap its PIN.
        showManageQuickUnlockDialog(
            username = username,
            onChangePin = {
                showPasswordVerificationDialog {
                    // CHANGE PIN: password re-verified above; reuse the
                    // already-stored replay credentials rather than the
                    // just-typed password, since QuickUnlockManager's
                    // saved copy is what a PIN replay actually uses.
                    val creds = com.example.easy_billing.util.QuickUnlockManager.getCredentials(this, username)
                    if (creds != null) {
                        launchQuickUnlockSetup(creds.first, creds.second, isChange = true)
                    } else {
                        launchQuickUnlockSetup(username, it, isChange = true)
                    }
                }
            },
            onTurnOff = {
                showPasswordVerificationDialog {
                    confirmDisableQuickUnlock(username)
                }
            }
        )
    }

    /** Themed replacement for a plain AlertDialog#setItems picker — same
     *  champagne card shell as the rest of this screen's dialogs. */
    private fun showManageQuickUnlockDialog(
        username: String,
        onChangePin: () -> Unit,
        onTurnOff: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_quick_unlock, null)

        val tvAccount = dialogView.findViewById<TextView>(R.id.tvManageQuickUnlockAccount)
        val btnChangePin = dialogView.findViewById<View>(R.id.btnQuickUnlockChangeOption)
        val btnTurnOff = dialogView.findViewById<View>(R.id.btnQuickUnlockOffOption)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvAccount.text = getString(R.string.quick_unlock_signed_in_as, username)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnChangePin.setOnClickListener {
            dialog.dismiss()
            onChangePin()
        }
        btnTurnOff.setOnClickListener {
            dialog.dismiss()
            onTurnOff()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun launchQuickUnlockSetup(username: String, password: String, isChange: Boolean) {
        val intent = Intent(this, QuickUnlockActivity::class.java).apply {
            putExtra(QuickUnlockActivity.EXTRA_MODE, QuickUnlockActivity.MODE_SETUP)
            putExtra(QuickUnlockActivity.EXTRA_USERNAME, username)
            putExtra(QuickUnlockActivity.EXTRA_PASSWORD, password)
            putExtra(QuickUnlockActivity.EXTRA_NEXT_CLASS, DataSecurityActivity::class.java.name)
            putExtra(QuickUnlockActivity.EXTRA_IS_CHANGE, isChange)
        }
        startActivity(intent)
        finish()
    }

    private fun confirmDisableQuickUnlock(username: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_turn_off_quick_unlock, null)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmTurnOff)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelTurnOff)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnConfirm.setOnClickListener {
            com.example.easy_billing.util.QuickUnlockManager.clear(this, username)
            dialog.dismiss()
            Toast.makeText(this, R.string.quick_unlock_disabled_toast, Toast.LENGTH_SHORT).show()
            refreshQuickUnlockRow()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}