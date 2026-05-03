package com.example.easy_billing

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.network.*
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillingSettingsActivity : BaseActivity() {

    // GST
    private lateinit var etGstin: TextInputEditText
    private lateinit var etLegalName: TextInputEditText
    private lateinit var etTradeName: TextInputEditText
    private lateinit var spScheme: AutoCompleteTextView
    private lateinit var spRegType: AutoCompleteTextView
    private lateinit var etStateCode: TextInputEditText
    private lateinit var etAddress: TextInputEditText

    private lateinit var tilScheme: TextInputLayout
    private lateinit var tilRegType: TextInputLayout
    private lateinit var tilPrinter: TextInputLayout

    // Printer
    private lateinit var spPrinter: AutoCompleteTextView
    private lateinit var btnSave: Button

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        bindViews()
        setupDropdowns()
        loadData()
        setEditable(false)
        setupSave()
    }

    // ---------------- MENU ----------------

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_edit, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_edit) {
            toggleEditMode(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_edit)?.title =
            if (isEditMode) "Done" else "Click here to Edit"
        return super.onPrepareOptionsMenu(menu)
    }

    // ---------------- BIND ----------------

    private fun bindViews() {
        etGstin     = findViewById(R.id.etGstin)
        etLegalName = findViewById(R.id.etLegalName)
        etTradeName = findViewById(R.id.etTradeName)
        spScheme    = findViewById(R.id.spScheme)
        spRegType   = findViewById(R.id.spRegType)
        etStateCode = findViewById(R.id.etStateCode)
        etAddress   = findViewById(R.id.etAddress)

        spPrinter   = findViewById(R.id.spPrinterLayout)
        btnSave     = findViewById(R.id.btnSaveBilling)

        tilScheme  = findViewById(R.id.tilScheme)
        tilRegType = findViewById(R.id.tilRegType)
        tilPrinter = findViewById(R.id.tilPrinter)
    }

    private fun setupDropdowns() {
        spScheme.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                listOf("REGULAR", "COMPOSITION"))
        )
        spRegType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                listOf("Regular", "Composition", "Casual", "SEZ", "Non-Resident"))
        )
        spPrinter.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                listOf("80mm", "A4"))
        )
    }

    // ---------------- LOAD ----------------

    private fun loadData() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@BillingSettingsActivity)
            val localGst = db.gstProfileDao().get()
            val billing  = db.billingSettingsDao().get()

            // ✅ Load ROOM first
            withContext(Dispatchers.Main) {
                etGstin.setText(localGst?.gstin.orEmpty())
                etLegalName.setText(localGst?.legalName.orEmpty())
                etTradeName.setText(localGst?.tradeName.orEmpty())
                spScheme.setText(localGst?.gstScheme ?: "REGULAR", false)
                spRegType.setText(localGst?.registrationType ?: "Regular", false)
                etStateCode.setText(localGst?.stateCode.orEmpty())
                etAddress.setText(localGst?.address.orEmpty())
                spPrinter.setText(billing?.printerLayout ?: "80mm", false)
            }

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                // ✅ FETCH GST PROFILE FROM BACKEND
                val gstResp = RetrofitClient.api.getGstProfile("Bearer $token")

                val updatedGst = GstProfile(
                    gstin = gstResp.gstin ?: "",
                    legalName = gstResp.legal_name ?: "",
                    tradeName = gstResp.trade_name ?: "",
                    gstScheme = gstResp.gst_scheme ?: "REGULAR",
                    registrationType = gstResp.registration_type ?: "Regular",
                    stateCode = gstResp.state_code ?: "",
                    address = gstResp.address ?: "",
                    syncStatus = "synced",
                    updatedAt = System.currentTimeMillis()
                )

                db.gstProfileDao().insert(updatedGst)

                val billingResp = RetrofitClient.api.getBillingSettings("Bearer $token")

                val updatedBilling = (billing ?: BillingSettings(
                    defaultGst = 0f,
                    printerLayout = billingResp.printer_layout
                )).copy(printerLayout = billingResp.printer_layout)

                db.billingSettingsDao().insert(updatedBilling)

                withContext(Dispatchers.Main) {

                    etGstin.setText(updatedGst.gstin)
                    etLegalName.setText(updatedGst.legalName)
                    etTradeName.setText(updatedGst.tradeName)
                    spScheme.setText(updatedGst.gstScheme, false)
                    spRegType.setText(updatedGst.registrationType, false)
                    etStateCode.setText(updatedGst.stateCode)
                    etAddress.setText(updatedGst.address)

                    spPrinter.setText(updatedBilling.printerLayout, false)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------------- EDIT ----------------

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        setEditable(isEditMode)
        invalidateOptionsMenu()
    }

    private fun setEditable(enable: Boolean) {

        etGstin.isEnabled = false

        listOf(etLegalName, etTradeName, etStateCode, etAddress).forEach {

            it.isEnabled = enable
            it.isFocusable = enable
            it.isFocusableInTouchMode = enable
            it.isClickable = enable
            it.isCursorVisible = enable
            it.alpha = if (enable) 1f else 0.6f
        }

        fun controlDropdown(
            view: AutoCompleteTextView,
            layout: TextInputLayout
        ) {
            view.isEnabled = enable

            if (!enable) {
                view.setOnTouchListener { _, _ -> true }
                layout.endIconMode = TextInputLayout.END_ICON_NONE
            } else {
                view.setOnTouchListener(null)
                layout.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }

            view.alpha = if (enable) 1f else 0.6f
        }

        controlDropdown(spScheme, tilScheme)
        controlDropdown(spRegType, tilRegType)
        controlDropdown(spPrinter, tilPrinter)

        btnSave.visibility = if (enable) View.VISIBLE else View.GONE
    }

    // ---------------- SAVE ----------------

    private fun setupSave() {
        btnSave.setOnClickListener {
            showPasswordVerificationDialog { saveBillingSettings() }
        }
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify  = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel  = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {
            val pw = etPassword.text.toString().trim()
            if (pw.isEmpty()) {
                etPassword.error = "Enter password"
                return@setOnClickListener
            }
            verifyPassword(pw) {
                dialog.dismiss()
                onVerified()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun saveBillingSettings() {

        lifecycleScope.launch(Dispatchers.IO) {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            val db = AppDatabase.getDatabase(this@BillingSettingsActivity)

            // ================= PRINTER =================
            val printer = spPrinter.text.toString().ifEmpty { "80mm" }

            val existingBilling = db.billingSettingsDao().get()

            val updatedBilling = (existingBilling ?: BillingSettings(
                defaultGst = 0f,
                printerLayout = printer
            )).copy(
                printerLayout = printer
            )

            db.billingSettingsDao().insert(updatedBilling)

            // ================= GST =================
            val updatedGst = GstProfile(
                gstin = etGstin.text.toString(),
                legalName = etLegalName.text.toString(),
                tradeName = etTradeName.text.toString(),
                gstScheme = spScheme.text.toString(),
                registrationType = spRegType.text.toString(),
                stateCode = etStateCode.text.toString(),
                address = etAddress.text.toString(),
                syncStatus = "pending",
                updatedAt = System.currentTimeMillis()
            )

            db.gstProfileDao().insert(updatedGst)

            // ================= BACKEND SYNC =================
            if (token != null) {

                // ---- GST PROFILE ----
                runCatching {
                    RetrofitClient.api.upsertGstProfile(
                        "Bearer $token",
                        GstProfileRequest(
                            gstin = updatedGst.gstin,
                            legal_name = updatedGst.legalName,
                            trade_name = updatedGst.tradeName,
                            gst_scheme = updatedGst.gstScheme,
                            registration_type = updatedGst.registrationType,
                            state_code = updatedGst.stateCode,
                            address = updatedGst.address
                        )
                    )
                    db.gstProfileDao().updateSyncStatus("synced")
                }

                // ---- PRINTER SETTINGS ----
                runCatching {
                    RetrofitClient.api.updateBillingSettings(
                        "Bearer $token",
                        BillingSettingsUpdateRequest(
                            default_gst = 0f,
                            printer_layout = printer
                        )
                    )
                }
            }

            // ================= OPTIONAL SYNC ENGINE =================
            com.example.easy_billing.sync.SyncCoordinator
                .get(this@BillingSettingsActivity)
                .requestSync()

            // ================= UI =================
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@BillingSettingsActivity,
                    if (token == null) "Saved offline. Will sync later."
                    else "Billing settings updated",
                    Toast.LENGTH_SHORT
                ).show()

                setEditable(false)
                isEditMode = false
            }
        }
    }
}