package com.example.easy_billing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.BillingSettingsUpdateRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch

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

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getBillingSettings("Bearer $token")

                etGst.setText(response.default_gst.toString())
                autoPrinter.setText(response.printer_layout, false)

            } catch (e: Exception) {

                Toast.makeText(
                    this@BillingSettingsActivity,
                    "Failed to load billing settings",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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

            showPasswordVerificationDialog {
                saveBillingSettings()
            }

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

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveBillingSettings() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            val gstValue = etGst.text.toString().toDoubleOrNull() ?: 0.0
            val printerType = autoPrinter.text.toString()

            try {

                val request = BillingSettingsUpdateRequest(
                    default_gst = gstValue,
                    printer_layout = printerType
                )

                RetrofitClient.api.updateBillingSettings(
                    "Bearer $token",
                    request
                )

                Toast.makeText(
                    this@BillingSettingsActivity,
                    "Billing settings updated",
                    Toast.LENGTH_SHORT
                ).show()

                setEditable(false)
                isEditMode = false

            } catch (e: Exception) {

                Toast.makeText(
                    this@BillingSettingsActivity,
                    "Failed to update settings",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ===== CHANGE EDIT BUTTON TEXT =====
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val item = menu?.findItem(R.id.action_edit)
        item?.title = if (isEditMode) "Done" else "Click here to Edit"
        return super.onPrepareOptionsMenu(menu)
    }
}