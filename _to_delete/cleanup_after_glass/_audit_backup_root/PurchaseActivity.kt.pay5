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
    // True only for the Inventory "Add stock" single-product flow (see
    // handlePrefill). Turns on the dim/spotlight visual treatment in
    // PurchaseLineDialog for that one line's editor, AND reorders this
    // screen's flow: the header is filled in first (button reads "Add
    // stock" instead of "Save purchase"), and only once that's valid does
    // tapping it open the line popup — saving that popup is what finally
    // commits the purchase (see performSavePurchase()/addSingleModeLine()).
    private var isSingleModePurchase: Boolean = false
    // Stashed from the Inventory intent extras in handlePrefill() and used
    // once the header is valid and "Add stock" is tapped, to open the line
    // popup for this exact product.
    private var singleModeProductId: Int = -1
    private var singleModeProductName: String? = null
    private var singleModeProductVariant: String? = null
    private var singleModeProductUnit: String? = null
    // Fetched once handlePrefill() knows the product id — supplies the
    // GST rate, HSN, unit, selling price etc. that the inline stock card
    // (llSingleModeStockFields) needs to compute taxable/invoice value
    // live and to build the final PurchaseItemDraft on Save.
    private var singleModeProduct: com.example.easy_billing.db.Product? = null

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
    private lateinit var llEmptyLineItems: LinearLayout
    private lateinit var btnAddLine: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSavePurchase: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvSingleModeExplainer: TextView
    // Step-3 card's own title + red "Required" badge — swapped to
    // plain language (and the badge hidden) for the single-mode Add
    // stock flow only; the normal multi-item purchase flow keeps
    // today's "Line items" / "Required" wording untouched.
    private lateinit var tvLineItemsCardTitle: TextView
    private lateinit var tvLineItemsRequiredBadge: TextView
    // Inline "qty / before-discount cost / discount" card shown in place
    // of the normal line-item list for the single-product Add stock flow —
    // there is no separate popup: this card plus the header fields are
    // committed together by the one Save button at the bottom of the page.
    private lateinit var llSingleModeStockFields: LinearLayout
    private lateinit var etSingleQty: TextInputEditText
    private lateinit var etSingleGross: TextInputEditText
    private lateinit var etSingleDiscount: TextInputEditText
    private lateinit var tvSingleTaxable: TextView
    private lateinit var tvSingleInvoice: TextView
    private lateinit var tvTaxableTotal: TextView
    private lateinit var tvInvoiceTotal: TextView

    // Credit Integration
    private lateinit var rgCreditOption: android.widget.RadioGroup
    private lateinit var rbCredit: android.widget.RadioButton
    private lateinit var rbNotCredit: android.widget.RadioButton
    private lateinit var cardSelectedAccount: com.google.android.material.card.MaterialCardView
    private lateinit var cardSelectedAccountWrap: View
    private lateinit var tvSelectedAccountName: TextView
    private lateinit var btnChangeAccount: MaterialButton
    private lateinit var btnClearAccount: MaterialButton

    // GSTR-2 ITC Details
    private lateinit var etPlaceOfSupplyCode: AutoCompleteTextView
    private lateinit var switchReverseCharge: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var etInvoiceType: AutoCompleteTextView
    private lateinit var etSupplyType: AutoCompleteTextView
    private lateinit var etCessPaid: TextInputEditText
    private lateinit var etAvailedItcIntegrated: TextInputEditText
    private lateinit var etAvailedItcCentral: TextInputEditText
    private lateinit var etAvailedItcState: TextInputEditText
    private lateinit var etAvailedItcCess: TextInputEditText
    private lateinit var tilAvailedItcIntegrated: View
    private lateinit var tilAvailedItcCentral: View
    private lateinit var tilAvailedItcState: View
    private lateinit var tilAvailedItcCess: View
    private lateinit var btnToggleGstrMoreTaxDetails: View
    private lateinit var groupGstrMoreTaxDetails: View
    private lateinit var ivGstrMoreTaxDetailsChevron: android.widget.ImageView
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
        com.example.easy_billing.util.UserEventLogger.logAction("Purchase", "opened")

        setupToolbar(R.id.toolbar)

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
     * Type, Supply Type, Availed ITC) already has sensible
     * defaults set on open — most purchases never need to touch it. It
     * starts collapsed behind a single row and expands on tap; nothing
     * about validation or the fields themselves changes, they're just
     * hidden until the user wants them.
     */
    private fun setupGstrToggle() {
        val header = findViewById<View>(R.id.headerGstrToggle)
        val group = findViewById<View>(R.id.groupGstrDetails)
        val chevron = findViewById<android.widget.ImageView>(R.id.ivGstrChevron)
        // Starts collapsed (layout default is gone) — the docstring above
        // already promised this; the layout used to default to visible,
        // leaving the whole GSTR-2 block open on every purchase. Chevron
        // starts unrotated to match.
        chevron.rotation = 0f
        header.setOnClickListener {
            val expand = group.visibility != View.VISIBLE
            group.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.rotation = if (expand) 180f else 0f
        }
        setupGstrMoreTaxDetailsToggle()
    }

    /**
     * Nested sub-collapse inside GST Compliance: Supply type, Cess paid, and
     * the four Availed ITC fields are all auto-computed from the purchase's
     * line items (updateAvailedItcValues()) — a normal purchase never needs
     * to open this. Invoice Type and Reverse Charge stay directly visible
     * one level up since those are real, if rare, decisions; Place of
     * Supply is visible too but read-only — it always mirrors Supplier
     * State, so there's nothing to decide there either. Starts collapsed
     * to match.
     */
    private fun setupGstrMoreTaxDetailsToggle() {
        ivGstrMoreTaxDetailsChevron.rotation = 0f
        btnToggleGstrMoreTaxDetails.setOnClickListener {
            val expand = groupGstrMoreTaxDetails.visibility != View.VISIBLE
            groupGstrMoreTaxDetails.visibility = if (expand) View.VISIBLE else View.GONE
            ivGstrMoreTaxDetailsChevron.rotation = if (expand) 180f else 0f
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
            isSingleModePurchase = true
            btnAddLine.visibility = View.GONE
            singleModeProductId = intent.getIntExtra("EXTRA_PRODUCT_ID", -1)
            singleModeProductName = intent.getStringExtra("EXTRA_PRODUCT_NAME")
            singleModeProductVariant = intent.getStringExtra("EXTRA_PRODUCT_VARIANT")
            singleModeProductUnit = intent.getStringExtra("EXTRA_PRODUCT_UNIT")

            // Header-first flow: the user sees this screen with no line
            // added yet. They fill in the supplier/invoice details, then
            // tap this relabeled button — only then does the line popup
            // (quantity/cost/discount, with taxable/invoice value shown
            // alongside) appear. Saving that popup is what finally adds
            // the stock; this button is never tapped a second time.
            btnSave.text = getString(R.string.purchase_add_stock_button_label)
            tvSingleModeExplainer.text = getString(
                R.string.purchase_add_stock_explainer,
                singleModeProductName ?: ""
            )
            tvSingleModeExplainer.visibility = View.VISIBLE

            // One screen, no popup: the qty/cost/discount card replaces the
            // normal line-item list entirely, and stays visible the whole
            // time — there's no intermediate "tap to open the line editor"
            // step for this flow.
            llSingleModeStockFields.visibility = View.VISIBLE
            rv.visibility = View.GONE
            llEmptyLineItems.visibility = View.GONE

            // "Line items" + the red "Required" badge are multi-item,
            // invoice-processing language that doesn't mean anything in
            // a one-product "add more stock" screen — swap to a plain
            // title and drop the badge for this flow only.
            tvLineItemsCardTitle.text = getString(R.string.purchase_single_stock_card_title)
            tvLineItemsRequiredBadge.visibility = View.GONE

            val prefillProductId = singleModeProductId
            lifecycleScope.launch {
                singleModeProduct = if (prefillProductId > 0) {
                    withContext(Dispatchers.IO) {
                        ProductRepository.get(this@PurchaseActivity).getById(prefillProductId)
                    }
                } else null
                recomputeSingleModeAmounts()
            }
        }
    }

    /** Live-updates the read-only taxable/invoice value shown in the
     *  single-mode stock card as the user types cost/discount — mirrors
     *  the GST split PurchaseLineDialog uses (intra-state CGST+SGST
     *  half/half, inter-state IGST), reading the product's own tax rate
     *  since this card has no per-line tax-rate fields to edit. */
    private fun recomputeSingleModeAmounts() {
        if (!isSingleModePurchase || !::etSingleGross.isInitialized) return
        val gross = etSingleGross.text?.toString()?.toDoubleOrNull() ?: 0.0
        val discount = etSingleDiscount.text?.toString()?.toDoubleOrNull() ?: 0.0
        val taxable = (gross - discount).coerceAtLeast(0.0)
        tvSingleTaxable.text = "%.2f".format(taxable)

        val product = singleModeProduct
        val cgstRate = product?.cgstPercentage ?: 0.0
        val sgstRate = product?.sgstPercentage ?: 0.0
        val igstRate = product?.igstPercentage ?: 0.0
        val totalRate = (cgstRate + sgstRate).takeIf { it > 0 } ?: igstRate
        val invoiceValue = taxable + (taxable * totalRate / 100.0)
        tvSingleInvoice.text = "%.2f".format(invoiceValue)
    }

    /** Builds the initial line for the "Add stock" single-product flow
     *  directly from the product's own saved details, no dialog needed. */
    private fun addSingleModeLine(productId: Int, name: String, variant: String?, unit: String?) {
        lifecycleScope.launch {
            val product = if (productId > 0) {
                withContext(Dispatchers.IO) { ProductRepository.get(this@PurchaseActivity).getById(productId) }
            } else null

            val avgCost = if (productId > 0) {
                withContext(Dispatchers.IO) {
                    com.example.easy_billing.db.AppDatabase.getDatabase(this@PurchaseActivity)
                        .inventoryDao().getInventory(productId)?.averageCost
                }
            } else null

            val costBasis = (avgCost?.takeIf { it > 0 }) ?: product?.price ?: 0.0
            val taxable = costBasis
            val sellingPrice = product?.price?.takeIf { it > 0 } ?: costBasis

            viewModel.addLine(
                PurchaseItemDraft(
                    productName = name,
                    variant = variant?.takeIf { it.isNotBlank() },
                    hsnCode = product?.hsnCode,
                    unit = unit?.takeIf { it.isNotBlank() } ?: product?.unit,
                    quantity = 1.0,
                    taxableAmount = taxable,
                    invoiceValue = taxable,
                    costPrice = taxable,
                    sellingPrice = sellingPrice,
                    isTaxInclusive = product?.isTaxInclusive ?: false,
                    salesCgst = product?.cgstPercentage ?: 0.0,
                    salesSgst = product?.sgstPercentage ?: 0.0,
                    salesIgst = product?.igstPercentage ?: 0.0,
                    officialUqc = product?.officialUqc,
                    hsnDescription = product?.hsnDescription,
                    cessRate = product?.cessRate ?: 0.0,
                    supplyClassification = product?.supplyClassification ?: "TAXABLE",
                    category = product?.category ?: "",
                    // Placeholder quantity/cost — flagged until reviewed.
                    reviewed = false
                )
            )

            // Straight into the editor so the common case (review the
            // number, hit Save) takes one tap instead of "spot the row,
            // tap it, then edit" — Cancelling out still leaves the amber
            // "Review" tag on the row and blocks Save Purchase (see
            // btnSave.setOnClickListener) as a backstop for anyone who
            // bails without confirming it.
            editLine(0)
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
        btnPickSupplier.contentDescription = getString(R.string.purchase_choose_supplier_desc)
        rv              = findViewById(R.id.rvLines)
        llEmptyLineItems = findViewById(R.id.llEmptyLineItems)
        btnAddLine      = findViewById(R.id.btnAddLine)
        btnSave         = findViewById(R.id.btnSavePurchase)
        progressSavePurchase = findViewById(R.id.progressSavePurchase)
        tvSingleModeExplainer = findViewById(R.id.tvSingleModeExplainer)
        tvLineItemsCardTitle = findViewById(R.id.tvLineItemsCardTitle)
        tvLineItemsRequiredBadge = findViewById(R.id.tvLineItemsRequiredBadge)
        llSingleModeStockFields = findViewById(R.id.llSingleModeStockFields)
        etSingleQty = findViewById(R.id.etSingleQty)
        etSingleGross = findViewById(R.id.etSingleGross)
        etSingleDiscount = findViewById(R.id.etSingleDiscount)
        tvSingleTaxable = findViewById(R.id.tvSingleTaxable)
        tvSingleInvoice = findViewById(R.id.tvSingleInvoice)
        listOf(etSingleGross, etSingleDiscount).forEach {
            it.addTextChangedListener { recomputeSingleModeAmounts() }
        }
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            com.example.easy_billing.util.UserEventLogger.logAction(
                "Purchase", "cancel_clicked: lines=${viewModel.lines.value.size}"
            )
            finish()
        }
        tvTaxableTotal  = findViewById(R.id.tvTaxableTotal)
        tvInvoiceTotal  = findViewById(R.id.tvInvoiceTotal)

        rgCreditOption = findViewById(R.id.rgCreditOption)
        rbCredit = findViewById(R.id.rbCredit)
        rbNotCredit = findViewById(R.id.rbNotCredit)
        cardSelectedAccount = findViewById(R.id.cardSelectedAccount)
        cardSelectedAccountWrap = findViewById(R.id.cardSelectedAccountWrap)
        tvSelectedAccountName = findViewById(R.id.tvSelectedAccountName)
        btnChangeAccount = findViewById(R.id.btnChangeAccount)
        btnClearAccount = findViewById(R.id.btnClearAccount)

        // Bind GSTR-2 Views
        etPlaceOfSupplyCode = findViewById(R.id.etPlaceOfSupplyCode)
        switchReverseCharge = findViewById(R.id.switchReverseCharge)
        etInvoiceType = findViewById(R.id.etInvoiceType)
        etSupplyType = findViewById(R.id.etSupplyType)
        etCessPaid = findViewById(R.id.etCessPaid)
        etAvailedItcIntegrated = findViewById(R.id.etAvailedItcIntegrated)
        etAvailedItcCentral = findViewById(R.id.etAvailedItcCentral)
        etAvailedItcState = findViewById(R.id.etAvailedItcState)
        etAvailedItcCess = findViewById(R.id.etAvailedItcCess)
        tilAvailedItcIntegrated = findViewById(R.id.tilAvailedItcIntegrated)
        tilAvailedItcCentral = findViewById(R.id.tilAvailedItcCentral)
        tilAvailedItcState = findViewById(R.id.tilAvailedItcState)
        tilAvailedItcCess = findViewById(R.id.tilAvailedItcCess)
        btnToggleGstrMoreTaxDetails = findViewById(R.id.btnToggleGstrMoreTaxDetails)
        groupGstrMoreTaxDetails = findViewById(R.id.groupGstrMoreTaxDetails)
        ivGstrMoreTaxDetailsChevron = findViewById(R.id.ivGstrMoreTaxDetailsChevron)

        // Bind Imported Goods Views
        switchImportedGoods = findViewById(R.id.switchImportedGoods)
        layoutImportedGoods = findViewById(R.id.layoutImportedGoods)
        etPortCode = findViewById(R.id.etPortCode)
        etBillOfEntryNumber = findViewById(R.id.etBillOfEntryNumber)
        etBillOfEntryDate = findViewById(R.id.etBillOfEntryDate)
        etBillOfEntryValue = findViewById(R.id.etBillOfEntryValue)
        tilSezSupplierGstin = findViewById(R.id.tilSezSupplierGstin)
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
        rawAnchor: View,
        options: List<String>,
        current: String,
        subtitles: List<String>? = null,
        onPick: (String) -> Unit
    ) {
        val anchor = if (rawAnchor.parent is android.widget.LinearLayout && 
            (rawAnchor.parent as android.view.View).background != null) {
            rawAnchor.parent as android.view.View
        } else rawAnchor
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
        val rowHeight = if (subtitles != null) dp(56) else dp(44)
        val wanted = minOf(options.size * rowHeight + dp(10), dp(320))
        val showAbove = spaceBelow < wanted && spaceAbove > spaceBelow
        val available = (if (showAbove) spaceAbove else spaceBelow).coerceAtLeast(dp(88))
        val height = minOf(wanted, available)

        val popup = android.widget.PopupWindow(scroll, anchor.width, height, true).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEachIndexed { i, label ->
            val isSel = i == currentIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) green else ink)
            }
            textContainer.addView(tv)
            val subtitleText = subtitles?.getOrNull(i)
            if (subtitleText != null) {
                textContainer.addView(TextView(this).apply {
                    text = subtitleText
                    textSize = 11.5f
                    setTextColor(android.graphics.Color.parseColor("#8A8272"))
                })
            }
            row.addView(textContainer)
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

        // Place of Supply Code is read-only — it always mirrors the
        // Supplier State field above (see etState's watcher) so the two
        // can never disagree. Nothing to wire up here.

        // Invoice Type — a plain-language subtitle is shown under each
        // option in the picker sheet (see showSortStylePopup's subtitles
        // param) so a 60+ shop owner can tell what each choice means
        // without knowing GST jargon. The stored/synced value is still the
        // exact official term (label), unchanged.
        val invoiceTypes = listOf(
            getString(R.string.purchase_invoice_type_regular),
            getString(R.string.purchase_invoice_type_sez_with_payment),
            getString(R.string.purchase_invoice_type_sez_without_payment),
            getString(R.string.purchase_invoice_type_deemed_exp),
            getString(R.string.purchase_invoice_type_composition)
        )
        val invoiceTypeSubtitles = listOf(
            getString(R.string.purchase_invoice_type_regular_desc),
            getString(R.string.purchase_invoice_type_sez_with_payment_desc),
            getString(R.string.purchase_invoice_type_sez_without_payment_desc),
            getString(R.string.purchase_invoice_type_deemed_exp_desc),
            getString(R.string.purchase_invoice_type_composition_desc)
        )
        etInvoiceType.setText(getString(R.string.purchase_invoice_type_regular), false)
        etInvoiceType.setOnClickListener {
            showSortStylePopup(etInvoiceType, invoiceTypes, etInvoiceType.text.toString(), invoiceTypeSubtitles) { picked ->
                etInvoiceType.setText(picked, false)
            }
        }

        // Supply Type
        val supplyTypes = listOf(
            getString(R.string.purchase_supply_type_intrastate),
            getString(R.string.purchase_supply_type_interstate)
        )
        etSupplyType.setText(getString(R.string.purchase_supply_type_intrastate), false)
        etSupplyType.setOnClickListener {
            showSortStylePopup(etSupplyType, supplyTypes, etSupplyType.text.toString()) { picked ->
                etSupplyType.setText(picked, false)
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
            val detectedType = if (sameState) getString(R.string.purchase_supply_type_intrastate) else getString(R.string.purchase_supply_type_interstate)
            etSupplyType.setText(detectedType, false)
        }
        recomputeSingleModeAmounts()
    }

    private fun updateAvailedItcValues() {
        // Header-level ITC eligibility was removed — GST filing reads each
        // line item's own eligibility instead (gst_routes.py). The
        // Ineligible/None zeroing branch that used to key off the header
        // value is gone with it; Availed ITC always defaults to the tax
        // actually paid, subject to the user's own overrides.
        val totals = computeTotals()
        val cess = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0

        settingItc = true
        try {
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
        } finally {
            settingItc = false
        }
    }

    private fun setupRecycler() {
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PurchaseLinesAdapter(
            emptyList(),
            onRemove = { idx -> viewModel.removeLine(idx) },
            onEdit = { idx -> editLine(idx) }
        )
        rv.adapter = adapter
    }

    /** Reopens the line dialog pre-filled with an already-added line's
     *  values, so tapping a row in the list lets it be edited instead of
     *  only removed and re-added from scratch. */
    private fun editLine(index: Int) {
        val line = viewModel.lines.value.getOrNull(index) ?: return
        PurchaseLineDialog(
            activity = this,
            viewModel = viewModel,
            supplierState = { etState.text?.toString()?.trim().orEmpty() }
        ).show(
            prefillName = line.productName,
            prefillVariant = line.variant,
            prefillUnit = line.unit,
            disableMeta = true,
            existingDraft = line,
            editIndex = index,
            spotlightMode = isSingleModePurchase,
            // Single mode: saving this one line IS the final step — it
            // triggers the same commit btnSave used to run directly.
            onSaveComplete = if (isSingleModePurchase) {
                { performSavePurchase() }
            } else null
        )
    }

    private fun performSavePurchase() {
            if (isSingleModePurchase) {
                val qty = etSingleQty.text?.toString()?.toDoubleOrNull() ?: 0.0
                val gross = etSingleGross.text?.toString()?.toDoubleOrNull() ?: 0.0
                val discount = etSingleDiscount.text?.toString()?.toDoubleOrNull() ?: 0.0
                if (qty <= 0.0 || gross <= 0.0) {
                    Toast.makeText(this, R.string.purchase_fill_header, Toast.LENGTH_SHORT).show()
                    return
                }
                val taxable = (gross - discount).coerceAtLeast(0.0)
                val product = singleModeProduct
                val cgstRate = product?.cgstPercentage ?: 0.0
                val sgstRate = product?.sgstPercentage ?: 0.0
                val igstRate = product?.igstPercentage ?: 0.0
                val totalRate = (cgstRate + sgstRate).takeIf { it > 0 } ?: igstRate
                val intra = etSupplyType.text?.toString()?.trim() ==
                    getString(R.string.purchase_supply_type_intrastate)
                val purchaseCgst = if (intra) totalRate / 2.0 else 0.0
                val purchaseSgst = if (intra) totalRate / 2.0 else 0.0
                val purchaseIgst = if (intra) 0.0 else totalRate
                val invoiceValue = taxable + (taxable * totalRate / 100.0)
                val draft = PurchaseItemDraft(
                    productName = singleModeProductName ?: (product?.name ?: ""),
                    variant = singleModeProductVariant?.takeIf { it.isNotBlank() },
                    // Must match the existing row's brand exactly — upsert()
                    // matches on name+variant+brand+isSellable, and leaving
                    // this null when the product actually has a brand set
                    // fails that match and silently inserts a brand-new
                    // product/tile instead of adding stock to this one.
                    brand = product?.brand,
                    hsnCode = product?.hsnCode,
                    unit = singleModeProductUnit?.takeIf { it.isNotBlank() } ?: product?.unit,
                    quantity = qty,
                    taxableAmount = taxable,
                    discountAmount = discount,
                    invoiceValue = invoiceValue,
                    sellingPrice = product?.price?.takeIf { it > 0 } ?: taxable,
                    isTaxInclusive = product?.isTaxInclusive ?: false,
                    purchaseCgst = purchaseCgst,
                    purchaseSgst = purchaseSgst,
                    purchaseIgst = purchaseIgst,
                    salesCgst = product?.cgstPercentage ?: 0.0,
                    salesSgst = product?.sgstPercentage ?: 0.0,
                    salesIgst = product?.igstPercentage ?: 0.0,
                    officialUqc = product?.officialUqc,
                    hsnDescription = product?.hsnDescription,
                    cessRate = product?.cessRate ?: 0.0,
                    supplyClassification = product?.supplyClassification ?: "TAXABLE",
                    category = product?.category ?: "",
                    reviewed = true
                )
                if (viewModel.lines.value.isEmpty()) {
                    viewModel.addLine(draft)
                } else {
                    viewModel.replaceLine(0, draft)
                }
            }
            val importPart = if (switchImportedGoods.isChecked) {
                ", port_code=${etPortCode.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "boe_number=${etBillOfEntryNumber.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "boe_date=${boeDateProvider() ?: "-"}, " +
                    "boe_value=${etBillOfEntryValue.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "sez_gstin=${etSezSupplierGstin.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}"
            } else ""
            com.example.easy_billing.util.UserEventLogger.logAction(
                "Purchase",
                "save_clicked: lines=${viewModel.lines.value.size}, " +
                    "invoice_number=${etInvoiceNumber.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "supplier=${etSupplierName.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "state=${etState.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "gstin=${etSupplierGstin.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "invoice_date=${invoiceDateProvider() ?: "-"}, " +
                    "place_of_supply=${etPlaceOfSupplyCode.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "invoice_type=${etInvoiceType.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "supply_type=${etSupplyType.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "cess_paid=${etCessPaid.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "availed_itc_integrated=${etAvailedItcIntegrated.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "availed_itc_central=${etAvailedItcCentral.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "availed_itc_state=${etAvailedItcState.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "availed_itc_cess=${etAvailedItcCess.text?.toString()?.trim()?.ifEmpty { "-" } ?: "-"}, " +
                    "imported_goods=${switchImportedGoods.isChecked}, reverse_charge=${switchReverseCharge.isChecked}" +
                    importPart
            )
            val invoice = etInvoiceNumber.text?.toString()?.trim().orEmpty()
            val supplier = etSupplierName.text?.toString()?.trim().orEmpty()
            val state = etState.text?.toString()?.trim().orEmpty()
            if (invoice.isEmpty() || supplier.isEmpty() || state.isEmpty()) {
                Toast.makeText(this, R.string.purchase_fill_header, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "header_fields_missing")
                return
            }
            // Auto-added lines (Inventory's "Add stock") start with
            // placeholder quantity/cost — block save until each one has
            // actually been opened and confirmed, so a header filled in
            // while forgetting the line can't slip through.
            val unreviewedIndex = viewModel.lines.value.indexOfFirst { !it.reviewed }
            if (unreviewedIndex != -1) {
                Toast.makeText(
                    this, R.string.purchase_review_line_item,
                    Toast.LENGTH_SHORT
                ).show()
                editLine(unreviewedIndex)
                return
            }
            // A malformed GSTIN flows straight into GSTR-2, where it fails
            // at filing time instead of here. Blank stays allowed —
            // unregistered suppliers are legitimate.
            val typedGstin = etSupplierGstin.text?.toString()?.trim()?.uppercase().orEmpty()
            if (typedGstin.isNotEmpty() &&
                !com.example.easy_billing.util.GstEngine.isValidGstin(typedGstin)
            ) {
                etSupplierGstin.error = getString(R.string.purchase_invalid_gstin_error)
                Toast.makeText(
                    this,
                    R.string.purchase_invalid_gstin,
                    Toast.LENGTH_LONG
                ).show()
                return
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
                    return
                }
            }

            val pickedInvoiceDate = invoiceDateProvider()
            if (pickedInvoiceDate == null) {
                etInvoiceDate.error = getString(R.string.purchase_pick_invoice_date_error)
                Toast.makeText(this, R.string.purchase_invoice_date_required, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "invoice_date_missing")
                return
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
                Toast.makeText(this, R.string.purchase_place_of_supply_required, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "place_of_supply_missing")
                return
            }

            if (switchImportedGoods.isChecked) {
                val portCode = etPortCode.text?.toString()?.trim()
                if (portCode.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.purchase_port_code_required, Toast.LENGTH_SHORT).show()
                    com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "boe_fields_missing")
                    return
                }
                val boeNumber = etBillOfEntryNumber.text?.toString()?.trim()
                if (boeNumber.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.purchase_boe_number_required, Toast.LENGTH_SHORT).show()
                    com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "boe_fields_missing")
                    return
                }
                val pickedBoeDate = boeDateProvider()
                if (pickedBoeDate == null) {
                    Toast.makeText(this, R.string.purchase_boe_date_required, Toast.LENGTH_SHORT).show()
                    com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "boe_fields_missing")
                    return
                }
                val boeValue = etBillOfEntryValue.text?.toString()?.toDoubleOrNull()
                if (boeValue == null) {
                    Toast.makeText(this, R.string.purchase_boe_value_required, Toast.LENGTH_SHORT).show()
                    com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "boe_fields_missing")
                    return
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
                Toast.makeText(this, R.string.purchase_invoice_type_required, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "invoice_type_or_eligibility_invalid")
                return
            }

            val supplyType = etSupplyType.text?.toString()?.trim().orEmpty()
            if (supplyType != "intrastate" && supplyType != "interstate") {
                Toast.makeText(this, R.string.purchase_supply_type_invalid, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "invoice_type_or_eligibility_invalid")
                return
            }

            val cessPaid = etCessPaid.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (cessPaid < 0.0) {
                Toast.makeText(this, R.string.purchase_cess_paid_invalid, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.UserEventLogger.logValidationFailed("Purchase", "invoice_type_or_eligibility_invalid")
                return
            }

            val availedItcIntegrated = etAvailedItcIntegrated.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcCentral = etAvailedItcCentral.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcState = etAvailedItcState.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedItcCess = etAvailedItcCess.text?.toString()?.toDoubleOrNull() ?: 0.0

            if (availedItcIntegrated < 0.0 || availedItcCentral < 0.0 || availedItcState < 0.0 || availedItcCess < 0.0) {
                Toast.makeText(this, R.string.purchase_itc_fields_negative, Toast.LENGTH_SHORT).show()
                return
            }

            val totals = computeTotals()
            if (availedItcIntegrated > totals.igstAmt) {
                Toast.makeText(this, "Availed ITC Integrated Tax cannot exceed IGST amount (${totals.igstAmt})", Toast.LENGTH_SHORT).show()
                return
            }
            if (availedItcCentral > totals.cgstAmt) {
                Toast.makeText(this, "Availed ITC Central Tax cannot exceed CGST amount (${totals.cgstAmt})", Toast.LENGTH_SHORT).show()
                return
            }
            if (availedItcState > totals.sgstAmt) {
                Toast.makeText(this, "Availed ITC State Tax cannot exceed SGST amount (${totals.sgstAmt})", Toast.LENGTH_SHORT).show()
                return
            }
            if (availedItcCess > cessPaid) {
                Toast.makeText(this, "Availed ITC Cess cannot exceed Cess Paid ($cessPaid)", Toast.LENGTH_SHORT).show()
                return
            }

            // A credit purchase must name the account that owes it, else the
            // amount is recorded against nobody and never appears in payables.
            if (rbCredit.isChecked && viewModel.selectedCreditAccount.value == null) {
                Toast.makeText(this, R.string.purchase_select_credit_account, Toast.LENGTH_SHORT).show()
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account -> viewModel.selectCreditAccount(account) },
                    onDismissedWithoutSelection = {
                        if (viewModel.selectedCreditAccount.value == null) {
                            rbNotCredit.isChecked = true
                            Toast.makeText(
                                this,
                                R.string.purchase_credit_needs_account,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                return
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
                    availedItcIntegratedTax = availedItcIntegrated,
                    availedItcCentralTax = availedItcCentral,
                    availedItcStateTax = availedItcState,
                    availedItcCess = availedItcCess,
                    purchaseSource = if (viewModel.isImportedGoods.value) "IMPORT" else "DOMESTIC"
                )
            )
        }

    private fun wireActions() {
        listOf(etInvoiceNumber, etSupplierName).forEach { input ->
            input.addTextChangedListener { recomputeHeaderValid() }
        }

        etState.addTextChangedListener {
            val supplierState = etState.text?.toString()?.trim().orEmpty()
            val code = com.example.easy_billing.util.GstEngine.getStateCodeFromName(supplierState)
            // Place of Supply is read-only and always mirrors Supplier
            // State, so it can never disagree with it.
            if (code != null) {
                val name = com.example.easy_billing.util.GstEngine.INDIA_STATES[code]
                if (name != null) {
                    etPlaceOfSupplyCode.setText("$code - $name", false)
                }
            } else {
                // State cleared or unrecognised — the old code described
                // the previous supplier, and leaving it behind would put
                // that state on this invoice. Save's self-heal only fires
                // when the field is empty, so it has to actually be
                // emptied.
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

        switchImportedGoods.setOnClickListener {
            if (viewModel.lines.value.isNotEmpty()) {
                switchImportedGoods.isChecked = !switchImportedGoods.isChecked
                Toast.makeText(this, R.string.purchase_imported_toggle_locked, Toast.LENGTH_SHORT).show()
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
                    this, R.string.purchase_fill_header_first,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            showLineDialog()
        }

        btnSave.setOnClickListener {
            // One screen, no popup: the inline stock card (qty/cost/
            // discount/taxable/invoice) plus the header fields are all
            // validated and committed together in a single tap — see the
            // isSingleModePurchase branch at the top of performSavePurchase().
            performSavePurchase()
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
                                    R.string.purchase_credit_needs_account,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                } else {
                    cardSelectedAccount.visibility = View.VISIBLE
                    cardSelectedAccountWrap.visibility = View.VISIBLE
                }
            } else {
                cardSelectedAccount.visibility = View.GONE
                cardSelectedAccountWrap.visibility = View.GONE
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
                        llEmptyLineItems.visibility = if (lines.isEmpty() && !isSingleModePurchase) View.VISIBLE else View.GONE
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
                                (isSingleModePurchase || viewModel.lines.value.isNotEmpty())
                        // Instant feedback the instant Save/Add stock is
                        // tapped — text swaps to "Saving…"/"Adding stock…"
                        // and a small spinner appears, instead of the
                        // button just going dim with no other sign the tap
                        // registered (which read as the app freezing while
                        // the invoice write + backend push ran).
                        // INVISIBLE, not GONE, when idle — the row already
                        // reserves this width, so hiding it with GONE pulled
                        // width back into the weighted Save button and made
                        // it visibly resize/jump each time loading toggled.
                        progressSavePurchase.visibility = if (state.loading) View.VISIBLE else View.INVISIBLE
                        btnSave.text = if (state.loading) {
                            getString(
                                if (isSingleModePurchase) R.string.purchase_adding_stock_label
                                else R.string.purchase_saving_label
                            )
                        } else {
                            getString(
                                if (isSingleModePurchase) R.string.purchase_add_stock_button_label
                                else R.string.save_purchase
                            )
                        }
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
                            val msg = state.message ?: getString(R.string.purchase_saved_default_msg)
                            Toast.makeText(this@PurchaseActivity, msg, Toast.LENGTH_LONG).show()
                            // Was calling finish() in the same instant as the
                            // toast — this message is the user's only
                            // confirmation of whether the backend push
                            // actually worked, so it's worth a beat to read
                            // rather than getting torn down immediately.
                            android.os.Handler(mainLooper).postDelayed({ finish() }, 600)
                        }
                    }
                }
                launch {
                    viewModel.selectedCreditAccount.collect { account ->
                        if (account != null) {
                            tvSelectedAccountName.text = account.name
                            cardSelectedAccount.visibility = View.VISIBLE
                            cardSelectedAccountWrap.visibility = View.VISIBLE
                            rbCredit.isChecked = true
                        } else {
                            cardSelectedAccount.visibility = View.GONE
                            cardSelectedAccountWrap.visibility = View.GONE
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
        btnSave.isEnabled = ok && (isSingleModePurchase || viewModel.lines.value.isNotEmpty())
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
        // `invoice` is the sum of `line.invoiceValue`.
        // `PurchaseLineDialog` includes `cessAmount` in `line.invoiceValue`.
        // However, if the user manually overrides `etCessPaid` to a custom value 
        // that differs from the sum of line cesses, we should adjust the total.
        val lineCessSum = viewModel.lines.value.sumOf { it.cessAmount }
        val adjustedInvoice = invoice - lineCessSum + cess
        return Totals(taxable, adjustedInvoice, cgstAmt, sgstAmt, igstAmt)
    }

    private data class Totals(
        val taxable: Double,
        val invoice: Double,
        val cgstAmt: Double,
        val sgstAmt: Double,
        val igstAmt: Double
    )

}
