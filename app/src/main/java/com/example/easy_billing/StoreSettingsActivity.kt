package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.ShopSettingsUpdateRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ================= UI =================

    private fun bindViews() {
        etStoreName = findViewById(R.id.etStoreName)
        etStoreAddress = findViewById(R.id.etStoreAddress)
        etStorePhone = findViewById(R.id.etStorePhone)
        etStoreGstin = findViewById(R.id.etStoreGstin)
        btnSave = findViewById(R.id.btnSave)
    }

    private fun setEditMode(enabled: Boolean) {

        val fields = listOf(
            etStoreName,
            etStoreAddress,
            etStorePhone,
            etStoreGstin
        )

        fields.forEach {
            it.isEnabled = enabled
            it.isFocusable = enabled
            it.isFocusableInTouchMode = enabled
            it.isClickable = enabled
            it.isCursorVisible = enabled
        }

        btnSave.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        setEditMode(isEditMode)
    }

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

    // ================= LOAD =================

    private fun loadStoreSettings() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@StoreSettingsActivity)

            // ✅ ALWAYS LOAD FROM ROOM FIRST
            val local = db.storeInfoDao().get()

            withContext(Dispatchers.Main) {
                local?.let {
                    etStoreName.setText(it.name)
                    etStoreAddress.setText(it.address)
                    etStorePhone.setText(it.phone)
                    etStoreGstin.setText(it.gstin)
                }
            }

            // ✅ FETCH FROM BACKEND (IF ONLINE)
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getStoreSettings("Bearer $token")

                if (!response.shop_name.isNullOrBlank()) {

                    val updated = StoreInfo(
                        name = response.shop_name,
                        address = response.store_address ?: "",
                        phone = response.phone ?: "",
                        gstin = response.store_gstin ?: "",
                        isSynced = true
                    )

                    // ✅ UPDATE ROOM ONLY AFTER SUCCESS
                    db.storeInfoDao().insert(updated)

                    withContext(Dispatchers.Main) {
                        etStoreName.setText(updated.name)
                        etStoreAddress.setText(updated.address)
                        etStorePhone.setText(updated.phone)
                        etStoreGstin.setText(updated.gstin)
                    }
                }

            } catch (_: Exception) {
                // offline → keep Room data
            }
        }
    }

    // ================= SAVE =================

    private fun setupSave() {

        btnSave.setOnClickListener {
            showPasswordVerificationDialog {
                saveStoreSettings()
            }
        }
    }

    private fun saveStoreSettings() {

        val name = etStoreName.text.toString().trim()
        val address = etStoreAddress.text.toString().trim()
        val phone = etStorePhone.text.toString().trim()
        val gstin = etStoreGstin.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "Store name is required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@StoreSettingsActivity)

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoreSettingsActivity, "No internet connection", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {

                val request = ShopSettingsUpdateRequest(name, address, phone, gstin)

                // ✅ UPDATE BACKEND FIRST
                RetrofitClient.api.updateStoreSettings("Bearer $token", request)

                // ✅ THEN UPDATE ROOM (ONLY AFTER SUCCESS)
                val updated = StoreInfo(
                    name = name,
                    address = address,
                    phone = phone,
                    gstin = gstin,
                    isSynced = true
                )

                db.storeInfoDao().insert(updated)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoreSettingsActivity, "Store updated", Toast.LENGTH_SHORT).show()
                    setEditMode(false)
                }

            } catch (_: Exception) {

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoreSettingsActivity, "Update failed (check internet)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ================= PASSWORD =================

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
}