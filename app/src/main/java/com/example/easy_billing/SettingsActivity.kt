package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

class SettingsActivity : BaseActivity() {

    // Hidden admin switch: 7 quick taps on the version text.
    private var versionTaps = 0
    private var lastVersionTapAt = 0L
    private var keepAdminOnStop = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        com.example.easy_billing.util.UserEventLogger.logAction("Settings", "opened")

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        // Title shown in the cream header below; hide the default action-bar title.
        supportActionBar?.setDisplayShowTitleEnabled(false)

        findViewById<View>(R.id.btnStoreSettings).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_store_settings_clicked")
            startActivity(Intent(this, StoreSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnLocalization).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_localization_clicked")
            startActivity(Intent(this, LocalizationSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnPaymentSetup).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_payment_setup_clicked")
            keepAdminOnStop = true   // keep Maintenance mode on while the payment page is open
            startActivity(Intent(this, PaymentSetupActivity::class.java))
        }

        findViewById<View>(R.id.btnInvoiceDesign).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_invoice_design_clicked")
            startActivity(Intent(this, InvoiceDesignActivity::class.java))
        }

        findViewById<View>(R.id.btnDataManagement).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_data_management_clicked")
            startActivity(Intent(this, DataSecurityActivity::class.java))
        }

        // Always reflects the real build version instead of a hardcoded "1.0".
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = "ExPOS · v${BuildConfig.VERSION_NAME}"

        findViewById<View>(R.id.btnAdminTools).setOnClickListener {
            keepAdminOnStop = true
            startActivity(
                Intent(this, DataSecurityActivity::class.java)
                    .putExtra(DataSecurityActivity.EXTRA_ADMIN, true)
            )
        }
        tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            versionTaps = if (now - lastVersionTapAt > 3000L) 1 else versionTaps + 1
            lastVersionTapAt = now
            if (versionTaps >= 7) {
                versionTaps = 0
                com.example.easy_billing.util.AdminMode.enabled =
                    !com.example.easy_billing.util.AdminMode.enabled
                refreshAdminRow()
                android.widget.Toast.makeText(
                    this,
                    if (com.example.easy_billing.util.AdminMode.enabled) R.string.admin_mode_on
                    else R.string.admin_mode_off,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        refreshAdminRow()
    }

    private fun refreshAdminRow() {
        findViewById<View>(R.id.btnAdminTools).visibility =
            if (com.example.easy_billing.util.AdminMode.enabled) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        keepAdminOnStop = false
        refreshAdminRow()
    }

    override fun onStop() {
        super.onStop()
        // Admin tools switch themselves off when Settings is left (other
        // than to open the admin page itself).
        if (!keepAdminOnStop && !isChangingConfigurations) {
            com.example.easy_billing.util.AdminMode.enabled = false
        }
    }
}
