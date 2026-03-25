package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VerifyPasswordRequest
import kotlinx.coroutines.launch
import androidx.core.content.edit

class LocalizationSettingsActivity : BaseActivity() {

    private lateinit var spLanguage: AutoCompleteTextView
    private lateinit var spRegion: AutoCompleteTextView
    private lateinit var spCurrency: AutoCompleteTextView
    private lateinit var btnSave: Button

    private var isEditMode = false

    private val languages = arrayOf(
        "English","Hindi","Tamil","Malayalam","Telugu","Kannada"
    )

    private val codes = arrayOf(
        "en","hi","ta","ml","te","kn"
    )

    private val regions = listOf("India","USA","Europe")
    private val currencies = listOf("₹ INR","$ USD","€ EUR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localization_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = ""

        bindViews()

        setEditMode(false)

        setupDropdowns()

        loadSavedSettings()

        setupSave()
    }

    // ===== Toolbar Menu =====

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit -> {
                toggleEditMode()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        setEditMode(isEditMode)
    }

    // ===== Enable / Disable =====

    private fun setEditMode(enabled: Boolean) {

        spLanguage.isEnabled = enabled
        spRegion.isEnabled = enabled
        spCurrency.isEnabled = enabled

        spLanguage.isClickable = enabled
        spRegion.isClickable = enabled
        spCurrency.isClickable = enabled

        btnSave.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun bindViews() {

        spLanguage = findViewById(R.id.spLanguage)
        spRegion = findViewById(R.id.spRegion)
        spCurrency = findViewById(R.id.spCurrency)

        btnSave = findViewById(R.id.btnSaveLocalization)
    }

    // ===== Setup dropdowns =====

    private fun setupDropdowns() {

        val langAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            languages
        )

        val regionAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            regions
        )

        val currencyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            currencies
        )

        spLanguage.setAdapter(langAdapter)
        spRegion.setAdapter(regionAdapter)
        spCurrency.setAdapter(currencyAdapter)

        // Disable keyboard
        spLanguage.keyListener = null
        spRegion.keyListener = null
        spCurrency.keyListener = null

        // Open dropdown only when edit mode is active
        spLanguage.setOnClickListener {
            if (isEditMode) spLanguage.showDropDown()
        }

        spRegion.setOnClickListener {
            if (isEditMode) spRegion.showDropDown()
        }

        spCurrency.setOnClickListener {
            if (isEditMode) spCurrency.showDropDown()
        }
    }

    // ===== Load saved settings =====

    private fun loadSavedSettings() {

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        spLanguage.setText(prefs.getString("app_language_name","English"), false)
        spRegion.setText(prefs.getString("app_region","India"), false)
        val savedSymbol = prefs.getString("app_currency", "₹") ?: "₹"

        val displayCurrency = currencies.find { it.startsWith(savedSymbol) } ?: "₹ INR"

        spCurrency.setText(displayCurrency, false)
    }

    // ===== Save Settings =====

    private fun setupSave() {

        btnSave.setOnClickListener {

            showPasswordVerificationDialog {
                saveSettings()
            }
        }
    }

    private fun saveSettings() {

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val languageName = spLanguage.text.toString()
        val languageIndex = languages.indexOf(languageName)

        val currencyFull = spCurrency.text.toString()

        // ✅ Extract symbol only (important fix)
        val currencySymbol = currencyFull.split(" ")[0]

        prefs.edit {
            putString("app_language", codes[languageIndex])
                .putString("app_language_name", languageName)
                .putString("app_region", spRegion.text.toString())
                .putString("app_currency", currencySymbol)
        }

        restartApp()
    }

    // ===== Password Verification =====

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

    // ===== Restart App =====

    private fun restartApp() {

        val intent = Intent(this, MainActivity::class.java)

        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)
    }
}
