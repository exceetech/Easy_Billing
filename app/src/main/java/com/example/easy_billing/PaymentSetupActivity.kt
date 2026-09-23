package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
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
    }

    private fun applyStatus(configured: Boolean) {
        if (configured) {
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
