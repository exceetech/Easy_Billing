package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        findViewById<View>(R.id.btnStoreSettings).setOnClickListener {
            startActivity(Intent(this, StoreSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnLocalization).setOnClickListener {
            startActivity(Intent(this, LocalizationSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnBillingSettings).setOnClickListener {
            startActivity(Intent(this, BillingSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btnInvoiceDesign).setOnClickListener {
            startActivity(Intent(this, InvoiceDesignActivity::class.java))
        }

        findViewById<View>(R.id.btnDataManagement).setOnClickListener {
            startActivity(Intent(this, DataSecurityActivity::class.java))
        }
    }
}