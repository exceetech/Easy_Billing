package com.example.easy_billing

import android.os.Bundle
import android.widget.*
class InvoiceDesignActivity : BaseActivity() {

    private lateinit var etFooter: EditText
    private lateinit var btnSave: Button

    private lateinit var switchShowLogo: Switch
    private lateinit var switchShowGstin: Switch
    private lateinit var switchShowPhone: Switch
    private lateinit var switchShowDiscount: Switch
    private lateinit var switchRoundOff: Switch

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_design)

        // Setup Toolbar + Back Arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "Invoice Design"

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        loadData()
        setupSave()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindViews() {

        etFooter = findViewById(R.id.etFooter)
        btnSave = findViewById(R.id.btnSaveDesign)

        switchShowLogo = findViewById(R.id.switchShowLogo)
        switchShowGstin = findViewById(R.id.switchShowGstin)
        switchShowPhone = findViewById(R.id.switchShowPhone)
        switchShowDiscount = findViewById(R.id.switchShowDiscount)
        switchRoundOff = findViewById(R.id.switchRoundOff)
    }

    private fun loadData() {

        etFooter.setText(prefs.getString("invoice_footer", ""))

        switchShowLogo.isChecked = prefs.getBoolean("show_logo", true)
        switchShowGstin.isChecked = prefs.getBoolean("show_gstin", true)
        switchShowPhone.isChecked = prefs.getBoolean("show_phone", true)
        switchShowDiscount.isChecked = prefs.getBoolean("show_discount", true)
        switchRoundOff.isChecked = prefs.getBoolean("round_off", false)
    }

    private fun setupSave() {

        btnSave.setOnClickListener {

            prefs.edit()
                .putString("invoice_footer", etFooter.text.toString().trim())
                .putBoolean("show_logo", switchShowLogo.isChecked)
                .putBoolean("show_gstin", switchShowGstin.isChecked)
                .putBoolean("show_phone", switchShowPhone.isChecked)
                .putBoolean("show_discount", switchShowDiscount.isChecked)
                .putBoolean("round_off", switchRoundOff.isChecked)
                .apply()

            Toast.makeText(this, "Invoice Design Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}