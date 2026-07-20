package com.example.easy_billing

import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.Product
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.network.VariantResponse
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft
import com.example.easy_billing.util.CatalogAutofill
import com.example.easy_billing.util.HsnHelpLauncher
import com.example.easy_billing.util.UqcMapper
import com.example.easy_billing.viewmodel.PurchaseViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "add items to purchase" dialog, extracted from [PurchaseActivity].
 *
 * Behaviour is intentionally identical to the original inline
 * implementation — this is a relocation, not a rewrite. Per-dialog
 * state lives in instance fields, so **create a new instance for every
 * [show] call**.
 *
 * @param activity      host, used for inflation / dialogs / coroutines
 * @param viewModel     receives the finished line via `addLine`
 * @param supplierState supplier state name taken from the invoice header
 */
class PurchaseLineDialog(
    private val activity: AppCompatActivity,
    private val viewModel: PurchaseViewModel,
    private val supplierState: () -> String
) {

    private val productRepo = ProductRepository.get(activity)

    // ── per-dialog mutable state ──
    private var lastProductName = ""
    private var lastVariantName = ""

    /**
     * Name we last ran the catalog lookup for. Without this, every focus change
     * on the product field re-ran the fetch and re-applied the autofill — which
     * silently undid a clear the user had just made by emptying the variant.
     * Mirrors AddProductActivity.lastFetchedProduct.
     */
    private var lastFetchedProduct: String? = null

    /** Variant we actually autofilled for; null means nothing to undo. */
    private var autofilledForVariant: String? = null

    /**
     * "product|variant" we last ran the variant lookup for. Same purpose as
     * [lastFetchedProduct]: re-settling an unchanged variant must not re-apply
     * the fill, or it resurrects values the user has just edited or cleared.
     * Reset to null when the fill could not resolve, so it retries.
     */
    private var lastFetchedVariant: String? = null
    private var userOverroteCess = false
    private var userOverroteAvailedIgst = false
    private var userOverroteAvailedCgst = false
    private var userOverroteAvailedSgst = false
    private var userOverroteAvailedCess = false
    private var userOverroteTaxable = false
    private var userOverroteInvoice = false
    private var shopStateCode = ""

    /** Catalog variants for the currently-entered product name. */
    private var variantCache: List<VariantResponse> = emptyList()

    /**
     * This shop's own products grouped by lowercased name, so the variant
     * dropdown can offer variants you already stock even when the global
     * catalogue doesn't carry them. Mirrors AddProductActivity.
     */
    private val localVariantsByName = HashMap<String, MutableList<Product>>()

    // In-flight lookups — cancelled when a newer one starts, so an older
    // (slower) response can never overwrite a newer one.
    private var productLookup: Job? = null
    private var variantLookup: Job? = null

    /** True while autofill is writing, so watchers ignore their own writes. */
    private var applyingAutofill = false

    /** True once the user deliberately picks a unit — autofill then leaves it alone. */
    private var unitUserSet = false

    /** True once the user edits the supplier-tax fields themselves, so the
     *  derivation stops (composition dealer, reverse charge, odd invoices). */
    private var userOverrotePurchaseTax = false

    /** True while the derivation writes, so its own writes aren't counted as edits. */
    private var settingPurchaseTax = false

    // Picker option lists (unit + category arrive asynchronously).
    private var unitOptions: List<String> = listOf("piece", "kilogram", "litre", "gram", "millilitre")
    private var categoryOptions: List<String> = emptyList()

    /**
     * Writes a catalog variant's statutory values into the empty fields
     * only — never overwrites something the user already typed. Mirrors
     * `AddProductActivity.fillStatutoryFrom`.
     */
    private fun applyStatutoryFrom(
        v: VariantResponse,
        etHsn: TextInputEditText,
        etSCgst: TextInputEditText,
        etSSgst: TextInputEditText,
        etUnit: AutoCompleteTextView,
        spinnerUqc: AutoCompleteTextView,
        etHsnDesc: TextInputEditText,
        etCessRate: TextInputEditText
    ) {
        applyingAutofill = true
        try {
            if (!unitUserSet && v.unit.isNotBlank() && !v.unit.equals("unit", true))
                etUnit.setText(v.unit, false)
            if (etHsn.text.isNullOrBlank()) v.hsn_code?.let { etHsn.setText(it) }
            if (etHsnDesc.text.isNullOrBlank()) v.hsn_description?.let { etHsnDesc.setText(it) }
            if (spinnerUqc.text.isNullOrBlank() && !v.official_uqc.isNullOrBlank())
                UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.setText(it, false) }
            if (etSCgst.text.isNullOrBlank() && v.cgst_percentage > 0)
                etSCgst.setText(trimNum(v.cgst_percentage))
            if (etSSgst.text.isNullOrBlank() && v.sgst_percentage > 0)
                etSSgst.setText(trimNum(v.sgst_percentage))
            if (etCessRate.text.isNullOrBlank() && v.cess_rate > 0)
                etCessRate.setText(trimNum(v.cess_rate))
        } finally {
            applyingAutofill = false
        }
    }

    /** Clears every field autofill may have written for a product. */
    private fun resetAutofilledFields(
        etHsn: TextInputEditText,
        etSCgst: TextInputEditText,
        etSSgst: TextInputEditText,
        etSIgst: TextInputEditText,
        etSelling: TextInputEditText,
        switchTaxInclusive: com.google.android.material.materialswitch.MaterialSwitch,
        spinnerUqc: AutoCompleteTextView,
        etHsnDesc: TextInputEditText,
        etCessRate: TextInputEditText,
        spinnerSupplyClass: AutoCompleteTextView,
        etCategory: AutoCompleteTextView,
        etUnit: AutoCompleteTextView
    ) {
        applyingAutofill = true
        unitUserSet = false
        try {
            etUnit.setText("piece", false)
            etHsn.setText("")
            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")
            etSelling.setText("")
            switchTaxInclusive.isChecked = false
            spinnerUqc.setText("", false)
            etHsnDesc.setText("")
            etCessRate.setText("")
            spinnerSupplyClass.setText("", false)
            etCategory.setText("", false)
        } finally {
            applyingAutofill = false
        }
    }

    /**
     * Merges this shop's saved variants for [nameKey] with the global
     * catalogue's variants into the Variant dropdown, so tapping the field
     * shows every variant that actually belongs to this product name.
     * Same rule as AddProductActivity.refreshVariantAdapter.
     */
    private fun refreshVariantAdapter(etVariant: AutoCompleteTextView, nameKey: String) {
        val localNames = localVariantsByName[nameKey].orEmpty()
            .mapNotNull { it.variant?.trim()?.takeIf { v -> v.isNotBlank() } }
        val globalNames = variantCache.filter { it.variant_name.isNotBlank() }.map { it.variant_name }
        val merged = LinkedHashSet<String>().apply { addAll(localNames); addAll(globalNames) }
        etVariant.setAdapter(
            ArrayAdapter(activity, R.layout.item_dropdown_ep, merged.toList())
        )
    }

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /**
     * True when the supplier is in the shop's own state, so the purchase is
     * taxed CGST + SGST rather than IGST. Until the shop's state code has
     * loaded this reports false (inter-state), matching the invoice-value
     * behaviour; the caller re-runs once the code arrives.
     */
    private fun isIntraState(invoiceState: String): Boolean {
        val invoiceStateCode = com.example.easy_billing.util.GstEngine.getStateCodeFromName(invoiceState)
        return shopStateCode.isNotBlank() &&
                invoiceStateCode != null &&
                shopStateCode == invoiceStateCode
    }

    /**
     * The single rule for a line's invoice value: intra-state adds CGST +
     * SGST, inter-state adds IGST — never both. Used by the live
     * recalculation *and* the fallback used when the field is empty, so
     * the two can't drift apart.
     */
    private fun invoiceValue(
        taxable: Double,
        cgst: Double,
        sgst: Double,
        igst: Double,
        invoiceState: String
    ): Double {
        val sameState = isIntraState(invoiceState)
        return if (sameState) {
            taxable + (taxable * cgst / 100.0) + (taxable * sgst / 100.0)
        } else {
            taxable + (taxable * igst / 100.0)
        }
    }

    private fun String.firstCapital(): String =
        trim().split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }


    /**
     * Mirrors [BaseActivity.hideSystemUI] for a dialog window. A dialog does
     * not inherit the activity's immersive state, so without this the status
     * and navigation bars reappear as soon as it takes focus.
     */
    private fun applyImmersive(window: android.view.Window?) {
        window ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    /**
     * Shows [dialog] without the system bars flashing back in. The window is
     * made non-focusable for the moment of showing (so it can't pull focus and
     * re-trigger the bars), then focus is restored so inputs still work.
     */
    private fun showImmersive(dialog: Dialog) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        dialog.window?.setFlags(flag, flag)
        applyImmersive(dialog.window)
        dialog.show()
        applyImmersive(dialog.window)
        dialog.window?.clearFlags(flag)
    }

    fun show(
        prefillName: String? = null,
        prefillVariant: String? = null,
        prefillUnit: String? = null,
        disableMeta: Boolean = false
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_purchase_line, null)

        val etProduct = view.findViewById<AutoCompleteTextView>(R.id.etProductName)
        val tilVariant = view.findViewById<View>(R.id.tilVariant)
        val etVariant = view.findViewById<AutoCompleteTextView>(R.id.etVariant)
        val etUnit = view.findViewById<AutoCompleteTextView>(R.id.etUnit)
        val etHsn = view.findViewById<TextInputEditText>(R.id.etHsn)
        val etSelling = view.findViewById<TextInputEditText>(R.id.etSellingPrice)
        val switchTaxInclusive =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTaxInclusive)
        val etQty = view.findViewById<TextInputEditText>(R.id.etQuantity)
        val etGross = view.findViewById<TextInputEditText>(R.id.etGrossAmount)
        val etDiscount = view.findViewById<TextInputEditText>(R.id.etDiscountAmount)
        val etTax = view.findViewById<TextInputEditText>(R.id.etTaxable)
        val etInv = view.findViewById<TextInputEditText>(R.id.etInvoiceValue)

        val etPCgst = view.findViewById<TextInputEditText>(R.id.etPurchaseCgst)
        val etPSgst = view.findViewById<TextInputEditText>(R.id.etPurchaseSgst)
        val etPIgst = view.findViewById<TextInputEditText>(R.id.etPurchaseIgst)
        val etSCgst = view.findViewById<TextInputEditText>(R.id.etSalesCgst)
        val etSSgst = view.findViewById<TextInputEditText>(R.id.etSalesSgst)
        val etSIgst = view.findViewById<TextInputEditText>(R.id.etSalesIgst)

        if (viewModel.isImportedGoods.value) {
            etPCgst.setText("0.0")
            etPCgst.isEnabled = false
            etPSgst.setText("0.0")
            etPSgst.isEnabled = false
        }

        // ── Supplier tax mirrors the sales rate ────────────────────────────
        // The GST rate is a property of the product, not of the direction of
        // the trade, so the supplier trio is derived from the sales trio and
        // split by the supplier's state. Editing a supplier field by hand
        // stops the derivation (composition dealer, reverse charge, imports).
        val syncPurchaseTax = syncPurchaseTax@{
            if (userOverrotePurchaseTax) return@syncPurchaseTax

            val salesBlank = etSCgst.text.isNullOrBlank() &&
                    etSSgst.text.isNullOrBlank() && etSIgst.text.isNullOrBlank()
            // Leave the supplier fields empty rather than writing a fake 0%.
            if (salesBlank) return@syncPurchaseTax

            val sCgst = etSCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sSgst = etSSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sIgst = etSIgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val total = (sCgst + sSgst).takeIf { it > 0 } ?: sIgst

            // Imported goods are always IGST — CGST/SGST stay zero and disabled.
            val intra = !viewModel.isImportedGoods.value && isIntraState(supplierState())

            settingPurchaseTax = true
            try {
                if (intra) {
                    val half = trimNum(total / 2.0)
                    if (etPCgst.text?.toString() != half) etPCgst.setText(half)
                    if (etPSgst.text?.toString() != half) etPSgst.setText(half)
                    if (etPIgst.text?.toString() != "0") etPIgst.setText("0")
                } else {
                    val whole = trimNum(total)
                    if (etPCgst.text?.toString() != "0") etPCgst.setText("0")
                    if (etPSgst.text?.toString() != "0") etPSgst.setText("0")
                    if (etPIgst.text?.toString() != whole) etPIgst.setText(whole)
                }
            } finally {
                settingPurchaseTax = false
            }
        }

        /** Clears the supplier trio and re-arms the derivation. */
        val clearPurchaseTax = {
            settingPurchaseTax = true
            try {
                if (!viewModel.isImportedGoods.value) {
                    etPCgst.setText("")
                    etPSgst.setText("")
                }
                etPIgst.setText("")
            } finally {
                settingPurchaseTax = false
                userOverrotePurchaseTax = false
            }
        }

        // A hand edit wins from then on.
        listOf(etPCgst, etPSgst, etPIgst).forEach { f ->
            f.addTextChangedListener {
                if (!settingPurchaseTax && f.isFocused) userOverrotePurchaseTax = true
            }
        }
        // Every autofill path writes the sales trio, so watching it covers them all.
        listOf(etSCgst, etSSgst, etSIgst).forEach { it.addTextChangedListener { syncPurchaseTax() } }

        val btnHelp = view.findViewById<MaterialButton>(R.id.btnHsnHelp)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnLineAdd)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnLineCancel)

        // ── GSTR-1 product master fields ──
        val spinnerUqcPurchase = view.findViewById<AutoCompleteTextView>(R.id.spinnerOfficialUqcPurchase)
        val etHsnDescPurchase = view.findViewById<TextInputEditText>(R.id.etHsnDescriptionPurchase)
        val etCessRatePurchase = view.findViewById<TextInputEditText>(R.id.etCessRatePurchase)
        val spinnerSupplyClassPurchase =
            view.findViewById<AutoCompleteTextView>(R.id.spinnerSupplyClassificationPurchase)
        val etCategoryPurchase = view.findViewById<AutoCompleteTextView>(R.id.etCategoryPurchase)

        setupMasterDropdowns(spinnerUqcPurchase, spinnerSupplyClassPurchase, etCategoryPurchase)

        // ── GSTR-2 collapsible section ──
        setupGstr2Toggle(view)

        val etCessAmountPurchase = view.findViewById<TextInputEditText>(R.id.etCessAmountPurchase)
        val spinnerEligibilityItemPurchase =
            view.findViewById<AutoCompleteTextView>(R.id.spinnerEligibilityItemPurchase)
        val etAvailedItcIgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcIgstPurchase)
        val etAvailedItcCgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcCgstPurchase)
        val etAvailedItcSgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcSgstPurchase)
        val etAvailedItcCessPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcCessPurchase)

        val eligibilityOptions = listOf("Inputs", "Capital goods", "Input services", "Ineligible", "None")
        spinnerEligibilityItemPurchase.setText("Inputs", false)
        spinnerEligibilityItemPurchase.setOnClickListener {
            showSortStylePopup(
                spinnerEligibilityItemPurchase, eligibilityOptions,
                spinnerEligibilityItemPurchase.text.toString()
            ) { picked -> spinnerEligibilityItemPurchase.setText(picked, false) }
        }

        etCessAmountPurchase.addTextChangedListener {
            if (etCessAmountPurchase.isFocused) userOverroteCess = true
        }
        etAvailedItcIgstPurchase.addTextChangedListener { if (etAvailedItcIgstPurchase.isFocused) userOverroteAvailedIgst = true }
        etAvailedItcCgstPurchase.addTextChangedListener { if (etAvailedItcCgstPurchase.isFocused) userOverroteAvailedCgst = true }
        etAvailedItcSgstPurchase.addTextChangedListener { if (etAvailedItcSgstPurchase.isFocused) userOverroteAvailedSgst = true }
        etAvailedItcCessPurchase.addTextChangedListener { if (etAvailedItcCessPurchase.isFocused) userOverroteAvailedCess = true }

        val recomputeCessAndItc = recomputeCessAndItc@{
            val taxable = etTax.text?.toString()?.toDoubleOrNull() ?: 0.0
            val cessPercent = etCessRatePurchase.text?.toString()?.toDoubleOrNull() ?: 0.0

            val computedCessAmount = if (taxable > 0) taxable * cessPercent / 100.0 else 0.0

            if (!userOverroteCess) {
                val roundedCess = "%.2f".format(computedCessAmount)
                if (etCessAmountPurchase.text?.toString() != roundedCess) {
                    etCessAmountPurchase.setText(roundedCess)
                }
            }

            val eligibility = spinnerEligibilityItemPurchase.text.toString().trim()
            val cessAmountVal = etCessAmountPurchase.text?.toString()?.toDoubleOrNull() ?: computedCessAmount

            val cgstPercent = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sgstPercent = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val igstPercent = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0

            val cgstAmt = taxable * cgstPercent / 100.0
            val sgstAmt = taxable * sgstPercent / 100.0
            val igstAmt = taxable * igstPercent / 100.0

            if (eligibility in listOf("Ineligible", "None")) {
                etAvailedItcIgstPurchase.setText("0.0")
                etAvailedItcCgstPurchase.setText("0.0")
                etAvailedItcSgstPurchase.setText("0.0")
                etAvailedItcCessPurchase.setText("0.0")

                listOf(etAvailedItcIgstPurchase, etAvailedItcCgstPurchase, etAvailedItcSgstPurchase, etAvailedItcCessPurchase).forEach {
                    it.isEnabled = false
                    (it.parent as? View)?.alpha = 0.5f
                }
            } else {
                listOf(etAvailedItcIgstPurchase, etAvailedItcCgstPurchase, etAvailedItcSgstPurchase, etAvailedItcCessPurchase).forEach {
                    it.isEnabled = true
                    (it.parent as? View)?.alpha = 1.0f
                }

                if (!userOverroteAvailedIgst) etAvailedItcIgstPurchase.setText("%.2f".format(igstAmt))
                if (!userOverroteAvailedCgst) etAvailedItcCgstPurchase.setText("%.2f".format(cgstAmt))
                if (!userOverroteAvailedSgst) etAvailedItcSgstPurchase.setText("%.2f".format(sgstAmt))
                if (!userOverroteAvailedCess) etAvailedItcCessPurchase.setText("%.2f".format(cessAmountVal))
            }
        }

        listOf(etTax, etCessRatePurchase, etPCgst, etPSgst, etPIgst).forEach {
            it.addTextChangedListener { recomputeCessAndItc() }
        }
        spinnerEligibilityItemPurchase.addTextChangedListener { recomputeCessAndItc() }

        etCessAmountPurchase.addTextChangedListener {
            val eligibility = spinnerEligibilityItemPurchase.text.toString().trim()
            if (eligibility !in listOf("Ineligible", "None") && !userOverroteAvailedCess) {
                val cessVal = etCessAmountPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
                etAvailedItcCessPurchase.setText("%.2f".format(cessVal))
            }
        }

        val setProductMasterFieldsEnabled: (Boolean) -> Unit = { enabled ->
            val fields = listOf(
                etSelling, switchTaxInclusive, etSCgst, etSSgst, etSIgst, etHsn, etUnit,
                spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase,
                spinnerSupplyClassPurchase
            )
            fields.forEach {
                it.isEnabled = enabled
                it.isFocusable = enabled
                it.isFocusableInTouchMode = enabled
                it.alpha = if (enabled) 1.0f else 0.5f
                (it.parent as? View)?.alpha = if (enabled) 1.0f else 0.6f
            }
        }

        // Undoes everything the variant match writes. Called as soon as the typed
        // text diverges from the variant we filled for, and again if the field
        // settles empty — so neither route can miss it.
        val clearVariantAutofill = {
            if (autofilledForVariant != null) {
                autofilledForVariant = null
                lastFetchedVariant = null
                lastVariantName = ""
                applyingAutofill = true
                unitUserSet = false
                try {
                    // Must undo everything the variant match writes, not just the
                    // statutory fields — otherwise the previous variant's price and
                    // category would be saved against a line that no longer names it.
                    etSelling.setText("")
                    switchTaxInclusive.isChecked = false
                    etUnit.setText("piece", false)
                    etHsn.setText("")
                    etHsnDescPurchase.setText("")
                    spinnerUqcPurchase.setText("", false)
                    etSCgst.setText("")
                    etSSgst.setText("")
                    etSIgst.setText("")
                    etCessRatePurchase.setText("")
                    spinnerSupplyClassPurchase.setText("", false)
                    etCategoryPurchase.setText("", false)
                } finally {
                    applyingAutofill = false
                }
                clearPurchaseTax()
                // A previous match may have locked these — unlock again.
                setProductMasterFieldsEnabled(true)
            }
        }

        val onVariantSettled = {
            val vName = etVariant.text.toString().trim()
            val pName = etProduct.text.toString().trim()

            if (vName.isBlank()) {
                clearVariantAutofill()
            } else if (vName != lastVariantName || pName != lastProductName) {
                lastVariantName = vName
                lastProductName = pName

                val variantKey = "$pName|$vName".lowercase()
                if (pName.isNotBlank() && variantKey != lastFetchedVariant) {
                    lastFetchedVariant = variantKey
                    variantLookup?.cancel()
                    variantLookup = activity.lifecycleScope.launch {
                        val match = withContext(Dispatchers.IO) {
                            productRepo.getByNameAndVariant(pName, vName)
                        }
                        if (match != null && match.isActive) {
                            withContext(Dispatchers.Main) {
                                applyingAutofill = true
                                etSelling.setText(match.price.toString())
                                switchTaxInclusive.isChecked = match.isTaxInclusive
                                etSCgst.setText(match.cgstPercentage.toString())
                                etSSgst.setText(match.sgstPercentage.toString())
                                etSIgst.setText(match.igstPercentage.toString())
                                etHsn.setText(match.hsnCode.orEmpty())
                                etUnit.setText(match.unit, false)
                                spinnerUqcPurchase.setText(UqcMapper.codeToDisplay(match.officialUqc) ?: "", false)
                                etHsnDescPurchase.setText(match.hsnDescription ?: "")
                                etCessRatePurchase.setText(match.cessRate.toString())
                                spinnerSupplyClassPurchase.setText(match.supplyClassification, false)
                                etCategoryPurchase.setText(match.category, false)
                                applyingAutofill = false
                                autofilledForVariant = vName

                                setProductMasterFieldsEnabled(false)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                spinnerSupplyClassPurchase.setText("TAXABLE", false)
                                setProductMasterFieldsEnabled(true)
                                // Not in this shop yet — fall back to the
                                // global catalog entry for the chosen variant.
                                val fallback = CatalogAutofill.variantNamed(variantCache, vName)
                                if (fallback != null) {
                                    applyStatutoryFrom(
                                        fallback, etHsn, etSCgst, etSSgst, etUnit,
                                        spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase
                                    )
                                    autofilledForVariant = vName
                                } else if (variantCache.isEmpty()) {
                                    // Catalog never loaded (offline) — nothing was
                                    // applied, so let a later settle try again.
                                    lastFetchedVariant = null
                                }
                            }
                        }
                    }
                } else if (pName.isBlank()) {
                    setProductMasterFieldsEnabled(true)
                }
            }
        }

        // Reveal variant + autofill when the user picks / settles on a product name.
        val onProductSettled = {
            val name = etProduct.text?.toString()?.trim().orEmpty()

            if (name != lastProductName) {
                lastProductName = name
                lastVariantName = ""
                autofilledForVariant = null
                lastFetchedVariant = null
                etVariant.setText("")
                variantCache = emptyList()

                // Clear EVERY autofilled field, so nothing from the
                // previous product leaks into this one.
                resetAutofilledFields(
                    etHsn, etSCgst, etSSgst, etSIgst, etSelling, switchTaxInclusive,
                    spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase,
                    spinnerSupplyClassPurchase, etCategoryPurchase, etUnit
                )
                clearPurchaseTax()
                setProductMasterFieldsEnabled(true)
            }

            if (name.isNotBlank()) {
                com.example.easy_billing.util.FloatingLabels.setFieldVisible(tilVariant, true)

                // Same product as last time? Nothing new to learn, and re-running
                // the fill here would resurrect fields the user just cleared.
                val fetchKey = name.lowercase()
                if (fetchKey != lastFetchedProduct) {
                lastFetchedProduct = fetchKey

                productLookup?.cancel()
                productLookup = activity.lifecycleScope.launch {
                    // One catalog call returns every variant plus the full
                    // statutory payload — no separate HSN lookup needed.
                    val fetched = CatalogAutofill.fetchVariants(activity, name)
                    if (fetched == null) lastFetchedProduct = null   // offline — allow a retry
                    val variants = fetched ?: emptyList()
                    val history = withContext(Dispatchers.IO) {
                        productRepo.autoFillFromHistory(name = name)
                    }
                    variantCache = variants

                    val named = CatalogAutofill.namedVariants(variants)
                    refreshVariantAdapter(etVariant, name.trim().lowercase())

                    // Only safe to auto-apply when there's nothing to choose from.
                    CatalogAutofill.productLevelDefault(variants)?.let { v ->
                        applyStatutoryFrom(
                            v, etHsn, etSCgst, etSSgst, etUnit,
                            spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase
                        )
                    }

                    // Local purchase history fills anything the catalog didn't.
                    if (named.isEmpty()) {
                        history?.let { match ->
                            applyingAutofill = true
                            try {
                                if (etHsn.text.isNullOrBlank() && !match.hsnCode.isNullOrBlank())
                                    etHsn.setText(match.hsnCode)
                                if (etSCgst.text.isNullOrBlank() && match.cgstPercentage > 0)
                                    etSCgst.setText(match.cgstPercentage.toString())
                                if (etSSgst.text.isNullOrBlank() && match.sgstPercentage > 0)
                                    etSSgst.setText(match.sgstPercentage.toString())
                                if (etSIgst.text.isNullOrBlank() && match.igstPercentage > 0)
                                    etSIgst.setText(match.igstPercentage.toString())
                            } finally {
                                applyingAutofill = false
                            }
                        }
                    }
                }
                }
            }
        }

        // Tapping the field shows the list, same as Add Product.
        etVariant.setOnClickListener { etVariant.showDropDown() }
        etVariant.setOnItemClickListener { _, _, _, _ -> onVariantSettled() }
        etVariant.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onVariantSettled() }

        // Clearing the variant means no specific variant is chosen any more, so
        // wipe the statutory values that were filled for the previous one —
        // otherwise stale HSN/UQC/GST would be saved against a blank variant.
        // Mirrors AddProductActivity.resetAutofilledFields().
        // The instant the variant text stops matching the one we filled for —
        // a single backspace ("1ltr" -> "1lt"), an edit, or clearing it — the
        // autofilled values no longer describe what's typed, so undo them.
        // Guarded by autofilledForVariant, so a line being filled by hand
        // (variant left blank or typed fresh) is never wiped.
        etVariant.addTextChangedListener {
            if (applyingAutofill) return@addTextChangedListener
            val typed = etVariant.text?.toString()?.trim().orEmpty()
            if (autofilledForVariant != null && !typed.equals(autofilledForVariant, true)) {
                clearVariantAutofill()
            }
        }

        // Product autocomplete — this shop's own products plus the global
        // catalogue, so suggestions match the Add Product screen.
        activity.lifecycleScope.launch {
            val names = LinkedHashSet<String>()
            // Same source as Add Product: this shop's products (which also
            // gives us their variants) plus the global catalogue.
            runCatching {
                withContext(Dispatchers.IO) { productRepo.getAllForCurrentShop() }
            }.getOrNull()?.let { locals ->
                for (prod in locals) {
                    localVariantsByName
                        .getOrPut(prod.name.trim().lowercase()) { mutableListOf() }
                        .add(prod)
                    names.add(prod.name)
                }
            }

            runCatching {
                val token = activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                    .getString("TOKEN", null)
                if (!token.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        com.example.easy_billing.network.RetrofitClient.api.getCatalog(token)
                    }.forEach { names.add(it.name) }
                }
            }   // best-effort: offline just means local-only suggestions

            etProduct.setAdapter(
                ArrayAdapter(activity, R.layout.item_dropdown_ep, names.toList())
            )
            // The local map may have arrived after a name was already typed —
            // refresh the variant list so its own variants show up.
            etProduct.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                refreshVariantAdapter(etVariant, it.lowercase())
            }

            if (prefillName != null) {
                etProduct.setText(prefillName)
                if (disableMeta) {
                    etProduct.isEnabled = false
                    etProduct.isFocusable = false
                    etProduct.isFocusableInTouchMode = false
                    etProduct.setOnClickListener(null)
                }
                onProductSettled()

                if (prefillVariant != null) {
                    etVariant.setText(prefillVariant)
                    if (disableMeta) {
                        etVariant.isEnabled = false
                        etVariant.isFocusable = false
                        etVariant.isFocusableInTouchMode = false
                        etVariant.setOnClickListener(null)
                    }
                    onVariantSettled()
                }
            }
        }

        setupUnitDropdown(etUnit)

        btnHelp.setOnClickListener { HsnHelpLauncher.open(activity) }

        etProduct.setOnItemClickListener { _, _, _, _ -> onProductSettled() }
        etProduct.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onProductSettled() }

        // HSN lookup — only reacts to what the *user* types. Autofill's own
        // writes are ignored, so a variant's real rates are never clobbered.
        etHsn.addTextChangedListener { editable ->
            if (applyingAutofill) return@addTextChangedListener
            val hsn = editable?.toString()?.trim().orEmpty()

            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")

            if (hsn.length < 4) return@addTextChangedListener
            activity.lifecycleScope.launch {
                val match = withContext(Dispatchers.IO) {
                    productRepo.autoFillFromHistory(hsn = hsn)
                } ?: return@launch   // no history for this HSN — leave the rates to the user
                withContext(Dispatchers.Main) {
                    if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                    if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                    if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                }
            }
        }

        // A plain themed Dialog, not an AlertDialog: the theme is
        // non-floating, so the window is full-screen from the moment it is
        // created. Resizing an AlertDialog after show() cost a frame and
        // showed as a blink.
        val dialog = Dialog(activity, R.style.PurchaseLineFullScreen)
        dialog.setContentView(view)

        // Field names float up out of the box once it has content.
        com.example.easy_billing.util.FloatingLabels.bind(view)

        // Usual toolbar back arrow (BaseActivity.setupToolbar can't be used
        // here — this is a Dialog, not an Activity — so wire it directly).
        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { dialog.dismiss() }
        }

        // ===== Live invoice-value auto-calc =====
        val invoiceState = supplierState()
        activity.lifecycleScope.launch {
            shopStateCode = withContext(Dispatchers.IO) {
                val db = com.example.easy_billing.db.AppDatabase.getDatabase(activity)
                val gst = db.gstProfileDao().get()
                val store = db.storeInfoDao().get()
                gst?.stateCode?.takeIf { it.isNotBlank() }
                    ?: com.example.easy_billing.util.GstEngine.getStateCode(store?.gstin)
            }
            // The intra/inter-state split depends on this, so redo the
            // supplier-tax derivation now that the code is known.
            syncPurchaseTax()
        }

        etTax.addTextChangedListener { if (etTax.isFocused) userOverroteTaxable = true }

        val recomputeTaxable = recomputeTaxable@{
            if (userOverroteTaxable) return@recomputeTaxable
            val gross = etGross.text?.toString()?.toDoubleOrNull()
            val discount = etDiscount.text?.toString()?.toDoubleOrNull() ?: 0.0

            if (gross != null) {
                val taxable = gross - discount
                val rounded = "%.2f".format(taxable)
                if (etTax.text?.toString() != rounded) etTax.setText(rounded)
            }
        }

        listOf(etGross, etDiscount).forEach { it.addTextChangedListener { recomputeTaxable() } }

        etInv.addTextChangedListener { if (etInv.isFocused) userOverroteInvoice = true }

        val invoiceValueFor = { taxable: Double ->
            invoiceValue(
                taxable = taxable,
                cgst = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                sgst = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                igst = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                invoiceState = invoiceState
            )
        }

        val recomputeInvoice = recomputeInvoice@{
            if (userOverroteInvoice) return@recomputeInvoice
            val taxable = etTax.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (taxable <= 0) return@recomputeInvoice
            val rounded = "%.2f".format(invoiceValueFor(taxable))
            if (etInv.text?.toString() != rounded) etInv.setText(rounded)
        }
        listOf(etTax, etPCgst, etPSgst, etPIgst).forEach { it.addTextChangedListener { recomputeInvoice() } }

        // Enable "Add" only when product, quantity, taxable and selling are populated.
        val recompute = {
            btnAdd.isEnabled = !etProduct.text.isNullOrBlank() &&
                    (etQty.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0 &&
                    (etTax.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0 &&
                    (etSelling.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0
        }
        listOf(etProduct, etQty, etTax, etSelling).forEach { it.addTextChangedListener { recompute() } }
        recompute()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnAdd.setOnClickListener {
            val name = etProduct.text?.toString()?.trim().orEmpty()
            val qty = etQty.text?.toString()?.toDoubleOrNull() ?: 0.0
            val taxable = etTax.text?.toString()?.toDoubleOrNull() ?: 0.0
            val selling = etSelling.text?.toString()?.toDoubleOrNull() ?: 0.0
            val invoice = etInv.text?.toString()?.toDoubleOrNull() ?: invoiceValueFor(taxable)

            if (name.isEmpty() || qty <= 0 || taxable <= 0 || selling <= 0) {
                Toast.makeText(activity,
                    "Fill product, quantity, selling price and taxable",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cessPercent = etCessRatePurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val cessAmt = etCessAmountPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val eligibility = spinnerEligibilityItemPurchase.text?.toString()?.trim().orEmpty()
            val availedIgst = etAvailedItcIgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedCgst = etAvailedItcCgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedSgst = etAvailedItcSgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedCess = etAvailedItcCessPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val officialUqc = UqcMapper.displayToCode(spinnerUqcPurchase.text?.toString())

            val purchaseCgstAmt = taxable * (etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0
            val purchaseSgstAmt = taxable * (etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0
            val purchaseIgstAmt = taxable * (etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0

            if (cessPercent < 0 || cessAmt < 0 || availedIgst < 0 || availedCgst < 0 || availedSgst < 0 || availedCess < 0) {
                Toast.makeText(activity, "Negative GSTR-2 values are not allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (eligibility.isEmpty()) {
                Toast.makeText(activity, "Eligibility for ITC is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (officialUqc.isNullOrBlank()) {
                Toast.makeText(activity, "Official UQC (GST Unit) is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val eps = 0.011
            if (availedIgst > purchaseIgstAmt + eps) {
                Toast.makeText(activity, "Availed ITC IGST cannot exceed purchase IGST amount (${"%.2f".format(purchaseIgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedCgst > purchaseCgstAmt + eps) {
                Toast.makeText(activity, "Availed ITC CGST cannot exceed purchase CGST amount (${"%.2f".format(purchaseCgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedSgst > purchaseSgstAmt + eps) {
                Toast.makeText(activity, "Availed ITC SGST cannot exceed purchase SGST amount (${"%.2f".format(purchaseSgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedCess > cessAmt + eps) {
                Toast.makeText(activity, "Availed ITC Cess cannot exceed cess amount (${"%.2f".format(cessAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (eligibility in listOf("Ineligible", "None")) {
                if (availedIgst > 0.01 || availedCgst > 0.01 || availedSgst > 0.01 || availedCess > 0.01) {
                    Toast.makeText(activity, "Availed ITC must be 0 when Ineligible or None", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val finalHsnDesc = etHsnDescPurchase.text?.toString()?.trim().orEmpty()
            val variant = etVariant.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            val draft = PurchaseItemDraft(
                productName = name.firstCapital(),
                variant = variant?.firstCapital(),
                hsnCode = etHsn.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                unit = etUnit.text?.toString()?.trim()?.ifBlank { null },
                quantity = qty,
                taxableAmount = taxable,
                discountAmount = etDiscount.text?.toString()?.toDoubleOrNull() ?: 0.0,
                invoiceValue = invoice,
                costPrice = if (qty > 0) invoice / qty else 0.0,
                sellingPrice = selling,
                isTaxInclusive = switchTaxInclusive.isChecked,
                purchaseCgst = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                purchaseSgst = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                purchaseIgst = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesCgst = etSCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesSgst = etSSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesIgst = etSIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                officialUqc = officialUqc,
                hsnDescription = finalHsnDesc,
                cessRate = cessPercent,
                cessPercentage = cessPercent,
                cessAmount = cessAmt,
                eligibilityForItc = eligibility,
                availedItcIgst = availedIgst,
                availedItcCgst = availedCgst,
                availedItcSgst = availedSgst,
                availedItcCess = availedCess,
                supplyClassification = spinnerSupplyClassPurchase.text?.toString()?.trim()?.ifBlank { "TAXABLE" } ?: "TAXABLE",
                category = etCategoryPurchase.text?.toString()?.trim().orEmpty()
            )

            rememberCustomCategory(etCategoryPurchase.text?.toString()?.trim().orEmpty())
            resolveExistingProductAndAdd(draft, qty, dialog)
        }

        showImmersive(dialog)
    }

    /* ---------------- setup helpers ---------------- */

    private fun setupMasterDropdowns(
        spinnerUqc: AutoCompleteTextView,
        spinnerSupplyClass: AutoCompleteTextView,
        etCategory: AutoCompleteTextView
    ) {
        val supplyClasses = listOf("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST")

        spinnerUqc.setOnClickListener {
            showSortStylePopup(spinnerUqc, UqcMapper.ALL_UQC_DISPLAY, spinnerUqc.text.toString()) { picked ->
                spinnerUqc.setText(picked, false)
            }
        }
        spinnerSupplyClass.setOnClickListener {
            showSortStylePopup(spinnerSupplyClass, supplyClasses, spinnerSupplyClass.text.toString()) { picked ->
                spinnerSupplyClass.setText(picked, false)
            }
        }

        // Categories load asynchronously — keep them for the picker.
        activity.lifecycleScope.launch {
            categoryOptions = com.example.easy_billing.util.ProductCategories.dropdownFor(activity, shopId())
        }
        etCategory.setOnClickListener {
            showSortStylePopup(etCategory, categoryOptions, etCategory.text.toString()) { picked ->
                etCategory.setText(picked, false)
            }
        }
    }

    private fun setupGstr2Toggle(view: View) {
        val header = view.findViewById<LinearLayout>(R.id.llGstr2HeaderToggle)
        val arrow = view.findViewById<ImageView>(R.id.ivGstr2ToggleArrow)
        val details = view.findViewById<LinearLayout>(R.id.llGstr2ItemDetails)

        header.setOnClickListener {
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE
                arrow.rotation = 0f
            } else {
                details.visibility = View.VISIBLE
                arrow.rotation = 180f
            }
        }
    }

    /** Unit dropdown — backend list first, defaults when offline. */
    private fun setupUnitDropdown(etUnit: AutoCompleteTextView) {
        etUnit.setText("piece", false)
        etUnit.setOnClickListener {
            showSortStylePopup(etUnit, unitOptions, etUnit.text.toString()) { picked ->
                unitUserSet = true
                etUnit.setText(picked, false)
            }
        }

        activity.lifecycleScope.launch {
            val token = activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            val backendUnits = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.easy_billing.network.RetrofitClient.api.getUnits(token).units
                }.getOrNull()
            }
            val merged = ((backendUnits ?: emptyList()) + unitOptions).distinct()
            if (merged.isNotEmpty()) unitOptions = merged
        }
    }

    /* ---------------- Picker popup — same visual as
       AddProductActivity.showSortStylePopup() / PurchaseActivity. ---------------- */

    private fun showSortStylePopup(
        anchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        if (options.isEmpty()) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val green = android.graphics.Color.parseColor("#0F6E56")
        val ink = android.graphics.Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.googlesans_medium)
        val currentIndex = options.indexOf(current).coerceAtLeast(-1)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(container) }

        // Fit the sheet to the room actually available, and flip it above the
        // field when there isn't enough space below (otherwise a field low on
        // the screen gets clipped by the action buttons).
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val windowH = anchor.rootView.height
        val gap = dp(6)
        val margin = dp(12)
        val spaceBelow = windowH - (loc[1] + anchor.height) - gap - margin
        val spaceAbove = loc[1] - gap - margin
        val wanted = minOf(options.size * dp(44) + dp(10), dp(320))
        val showAbove = spaceBelow < wanted && spaceAbove > spaceBelow
        val available = (if (showAbove) spaceAbove else spaceBelow).coerceAtLeast(dp(88))
        val height = minOf(wanted, available)

        val popup = android.widget.PopupWindow(scroll, dp(200), height, true).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEachIndexed { i, label ->
            val isSel = i == currentIndex
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(activity).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) green else ink)
                layoutParams = LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(tv)
            if (isSel) {
                row.addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(green)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                onPick(label)
                popup.dismiss()
            }
            container.addView(row)
        }

        if (showAbove) {
            // Negative offset lifts the sheet so its bottom sits above the field.
            popup.showAsDropDown(anchor, 0, -(anchor.height + height + gap))
        } else {
            popup.showAsDropDown(anchor, 0, gap)
        }
    }

    /* ---------------- add-time helpers ---------------- */

    /** Remember a brand-new custom category for future dropdowns. */
    private fun rememberCustomCategory(catName: String) {
        if (catName.isEmpty()) return
        val categories = com.example.easy_billing.util.ProductCategories
        if (categories.PREDEFINED.any { it.equals(catName, true) }) return
        if (catName.equals(categories.UNCATEGORIZED, true)) return

        activity.lifecycleScope.launch {
            val db = com.example.easy_billing.db.AppDatabase.getDatabase(activity)
            val shopIdStr = shopId()
            if (db.productCategoryDao().getByName(catName, shopIdStr) == null) {
                db.productCategoryDao().insertIgnore(
                    com.example.easy_billing.db.ProductCategory(shopId = shopIdStr, name = catName)
                )
            }
        }
    }

    /**
     * Checks for an existing product with the same name+variant before
     * adding the line: active manual → blocked, active purchased → added,
     * inactive → restore prompt.
     */
    private fun resolveExistingProductAndAdd(
        draft: PurchaseItemDraft,
        qty: Double,
        dialog: Dialog
    ) {
        activity.lifecycleScope.launch {
            val db = com.example.easy_billing.db.AppDatabase.getDatabase(activity)
            val validShopIds = productRepo.getValidShopIds()

            val existingMatch = withContext(Dispatchers.IO) {
                db.productDao().getByNameAndVariant(draft.productName, draft.variant, validShopIds)
            }

            withContext(Dispatchers.Main) {
                if (existingMatch != null) {
                    if (existingMatch.isActive) {
                        if (!existingMatch.isPurchased) {
                            Toast.makeText(
                                activity,
                                "${existingMatch.name} is a MANUAL product. Deactivate it first before purchasing.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            viewModel.addLine(draft)
                            dialog.dismiss()
                        }
                    } else {
                        showRestoreDialog(existingMatch, qty, draft, dialog)
                    }
                } else {
                    viewModel.addLine(draft)
                    dialog.dismiss()
                }
            }
        }
    }

    /**
     * Shown when the added line's product name+variant matches a
     * previously deactivated shop_product row.
     */
    private fun showRestoreDialog(
        inactive: Product,
        qty: Double,
        draft: PurchaseItemDraft,
        lineDlg: Dialog
    ) {
        val customView = activity.layoutInflater.inflate(R.layout.dialog_product_exists, null)
        val restoreDialog = AlertDialog.Builder(activity).setView(customView).create()
        restoreDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvMessage = customView.findViewById<TextView>(R.id.tvMessage)
        val tvDetails = customView.findViewById<TextView>(R.id.tvDetails)
        val btnCancel = customView.findViewById<Button>(R.id.btnCancel)
        val btnRestore = customView.findViewById<Button>(R.id.btnUpdate)   // blue = Restore
        val btnNew = customView.findViewById<Button>(R.id.btnReplace)      // red  = Create New

        if (inactive.isPurchased) {
            btnNew.visibility = View.VISIBLE
            btnNew.text = "Restore Old"
            btnRestore.text = "Restore with New Values"
        } else {
            btnNew.visibility = View.GONE
            btnRestore.text = "Restore"
        }

        val productLabel = inactive.name +
                (inactive.variant?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

        tvMessage.text = "$productLabel was previously deactivated.\n" +
                "Restore it to proceed with the purchase?"

        tvDetails.text = buildString {
            append("Last price: ₹${inactive.price}\n")
            if (!inactive.hsnCode.isNullOrBlank()) append("HSN: ${inactive.hsnCode}\n")
            if (inactive.cgstPercentage > 0 || inactive.sgstPercentage > 0)
                append("CGST: ${inactive.cgstPercentage}%  SGST: ${inactive.sgstPercentage}%\n")
            if (inactive.igstPercentage > 0)
                append("IGST: ${inactive.igstPercentage}%\n")
            append("Unit: ${inactive.unit ?: "piece"}")
        }

        btnRestore.setOnClickListener {
            viewModel.addLine(draft)   // forceCreate=false → upsert reactivates
            restoreDialog.dismiss()
            lineDlg.dismiss()
        }

        btnNew.setOnClickListener {
            val oldValuesDraft = draft.copy(
                sellingPrice = inactive.price,
                hsnCode = inactive.hsnCode,
                salesCgst = inactive.cgstPercentage,
                salesSgst = inactive.sgstPercentage,
                salesIgst = inactive.igstPercentage,
                officialUqc = inactive.officialUqc,
                hsnDescription = inactive.hsnDescription,
                cessRate = inactive.cessRate,
                supplyClassification = inactive.supplyClassification,
                category = inactive.category
            )
            viewModel.addLine(oldValuesDraft)
            restoreDialog.dismiss()
            lineDlg.dismiss()
        }

        btnCancel.setOnClickListener { restoreDialog.dismiss() }

        showImmersive(restoreDialog)
    }

    private fun shopId(): String {
        val prefs = activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
        return try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            prefs.getInt("SHOP_ID", 0).toString()
        }
    }
}
