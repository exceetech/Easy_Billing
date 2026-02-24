package com.example.easy_billing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class BillingSettingsActivity : BaseActivity() {

    private lateinit var etGst: EditText
    private lateinit var autoPrinter: AutoCompleteTextView
    private lateinit var btnSave: Button
    private lateinit var prefs: android.content.SharedPreferences

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        setupDropdown()
        loadData()

        setEditable(false) // default state
        setupSave()
    }

    // ===== MENU =====
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_edit) {
            toggleEditMode()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ===== VIEW BINDING =====
    private fun bindViews() {
        etGst = findViewById(R.id.etDefaultGst)
        autoPrinter = findViewById(R.id.spPrinterLayout)
        btnSave = findViewById(R.id.btnSaveBilling)
    }

    private fun setupDropdown() {
        val layouts = listOf("80mm", "A4")

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            layouts
        )

        autoPrinter.setAdapter(adapter)
    }

    private fun loadData() {
        etGst.setText(prefs.getFloat("default_gst", 0f).toString())
        val savedLayout = prefs.getString("printer_layout", "80mm")
        autoPrinter.setText(savedLayout, false)
    }

    // ===== EDIT MODE =====
    private fun toggleEditMode() {
        isEditMode = !isEditMode
        setEditable(isEditMode)
        invalidateOptionsMenu()
    }

    private fun setEditable(enable: Boolean) {
        etGst.isEnabled = enable
        autoPrinter.isEnabled = enable

        // 👇 THIS IS THE IMPORTANT PART
        btnSave.visibility = if (enable) View.VISIBLE else View.GONE
    }

    // ===== SAVE =====
    private fun setupSave() {
        btnSave.setOnClickListener {

            val gstValue = etGst.text.toString().toFloatOrNull() ?: 0f
            val printerType = autoPrinter.text.toString()

            prefs.edit()
                .putFloat("default_gst", gstValue)
                .putString("printer_layout", printerType)
                .apply()

            Toast.makeText(this, "Billing Settings Saved", Toast.LENGTH_SHORT).show()

            setEditable(false)
            isEditMode = false
        }
    }

    // ===== CHANGE EDIT BUTTON TEXT =====
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val item = menu?.findItem(R.id.action_edit)
        item?.title = if (isEditMode) "Done" else "Click here to Edit"
        return super.onPrepareOptionsMenu(menu)
    }
}