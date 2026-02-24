package com.example.easy_billing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import com.google.android.material.materialswitch.MaterialSwitch

class InvoiceDesignActivity : BaseActivity() {

    private lateinit var etFooter: EditText
    private lateinit var switchLogo: MaterialSwitch
    private lateinit var switchGstin: MaterialSwitch
    private lateinit var switchPhone: MaterialSwitch
    private lateinit var switchDiscount: MaterialSwitch
    private lateinit var switchRoundOff: MaterialSwitch
    private lateinit var btnSave: Button
    private lateinit var prefs: android.content.SharedPreferences

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_design)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        loadData()
        setupSave()

        setEditable(false) // Locked by default
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

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val item = menu?.findItem(R.id.action_edit)
        item?.title = if (isEditMode) "Done" else "Click here to Edit"
        return super.onPrepareOptionsMenu(menu)
    }

    // ===== BIND =====
    private fun bindViews() {
        etFooter = findViewById(R.id.etFooter)
        switchLogo = findViewById(R.id.switchShowLogo)
        switchGstin = findViewById(R.id.switchShowGstin)
        switchPhone = findViewById(R.id.switchShowPhone)
        switchDiscount = findViewById(R.id.switchShowDiscount)
        switchRoundOff = findViewById(R.id.switchRoundOff)
        btnSave = findViewById(R.id.btnSaveDesign)
    }

    // ===== LOAD =====
    private fun loadData() {
        etFooter.setText(prefs.getString("footer_message", "Thank You! Visit Again"))
        switchLogo.isChecked = prefs.getBoolean("show_logo", true)
        switchGstin.isChecked = prefs.getBoolean("show_gstin", true)
        switchPhone.isChecked = prefs.getBoolean("show_phone", true)
        switchDiscount.isChecked = prefs.getBoolean("show_discount", true)
        switchRoundOff.isChecked = prefs.getBoolean("round_off", false)
    }

    // ===== EDIT MODE =====
    private fun toggleEditMode() {
        isEditMode = !isEditMode
        setEditable(isEditMode)
        invalidateOptionsMenu()
    }

    private fun setEditable(enable: Boolean) {
        etFooter.isEnabled = enable
        switchLogo.isEnabled = enable
        switchGstin.isEnabled = enable
        switchPhone.isEnabled = enable
        switchDiscount.isEnabled = enable
        switchRoundOff.isEnabled = enable

        btnSave.visibility = if (enable) View.VISIBLE else View.GONE
    }

    // ===== SAVE =====
    private fun setupSave() {

        btnSave.setOnClickListener {

            prefs.edit()
                .putString("footer_message", etFooter.text.toString())
                .putBoolean("show_logo", switchLogo.isChecked)
                .putBoolean("show_gstin", switchGstin.isChecked)
                .putBoolean("show_phone", switchPhone.isChecked)
                .putBoolean("show_discount", switchDiscount.isChecked)
                .putBoolean("round_off", switchRoundOff.isChecked)
                .apply()

            Toast.makeText(this, "Design Settings Saved", Toast.LENGTH_SHORT).show()

            setEditable(false)
            isEditMode = false
            invalidateOptionsMenu()
        }
    }
}