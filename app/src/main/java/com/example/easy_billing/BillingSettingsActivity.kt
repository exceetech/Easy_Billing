package com.example.easy_billing

import android.os.Bundle
import android.widget.*
import android.widget.ArrayAdapter

class BillingSettingsActivity : BaseActivity() {

    private lateinit var etGst: EditText
    private lateinit var spPrinter: Spinner
    private lateinit var btnSave: Button
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing_settings)

        // Setup professional toolbar
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "Billing Settings"

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        setupSpinner()
        loadData()
        setupSave()
    }

    private fun bindViews() {
        etGst = findViewById(R.id.etDefaultGst)
        spPrinter = findViewById(R.id.spPrinterLayout)
        btnSave = findViewById(R.id.btnSaveBilling)
    }

    private fun setupSpinner() {
        val layouts = arrayOf("80mm", "A4")

        spPrinter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            layouts
        )
    }

    private fun loadData() {
        etGst.setText(prefs.getFloat("default_gst", 0f).toString())

        val savedLayout = prefs.getString("printer_layout", "80mm")
        spPrinter.setSelection(if (savedLayout == "A4") 1 else 0)
    }

    private fun setupSave() {

        btnSave.setOnClickListener {

            val gstValue = etGst.text.toString().toFloatOrNull() ?: 0f
            val printerType = spPrinter.selectedItem.toString()

            prefs.edit()
                .putFloat("default_gst", gstValue)
                .putString("printer_layout", printerType)
                .apply()

            Toast.makeText(this, "Billing Settings Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}