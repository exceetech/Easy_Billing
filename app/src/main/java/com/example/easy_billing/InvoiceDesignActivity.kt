package com.example.easy_billing

import android.os.Bundle
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

    private lateinit var rowLogo: View
    private lateinit var rowGstin: View
    private lateinit var rowPhone: View
    private lateinit var rowDiscount: View
    private lateinit var rowRoundOff: View

    private lateinit var btnEdit: View
    private lateinit var tvEdit: TextView
    private lateinit var btnSave: Button
    private lateinit var prefs: android.content.SharedPreferences

    private var isEditMode = false
    private var snapshot: DesignSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_design)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        loadData()
        setupSave()

        setEditable(false) // Locked by default
    }

    // ===== BIND =====
    private fun bindViews() {
        etFooter       = findViewById(R.id.etFooter)
        switchLogo     = findViewById(R.id.switchShowLogo)
        switchGstin    = findViewById(R.id.switchShowGstin)
        switchPhone    = findViewById(R.id.switchShowPhone)
        switchDiscount = findViewById(R.id.switchShowDiscount)
        switchRoundOff = findViewById(R.id.switchRoundOff)

        rowLogo     = findViewById(R.id.rowLogo)
        rowGstin    = findViewById(R.id.rowGstin)
        rowPhone    = findViewById(R.id.rowPhone)
        rowDiscount = findViewById(R.id.rowDiscount)
        rowRoundOff = findViewById(R.id.rowRoundOff)

        btnEdit = findViewById(R.id.btnEdit)
        tvEdit  = findViewById(R.id.tvEdit)
        btnSave = findViewById(R.id.btnSaveDesign)

        btnEdit.setOnClickListener { toggleEditMode() }

        // Tapping anywhere on a row flips its switch (only in edit mode).
        rowLogo.setOnClickListener { if (isEditMode) switchLogo.toggle() }
        rowGstin.setOnClickListener { if (isEditMode) switchGstin.toggle() }
        rowPhone.setOnClickListener { if (isEditMode) switchPhone.toggle() }
        rowDiscount.setOnClickListener { if (isEditMode) switchDiscount.toggle() }
        rowRoundOff.setOnClickListener { if (isEditMode) switchRoundOff.toggle() }
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

    // ===== EDIT / DISCARD =====
    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            // Entering edit: snapshot so "Discard" can revert.
            snapshot = DesignSnapshot(
                footer = etFooter.text.toString(),
                logo = switchLogo.isChecked,
                gstin = switchGstin.isChecked,
                phone = switchPhone.isChecked,
                discount = switchDiscount.isChecked,
                roundOff = switchRoundOff.isChecked
            )
        } else {
            snapshot?.let { s ->
                etFooter.setText(s.footer)
                switchLogo.isChecked = s.logo
                switchGstin.isChecked = s.gstin
                switchPhone.isChecked = s.phone
                switchDiscount.isChecked = s.discount
                switchRoundOff.isChecked = s.roundOff
            }
        }
        setEditable(isEditMode)
    }

    private fun setEditable(enable: Boolean) {
        etFooter.isEnabled = enable
        switchLogo.isEnabled = enable
        switchGstin.isEnabled = enable
        switchPhone.isEnabled = enable
        switchDiscount.isEnabled = enable
        switchRoundOff.isEnabled = enable

        tvEdit.text = if (enable) "Discard" else getString(R.string.edit)
        btnSave.visibility = if (enable) View.VISIBLE else View.GONE
    }

    // ===== SAVE =====
    private fun setupSave() {
        btnSave.setOnClickListener {
            showPasswordVerificationDialog { saveDesignSettings() }
        }
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {
            val password = etPassword.text.toString().trim()
            if (password.isEmpty()) {
                etPassword.error = "Enter password"
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

    private fun saveDesignSettings() {
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
    }

    private data class DesignSnapshot(
        val footer: String,
        val logo: Boolean,
        val gstin: Boolean,
        val phone: Boolean,
        val discount: Boolean,
        val roundOff: Boolean
    )
}
