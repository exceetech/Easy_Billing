package com.example.easy_billing

import android.os.Bundle
import android.widget.*

class StoreSettingsActivity : BaseActivity() {

    private lateinit var etStoreName: EditText
    private lateinit var etStoreAddress: EditText
    private lateinit var etStorePhone: EditText
    private lateinit var etStoreGstin: EditText
    private lateinit var btnSave: Button

    private lateinit var prefs: android.content.SharedPreferences

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = ""

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        loadExistingData()
        setEditMode(false)   // 🔒 start in view mode
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

        btnSave.visibility = if (enabled) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    private fun bindViews() {
        etStoreName = findViewById(R.id.etStoreName)
        etStoreAddress = findViewById(R.id.etStoreAddress)
        etStorePhone = findViewById(R.id.etStorePhone)
        etStoreGstin = findViewById(R.id.etStoreGstin)
        btnSave = findViewById(R.id.btnSave)
    }

    private fun loadExistingData() {
        etStoreName.setText(prefs.getString("store_name", ""))
        etStoreAddress.setText(prefs.getString("store_address", ""))
        etStorePhone.setText(prefs.getString("store_phone", ""))
        etStoreGstin.setText(prefs.getString("store_gstin", ""))
    }

    // ===== SAVE =====
    private fun setupSave() {

        btnSave.setOnClickListener {

            val storeName = etStoreName.text.toString().trim()
            val storeAddress = etStoreAddress.text.toString().trim()
            val storePhone = etStorePhone.text.toString().trim()
            val storeGstin = etStoreGstin.text.toString().trim()

            prefs.edit()
                .putString("store_name", storeName)
                .putString("store_address", storeAddress)
                .putString("store_phone", storePhone)
                .putString("store_gstin", storeGstin)
                .apply()

            Toast.makeText(this,
                "Store details updated successfully",
                Toast.LENGTH_SHORT).show()

            setEditMode(false)   // 🔒 back to view mode
        }
    }
}