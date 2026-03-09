package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.ShopSettingsUpdateRequest
import com.example.easy_billing.network.ShopSettingsResponse
import androidx.core.content.edit

class StoreSettingsActivity : BaseActivity() {

    private lateinit var etStoreName: EditText
    private lateinit var etStoreAddress: EditText
    private lateinit var etStorePhone: EditText
    private lateinit var etStoreGstin: EditText
    private lateinit var btnSave: Button

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = ""

        bindViews()

        setEditMode(false)

        loadStoreSettings()

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

    // ===== Enable / Disable Fields =====
    private fun setEditMode(enabled: Boolean) {

        etStoreName.isEnabled = enabled
        etStoreAddress.isEnabled = enabled
        etStorePhone.isEnabled = enabled
        etStoreGstin.isEnabled = enabled

        btnSave.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun bindViews() {

        etStoreName = findViewById(R.id.etStoreName)
        etStoreAddress = findViewById(R.id.etStoreAddress)
        etStorePhone = findViewById(R.id.etStorePhone)
        etStoreGstin = findViewById(R.id.etStoreGstin)

        btnSave = findViewById(R.id.btnSave)
    }

    // ================= LOAD SETTINGS =================

    private fun loadStoreSettings() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response: ShopSettingsResponse =
                    RetrofitClient.api.getStoreSettings("Bearer $token")

                etStoreName.setText(response.shop_name ?: "")
                etStoreAddress.setText(response.store_address ?: "")
                etStorePhone.setText(response.phone ?: "")
                etStoreGstin.setText(response.store_gstin ?: "")

                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

                prefs.edit {
                    putString("store_name", response.shop_name ?: "")
                        .putString("store_address", response.store_address ?: "")
                        .putString("store_phone", response.phone ?: "")
                        .putString("store_gstin", response.store_gstin ?: "")
                }

            } catch (e: Exception) {

                Toast.makeText(
                    this@StoreSettingsActivity,
                    "Failed to load store settings",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ================= SAVE SETTINGS =================

    private fun setupSave() {

        btnSave.setOnClickListener {

            showPasswordVerificationDialog {
                saveStoreSettings()
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

    private fun saveStoreSettings() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val request = ShopSettingsUpdateRequest(
                    etStoreName.text.toString(),
                    etStoreAddress.text.toString(),
                    etStorePhone.text.toString(),
                    etStoreGstin.text.toString()
                )

                RetrofitClient.api.updateStoreSettings(
                    "Bearer $token",
                    request
                )

                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

                prefs.edit {
                    putString("store_name", etStoreName.text.toString())
                    putString("store_address", etStoreAddress.text.toString())
                    putString("store_phone", etStorePhone.text.toString())
                    putString("store_gstin", etStoreGstin.text.toString())
                }

                Toast.makeText(
                    this@StoreSettingsActivity,
                    "Store details updated successfully",
                    Toast.LENGTH_SHORT
                ).show()

                setEditMode(false)

            } catch (e: Exception) {

                Toast.makeText(
                    this@StoreSettingsActivity,
                    "Failed to update store settings",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}