package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

class SettingsActivity : BaseActivity() {

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

        findViewById<View>(R.id.btnBillingSettings).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction("Settings", "open_billing_settings_clicked")
            startActivity(Intent(this, BillingSettingsActivity::class.java))
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
        findViewById<TextView>(R.id.tvVersion).text = "ExPOS · v${BuildConfig.VERSION_NAME}"
    }
}