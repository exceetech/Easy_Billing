package com.example.easy_billing

import com.example.easy_billing.util.appNow

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillingSettings
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.network.*
import com.example.easy_billing.ui.ThemedDropdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillingSettingsActivity : BaseActivity() {

    // GST text fields
    private lateinit var etGstin: EditText
    private lateinit var etLegalName: EditText
    private lateinit var etTradeName: EditText
    private lateinit var etStateCode: EditText
    private lateinit var etAddress: EditText

    // Themed dropdown rows
    private lateinit var rowScheme: View
    private lateinit var tvScheme: TextView
    private lateinit var icSchemeChevron: ImageView

    private lateinit var rowRegType: View
    private lateinit var tvRegType: TextView
    private lateinit var icRegTypeChevron: ImageView

    private lateinit var rowPrinter: View
    private lateinit var tvPrinter: TextView
    private lateinit var icPrinterChevron: ImageView

    private lateinit var btnEdit: View
    private lateinit var tvEdit: TextView
    private lateinit var btnSave: Button

    private var isEditMode = false
    private var snapshot: BillingSnapshot? = null

    private val schemeOptions  = listOf("REGULAR", "COMPOSITION")
    private val regTypeOptions = listOf("Regular", "Composition", "Casual", "SEZ", "Non-Resident")
    private val printerOptions = listOf("80mm", "A4")

    private var selectedScheme  = "REGULAR"
    private var selectedRegType = "Regular"
    private var selectedPrinter = "80mm"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing_settings)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        bindViews()
        setupDropdowns()
        loadData()
        setEditable(false)
        setupSave()
    }

    // ---------------- BIND ----------------

    private fun bindViews() {
        etGstin     = findViewById(R.id.etGstin)
        etLegalName = findViewById(R.id.etLegalName)
        etTradeName = findViewById(R.id.etTradeName)
        etStateCode = findViewById(R.id.etStateCode)
        etAddress   = findViewById(R.id.etAddress)

        rowScheme        = findViewById(R.id.rowScheme)
        tvScheme         = findViewById(R.id.tvScheme)
        icSchemeChevron  = findViewById(R.id.icSchemeChevron)

        rowRegType       = findViewById(R.id.rowRegType)
        tvRegType        = findViewById(R.id.tvRegType)
        icRegTypeChevron = findViewById(R.id.icRegTypeChevron)

        rowPrinter       = findViewById(R.id.rowPrinter)
        tvPrinter        = findViewById(R.id.tvPrinter)
        icPrinterChevron = findViewById(R.id.icPrinterChevron)

        btnEdit = findViewById(R.id.btnEdit)
        tvEdit  = findViewById(R.id.tvEdit)
        btnSave = findViewById(R.id.btnSaveBilling)

        btnEdit.setOnClickListener { toggleEditMode() }
    }

    // ---------------- DROPDOWNS (themed) ----------------

    private fun setupDropdowns() {
        rowScheme.setOnClickListener {
            ThemedDropdown.show(
                anchor = rowScheme,
                options = schemeOptions,
                selectedIndex = schemeOptions.indexOf(selectedScheme).coerceAtLeast(0)
            ) { idx -> applyScheme(schemeOptions[idx]) }
        }
        rowRegType.setOnClickListener {
            ThemedDropdown.show(
                anchor = rowRegType,
                options = regTypeOptions,
                selectedIndex = regTypeOptions.indexOf(selectedRegType).coerceAtLeast(0)
            ) { idx -> applyRegType(regTypeOptions[idx]) }
        }
        rowPrinter.setOnClickListener {
            ThemedDropdown.show(
                anchor = rowPrinter,
                options = printerOptions,
                selectedIndex = printerOptions.indexOf(selectedPrinter).coerceAtLeast(0)
            ) { idx -> applyPrinter(printerOptions[idx]) }
        }
    }

    private fun applyScheme(v: String)  { selectedScheme = v;  tvScheme.text = v }
    private fun applyRegType(v: String) { selectedRegType = v; tvRegType.text = v }
    private fun applyPrinter(v: String) { selectedPrinter = v; tvPrinter.text = v }

    // ---------------- LOAD ----------------

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@BillingSettingsActivity)
            val localGst = db.gstProfileDao().get()
            val billing  = db.billingSettingsDao().get()

            withContext(Dispatchers.Main) {
                etGstin.setText(localGst?.gstin.orEmpty())
                etLegalName.setText(localGst?.legalName.orEmpty())
                etTradeName.setText(localGst?.tradeName.orEmpty())
                etStateCode.setText(localGst?.stateCode.orEmpty())
                etAddress.setText(localGst?.address.orEmpty())
                applyScheme(localGst?.gstScheme ?: "REGULAR")
                applyRegType(localGst?.registrationType ?: "Regular")
                applyPrinter(billing?.printerLayout ?: "80mm")
            }

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {
                val gstResp = RetrofitClient.api.getGstProfile(token)

                val updatedGst = GstProfile(
                    gstin = gstResp.gstin ?: "",
                    legalName = gstResp.legal_name ?: "",
                    tradeName = gstResp.trade_name ?: "",
                    gstScheme = gstResp.gst_scheme ?: "REGULAR",
                    registrationType = gstResp.registration_type ?: "Regular",
                    stateCode = gstResp.state_code ?: "",
                    address = gstResp.address ?: "",
                    syncStatus = "synced",
                    updatedAt = appNow()
                )
                db.gstProfileDao().insert(updatedGst)

                val billingResp = RetrofitClient.api.getBillingSettings(token)
                val updatedBilling = (billing ?: BillingSettings(
                    defaultGst = 0f,
                    printerLayout = billingResp.printer_layout
                )).copy(printerLayout = billingResp.printer_layout)
                db.billingSettingsDao().insert(updatedBilling)

                withContext(Dispatchers.Main) {
                    etGstin.setText(updatedGst.gstin)
                    etLegalName.setText(updatedGst.legalName)
                    etTradeName.setText(updatedGst.tradeName)
                    etStateCode.setText(updatedGst.stateCode)
                    etAddress.setText(updatedGst.address)
                    applyScheme(updatedGst.gstScheme)
                    applyRegType(updatedGst.registrationType)
                    applyPrinter(updatedBilling.printerLayout)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------------- EDIT / DISCARD ----------------

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            // Entering edit: snapshot so "Discard" can revert. GSTIN excluded (locked).
            snapshot = BillingSnapshot(
                legalName = etLegalName.text.toString(),
                tradeName = etTradeName.text.toString(),
                stateCode = etStateCode.text.toString(),
                address = etAddress.text.toString(),
                scheme = selectedScheme,
                regType = selectedRegType,
                printer = selectedPrinter
            )
        } else {
            snapshot?.let { s ->
                etLegalName.setText(s.legalName)
                etTradeName.setText(s.tradeName)
                etStateCode.setText(s.stateCode)
                etAddress.setText(s.address)
                applyScheme(s.scheme)
                applyRegType(s.regType)
                applyPrinter(s.printer)
            }
        }
        setEditable(isEditMode)
    }

    private fun setEditable(enable: Boolean) {

        // GSTIN is always read-only (entered in Store Information).
        etGstin.isEnabled = false

        listOf(etLegalName, etTradeName, etStateCode, etAddress).forEach {
            it.isEnabled = enable
            it.isFocusable = enable
            it.isFocusableInTouchMode = enable
            it.isClickable = enable
            it.isCursorVisible = enable
            it.alpha = if (enable) 1f else 0.6f
        }

        fun controlRow(row: View, chevron: View) {
            row.isEnabled = enable
            row.isClickable = enable
            row.alpha = if (enable) 1f else 0.6f
            chevron.visibility = if (enable) View.VISIBLE else View.INVISIBLE
        }
        controlRow(rowScheme, icSchemeChevron)
        controlRow(rowRegType, icRegTypeChevron)
        controlRow(rowPrinter, icPrinterChevron)

        tvEdit.text = if (enable) "Discard" else getString(R.string.edit)
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
            val printer = selectedPrinter.ifEmpty { "80mm" }

            val existingBilling = db.billingSettingsDao().get()
            val updatedBilling = (existingBilling ?: BillingSettings(
                defaultGst = 0f,
                printerLayout = printer
            )).copy(printerLayout = printer)
            db.billingSettingsDao().insert(updatedBilling)

            // ================= GST =================
            val updatedGst = GstProfile(
                gstin = etGstin.text.toString(),
                legalName = etLegalName.text.toString(),
                tradeName = etTradeName.text.toString(),
                gstScheme = selectedScheme,
                registrationType = selectedRegType,
                stateCode = etStateCode.text.toString(),
                address = etAddress.text.toString(),
                syncStatus = "pending",
                updatedAt = appNow()
            )
            db.gstProfileDao().insert(updatedGst)

            // ================= BACKEND SYNC =================
            if (token != null) {
                runCatching {
                    RetrofitClient.api.upsertGstProfile(
                        token,
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

                runCatching {
                    RetrofitClient.api.updateBillingSettings(
                        token,
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

    private data class BillingSnapshot(
        val legalName: String,
        val tradeName: String,
        val stateCode: String,
        val address: String,
        val scheme: String,
        val regType: String,
        val printer: String
    )
}
