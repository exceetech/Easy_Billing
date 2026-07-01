package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.example.easy_billing.ui.ThemedDropdown
import androidx.core.content.edit

class LocalizationSettingsActivity : BaseActivity() {

    private lateinit var rowLanguage: View
    private lateinit var tvLanguage: TextView
    private lateinit var icLanguageChevron: ImageView

    private lateinit var rowCurrency: View
    private lateinit var tvCurrency: TextView
    private lateinit var icCurrencyChevron: ImageView

    private lateinit var btnEdit: View
    private lateinit var tvEdit: TextView
    private lateinit var btnSave: Button

    private var isEditMode = false
    private var snapshot: LocaleSnapshot? = null

    private val languages = arrayOf(
        "English", "Hindi", "Tamil", "Malayalam", "Telugu", "Kannada"
    )
    private val codes = arrayOf("en", "hi", "ta", "ml", "te", "kn")
    private val currencies = listOf("₹ INR", "$ USD", "€ EUR")

    private var selectedLanguage = "English"
    private var selectedCurrency = "₹ INR"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localization_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        bindViews()
        setEditMode(false)
        setupDropdowns()
        loadSavedSettings()
        setupSave()
    }

    private fun bindViews() {
        rowLanguage       = findViewById(R.id.rowLanguage)
        tvLanguage        = findViewById(R.id.tvLanguage)
        icLanguageChevron = findViewById(R.id.icLanguageChevron)
        rowCurrency       = findViewById(R.id.rowCurrency)
        tvCurrency        = findViewById(R.id.tvCurrency)
        icCurrencyChevron = findViewById(R.id.icCurrencyChevron)
        btnEdit           = findViewById(R.id.btnEdit)
        tvEdit            = findViewById(R.id.tvEdit)
        btnSave           = findViewById(R.id.btnSaveLocalization)

        btnEdit.setOnClickListener { toggleEditMode() }
    }

    // ===== Edit / Discard =====

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            // Entering edit: snapshot so "Discard" can revert.
            snapshot = LocaleSnapshot(selectedLanguage, selectedCurrency)
        } else {
            // Discard: restore the snapshot taken when edit mode began.
            snapshot?.let {
                applyLanguage(it.language)
                applyCurrency(it.currency)
            }
        }
        setEditMode(isEditMode)
    }

    private fun setEditMode(enabled: Boolean) {
        rowLanguage.isEnabled = enabled
        rowLanguage.isClickable = enabled
        rowLanguage.alpha = if (enabled) 1f else 0.6f
        icLanguageChevron.visibility = if (enabled) View.VISIBLE else View.INVISIBLE

        rowCurrency.isEnabled = enabled
        rowCurrency.isClickable = enabled
        rowCurrency.alpha = if (enabled) 1f else 0.6f
        icCurrencyChevron.visibility = if (enabled) View.VISIBLE else View.INVISIBLE

        tvEdit.text = if (enabled) "Discard" else getString(R.string.edit)
        btnSave.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    // ===== Dropdowns (themed) =====

    private fun setupDropdowns() {
        rowLanguage.setOnClickListener {
            val current = languages.indexOf(selectedLanguage).coerceAtLeast(0)
            ThemedDropdown.show(
                anchor = rowLanguage,
                options = languages.toList(),
                selectedIndex = current
            ) { idx -> applyLanguage(languages[idx]) }
        }

        rowCurrency.setOnClickListener {
            val current = currencies.indexOf(selectedCurrency).coerceAtLeast(0)
            ThemedDropdown.show(
                anchor = rowCurrency,
                options = currencies,
                selectedIndex = current
            ) { idx -> applyCurrency(currencies[idx]) }
        }
    }

    private fun applyLanguage(name: String) {
        selectedLanguage = name
        tvLanguage.text = name
    }

    private fun applyCurrency(display: String) {
        selectedCurrency = display
        tvCurrency.text = display
    }

    // ===== Load saved settings =====

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        applyLanguage(prefs.getString("app_language_name", "English") ?: "English")

        val savedSymbol = prefs.getString("app_currency", "₹") ?: "₹"
        applyCurrency(currencies.find { it.startsWith(savedSymbol) } ?: "₹ INR")
    }

    // ===== Save =====

    private fun setupSave() {
        btnSave.setOnClickListener {
            showPasswordVerificationDialog { saveSettings() }
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val languageName = selectedLanguage
        val languageIndex = languages.indexOf(languageName).coerceAtLeast(0)

        // Extract symbol only (e.g. "₹" from "₹ INR").
        val currencySymbol = selectedCurrency.split(" ")[0]

        prefs.edit {
            putString("app_language", codes[languageIndex])
                .putString("app_language_name", languageName)
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
        btnCancel.setOnClickListener { dialog.dismiss() }
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

    private data class LocaleSnapshot(
        val language: String,
        val currency: String
    )
}
