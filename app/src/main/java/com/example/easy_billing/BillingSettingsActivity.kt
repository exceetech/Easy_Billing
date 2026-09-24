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
import com.example.easy_billing.util.GstEngine
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

    private lateinit var rowPrinter: View
    private lateinit var tvPrinter: TextView
    private lateinit var icPrinterChevron: ImageView

    private lateinit var btnEdit: View
    private lateinit var tvEdit: TextView
    private lateinit var btnSave: Button

    private lateinit var cardGstProfile: View
    private lateinit var cardPrinter: View

    private var isEditMode = false
    private var snapshot: BillingSnapshot? = null

    // Set when launched from OnboardingActivity — see the matching flag
    // in StoreSettingsActivity for the full reasoning (plan §2.3).
    private var isOnboardingFlow = false

    private val schemeOptions  = listOf("Regular", "Composition")
    private val printerOptions = listOf("80mm", "A4")

    private var selectedScheme          = "Regular"
    private var currentRegistrationType = "Active"
    private var selectedPrinter         = "80mm"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billing_settings)
        com.example.easy_billing.util.UserEventLogger.logAction("BillingSettings", "opened")

        isOnboardingFlow = intent.getBooleanExtra(EXTRA_ONBOARDING, false)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        bindViews()
        setupDropdowns()
        loadData()
        setupSave()

        // Always editable: fields ready to type in, one Save button, no
        // Edit/Discard toggle (same in onboarding and in Settings).
        isEditMode = true
        setEditable(true)
        btnEdit.visibility = View.GONE
    }

    companion object {
        const val EXTRA_ONBOARDING = "extra_onboarding"
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

        rowPrinter       = findViewById(R.id.rowPrinter)
        tvPrinter        = findViewById(R.id.tvPrinter)
        icPrinterChevron = findViewById(R.id.icPrinterChevron)

        btnEdit = findViewById(R.id.btnEdit)
        tvEdit  = findViewById(R.id.tvEdit)
        btnSave = findViewById(R.id.btnSaveBilling)

        cardGstProfile = findViewById(R.id.cardGstProfile)
        cardPrinter    = findViewById(R.id.cardPrinter)

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
        rowPrinter.setOnClickListener {
            ThemedDropdown.show(
                anchor = rowPrinter,
                options = printerOptions,
                selectedIndex = printerOptions.indexOf(selectedPrinter).coerceAtLeast(0)
            ) { idx -> applyPrinter(printerOptions[idx]) }
        }
    }

    private fun applyScheme(v: String)  { selectedScheme = v;  tvScheme.text = v }
    private fun applyPrinter(v: String) { selectedPrinter = v; tvPrinter.text = v }

    // ---------------- LOAD ----------------

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@BillingSettingsActivity)
            val localGst = db.gstProfileDao().get()
            val billing  = db.billingSettingsDao().get()
            val storeInfo = db.storeInfoDao().get()
            val prefs = getSharedPreferences("auth", MODE_PRIVATE)
            val token = prefs.getString("TOKEN", null)

            val cachedOwnerName = prefs.getString("OWNER_NAME", null)
            val ownerName = if (!cachedOwnerName.isNullOrBlank()) {
                cachedOwnerName
            } else if (token != null) {
                try {
                    val prof = RetrofitClient.api.getProfile(token)
                    prefs.edit().putString("OWNER_NAME", prof.owner_name).apply()
                    prof.owner_name
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }

            withContext(Dispatchers.Main) {
                val gstinToShow = storeInfo?.gstin?.takeIf { it.isNotBlank() } ?: localGst?.gstin.orEmpty()
                etGstin.setText(gstinToShow)

                val hasValidLocalGst = localGst != null && localGst.gstin.isNotBlank() &&
                        (storeInfo == null || storeInfo.gstin.isBlank() || storeInfo.gstin == localGst.gstin)

                val defaultLegalName = ownerName.ifBlank { storeInfo?.name.orEmpty() }
                val defaultTradeName = storeInfo?.name.orEmpty()
                val defaultAddress = storeInfo?.address.orEmpty()

                val rawLocalScheme = localGst?.gstScheme?.ifBlank { "Regular" } ?: "Regular"
                val normalizedScheme = if (rawLocalScheme.contains("compos", ignoreCase = true)) "Composition" else "Regular"

                val rawLocalStatus = localGst?.registrationType?.ifBlank { "Active" } ?: "Active"
                currentRegistrationType = when {
                    rawLocalStatus.contains("suspend", ignoreCase = true) -> "Suspended"
                    rawLocalStatus.contains("cancel", ignoreCase = true) -> "Cancelled"
                    rawLocalStatus.contains("provis", ignoreCase = true) -> "Provisional"
                    else -> "Active"
                }

                if (hasValidLocalGst) {
                    etLegalName.setText(localGst!!.legalName.ifBlank { defaultLegalName })
                    etTradeName.setText(localGst.tradeName.ifBlank { defaultTradeName })
                    etStateCode.setText(localGst.stateCode.ifBlank { GstEngine.getStateCode(gstinToShow) })
                    etAddress.setText(localGst.address.ifBlank { defaultAddress })
                    applyScheme(normalizedScheme)
                } else {
                    etLegalName.setText(defaultLegalName)
                    etTradeName.setText(defaultTradeName)
                    etAddress.setText(defaultAddress)
                    etStateCode.setText(GstEngine.getStateCode(gstinToShow))
                    applyScheme("Regular")
                }
                applyPrinter(billing?.printerLayout ?: "80mm")
            }

            if (token == null) return@launch

            // The GST profile and the printer setting are two unrelated
            // reads, so they get two separate try blocks.
            //
            // Sharing one meant that a shop which hadn't configured GST yet —
            // where GET /gst/profile answers 404 "GST profile not configured"
            // — threw before the billing call was ever made, so the printer
            // layout silently never came down from the server. The catch only
            // printed a stack trace, so nothing on screen said so.

            try {
                val gstResp = RetrofitClient.api.getGstProfile(token)

                // Blank-guard, the same rule SyncManager.syncGstProfile uses:
                // an empty server row must never overwrite a populated local
                // profile. With no GSTIN there is nothing worth adopting.
                if (gstResp.gstin.isNotBlank()) {
                    val defaultLegalName = ownerName.ifBlank { storeInfo?.name.orEmpty() }
                    val defaultTradeName = storeInfo?.name.orEmpty()
                    val defaultAddress = storeInfo?.address.orEmpty()

                    val rawRemoteScheme = gstResp.gst_scheme.ifBlank { "Regular" }
                    val normRemoteScheme = if (rawRemoteScheme.contains("compos", ignoreCase = true)) "Composition" else "Regular"

                    val rawRemoteStatus = gstResp.registration_type.ifBlank { "Active" }
                    val normRemoteStatus = when {
                        rawRemoteStatus.contains("suspend", ignoreCase = true) -> "Suspended"
                        rawRemoteStatus.contains("cancel", ignoreCase = true) -> "Cancelled"
                        rawRemoteStatus.contains("provis", ignoreCase = true) -> "Provisional"
                        else -> "Active"
                    }
                    currentRegistrationType = normRemoteStatus

                    val updatedGst = GstProfile(
                        gstin = gstResp.gstin,
                        legalName = gstResp.legal_name.ifBlank { defaultLegalName },
                        tradeName = gstResp.trade_name.ifBlank { defaultTradeName },
                        gstScheme = normRemoteScheme,
                        registrationType = normRemoteStatus,
                        stateCode = gstResp.state_code,
                        address = gstResp.address?.takeIf { it.isNotBlank() } ?: defaultAddress,
                        syncStatus = "synced",
                        updatedAt = appNow()
                    )
                    db.gstProfileDao().insert(updatedGst)

                    withContext(Dispatchers.Main) {
                        etGstin.setText(updatedGst.gstin)
                        etLegalName.setText(updatedGst.legalName)
                        etTradeName.setText(updatedGst.tradeName)
                        etStateCode.setText(updatedGst.stateCode.ifBlank { GstEngine.getStateCode(updatedGst.gstin) })
                        etAddress.setText(updatedGst.address)
                        applyScheme(normRemoteScheme)
                    }
                }
            } catch (e: Exception) {
                // Expected on a shop with no GST profile yet (404). The
                // fields already show the local row loaded above.
                e.printStackTrace()
            }

            try {
                val billingResp = RetrofitClient.api.getBillingSettings(token)
                val updatedBilling = (billing ?: BillingSettings(
                    defaultGst = 0f,
                    printerLayout = billingResp.printer_layout
                )).copy(
                    printerLayout = billingResp.printer_layout
                )
                db.billingSettingsDao().insert(updatedBilling)

                withContext(Dispatchers.Main) {
                    applyPrinter(updatedBilling.printerLayout)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.example.easy_billing.util.UserEventLogger.logError(
                    "BillingSettings", "settings_load_failed: ${e.javaClass.simpleName}"
                )
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
                printer = selectedPrinter
            )
        } else {
            snapshot?.let { s ->
                etLegalName.setText(s.legalName)
                etTradeName.setText(s.tradeName)
                etStateCode.setText(s.stateCode)
                etAddress.setText(s.address)
                applyScheme(s.scheme)
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
        }

        fun controlRow(row: View, chevron: View) {
            row.isEnabled = enable
            row.isClickable = enable
            chevron.visibility = if (enable) View.VISIBLE else View.INVISIBLE
        }
        controlRow(rowScheme, icSchemeChevron)
        controlRow(rowPrinter, icPrinterChevron)

        // Faded until Edit is tapped — same locked/unlocked feel as
        // InvoiceDesignActivity.setEditable() / DataSecurityActivity.setLocked().
        val alpha = if (enable) 1f else 0.6f
        cardGstProfile.alpha = alpha
        cardPrinter.alpha = alpha

        tvEdit.text = if (enable) "Discard" else getString(R.string.edit)
        btnSave.visibility = if (enable) View.VISIBLE else View.GONE
    }

    // ---------------- SAVE ----------------

    private fun setupSave() {
        btnSave.setOnClickListener {
            saveBillingSettings()
        }
    }

    private fun showPasswordVerificationDialog(onVerified: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_password, null)

        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val btnVerify  = dialogView.findViewById<Button>(R.id.btnVerify)
        val btnCancel  = dialogView.findViewById<Button>(R.id.btnCancel)
        val ivTogglePassword = dialogView.findViewById<ImageView>(R.id.ivTogglePassword)

        var isPasswordVisible = false
        ivTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_lucide_eye_off)
            } else {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_lucide_eye)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerify.setOnClickListener {
            val pw = etPassword.text.toString().trim()
            if (pw.isEmpty()) {
                etPassword.error = getString(R.string.billing_settings_enter_password_error)
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

        // ================= STATE CODE VALIDATION =================
        // This is a free-text field, but its value becomes sellerStateCode
        // in InvoiceActivity — the ONLY input to intra-vs-inter-state (CGST+SGST
        // vs IGST) determination on every invoice this shop issues. Unlike the
        // customer-side state field (a picker constrained to GstEngine.INDIA_STATES),
        // this had no validation at all, so a typo here silently miscalculated
        // GST on every bill from that point on. Accept a valid 2-digit code, a
        // valid state name (normalized to its code), or fall back to deriving
        // it from the GSTIN — reject anything else rather than save garbage.
        val typedState = etStateCode.text.toString().trim()
        val resolvedStateCode = when {
            typedState.isEmpty() -> GstEngine.getStateCode(etGstin.text.toString())
            GstEngine.INDIA_STATES.containsKey(typedState) -> typedState
            GstEngine.getStateCodeFromName(typedState) != null -> GstEngine.getStateCodeFromName(typedState)!!
            else -> ""
        }
        com.example.easy_billing.util.UserEventLogger.logAction(
            "BillingSettings",
            "save_clicked: state_typed=${typedState.ifBlank { "-" }}, resolved=${resolvedStateCode.ifBlank { "-" }}, " +
                "scheme=$selectedScheme, reg_type=$currentRegistrationType, " +
                "gstin=${etGstin.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                "legal_name=${etLegalName.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                "trade_name=${etTradeName.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                "address=${etAddress.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                "printer_layout=${selectedPrinter.ifEmpty { "-" }}"
        )
        if (resolvedStateCode.isBlank()) {
            Toast.makeText(
                this,
                R.string.enter_valid_state,
                Toast.LENGTH_LONG
            ).show()
            com.example.easy_billing.util.UserEventLogger.logValidationFailed("BillingSettings", "state_code_unresolved")
            return
        }
        // Normalize the field to the canonical name so what's displayed always
        // matches what's stored/sent — typed codes or casing variants collapse
        // to one consistent value.
        etStateCode.setText(GstEngine.INDIA_STATES[resolvedStateCode] ?: typedState)

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
            )).copy(
                printerLayout = printer
            )
            db.billingSettingsDao().insert(updatedBilling)

            // ================= GST =================
            val updatedGst = GstProfile(
                gstin = etGstin.text.toString().trim(),
                legalName = etLegalName.text.toString().trim(),
                tradeName = etTradeName.text.toString().trim(),
                gstScheme = selectedScheme,
                registrationType = currentRegistrationType,
                stateCode = resolvedStateCode,
                address = etAddress.text.toString().trim(),
                syncStatus = if (etGstin.text.toString().trim().isNotBlank()) "pending" else "synced",
                updatedAt = appNow()
            )
            db.gstProfileDao().insert(updatedGst)

            // ================= BACKEND SYNC =================
            if (token != null) {
                if (updatedGst.gstin.isNotBlank()) {
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
                    if (token == null) R.string.saved_offline_will_sync_later
                    else R.string.billing_settings_updated,
                    Toast.LENGTH_SHORT
                ).show()

                // Go back automatically (to the onboarding hub or Settings).
                finish()
            }
        }
    }

    private data class BillingSnapshot(
        val legalName: String,
        val tradeName: String,
        val stateCode: String,
        val address: String,
        val scheme: String,
        val printer: String
    )
}
