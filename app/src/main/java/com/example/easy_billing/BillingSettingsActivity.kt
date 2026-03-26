package com.example.easy_billing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.network.BillingSettingsUpdateRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@BillingSettingsActivity)

            // ✅ LOAD FROM ROOM FIRST
            val local = db.billingSettingsDao().get()

            withContext(Dispatchers.Main) {
                local?.let {
                    etGst.setText(it.defaultGst.toString())
                    autoPrinter.setText(it.printerLayout, false)
                }
            }

            // ✅ TRY SYNC FROM BACKEND
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getBillingSettings("Bearer $token")

                val updated = BillingSettings(
                    defaultGst = response.default_gst,
                    printerLayout = response.printer_layout
                )

                db.billingSettingsDao().insert(updated)

                withContext(Dispatchers.Main) {
                    etGst.setText(updated.defaultGst.toString())
                    autoPrinter.setText(updated.printerLayout, false)
                }

            } catch (_: Exception) {
                // offline → keep Room data
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

        lifecycleScope.launch(Dispatchers.IO) {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillingSettingsActivity, "No internet connection", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val gstValue = etGst.text.toString().toFloatOrNull() ?: 0.0f
            val printerType = autoPrinter.text.toString()

            try {

                val request = BillingSettingsUpdateRequest(
                    default_gst = gstValue,
                    printer_layout = printerType
                )

                // ✅ UPDATE BACKEND FIRST
                RetrofitClient.api.updateBillingSettings("Bearer $token", request)

                // ✅ THEN UPDATE ROOM
                val db = AppDatabase.getDatabase(this@BillingSettingsActivity)

                val updated = BillingSettings(
                    defaultGst = gstValue,
                    printerLayout = printerType
                )

                db.billingSettingsDao().insert(updated)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillingSettingsActivity, "Billing settings updated", Toast.LENGTH_SHORT).show()
                    setEditable(false)
                    isEditMode = false
                }

            } catch (_: Exception) {

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillingSettingsActivity, "Update failed (check internet)", Toast.LENGTH_SHORT).show()
                }
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
