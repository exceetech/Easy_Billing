package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class StoreSettingsActivity : BaseActivity() {

    private lateinit var etStoreName: EditText
    private lateinit var etStoreAddress: EditText
    private lateinit var etStorePhone: EditText
    private lateinit var etStoreGstin: EditText
    private lateinit var btnSave: Button

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store_settings)

        // Setup toolbar
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "Store Information"

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        bindViews()
        loadExistingData()
        setupValidation()
        setupSave()
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

    // ================= VALIDATION =================
    private fun setupValidation() {

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                validateForm()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        etStoreName.addTextChangedListener(watcher)
        etStorePhone.addTextChangedListener(watcher)
        etStoreGstin.addTextChangedListener(watcher)

        validateForm()
    }

    private fun validateForm() {

        val name = etStoreName.text.toString().trim()
        val phone = etStorePhone.text.toString().trim()
        val gstin = etStoreGstin.text.toString().trim()

        var isValid = true

        if (name.isEmpty()) {
            etStoreName.error = "Store name required"
            isValid = false
        }

        if (phone.isNotEmpty() && phone.length < 10) {
            etStorePhone.error = "Invalid phone number"
            isValid = false
        }

        if (gstin.isNotEmpty() && gstin.length != 15) {
            etStoreGstin.error = "GSTIN must be 15 characters"
            isValid = false
        }

        btnSave.isEnabled = isValid
    }

    // ================= SAVE =================
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

            Toast.makeText(this, "Store details updated successfully", Toast.LENGTH_SHORT).show()

            finish()
        }
    }
}