package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.repository.ProductVerificationRepository
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft
import com.example.easy_billing.util.HsnHelpLauncher
import com.example.easy_billing.util.InvoiceDatePicker
import com.example.easy_billing.util.UqcMapper
import com.example.easy_billing.viewmodel.PurchaseViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import com.example.easy_billing.db.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture a purchase invoice — header + N line items.
 *
 * Each line carries both the supplier-side tax (what we paid) and
 * the sales-side tax (what we'll charge later). On save, the
 * repository upserts the products with sales tax (and marks them
 * `isPurchased = true`), inserts the invoice + items, and updates
 * inventory in a single transaction.
 *
 * UX rules enforced here:
 *   • The "Add product" + "Save purchase" buttons stay disabled
 *     until the invoice number, supplier name and state fields are
 *     filled — see [recomputeHeaderValid].
 *   • The line dialog reveals the variant dropdown only after a
 *     product is selected, and pre-fills HSN + sales tax via the
 *     global verification endpoints + local autofill.
 */
class PurchaseActivity : BaseActivity() {

    private val viewModel: PurchaseViewModel by viewModels()
    private lateinit var adapter: PurchaseLinesAdapter

    private lateinit var etInvoiceNumber: TextInputEditText
    private lateinit var etInvoiceDate: TextInputEditText
    /**
     * Returns the currently-selected invoice date (epoch millis at
     * UTC midnight) or null if the user hasn't picked one yet.
     * Populated by [InvoiceDatePicker.bind] in onCreate.
     */
    private var invoiceDateProvider: () -> Long? = { null }
    private lateinit var etSupplierName: TextInputEditText
    private lateinit var etSupplierGstin: TextInputEditText
    private lateinit var etState: AutoCompleteTextView
    private lateinit var btnPickSupplier: ImageView

    /**
     * In-flight GSTIN lookup. Cancelled when a newer one starts, so a slower
     * response can never land on top of a newer one — the same rule
     * PurchaseLineDialog uses for its product/variant lookups.
     */
    private var supplierGstinLookup: kotlinx.coroutines.Job? = null

    /**
     * Set once the user edits an Availed-ITC field by hand. The auto-fill
     * then leaves that field alone — otherwise adding another line item
     * silently overwrote whatever they had typed.
     */
    private var itcIntegratedUserSet = false
    private var itcCentralUserSet = false
    private var itcStateUserSet = false
    private var itcCessUserSet = false

    /** True while the ITC auto-fill writes, so its own writes aren't edits. */
    private var settingItc = false

    /**
     * Set once the user types their own Cess Paid figure. The per-line
     * total then stops overwriting it — a supplier invoice can legitimately
     * state a header cess that doesn't equal the sum of the lines.
     */
    private var cessPaidUserSet = false

    /** True while the cess auto-fill writes, so its own write isn't an edit. */
    private var settingCessPaid = false
    private lateinit var rv: RecyclerView
    private lateinit var btnAddLine: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var tvTaxableTotal: TextView
    private lateinit var tvInvoiceTotal: TextView

    // Credit Integration
    private lateinit var rgCreditOption: android.widget.RadioGroup
    private lateinit var rbCredit: android.widget.RadioButton
    private lateinit var rbNotCredit: android.widget.RadioButton
    private lateinit var cardSelectedAccount: com.google.android.material.card.MaterialCardView
    private lateinit var tvSelectedAccountName: TextView
    private lateinit var btnChangeAccount: MaterialButton
    private lateinit var btnClearAccount: MaterialButton

    // GSTR-2 ITC Details
    private lateinit var etPlaceOfSupplyCode: AutoCompleteTextView
    private lateinit var switchReverseCharge: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var etInvoiceType: AutoCompleteTextView
    private lateinit var etSupplyType: AutoCompleteTextView
    private lateinit var etCessPaid: TextInputEditText
    private lateinit var etEligibilityForItc: AutoCompleteTextView
    private lateinit var etAvailedItcIntegrated: TextInputEditText
    private lateinit var etAvailedItcCentral: TextInputEditText
    private lateinit var etAvailedItcState: TextInputEditText
    private lateinit var etAvailedItcCess: TextInputEditText
    private lateinit var tilAvailedItcIntegrated: View
    private lateinit var tilAvailedItcCentral: View
    private lateinit var tilAvailedItcState: View
    private lateinit var tilAvailedItcCess: View
    private var shopStateCode: String = ""

    // Imported Goods
    private lateinit var switchImportedGoods: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var layoutImportedGoods: LinearLayout
    private lateinit var etPortCode: TextInputEditText
    private lateinit var etBillOfEntryNumber: TextInputEditText
    private lateinit var etBillOfEntryDate: TextInputEditText
    private lateinit var etBillOfEntryValue: TextInputEditText
    private lateinit var tilSezSupplierGstin: View
    private lateinit var etSezSupplierGstin: TextInputEditText
    private var boeDateProvider: () -> Long? = { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = getString(R.string.purchase_title)

        bindViews()
        setupRecycler()
        wireActions()
        observe()
        recomputeHeaderValid()
        setupGstrToggle()

        fetchShopStateCode()
        handlePrefill()
    }

    /**
     * The GST compliance block (Place of Supply, Reverse Charge, Invoice
     * Type, Supply Type, Eligibility, Availed ITC) already has sensible
     * defaults set on open — most purchases never need to touch it. It
     * starts collapsed behind a single row and expands on tap; nothing
     * about validation or the fields themselves changes, they're just
     * hidden until the user wants them.
     */
    private fun setupGstrToggle() {
        val header = findViewById<View>(R.id.headerGstrToggle)
        val group = findViewById<View>(R.id.groupGstrDetails)
        val chevron = findViewById<android.widget.ImageView>(R.id.ivGstrChevron)
        header.setOnClickListener {
            val expand = group.visibility != View.VISIBLE
            group.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.rotation = if (expand) 180f else 0f
        }
    }

    private fun handlePrefill() {
        val inv = intent.getStringExtra("EXTRA_INVOICE_NUMBER")
        val sup = intent.getStringExtra("EXTRA_SUPPLIER_NAME")
        val gst = intent.getStringExtra("EXTRA_SUPPLIER_GSTIN")
        val st = intent.getStringExtra("EXTRA_STATE")
        // Coming from the Inventory → "Add Purchased Stock" dialog —
        // the user has already picked the invoice date there. Re-bind
        // the picker so the field renders the chosen day and the
        // submit-time provider returns it.
        val invoiceDateExtra = intent
            .getLongExtra("EXTRA_INVOICE_DATE", -1L)
            .takeIf { it > 0L }
        val singleMode = intent.getBooleanExtra("EXTRA_SINGLE_MODE", false)

        if (inv != null) etInvoiceNumber.setText(inv)
        if (sup != null) etSupplierName.setText(sup)
        if (gst != null) etSupplierGstin.setText(gst)
        if (st != null) etState.setText(st, false)
        if (invoiceDateExtra != null) {
            invoiceDateProvider = InvoiceDatePicker.bind(
                etInvoiceDate,
                initialMillis = invoiceDateExtra
            )
        }

        if (singleMode) {
            btnAddLine.visibility = View.GONE
            val prodName = intent.getStringExtra("EXTRA_PRODUCT_NAME")
            val variant = intent.getStringExtra("EXTRA_PRODUCT_VARIANT")
            val unit = intent.getStringExtra("EXTRA_PRODUCT_UNIT")
            if (prodName != null) {
                showLineDialog(
                    prefillName = prodName,
                    prefillVariant = variant,
                    prefillUnit = unit,
                    disableMeta = true
                )
            }
        }
    }

    private fun bindViews() {
        etInvoiceNumber = findViewById(R.id.etInvoiceNumber)
        etInvoiceDate   = findViewById(R.id.etInvoiceDate)
        // Reusable picker — opens DatePickerDialog on tap, blocks
        // soft keyboard, formats display as dd/MM/yyyy. The lambda
        // it returns is how `btnSave` pulls the picked millis.
        invoiceDateProvider = InvoiceDatePicker.bind(etInvoiceDate)
        etSupplierName  = findViewById(R.id.etSupplierName)
        etSupplierGstin = findViewById(R.id.etSupplierGstin)
        etState         = findViewById(R.id.etState)
        btnPickSupplier = findViewById(R.id.btnPickSupplier)
        rv              = findViewById(R.id.rvLines)
        btnAddLine      = findViewById(R.id.btnAddLine)
        btnSave         = findViewById(R.id.btnSavePurchase)
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { finish() }
        tvTaxableTotal  = findViewById(R.id.tvTaxableTotal)
        tvInvoiceTotal  = findViewById(R.id.tvInvoiceTotal)

        rgCreditOption = findViewById(R.id.rgCreditOption)
        rbCredit = findViewById(R.id.rbCredit)
        rbNotCredit = findViewById(R.id.rbNotCredit)
        cardSelectedAccount = findViewById(R.id.cardSelectedAccount)
        tvSelectedAccountName = findViewById(R.id.tvSelectedAccountName)
        btnChangeAccount = findViewById(R.id.btnChangeAccount)
        btnClearAccount = findViewById(R.id.btnClearAccount)

        // Bind GSTR-2 Views
        etPlaceOfSupplyCode = findViewById(R.id.etPlaceOfSupplyCode)
        switchReverseCharge = findViewById(R.id.switchReverseCharge)
        etInvoiceType = findViewById(R.id.etInvoiceType)
        etSupplyType = findViewById(R.id.etSupplyType)
        etCessPaid = findViewById(R.id.etCessPaid)
        etEligibilityForItc = findViewById(R.id.etEligibilityForItc)
        etAvailedItcIntegrated = findViewById(R.id.etAvailedItcIntegrated)
        etAvailedItcCentral = findViewById(R.id.etAvailedItcCentral)
        etAvailedItcState = findViewById(R.id.etAvailedItcState)
        etAvailedItcCess = findViewById(R.id.etAvailedItcCess)
        tilAvailedItcIntegrated = findViewById(R.id.tilAvailedItcIntegrated)
        tilAvailedItcCentral = findViewById(R.id.tilAvailedItcCentral)
        tilAvailedItcState = findViewById(R.id.tilAvailedItcState)
        tilAvailedItcCess = findViewById(R.id.tilAvailedItcCess)

        // Bind Imported Goods Views
        switchImportedGoods = findViewById(R.id.switchImportedGoods)
        layoutImportedGoods = findViewById(R.id.layoutImportedGoods)
        etPortCode = findViewById(R.id.etPortCode)
        etBillOfEntryNumber = findViewById(R.id.etBillOfEntryNumber)
        etBillOfEntryDate = findViewById(R.id.etBillOfEntryDate)
        etBillOfEntryValue = findViewById(R.id.etBillOfEntryValue)
        tilSezSupplierGstin = findViewById(R.id.tilSezSupplierGstin)
        // Field names float up out of the box once it has content.
        com.example.easy_billing.util.FloatingLabels.bind(findViewById(android.R.id.content))
        etSezSupplierGstin = findViewById(R.id.etSezSupplierGstin)

        boeDateProvider = InvoiceDatePicker.bind(etBillOfEntryDate)

        etCessPaid.setText("0.0")

        setupStateSuggestions()
        setupSupplierAutofill()
        setupItcOverrideWatchers()
        setupGstr2Dropdowns()
    }

    /* ------------------------------------------------------------------
     *  Supplier selection
     *
     *  GSTIN is the supplier's identity — it is government-issued, unique,
     *  and its first two characters *are* the state code. Name is only a
     *  label: two branches of "Raj Traders" in different states are two
     *  different suppliers, and the same supplier gets typed three ways.
     *
     *  So the name is never typed here. Tapping it opens the picker sheet,
     *  where a supplier is either selected or added — and name, GSTIN and
     *  state then always arrive as one consistent set. Picking the wrong
     *  state puts the wrong tax on the invoice (CGST+SGST vs IGST), which
     *  is exactly what free-typed names used to risk.
     * ------------------------------------------------------------------ */
    private fun setupSupplierAutofill() {

        // The supplier is chosen, never typed. Free text lets two spellings
        // of one supplier drift apart, and leaves the GSTIN and state beside
        // it describing somebody else — so the field opens the picker sheet
        // instead of the keyboard.
        etSupplierName.isFocusable = false
        etSupplierName.isFocusableInTouchMode = false
        etSupplierName.isCursorVisible = false
        etSupplierName.inputType = android.text.InputType.TYPE_NULL
        etSupplierName.setOnClickListener { openSupplierPicker() }
        btnPickSupplier.setOnClickListener { openSupplierPicker() }

        // A complete GSTIN identifies the supplier outright, so it can fill
        // the name too — and the state always comes from the GSTIN itself.
        etSupplierGstin.addTextChangedListener { etSupplierGstin.error = null }
        etSupplierGstin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) lookupSupplierByGstin()
        }
    }

    /** Select an existing supplier, or add one, from the champagne sheet. */
    private fun openSupplierPicker() {
        com.example.easy_billing.util.SupplierPicker.show(this) { supplier ->
            applySupplier(supplier.name, supplier.gstin, supplier.state)
        }
    }

    /**
     * Marks an Availed-ITC field as the user's own the moment they type in
     * it. [settingItc] keeps the auto-fill's own writes from counting.
     */
    /**
     * Header "Cess Paid" defaults to the sum of the lines' cess.
     *
     * Each line captures its own cess amount, but a line's invoice value is
     * only taxable + GST — cess is not in it (see PurchaseLineDialog
     * .invoiceValue). The header field is what carries cess into the invoice
     * total, so leaving it at 0 understated the total by exactly the cess,
     * and made the "Availed ITC Cess cannot exceed Cess Paid" check reject
     * a credit the user was entitled to.
     *
     * Nothing about how totals are calculated changes — this only fills in
     * the number the user would otherwise have to add up by hand. Typing
     * over it wins, permanently.
     */
    private fun syncCessPaidFromLines() {
        if (cessPaidUserSet) return
        val total = viewModel.lines.value.sumOf { it.cessAmount }
        val rounded = "%.2f".format(total)
        if (etCessPaid.text?.toString() == rounded) return
        settingCessPaid = true
        try { etCessPaid.setText(rounded) } finally { settingCessPaid = false }
    }

    private fun setupItcOverrideWatchers() {
        etAvailedItcIntegrated.addTextChangedListener { if (!settingItc) itcIntegratedUserSet = true }
        etAvailedItcCentral.addTextChangedListener { if (!settingItc) itcCentralUserSet = true }
        etAvailedItcState.addTextChangedListener { if (!settingItc) itcStateUserSet = true }
        etAvailedItcCess.addTextChangedListener { if (!settingItc) itcCessUserSet = true }
    }

    /** A full GSTIN is unambiguous — fill everything from it. */
    private fun lookupSupplierByGstin() {
        val gstin = etSupplierGstin.text?.toString()?.trim()?.uppercase().orEmpty()
        if (gstin.length != 15) return

        // The state is encoded in the GSTIN, so a mismatch with the picked
        // state is a data error worth surfacing: it decides CGST+SGST vs IGST.
        val codeState = com.example.easy_billing.util.GstEngine
            .INDIA_STATES[com.example.easy_billing.util.GstEngine.getStateCode(gstin)]

        supplierGstinLookup?.cancel()
        supplierGstinLookup = lifecycleScope.launch {
            val saved = com.example.easy_billing.repository.SupplierRepository
                .byGstin(this@PurchaseActivity, gstin)
            if (saved != null) {
                applySupplier(saved.name, saved.gstin, saved.state)
                return@launch
            }
            if (codeState == null) return@launch
            val typedState = etState.text?.toString()?.trim().orEmpty()
            if (typedState.isBlank()) {
                etState.setText(codeState, false)
            } else if (!typedState.equals(codeState, ignoreCase = true)) {
                Toast.makeText(
                    this@PurchaseActivity,
                    "GSTIN is registered in $codeState, not $typedState",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Writes a resolved supplier into the header. The state is taken from
     * the GSTIN when there is one, since that is authoritative.
     */
    private fun applySupplier(name: String, gstin: String?, state: String) {
        val resolvedState = gstin
            ?.let {
                com.example.easy_billing.util.GstEngine
                    .INDIA_STATES[com.example.easy_billing.util.GstEngine.getStateCode(it)]
            }
            ?: state

        etSupplierName.setText(name)
        etSupplierGstin.setText(gstin.orEmpty())
        // Fires the etState watcher, which sets Place of Supply and
        // re-derives intrastate / interstate.
        etState.setText(resolvedState, false)
        recomputeHeaderValid()
    }

    private fun setupStateSuggestions() {
        val states = com.example.easy_billing.util.GstEngine.INDIA_STATES.values.toList()
        // Same picker sheet as the Add Product screen.
        etState.setOnClickListener {
            showSortStylePopup(etState, states, etState.text.toString()) { picked ->
                etState.setText(picked, false)
            }
        }
    }

    /* ---------------- Picker popup — same visual as
       AddProductActivity.showSortStylePopup() / ManageProductsActivity. ---------------- */

    private fun showSortStylePopup(
        anchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val green = android.graphics.Color.parseColor("#0F6E56")
        val ink = android.graphics.Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val currentIndex = options.indexOf(current).coerceAtLeast(-1)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(this).apply {
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
                row.addView(ImageView(this).apply {
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

    private fun setupGstr2Dropdowns() {
        // All four use the same picker sheet as the Add Product screen.

        // Place of Supply Code: "code - state name"
        val stateCodesList = com.example.easy_billing.util.GstEngine.INDIA_STATES.map { "${it.key} - ${it.value}" }
        etPlaceOfSupplyCode.setOnClickListener {
            showSortStylePopup(etPlaceOfSupplyCode, stateCodesList, etPlaceOfSupplyCode.text.toString()) { picked ->
                etPlaceOfSupplyCode.setText(picked, false)
            }
        }

        // Invoice Type
        val invoiceTypes = listOf("Regular", "SEZ supplies with payment", "SEZ supplies without payment", "Deemed Exp", "From Composition Taxable Person")
        etInvoiceType.setText("Regular", false)
        etInvoiceType.setOnClickListener {
            showSortStylePopup(etInvoiceType, invoiceTypes, etInvoiceType.text.toString()) { picked ->
                etInvoiceType.setText(picked, false)
            }
        }

        // Supply Type
        val supplyTypes = listOf("intrastate", "interstate")
        etSupplyType.setText("intrastate", false)
        etSupplyType.setOnClickListener {
            showSortStylePopup(etSupplyType, supplyTypes, etSupplyType.text.toString()) { picked ->
                etSupplyType.setText(picked, false)
            }
        }

        // Eligibility For ITC
        val eligibilityTypes = listOf("Inputs", "Capital goods", "Input services", "Ineligible", "None")
        etEligibilityForItc.setText("Inputs", false)
        etEligibilityForItc.setOnClickListener {
            showSortStylePopup(etEligibilityForItc, eligibilityTypes, etEligibilityForItc.text.toString()) { picked ->
                etEligibilityForItc.setText(picked, false)
            }
        }
    }

    private fun fetchShopStateCode() {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) {
                val db = com.example.easy_billing.db.AppDatabase
                    .getDatabase(this@PurchaseActivity)
                val gst = db.gstProfileDao().get()
                val store = db.storeInfoDao().get()
                gst?.stateCode?.takeIf { it.isNotBlank() }
                    ?: com.example.easy_billing.util.GstEngine
                        .getStateCode(store?.gstin)
            }
            shopStateCode = code
            detectSupplyType()
        }
    }

    private fun detectSupplyType() {
        if (shopStateCode.isBlank()) return
        val supplierState = etState.text?.toString()?.trim().orEmpty()
        val supplierStateCode = com.example.easy_billing.util.GstEngine
            .getStateCodeFromName(supplierState) ?: ""

        val placeOfSupplyCodeText = etPlaceOfSupplyCode.text?.toString()?.trim().orEmpty()
        val placeOfSupplyCode = placeOfSupplyCodeText.split(" - ").firstOrNull()?.trim() ?: ""

        val codeToCompare = if (placeOfSupplyCode.isNotBlank()) placeOfSupplyCode else supplierStateCode

        if (codeToCompare.isNotBlank()) {
            val sameState = shopStateCode == codeToCompare
            val detectedType = if (sameState) "intrastate" else "interstate"
            etSupplyType.setText(detectedType, false)
        }
    }

    private fun updateAvailedItcValues() {
        val eligibility = etEligibilityForItc.text.toString().trim()
        val totals = computeTotals()
        val cess = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0

        settingItc = true
        try {
            if (eligibility == "Ineligible" || eligibility == "None") {
                // Statutory zero — overrides anything typed, and clears the
                // override flags so the defaults come back if the user
                // switches eligibility again.
                etAvailedItcIntegrated.setText("0.0")
                etAvailedItcCentral.setText("0.0")
                etAvailedItcState.setText("0.0")
                etAvailedItcCess.setText("0.0")
                itcIntegratedUserSet = false
                itcCentralUserSet = false
                itcStateUserSet = false
                itcCessUserSet = false

                etAvailedItcIntegrated.isEnabled = false
                etAvailedItcCentral.isEnabled = false
                etAvailedItcState.isEnabled = false
                etAvailedItcCess.isEnabled = false
            } else {
                etAvailedItcIntegrated.isEnabled = true
                etAvailedItcCentral.isEnabled = true
                etAvailedItcState.isEnabled = true
                etAvailedItcCess.isEnabled = true

                // Default to the tax actually paid — but only where the user
                // hasn't claimed a different amount themselves. Partial ITC
                // claims are normal, and this runs on every line change.
                if (!itcIntegratedUserSet) etAvailedItcIntegrated.setText(totals.igstAmt.toString())
                if (!itcCentralUserSet) etAvailedItcCentral.setText(totals.cgstAmt.toString())
                if (!itcStateUserSet) etAvailedItcState.setText(totals.sgstAmt.toString())
                if (!itcCessUserSet) etAvailedItcCess.setText(cess.toString())
            }
        } finally {
            settingItc = false
        }
    }

    private fun setupRecycler() {
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PurchaseLinesAdapter(emptyList()) { idx -> viewModel.removeLine(idx) }
        rv.adapter = adapter
    }

    private fun wireActions() {
        listOf(etInvoiceNumber, etSupplierName).forEach { input ->
            input.addTextChangedListener { recomputeHeaderValid() }
        }

        etState.addTextChangedListener {
            val supplierState = etState.text?.toString()?.trim().orEmpty()
            val code = com.example.easy_billing.util.GstEngine.getStateCodeFromName(supplierState)
            if (code != null) {
                val name = com.example.easy_billing.util.GstEngine.INDIA_STATES[code]
                if (name != null) {
                    etPlaceOfSupplyCode.setText("$code - $name", false)
                }
            } else {
                // State cleared or unrecognised — the old code described the
                // previous supplier, and leaving it behind would put that
                // state on this invoice. Save's self-heal only fires when the
                // field is empty, so it has to actually be emptied.
                etPlaceOfSupplyCode.setText("", false)
            }
            detectSupplyType()
            recomputeHeaderValid()
        }

        etPlaceOfSupplyCode.addTextChangedListener {
            detectSupplyType()
        }

        etCessPaid.addTextChangedListener {
            if (!settingCessPaid && etCessPaid.isFocused) cessPaidUserSet = true
            val totals = computeTotals()
            tvInvoiceTotal.text = "%.2f".format(totals.invoice)
            updateAvailedItcValues()
        }

        etEligibilityForItc.addTextChangedListener {
            updateAvailedItcValues()
        }

        switchImportedGoods.setOnClickListener { 
            if (viewModel.lines.value.isNotEmpty()) {
                switchImportedGoods.isChecked = !switchImportedGoods.isChecked
                Toast.makeText(this, "Imported Goods toggle can only be changed before adding items", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isChecked = switchImportedGoods.isChecked
            if (isChecked) {
                layoutImportedGoods.visibility = View.VISIBLE
                viewModel.setIsImportedGoods(true)
            } else {
                layoutImportedGoods.visibility = View.GONE
                viewModel.setIsImportedGoods(false)
            }
            recomputeHeaderValid()
        }

        etInvoiceType.addTextChangedListener {
            val type = etInvoiceType.text?.toString() ?: ""
            if (type.startsWith("SEZ")) {
                com.example.easy_billing.util.FloatingLabels.setFieldVisible(tilSezSupplierGstin, true)
            } else {
                com.example.easy_billing.util.FloatingLabels.setFieldVisible(tilSezSupplierGstin, false)
            }
        }

        btnAddLine.setOnClickListener {
            if (!isHeaderValid()) {
                Toast.makeText(
                    this, "Fill invoice number, supplier and state first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            showLineDialog()
        }

        btnSave.setOnClickListener {
            val invoice = etInvoiceNumber.text?.toString()?.trim().orEmpty()
            val supplier = etSupplierName.text?.toString()?.trim().orEmpty()
            val state = etState.text?.toString()?.trim().orEmpty()
            if (invoice.isEmpty() || supplier.isEmpty() || state.isEmpty()) {
                Toast.makeText(this, "Fill invoice header first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // A malformed GSTIN flows straight into GSTR-2, where it fails
            // at filing time instead of here. Blank stays allowed —
            // unregistered suppliers are legitimate.
            val typedGstin = etSupplierGstin.text?.toString()?.trim()?.uppercase().orEmpty()
            if (typedGstin.isNotEmpty() &&
                !com.example.easy_billing.util.GstEngine.isValidGstin(typedGstin)
            ) {
                etSupplierGstin.error = "Invalid GSTIN"
                Toast.makeText(
                    this,
                    "Supplier GSTIN doesn't look valid — check it, or leave it blank",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // The state on the invoice decides CGST+SGST vs IGST, so it must
            // agree with the state the GSTIN is registered in.
            if (typedGstin.isNotEmpty()) {
                val gstinState = com.example.easy_billing.util.GstEngine
                    .INDIA_STATES[typedGstin.substring(0, 2)]
                if (gstinState != null && !gstinState.equals(state, ignoreCase = true)) {
                    Toast.makeText(
                        this,
                        "GSTIN is registered in $gstinState but the state says $state",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
            }

            val pickedInvoiceDate = invoiceDateProvider()
            if (pickedInvoiceDate == null) {
                etInvoiceDate.error = "Pick the invoice date"
                Toast.makeText(this, "Invoice date is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }


            // GSTR-2 validation
            val placeOfSupplyCodeText = etPlaceOfSupplyCode.text?.toString()?.trim().orEmpty()
            var placeOfSupplyCode = placeOfSupplyCodeText.split(" - ").firstOrNull()?.trim() ?: ""
            // Place of Supply auto-fills from the supplier's state the
            // moment it's picked (see detectSupplyType/etState watcher
            // above) — this should already be set every time. If it's
            // somehow still blank, self-heal from the supplier state one
            // more time before bothering the user with an error, instead
            // of blocking save over a field that's normally automatic.
            if (placeOfSupplyCode.isEmpty()) {
                val fallbackCode = com.example.easy_billing.util.GstEngine.getStateCodeFromName(state)
                if (fallbackCode != null) {
                    placeOfSupplyCode = fallbackCode
                    val name = com.example.easy_billing.util.GstEngine.INDIA_STATES[fallbackCode]
                    if (name != null) etPlaceOfSupplyCode.setText("$fallbackCode - $name", false)
                }
            }
            if (placeOfSupplyCode.isEmpty()) {
                Toast.makeText(this, "Place of Supply Code is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (switchImportedGoods.isChecked) {
                val portCode = etPortCode.text?.toString()?.trim()
                if (portCode.isNullOrEmpty()) {
                    Toast.makeText(this, "Port Code is required for Imported Goods", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val boeNumber = etBillOfEntryNumber.text?.toString()?.trim()
                if (boeNumber.isNullOrEmpty()) {
                    Toast.makeText(this, "Bill of Entry Number is required for Imported Goods", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pickedBoeDate = boeDateProvider()
                if (pickedBoeDate == null) {
                    Toast.makeText(this, "Bill of Entry Date is required for Imported Goods", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val boeValue = etBillOfEntryValue.text?.toString()?.toDoubleOrNull()
                if (boeValue == null) {
                    Toast.makeText(this, "Bill of Entry Value is required for Imported Goods", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val type = etInvoiceType.text?.toString() ?: ""
                val sezGstin = if (type.startsWith("SEZ")) etSezSupplierGstin.text?.toString()?.trim() else null

                viewModel.setImportDetails(
                    com.example.easy_billing.repository.PurchaseRepository.PurchaseImportDetailsDraft(
                        portCode = portCode,
                        billOfEntryNumber = boeNumber,
                        billOfEntryDate = pickedBoeDate,
                        billOfEntryValue = boeValue,
                        documentType = "Bill of Entry",
                        sezSupplierGstin = sezGstin
                    )
                )
            } else {
                viewModel.setImportDetails(null)
            }

            val reverseCharge = if (switchReverseCharge.isChecked) "Y" else "N"
            val invoiceType = etInvoiceType.text?.toString()?.trim().orEmpty()
            if (invoiceType.isEmpty()) {
                Toast.makeText(this, "Invoice Type is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val supplyType = etSupplyType.text?.toString()?.trim().orEmpty()
            if (supplyType != "intrastate" && supplyType != "interstate") {
                Toast.makeText(this, "Supply Type must be intrastate or interstate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cessPaid = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (cessPaid < 0.0) {
                Toast.makeText(this, "Cess Paid must be >= 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val eligibility = etEligibilityForItc.text?.toString()?.trim().orEmpty()
            if (eligibility.isEmpty()) {
                Toast.makeText(this, "Eligibility For ITC is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val availedItcIntegrated = etAvailedItcIntegrated.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcCentral = etAvailedItcCentral.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcState = etAvailedItcState.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcCess = etAvailedItcCess.text?.toString()?.toDoubleOrNull() ?: 0.0

            if (availedItcIntegrated < 0.0 || availedItcCentral < 0.0 || availedItcState < 0.0 || availedItcCess < 0.0) {
                Toast.makeText(this, "Availed ITC fields must be >= 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val totals = computeTotals()
            if (availedItcIntegrated > totals.igstAmt) {
                Toast.makeText(this, "Availed ITC Integrated Tax cannot exceed IGST amount (${totals.igstAmt})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedItcCentral > totals.cgstAmt) {
                Toast.makeText(this, "Availed ITC Central Tax cannot exceed CGST amount (${totals.cgstAmt})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedItcState > totals.sgstAmt) {
                Toast.makeText(this, "Availed ITC State Tax cannot exceed SGST amount (${totals.sgstAmt})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedItcCess > cessPaid) {
                Toast.makeText(this, "Availed ITC Cess cannot exceed Cess Paid ($cessPaid)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (eligibility == "Ineligible" || eligibility == "None") {
                if (availedItcIntegrated != 0.0 || availedItcCentral != 0.0 || availedItcState != 0.0 || availedItcCess != 0.0) {
                    Toast.makeText(this, "Availed ITC fields must be 0 when ineligible/None", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // A credit purchase must name the account that owes it, else the
            // amount is recorded against nobody and never appears in payables.
            if (rbCredit.isChecked && viewModel.selectedCreditAccount.value == null) {
                Toast.makeText(this, "Select a credit account", Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account -> viewModel.selectCreditAccount(account) },
                    onDismissedWithoutSelection = {
                        if (viewModel.selectedCreditAccount.value == null) {
                            rbNotCredit.isChecked = true
                            Toast.makeText(
                                this,
                                "Credit needs an account — set to paid now",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                return@setOnClickListener
            }

            val cgstPct = if (totals.taxable > 0) totals.cgstAmt / totals.taxable * 100 else 0.0
            val sgstPct = if (totals.taxable > 0) totals.sgstAmt / totals.taxable * 100 else 0.0
            val igstPct = if (totals.taxable > 0) totals.igstAmt / totals.taxable * 100 else 0.0

            viewModel.save(
                Purchase(
                    invoiceNumber  = invoice,
                    supplierGstin  = typedGstin.takeIf { it.isNotBlank() },
                    supplierName   = supplier,
                    state          = state,
                    taxableAmount  = totals.taxable,
                    cgstPercentage = cgstPct,
                    sgstPercentage = sgstPct,
                    igstPercentage = igstPct,
                    cgstAmount     = totals.cgstAmt,
                    sgstAmount     = totals.sgstAmt,
                    igstAmount     = totals.igstAmt,
                    invoiceValue   = totals.invoice,
                    invoiceDate    = pickedInvoiceDate,
                    isCredit       = rbCredit.isChecked,
                    creditAccountId = viewModel.selectedCreditAccount.value?.id,
                    placeOfSupplyCode = placeOfSupplyCode,
                    reverseCharge  = reverseCharge,
                    invoiceType    = invoiceType,
                    supplyType     = supplyType,
                    cessPaid       = cessPaid,
                    eligibilityForItc = eligibility,
                    availedItcIntegratedTax = availedItcIntegrated,
                    availedItcCentralTax = availedItcCentral,
                    availedItcStateTax = availedItcState,
                    availedItcCess = availedItcCess,
                    purchaseSource = if (viewModel.isImportedGoods.value) "IMPORT" else "DOMESTIC"
                )
            )
        }

        rgCreditOption.setOnCheckedChangeListener { _, checkedId ->
            val credit = checkedId == R.id.rbCredit
            updatePaymentTiles(credit)
            recomputeHeaderValid()
            if (credit) {
                if (viewModel.selectedCreditAccount.value == null) {
                    com.example.easy_billing.util.CreditAccountPicker.show(
                        activity = this,
                        onAccountSelected = { account -> viewModel.selectCreditAccount(account) },
                        onDismissedWithoutSelection = {
                            // A credit purchase with no account would be owed to
                            // nobody, so fall back to "paid now" rather than
                            // leaving the screen in an unsaveable state.
                            if (viewModel.selectedCreditAccount.value == null) {
                                rbNotCredit.isChecked = true
                                Toast.makeText(
                                    this,
                                    "Credit needs an account — set to paid now",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                } else {
                    cardSelectedAccount.visibility = View.VISIBLE
                }
            } else {
                cardSelectedAccount.visibility = View.GONE
                // Don't let a cash purchase carry a leftover account id.
                viewModel.clearCreditAccount()
            }
        }

        // Card-style payment tiles drive the hidden radios.
        findViewById<View>(R.id.tilePaidNow).setOnClickListener { rbNotCredit.isChecked = true }
        findViewById<View>(R.id.tileCredit).setOnClickListener { rbCredit.isChecked = true }
        updatePaymentTiles(rbCredit.isChecked)

        btnChangeAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(
                activity = this,
                onAccountSelected = { account -> viewModel.selectCreditAccount(account) }
            )   // keeps the existing account when dismissed
        }

        btnClearAccount.setOnClickListener {
            viewModel.clearCreditAccount()
            rbNotCredit.isChecked = true
        }
    }

    /** Reflects the credit selection on the two payment tiles. */
    private fun updatePaymentTiles(credit: Boolean) {
        val tilePaid   = findViewById<View>(R.id.tilePaidNow)
        val tileCredit = findViewById<View>(R.id.tileCredit)
        val chipPaid   = findViewById<View>(R.id.chipPaidNow)
        val chipCredit = findViewById<View>(R.id.chipCredit)
        val ivPaid     = findViewById<android.widget.ImageView>(R.id.ivPaidNow)
        val ivCredit   = findViewById<android.widget.ImageView>(R.id.ivCredit)
        val checkPaid  = findViewById<View>(R.id.checkPaidNow)
        val checkCred  = findViewById<View>(R.id.checkCredit)
        val tvPaid     = findViewById<TextView>(R.id.tvPaidNow)
        val tvPaidSub  = findViewById<TextView>(R.id.tvPaidNowSub)
        val tvCredit   = findViewById<TextView>(R.id.tvCredit)
        val tvCredSub  = findViewById<TextView>(R.id.tvCreditSub)

        if (credit) {
            tilePaid.setBackgroundResource(R.drawable.bg_pay_tile_idle)
            chipPaid.setBackgroundResource(R.drawable.bg_pay_chip_idle)
            ivPaid.setColorFilter(android.graphics.Color.parseColor("#8A8272"))
            checkPaid.visibility = View.GONE
            tvPaid.setTextColor(android.graphics.Color.parseColor("#1A1A18"))
            tvPaidSub.setTextColor(android.graphics.Color.parseColor("#9A8F79"))

            tileCredit.setBackgroundResource(R.drawable.bg_pay_tile_gold_sel)
            chipCredit.setBackgroundResource(R.drawable.bg_pay_chip_gold)
            ivCredit.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
            checkCred.visibility = View.VISIBLE
            tvCredit.setTextColor(android.graphics.Color.parseColor("#7A5A32"))
            tvCredSub.setTextColor(android.graphics.Color.parseColor("#A98B63"))
        } else {
            tilePaid.setBackgroundResource(R.drawable.bg_pay_tile_green_sel)
            chipPaid.setBackgroundResource(R.drawable.bg_pay_chip_green)
            ivPaid.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
            checkPaid.visibility = View.VISIBLE
            tvPaid.setTextColor(android.graphics.Color.parseColor("#0B5544"))
            tvPaidSub.setTextColor(android.graphics.Color.parseColor("#5E8C7C"))

            tileCredit.setBackgroundResource(R.drawable.bg_pay_tile_idle)
            chipCredit.setBackgroundResource(R.drawable.bg_pay_chip_idle)
            ivCredit.setColorFilter(android.graphics.Color.parseColor("#8A8272"))
            checkCred.visibility = View.GONE
            tvCredit.setTextColor(android.graphics.Color.parseColor("#1A1A18"))
            tvCredSub.setTextColor(android.graphics.Color.parseColor("#9A8F79"))
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.lines.collect { lines ->
                        adapter.submit(lines)
                        // Before computeTotals(), which reads etCessPaid.
                        syncCessPaidFromLines()
                        val totals = computeTotals()
                        tvTaxableTotal.text = "%.2f".format(totals.taxable)
                        tvInvoiceTotal.text = "%.2f".format(totals.invoice)
                        updateAvailedItcValues()
                        recomputeHeaderValid()
                    }
                }
                launch {
                    viewModel.state.collect { state ->
                        btnSave.isEnabled = !state.loading && isHeaderValid() &&
                                viewModel.lines.value.isNotEmpty()
                        state.error?.let {
                            Toast.makeText(this@PurchaseActivity, it, Toast.LENGTH_LONG).show()
                            viewModel.clearTransient()
                        }
                        state.savedPurchaseId?.let {
                            // Purchase is committed — safe to index the
                            // supplier for next time. Best-effort: the
                            // repository swallows its own failures so this
                            // can never turn a saved purchase into an error.
                            com.example.easy_billing.repository.SupplierRepository
                                .remember(
                                    context = this@PurchaseActivity,
                                    name = etSupplierName.text?.toString()?.trim().orEmpty(),
                                    gstin = etSupplierGstin.text?.toString()?.trim(),
                                    state = etState.text?.toString()?.trim().orEmpty()
                                )
                            // Use the precise sync outcome message from the
                            // VM rather than a generic "saved" toast — this
                            // is how the user finds out whether the backend
                            // push actually worked.
                            val msg = state.message ?: "Purchase saved"
                            Toast.makeText(this@PurchaseActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
                launch {
                    viewModel.selectedCreditAccount.collect { account ->
                        if (account != null) {
                            tvSelectedAccountName.text = account.name
                            cardSelectedAccount.visibility = View.VISIBLE
                            rbCredit.isChecked = true
                        } else {
                            cardSelectedAccount.visibility = View.GONE
                        }
                        // Credit selection is part of header validity.
                        recomputeHeaderValid()
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------
     *  Header validation (gates Add-Line + Save buttons)
     * ------------------------------------------------------------------ */

    private fun isHeaderValid(): Boolean =
        etInvoiceNumber.text?.toString()?.trim().isNullOrEmpty().not() &&
        etSupplierName.text?.toString()?.trim().isNullOrEmpty().not() &&
        etState.text?.toString()?.trim().isNullOrEmpty().not() &&
        // "On credit" is only valid once an account is chosen.
        (!rbCredit.isChecked || viewModel.selectedCreditAccount.value != null)

    private fun recomputeHeaderValid() {
        val ok = isHeaderValid()
        btnAddLine.isEnabled = ok
        btnSave.isEnabled = ok && viewModel.lines.value.isNotEmpty()
    }

    /* ------------------------------------------------------------------
     *  Add-line dialog — implemented in [PurchaseLineDialog].
     * ------------------------------------------------------------------ */

    private fun showLineDialog(
        prefillName: String? = null,
        prefillVariant: String? = null,
        prefillUnit: String? = null,
        disableMeta: Boolean = false
    ) {
        PurchaseLineDialog(
            activity = this,
            viewModel = viewModel,
            supplierState = { etState.text?.toString()?.trim().orEmpty() }
        ).show(prefillName, prefillVariant, prefillUnit, disableMeta)
    }


    /* ------------------------------------------------------------------
     *  Totals
     * ------------------------------------------------------------------ */

    private fun computeTotals(): Totals {
        var taxable = 0.0
        var invoice = 0.0
        var cgstAmt = 0.0
        var sgstAmt = 0.0
        var igstAmt = 0.0
        viewModel.lines.value.forEach { line ->
            taxable += line.taxableAmount
            invoice += line.invoiceValue
            cgstAmt += line.taxableAmount * line.purchaseCgst / 100.0
            sgstAmt += line.taxableAmount * line.purchaseSgst / 100.0
            igstAmt += line.taxableAmount * line.purchaseIgst / 100.0
        }
        val cess = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0
        return Totals(taxable, invoice + cess, cgstAmt, sgstAmt, igstAmt)
    }

    private data class Totals(
        val taxable: Double,
        val invoice: Double,
        val cgstAmt: Double,
        val sgstAmt: Double,
        val igstAmt: Double
    )

}
