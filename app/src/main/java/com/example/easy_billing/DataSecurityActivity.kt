package com.example.easy_billing

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.launch

class DataSecurityActivity : BaseActivity() {

    private lateinit var btnClearBills: Button
    private lateinit var btnFactoryReset: Button
    private lateinit var btnChangePassword: Button

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_security)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        btnClearBills = findViewById(R.id.btnClearBills)
        btnFactoryReset = findViewById(R.id.btnFactoryReset)
        btnChangePassword = findViewById(R.id.btnChangePassword)

        disableAllButtons()

        btnClearBills.setOnClickListener {
            clearBills()
        }

        btnFactoryReset.setOnClickListener {
            performFactoryReset()
        }

        btnChangePassword.setOnClickListener {
            showChangePinDialog()
        }
    }

    // ================= MENU =================
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
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

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        if (isEditMode) {
            enableAllButtons()
        } else {
            disableAllButtons()
        }
    }

    private fun disableAllButtons() {
        btnClearBills.isEnabled = false
        btnFactoryReset.isEnabled = false
        btnChangePassword.isEnabled = false

        btnClearBills.alpha = 0.5f
        btnFactoryReset.alpha = 0.5f
        btnChangePassword.alpha = 0.5f
    }

    private fun enableAllButtons() {
        btnClearBills.isEnabled = true
        btnFactoryReset.isEnabled = true
        btnChangePassword.isEnabled = true

        btnClearBills.alpha = 1f
        btnFactoryReset.alpha = 1f
        btnChangePassword.alpha = 1f
    }

    // ================= LOGIC =================

    private fun clearBills() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DataSecurityActivity)
            db.billDao().deleteAllItems()
            db.billDao().deleteAllBills()

            Toast.makeText(this@DataSecurityActivity,
                "All Bills Cleared",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun performFactoryReset() {
        val settingsPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        settingsPrefs.edit().clear().apply()

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DataSecurityActivity)
            db.clearAllTables()

            Toast.makeText(this@DataSecurityActivity,
                "App Reset Complete",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun showChangePinDialog() {

        val dialogView = layoutInflater.inflate(R.layout.dialog_change_pin, null)

        val etNewPin = dialogView.findViewById<EditText>(R.id.etNewPin)
        val etConfirmPin = dialogView.findViewById<EditText>(R.id.etConfirmPin)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSavePin)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {

            val newPin = etNewPin.text.toString().trim()
            val confirmPin = etConfirmPin.text.toString().trim()

            if (newPin.length < 4) {
                etNewPin.error = "PIN must be at least 4 digits"
                return@setOnClickListener
            }

            if (newPin != confirmPin) {
                etConfirmPin.error = "PINs do not match"
                return@setOnClickListener
            }

            val authPrefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)
            authPrefs.edit().putString("PASSWORD", newPin).apply()

            Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}