package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.ChangePasswordRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

            showPasswordVerificationDialog {
                clearBills()
            }

        }

        btnFactoryReset.setOnClickListener {

            showPasswordVerificationDialog {
                performFactoryReset()
            }

        }

        btnChangePassword.setOnClickListener {

            showPasswordVerificationDialog {
                showChangePinDialog()
            }

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

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                RetrofitClient.api.clearBills("Bearer $token")

                val db = AppDatabase.getDatabase(this@DataSecurityActivity)
                db.billDao().deleteAllItems()
                db.billDao().deleteAllBills()

                Toast.makeText(
                    this@DataSecurityActivity,
                    "Bills archived successfully",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@DataSecurityActivity,
                    "Failed to clear bills",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performFactoryReset() {

        lifecycleScope.launch(Dispatchers.IO) {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            try {

                // ✅ STEP 1: BACKEND RESET (MANDATORY)
                if (token != null) {
                    RetrofitClient.api.factoryReset("Bearer $token")
                }

                if (token != null) {
                    val response = RetrofitClient.api.resetCredit("Bearer $token")

                    if (!response.isSuccessful) {
                        throw Exception("Backend reset failed")
                    }
                }

                // ✅ STEP 2: DESTROY ROOM INSTANCE
                AppDatabase.destroyInstance()

                // ✅ STEP 3: DELETE DATABASE FILE
                applicationContext.deleteDatabase("easy_billing_db")

                // ✅ STEP 4: CLEAR PREFS
                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                prefs.edit().clear().apply()

                // 🔥 STEP 5: RESET LOCALIZATION DEFAULTS
                prefs.edit {
                    putString("app_language", "en")
                        .putString("app_language_name", "English")
                        .putString("app_region", "India")
                        .putString("app_currency", "₹")
                }

                // 🔥 CLEAR AI CACHE FLAG
                prefs.edit {
                    putBoolean("ai_reset", true)
                    .apply()
                }

                // ✅ STEP 6: CLEAR AUTH (LOGOUT)
                getSharedPreferences("auth", MODE_PRIVATE)
                    .edit {
                        clear()
                    }

                withContext(Dispatchers.Main) {

                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Factory Reset Completed",
                        Toast.LENGTH_LONG
                    ).show()

                    // ✅ STEP 7: RESTART APP (CLEAR UI CACHE)
                    val intent = Intent(this@DataSecurityActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Reset failed: Check internet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
                etNewPin.error = "Password must be at least 4 digits"
                return@setOnClickListener
            }

            if (newPin != confirmPin) {
                etConfirmPin.error = "Passwords do not match"
                return@setOnClickListener
            }

            lifecycleScope.launch {

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null) ?: return@launch

                try {

                    RetrofitClient.api.changePassword(
                        "Bearer $token",
                        ChangePasswordRequest(newPin)
                    )

                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Password changed. Please login again.",
                        Toast.LENGTH_LONG
                    ).show()

                    // logout user
                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    val intent = Intent(this@DataSecurityActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)

                    dialog.dismiss()

                    dialog.dismiss()

                } catch (e: Exception) {

                    Toast.makeText(
                        this@DataSecurityActivity,
                        "Failed to change password",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
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
}