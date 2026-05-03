package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.ShopSettingsUpdateRequest
import com.example.easy_billing.util.GstEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Store info + GSTIN — fully manual entry.
 *
 * Per current product spec:
 *   • This screen is the only place a GSTIN is entered.
 *   • There is **no** GST verification or Sandbox lookup here.
 *   • The full GST profile (legal name, trade name, scheme, …)
 *     is edited in [BillingSettingsActivity]; this screen only
 *     captures the GSTIN itself plus basic store contact info.
 */
class StoreSettingsActivity : BaseActivity() {

    private lateinit var etStoreName: EditText
    private lateinit var etStoreAddress: EditText
    private lateinit var etStorePhone: EditText
    private lateinit var etStoreGstin: EditText

    private lateinit var actShopType: AutoCompleteTextView
    private lateinit var btnSave: Button

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = ""

        bindViews()
        setEditMode(false)
        setupShopTypeDropdown()
        loadStoreSettings()
        setupSave()
    }

    /* ------------------------------------------------------------------
     *  UI
     * ------------------------------------------------------------------ */

    private fun bindViews() {
        etStoreName    = findViewById(R.id.etStoreName)
        etStoreAddress = findViewById(R.id.etStoreAddress)
        etStorePhone   = findViewById(R.id.etStorePhone)
        etStoreGstin   = findViewById(R.id.etStoreGstin)
        actShopType    = findViewById(R.id.actShopType)
        btnSave        = findViewById(R.id.btnSave)
    }

    private fun setEditMode(enabled: Boolean) {
        listOf(etStoreName, etStoreAddress, etStorePhone, etStoreGstin).forEach {
            it.isEnabled = enabled
            it.isFocusable = enabled
            it.isFocusableInTouchMode = enabled
            it.isClickable = enabled
            it.isCursorVisible = enabled
        }

        actShopType.isEnabled = enabled
        actShopType.isFocusable = enabled
        actShopType.isFocusableInTouchMode = enabled
        actShopType.isClickable = enabled
        actShopType.isCursorVisible = enabled

        val til = actShopType.parent.parent
            as? com.google.android.material.textfield.TextInputLayout
        til?.isEndIconVisible = enabled
        actShopType.alpha = if (enabled) 1f else 0.6f

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
            R.id.action_edit -> { toggleEditMode(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /* ------------------------------------------------------------------
     *  Load
     * ------------------------------------------------------------------ */

    private fun loadStoreSettings() {
        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@StoreSettingsActivity)

            // ---- Local cache ----
            val local = db.storeInfoDao().get()

            withContext(Dispatchers.Main) {
                local?.let {
                    etStoreName.setText(it.name)
                    etStoreAddress.setText(it.address)
                    etStorePhone.setText(it.phone)
                    etStoreGstin.setText(it.gstin)

                    val display = when (it.type) {
                        "hotel"   -> "Hotel"
                        "bakery"  -> "Bakery"
                        "grocery" -> "Grocery"
                        else      -> "General"
                    }
                    actShopType.setText(display, false)
                }
            }

            // ---- Backend sync (best-effort) ----
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {
                val response = RetrofitClient.api.getStoreSettings("Bearer $token")

                if (!response.shop_name.isNullOrBlank()) {
                    val type = response.type?.takeIf { it.isNotBlank() } ?: "general"

                    val updated = StoreInfo(
                        name = response.shop_name,
                        address = response.store_address ?: "",
                        phone = response.phone ?: "",
                        gstin = response.store_gstin ?: "",
                        type = type,
                        isSynced = true
                    )
                    db.storeInfoDao().insert(updated)

                    withContext(Dispatchers.Main) {
                        etStoreName.setText(updated.name)
                        etStoreAddress.setText(updated.address)
                        etStorePhone.setText(updated.phone)
                        etStoreGstin.setText(updated.gstin)

                        val display = when (type.lowercase()) {
                            "hotel"   -> "Hotel"
                            "bakery"  -> "Bakery"
                            "grocery" -> "Grocery"
                            else      -> "General"
                        }
                        actShopType.setText(display, false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /* ------------------------------------------------------------------
     *  Save — manual GSTIN entry, no verification
     * ------------------------------------------------------------------ */

    private fun setupSave() {
        btnSave.setOnClickListener {
            showPasswordVerificationDialog { saveStoreSettings() }
        }
    }

    private fun saveStoreSettings() {

        val name    = etStoreName.text.toString().trim()
        val address = etStoreAddress.text.toString().trim()
        val phone   = etStorePhone.text.toString().trim()
        val gstin   = etStoreGstin.text.toString().trim().uppercase()

        val type = when (actShopType.text.toString().lowercase()) {
            "hotel"   -> "hotel"
            "bakery"  -> "bakery"
            "grocery" -> "grocery"
            else      -> "general"
        }

        if (name.isEmpty()) {
            Toast.makeText(this, "Store name is required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@StoreSettingsActivity)

            // ---- 1. Save store_info locally ----
            val updated = StoreInfo(
                name = name,
                address = address,
                phone = phone,
                gstin = gstin,
                type = type,
                stateCode = GstEngine.getStateCode(gstin),
                isSynced = false
            )
            db.storeInfoDao().insert(updated)

            // ---- 2. Stamp GSTIN onto the gst_profile row (manual,
            //         no verification — full profile is filled in
            //         BillingSettings). State code is auto-derived
            //         from the GSTIN prefix only as a convenience.
            if (gstin.isNotBlank()) {
                val existingProfile = db.gstProfileDao().get()
                val merged = (existingProfile ?: GstProfile(gstin = gstin)).copy(
                    gstin     = gstin,
                    stateCode = existingProfile?.stateCode
                        ?.takeIf { it.isNotBlank() }
                        ?: GstEngine.getStateCode(gstin),
                    syncStatus = "pending",
                    updatedAt  = System.currentTimeMillis()
                )
                db.gstProfileDao().insert(merged)
            }

            // ---- 3. Push to backend (best-effort) ----
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)
            if (token != null) {
                try {
                    RetrofitClient.api.updateStoreSettings(
                        "Bearer $token",
                        ShopSettingsUpdateRequest(name, address, phone, gstin, type)
                    )
                    db.storeInfoDao().insert(updated.copy(isSynced = true))
                } catch (_: Exception) {
                    // offline → SyncManager will retry later
                }
            }

            // Kick the SyncCoordinator so any other pending rows
            // ride along with this network attempt. No-op if offline.
            com.example.easy_billing.sync.SyncCoordinator
                .get(this@StoreSettingsActivity)
                .requestSync()

            withContext(Dispatchers.Main) {
                val msg = if (token == null) "Saved offline. Will sync when connected."
                          else "Store updated"
                Toast.makeText(this@StoreSettingsActivity, msg, Toast.LENGTH_SHORT).show()
                setEditMode(false)
                isEditMode = false
            }
        }
    }

    /* ------------------------------------------------------------------
     *  Password gate
     * ------------------------------------------------------------------ */

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView).create()
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

    /* ------------------------------------------------------------------
     *  Dropdown
     * ------------------------------------------------------------------ */

    private fun setupShopTypeDropdown() {
        val options = listOf("General", "Hotel", "Bakery", "Grocery")
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, options
        )
        actShopType.setAdapter(adapter)
    }
}
