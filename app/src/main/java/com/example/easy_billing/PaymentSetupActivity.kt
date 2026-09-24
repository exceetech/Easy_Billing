package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.network.BillingSettingsUpdateRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone "UPI Payments" screen — the Razorpay credential block that
 * used to live at the bottom of Billing Settings, moved here so it isn't
 * mistaken for part of GST/tax configuration and isn't shown to a shop
 * mid-onboarding as if it were required. Reached from the main Settings
 * list, one row below Billing Settings.
 */
class PaymentSetupActivity : BaseActivity() {

    private lateinit var tvRazorpayStatus: TextView
    private lateinit var etRazorpayKeyId: EditText
    private lateinit var etRazorpayKeySecret: EditText
    private lateinit var etRazorpayWebhookSecret: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var tvPaymentsStatus: TextView
    private lateinit var containerAdmin: android.view.View
    private lateinit var icAdminChevron: android.view.View
    private lateinit var switchUpi: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var rowEditDetails: android.view.View
    private lateinit var tvEditDetails: TextView
    private var configured = false
    private var detailsUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_setup)
        com.example.easy_billing.util.UserEventLogger.logAction("PaymentSetup", "opened")

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        bindViews()
        loadSettings()

        btnSave.setOnClickListener { savePaymentSettings() }
    }

    private fun bindViews() {
        tvRazorpayStatus        = findViewById(R.id.tvRazorpayStatus)
        etRazorpayKeyId         = findViewById(R.id.etRazorpayKeyId)
        etRazorpayKeySecret     = findViewById(R.id.etRazorpayKeySecret)
        etRazorpayWebhookSecret = findViewById(R.id.etRazorpayWebhookSecret)
        btnSave                 = findViewById(R.id.btnSavePaymentSetup)
        tvPaymentsStatus        = findViewById(R.id.tvPaymentsStatus)
        containerAdmin          = findViewById(R.id.containerAdmin)
        icAdminChevron          = findViewById(R.id.icAdminChevron)

        switchUpi      = findViewById(R.id.switchUpi)
        rowEditDetails = findViewById(R.id.rowAdminToggle)
        tvEditDetails  = findViewById(R.id.tvEditDetails)

        // Start from the saved choice; before any choice, follow whether
        // Razorpay is already set up.
        switchUpi.isChecked = com.example.easy_billing.util.UpiSettings.isSet(this)
            .let { set -> if (set) com.example.easy_billing.util.UpiSettings.isEnabled(this) else false }
        switchUpi.setOnCheckedChangeListener { _, on ->
            com.example.easy_billing.util.UpiSettings.set(this, on)
            if (!on) detailsUnlocked = false
            render()
        }

        // Adding or changing the Razorpay details needs the password.
        rowEditDetails.setOnClickListener {
            if (detailsUnlocked) return@setOnClickListener
            showPasswordVerificationDialog {
                detailsUnlocked = true
                render()
            }
        }
        render()
    }

    /** One place that decides what is visible for the current state. */
    private fun render() {
        val on = switchUpi.isChecked
        rowEditDetails.visibility =
            if (on && !detailsUnlocked) android.view.View.VISIBLE else android.view.View.GONE
        containerAdmin.visibility =
            if (on && detailsUnlocked) android.view.View.VISIBLE else android.view.View.GONE
        tvEditDetails.setText(if (configured) R.string.payments_edit_change else R.string.payments_edit_add)
        tvPaymentsStatus.setText(
            when {
                !on -> R.string.payments_status_off
                configured -> R.string.payments_status_on
                else -> R.string.payments_status_setup
            }
        )
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val ivToggle = dialogView.findViewById<ImageView>(R.id.ivTogglePassword)

        var visible = false
        ivToggle.setOnClickListener {
            visible = !visible
            etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (visible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            ivToggle.setImageResource(
                if (visible) R.drawable.ic_lucide_eye_off else R.drawable.ic_lucide_eye
            )
            etPassword.setSelection(etPassword.text.length)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {
            val password = etPassword.text.toString().trim()
            if (password.isEmpty()) {
                etPassword.error = getString(R.string.localization_settings_enter_password_error)
                return@setOnClickListener
            }
            verifyPassword(password) {
                dialog.dismiss()
                onVerified()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun applyStatus(isConfigured: Boolean) {
        configured = isConfigured
        // No saved choice yet: follow the server state (ON when Razorpay is set up).
        if (!com.example.easy_billing.util.UpiSettings.isSet(this) && switchUpi.isChecked != isConfigured) {
            switchUpi.setOnCheckedChangeListener(null)
            switchUpi.isChecked = isConfigured
            switchUpi.setOnCheckedChangeListener { _, on ->
                com.example.easy_billing.util.UpiSettings.set(this, on)
                if (!on) detailsUnlocked = false
                render()
            }
        }
        render()
        if (isConfigured) {
            tvRazorpayStatus.text = getString(R.string.razorpay_status_connected)
            tvRazorpayStatus.setBackgroundResource(R.drawable.bg_pill_green)
            tvRazorpayStatus.setTextColor(Color.parseColor("#0F6E56"))
        } else {
            tvRazorpayStatus.text = getString(R.string.razorpay_status_not_connected)
            tvRazorpayStatus.setBackgroundResource(R.drawable.bg_pill_amber)
            tvRazorpayStatus.setTextColor(Color.parseColor("#8A6526"))
        }
    }

    // ---------------- LOAD ----------------

    private fun loadSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PaymentSetupActivity)
            val billing = db.billingSettingsDao().get()
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)

            withContext(Dispatchers.Main) {
                etRazorpayKeyId.setText(billing?.razorpayKeyId.orEmpty())
                applyStatus(billing?.razorpayConfigured ?: false)
            }

            if (token == null) return@launch

            try {
                val resp = RetrofitClient.api.getBillingSettings(token)
                val updatedBilling = (billing ?: BillingSettings(
                    defaultGst = 0f,
                    printerLayout = resp.printer_layout
                )).copy(
                    printerLayout = resp.printer_layout,
                    razorpayKeyId = resp.razorpay_key_id,
                    razorpayConfigured = resp.razorpay_configured
                )
                db.billingSettingsDao().insert(updatedBilling)

                withContext(Dispatchers.Main) {
                    etRazorpayKeyId.setText(updatedBilling.razorpayKeyId.orEmpty())
                    applyStatus(updatedBilling.razorpayConfigured)
                }
            } catch (e: Exception) {
                // Tolerate a failed refresh — the locally cached row loaded
                // above already covers the screen.
                e.printStackTrace()
            }
        }
    }

    // ---------------- SAVE ----------------

    private fun savePaymentSettings() {
        val typedKeyId = etRazorpayKeyId.text.toString().trim()
        val typedKeySecret = etRazorpayKeySecret.text.toString().trim()
        val typedWebhookSecret = etRazorpayWebhookSecret.text.toString().trim()

        if (typedKeyId.isBlank() && typedKeySecret.isBlank() && typedWebhookSecret.isBlank()) {
            Toast.makeText(this, R.string.razorpay_helper_text, Toast.LENGTH_SHORT).show()
            return
        }

        com.example.easy_billing.util.UserEventLogger.logAction(
            "PaymentSetup",
            "save_clicked: key_id=${typedKeyId.ifBlank { "-" }}"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            val db = AppDatabase.getDatabase(this@PaymentSetupActivity)

            // Preserve the printer layout untouched — this screen only
            // owns the Razorpay fields, but they share one combined row
            // and PATCH-style request with Billing Settings.
            val existingBilling = db.billingSettingsDao().get()
            val printer = existingBilling?.printerLayout ?: "80mm"

            val updatedBilling = (existingBilling ?: BillingSettings(
                defaultGst = 0f,
                printerLayout = printer
            )).copy(
                printerLayout = printer,
                razorpayKeyId = typedKeyId.ifBlank { existingBilling?.razorpayKeyId }
            )
            db.billingSettingsDao().insert(updatedBilling)

            var succeeded = token == null // offline save still counts as "handled"

            if (token != null) {
                runCatching {
                    val resp = RetrofitClient.api.updateBillingSettings(
                        token,
                        BillingSettingsUpdateRequest(
                            default_gst = 0f,
                            printer_layout = printer,
                            razorpay_key_id = typedKeyId.ifBlank { null },
                            razorpay_key_secret = typedKeySecret.ifBlank { null },
                            razorpay_webhook_secret = typedWebhookSecret.ifBlank { null }
                        )
                    )
                    db.billingSettingsDao().insert(
                        updatedBilling.copy(
                            razorpayKeyId = resp.razorpay_key_id,
                            razorpayConfigured = resp.razorpay_configured
                        )
                    )
                    withContext(Dispatchers.Main) {
                        applyStatus(resp.razorpay_configured)
                    }
                }.onSuccess {
                    succeeded = true
                }

                com.example.easy_billing.sync.SyncCoordinator
                    .get(this@PaymentSetupActivity)
                    .requestSync()
            }

            withContext(Dispatchers.Main) {
                // Secrets are write-only — never leave a typed value
                // sitting in memory once the save attempt is done.
                etRazorpayKeySecret.setText("")
                etRazorpayWebhookSecret.setText("")
                if (succeeded) { detailsUnlocked = false; render() }

                Toast.makeText(
                    this@PaymentSetupActivity,
                    if (token == null) R.string.saved_offline_will_sync_later
                    else if (succeeded) R.string.payment_setup_saved_toast
                    else R.string.payment_setup_save_failed_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
