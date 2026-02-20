package com.example.easy_billing

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DataSecurityActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_security)

        // Setup Toolbar + Back Arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "Invoice Design"

        val authPrefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)
        val loginPin = authPrefs.getString("PASSWORD", "")?.trim()

        // 🔐 Protect Entire Screen
        if (loginPin.isNullOrEmpty()) {
            Toast.makeText(this, "Login PIN not set", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showPinDialog { }

        val btnManualBackup = findViewById<Button>(R.id.btnManualBackup)
        val btnRestoreBackup = findViewById<Button>(R.id.btnRestoreBackup)
        val btnClearBills = findViewById<Button>(R.id.btnClearBills)
        val btnFactoryReset = findViewById<Button>(R.id.btnFactoryReset)
        val btnChangePassword = findViewById<Button>(R.id.btnChangePassword)

        // Load Settings

        btnManualBackup.setOnClickListener {
            showPinDialog { backupDatabase() }
        }

        btnRestoreBackup.setOnClickListener {
            showPinDialog { restoreDatabase() }
        }

        btnClearBills.setOnClickListener {
            showPinDialog { clearBills() }
        }

        btnFactoryReset.setOnClickListener {
            showPinDialog { performFactoryReset() }
        }

        // ✅ Change PIN
        btnChangePassword.setOnClickListener {
            showPinDialog {
                showChangePinDialog()
            }
        }
    }

    // ================= VERIFY PIN =================
    private fun showPinDialog(onSuccess: () -> Unit) {

        val prefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)
        val savedPin = prefs.getString("PASSWORD", "")?.trim()

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Admin Verification")
            .setMessage("Enter Login PIN")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Verify") { _, _ ->

                val enteredPin = input.text.toString().trim()

                if (enteredPin == savedPin) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= CHANGE PIN =================
    private fun showChangePinDialog() {

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 20, 50, 10)

        val etNewPin = EditText(this)
        etNewPin.hint = "New PIN"
        etNewPin.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD

        val etConfirmPin = EditText(this)
        etConfirmPin.hint = "Confirm PIN"
        etConfirmPin.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD

        layout.addView(etNewPin)
        layout.addView(etConfirmPin)

        AlertDialog.Builder(this)
            .setTitle("Change Login PIN")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newPin = etNewPin.text.toString().trim()
                val confirmPin = etConfirmPin.text.toString().trim()

                if (newPin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPin != confirmPin) {
                    Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val authPrefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)

                authPrefs.edit()
                    .putString("PASSWORD", newPin)
                    .apply()

                Toast.makeText(this, "PIN changed successfully. Please login again.", Toast.LENGTH_LONG).show()

                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= CLEAR BILLS =================
    private fun clearBills() {

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DataSecurityActivity)
            db.billDao().deleteAllItems()
            db.billDao().deleteAllBills()

            runOnUiThread {
                Toast.makeText(this@DataSecurityActivity,
                    "All Bills Cleared",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= FACTORY RESET =================
    private fun performFactoryReset() {

        val settingsPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        settingsPrefs.edit().clear().apply()

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DataSecurityActivity)
            db.clearAllTables()

            runOnUiThread {
                Toast.makeText(this@DataSecurityActivity,
                    "App Reset Complete",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ================= BACKUP =================
    private fun backupDatabase() {

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("easy_billing_db")
                val backupFile = File(getExternalFilesDir(null), "backup_easy_billing.db")
                dbFile.copyTo(backupFile, overwrite = true)

                runOnUiThread {
                    Toast.makeText(this@DataSecurityActivity,
                        "Backup Created Successfully",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@DataSecurityActivity,
                        "Backup Failed",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ================= RESTORE =================
    private fun restoreDatabase() {

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("easy_billing_db")
                val backupFile = File(getExternalFilesDir(null), "backup_easy_billing.db")

                if (backupFile.exists()) {
                    dbFile.delete()
                    backupFile.copyTo(dbFile, overwrite = true)

                    runOnUiThread {
                        Toast.makeText(this@DataSecurityActivity,
                            "Restore Successful. Restart App.",
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@DataSecurityActivity,
                            "No Backup Found",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@DataSecurityActivity,
                        "Restore Failed",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}