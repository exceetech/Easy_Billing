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
import com.example.easy_billing.viewmodel.PurchaseViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

        handlePrefill()
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
        rv              = findViewById(R.id.rvLines)
        btnAddLine      = findViewById(R.id.btnAddLine)
        btnSave         = findViewById(R.id.btnSavePurchase)
        tvTaxableTotal  = findViewById(R.id.tvTaxableTotal)
        tvInvoiceTotal  = findViewById(R.id.tvInvoiceTotal)

        rgCreditOption = findViewById(R.id.rgCreditOption)
        rbCredit = findViewById(R.id.rbCredit)
        rbNotCredit = findViewById(R.id.rbNotCredit)
        cardSelectedAccount = findViewById(R.id.cardSelectedAccount)
        tvSelectedAccountName = findViewById(R.id.tvSelectedAccountName)
        btnChangeAccount = findViewById(R.id.btnChangeAccount)
        btnClearAccount = findViewById(R.id.btnClearAccount)

        setupStateSuggestions()
    }

    private fun setupStateSuggestions() {
        val states = com.example.easy_billing.util.GstEngine.INDIA_STATES.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, states)
        etState.setAdapter(adapter)
        
        // Show dropdown on click
        etState.setOnClickListener { etState.showDropDown() }
    }

    private fun setupRecycler() {
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PurchaseLinesAdapter(emptyList()) { idx -> viewModel.removeLine(idx) }
        rv.adapter = adapter
    }

    private fun wireActions() {
        listOf(etInvoiceNumber, etSupplierName, etState).forEach { input ->
            input.addTextChangedListener { recomputeHeaderValid() }
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
            val pickedInvoiceDate = invoiceDateProvider()
            if (pickedInvoiceDate == null) {
                etInvoiceDate.error = "Pick the invoice date"
                Toast.makeText(this, "Invoice date is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickedInvoiceDate > System.currentTimeMillis()) {
                etInvoiceDate.error = "Invoice date cannot be in the future"
                return@setOnClickListener
            }
            val totals = computeTotals()
            val cgstPct = if (totals.taxable > 0) totals.cgstAmt / totals.taxable * 100 else 0.0
            val sgstPct = if (totals.taxable > 0) totals.sgstAmt / totals.taxable * 100 else 0.0
            val igstPct = if (totals.taxable > 0) totals.igstAmt / totals.taxable * 100 else 0.0

            viewModel.save(
                Purchase(
                    invoiceNumber  = invoice,
                    supplierGstin  = etSupplierGstin.text?.toString()?.trim()
                        ?.uppercase()?.takeIf { it.isNotBlank() },
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
                    creditAccountId = viewModel.selectedCreditAccount.value?.id
                )
            )
        }

        rgCreditOption.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCredit) {
                if (viewModel.selectedCreditAccount.value == null) {
                    com.example.easy_billing.util.CreditAccountPicker.show(this) { account ->
                        viewModel.selectCreditAccount(account)
                    }
                } else {
                    cardSelectedAccount.visibility = View.VISIBLE
                }
            } else {
                cardSelectedAccount.visibility = View.GONE
            }
        }

        btnChangeAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(this) { account ->
                viewModel.selectCreditAccount(account)
            }
        }

        btnClearAccount.setOnClickListener {
            viewModel.clearCreditAccount()
            rbNotCredit.isChecked = true
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.lines.collect { lines ->
                        adapter.submit(lines)
                        val totals = computeTotals()
                        tvTaxableTotal.text = "%.2f".format(totals.taxable)
                        tvInvoiceTotal.text = "%.2f".format(totals.invoice)
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
                            tvSelectedAccountName.text = "Selected Account: ${account.name}"
                            cardSelectedAccount.visibility = View.VISIBLE
                            rbCredit.isChecked = true
                        } else {
                            cardSelectedAccount.visibility = View.GONE
                        }
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
        etState.text?.toString()?.trim().isNullOrEmpty().not()

    private fun recomputeHeaderValid() {
        val ok = isHeaderValid()
        btnAddLine.isEnabled = ok
        btnSave.isEnabled = ok && viewModel.lines.value.isNotEmpty()
    }

    /* ------------------------------------------------------------------
     *  Add-line dialog (NEW field order + variant dropdown +
     *  selling price + autofill from global verification)
     * ------------------------------------------------------------------ */

    private fun showLineDialog(
        prefillName: String? = null,
        prefillVariant: String? = null,
        prefillUnit: String? = null,
        disableMeta: Boolean = false
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_purchase_line, null)

        val etProduct  = view.findViewById<AutoCompleteTextView>(R.id.etProductName)
        val tilVariant = view.findViewById<TextInputLayout>(R.id.tilVariant)
        val etVariant  = view.findViewById<AutoCompleteTextView>(R.id.etVariant)
        val etUnit     = view.findViewById<AutoCompleteTextView>(R.id.etUnit)
        val etHsn      = view.findViewById<TextInputEditText>(R.id.etHsn)
        val etSelling  = view.findViewById<TextInputEditText>(R.id.etSellingPrice)
        val etQty      = view.findViewById<TextInputEditText>(R.id.etQuantity)
        val etTax      = view.findViewById<TextInputEditText>(R.id.etTaxable)
        val etInv      = view.findViewById<TextInputEditText>(R.id.etInvoiceValue)

        val etPCgst = view.findViewById<TextInputEditText>(R.id.etPurchaseCgst)
        val etPSgst = view.findViewById<TextInputEditText>(R.id.etPurchaseSgst)
        val etPIgst = view.findViewById<TextInputEditText>(R.id.etPurchaseIgst)
        val etSCgst = view.findViewById<TextInputEditText>(R.id.etSalesCgst)
        val etSSgst = view.findViewById<TextInputEditText>(R.id.etSalesSgst)
        val etSIgst = view.findViewById<TextInputEditText>(R.id.etSalesIgst)

        val btnHelp   = view.findViewById<MaterialButton>(R.id.btnHsnHelp)
        val btnAdd    = view.findViewById<MaterialButton>(R.id.btnLineAdd)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnLineCancel)

        val productRepo = ProductRepository.get(this)
        val verifyRepo  = ProductVerificationRepository.get(this)

        // Reveal variant + autofill when the user picks / settles
        // on a product name.
        val onProductSettled = {
            val name = etProduct.text?.toString()?.trim().orEmpty()
            
            // 🔥 Reset fields whenever name changes to avoid stale data
            etHsn.setText("")
            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")
            etSelling.setText("")
            
            if (name.isNotBlank()) {
                tilVariant.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val (globalVariants, history, globalHsn) = withContext(Dispatchers.IO) {
                        val gv = verifyRepo.variantsFor(name).getOrNull()?.variants
                            ?: productRepo.distinctVariants()
                        val hist = productRepo.autoFillFromHistory(name = name)
                        
                        // Fetch global HSN if not in history
                        var gHsn: String? = null
                        if (hist == null || hist.hsnCode.isNullOrBlank()) {
                            val verify = verifyRepo.verifyProductName(name).getOrNull()
                            if (verify?.valid == true && verify.matched_global_id != null) {
                                gHsn = runCatching {
                                    verifyRepo.api.getHsn("Bearer ${verifyRepo.tokenProvider()}", verify.matched_global_id).hsn_code
                                }.getOrNull()
                            }
                        }
                        
                        Triple(gv, hist, gHsn)
                    }
                    
                    if (globalVariants.isNotEmpty()) {
                        etVariant.setAdapter(
                            ArrayAdapter(
                                this@PurchaseActivity,
                                android.R.layout.simple_list_item_1,
                                globalVariants.map { it.firstCapital() }
                            )
                        )
                    }
                    
                    // Autofill from history (preferred) or global HSN
                    if (etHsn.text.isNullOrBlank()) {
                        val finalHsn = history?.hsnCode ?: globalHsn
                        if (!finalHsn.isNullOrBlank()) {
                            etHsn.setText(finalHsn)
                            
                            // Derive Sales GST from HSN length if history is missing
                            if (history == null || (history.cgstPercentage == 0.0 && history.igstPercentage == 0.0)) {
                                val totalGst = when (finalHsn.length) {
                                    4 -> 5.0
                                    6 -> 12.0
                                    else -> 18.0
                                }
                                val halfGst = totalGst / 2.0
                                if (etSCgst.text.isNullOrBlank()) etSCgst.setText(halfGst.toString())
                                if (etSSgst.text.isNullOrBlank()) etSSgst.setText(halfGst.toString())
                                if (etSIgst.text.isNullOrBlank()) etSIgst.setText(totalGst.toString())
                            }
                        }
                    }

                    history?.let { match ->
                        if (etSCgst.text.isNullOrBlank() && match.cgstPercentage > 0)
                            etSCgst.setText(match.cgstPercentage.toString())
                        if (etSSgst.text.isNullOrBlank() && match.sgstPercentage > 0)
                            etSSgst.setText(match.sgstPercentage.toString())
                        if (etSIgst.text.isNullOrBlank() && match.igstPercentage > 0)
                            etSIgst.setText(match.igstPercentage.toString())
                    }
                }
            }
        }

        etVariant.setOnItemClickListener { _, _, position, _ ->
            val vName = etVariant.adapter.getItem(position).toString()
            val pName = etProduct.text.toString().trim()
            lifecycleScope.launch {
                val match = withContext(Dispatchers.IO) {
                    productRepo.getByNameAndVariant(pName, vName)
                }
                if (match != null && match.isActive) {
                    withContext(Dispatchers.Main) {
                        if (etSelling.text.isNullOrBlank() || etSelling.text.toString() == "0.0") {
                            etSelling.setText(match.price.toString())
                        }
                        // Also sync tax if history match found
                        if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                        if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                        if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                        if (etHsn.text.isNullOrBlank()) etHsn.setText(match.hsnCode.orEmpty())
                    }
                }
            }
        }

        // Product autocomplete from existing local catalogue.
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) { productRepo.distinctNames() }
            etProduct.setAdapter(
                ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_list_item_1, names)
            )

            if (prefillName != null) {
                etProduct.setText(prefillName)
                if (disableMeta) {
                    etProduct.isEnabled = false
                    etProduct.isFocusable = false
                    etProduct.isFocusableInTouchMode = false
                    etProduct.setOnClickListener(null)
                    (etProduct.parent.parent as? TextInputLayout)?.endIconMode = TextInputLayout.END_ICON_NONE
                }
                onProductSettled()
                
                // 🔥 For pre-filled flow (Inventory -> Add), fetch the specific variant's price immediately
                if (prefillVariant != null) {
                    etVariant.setText(prefillVariant)
                    if (disableMeta) {
                        etVariant.isEnabled = false
                        etVariant.isFocusable = false
                        etVariant.isFocusableInTouchMode = false
                        etVariant.setOnClickListener(null)
                        view.findViewById<TextInputLayout>(R.id.tilVariant).endIconMode = TextInputLayout.END_ICON_NONE
                    }
                    
                    val match = withContext(Dispatchers.IO) {
                        productRepo.getByNameAndVariant(prefillName, prefillVariant)
                    }
                    if (match != null && match.isActive) {
                        etSelling.setText(match.price.toString())
                        if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                        if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                        if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                        if (etHsn.text.isNullOrBlank()) etHsn.setText(match.hsnCode.orEmpty())
                        if (etUnit.text.isNullOrBlank()) etUnit.setText(match.unit, false)
                    }
                }
            }
        }

        // Unit dropdown — backend list first, fall back to defaults
        // when offline or the endpoint is missing.
        val defaultUnits = listOf("piece", "kilogram", "litre", "gram", "millilitre")
        etUnit.setText("piece", false)
        etUnit.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, defaultUnits)
        )
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)
            if (token != null) {
                val backendUnits = withContext(Dispatchers.IO) {
                    runCatching {
                        com.example.easy_billing.network.RetrofitClient.api
                            .getUnits("Bearer $token").units
                    }.getOrNull()
                }
                val merged = ((backendUnits ?: emptyList()) + defaultUnits).distinct()
                if (merged.isNotEmpty()) {
                    etUnit.setAdapter(
                        ArrayAdapter(
                            this@PurchaseActivity,
                            android.R.layout.simple_list_item_1,
                            merged
                        )
                    )
                }
            }
        }

        btnHelp.setOnClickListener { HsnHelpLauncher.open(this) }

        etProduct.setOnItemClickListener { _, _, _, _ -> onProductSettled() }
        etProduct.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onProductSettled()
        }

        // HSN debounced verify (best-effort) — reuses the same path
        // as AddProduct so the helper text / error renders identically.
        etHsn.addTextChangedListener { editable ->
            val hsn = editable?.toString()?.trim().orEmpty()
            
            // 🔥 Reset Sales GST when HSN changes manually
            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")

            if (hsn.length < 4) return@addTextChangedListener
            lifecycleScope.launch {
                val match = withContext(Dispatchers.IO) {
                    productRepo.autoFillFromHistory(hsn = hsn)
                }
                if (match != null) {
                    withContext(Dispatchers.Main) {
                        if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                        if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                        if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                    }
                } else {
                    // Fallback: length-based derivation
                    withContext(Dispatchers.Main) {
                        val totalGst = when (hsn.length) {
                            4 -> 5.0
                            6 -> 12.0
                            else -> 18.0
                        }
                        val halfGst = totalGst / 2.0
                        etSCgst.setText(halfGst.toString())
                        etSSgst.setText(halfGst.toString())
                        etSIgst.setText(totalGst.toString())
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ===== Live invoice-value auto-calc =====
        //
        // Compare the supplier state (from the header) with the
        // shop's own state code (from the GST profile or the GSTIN
        // prefix). If they match → intra-state, so the line invoice
        // value is taxable + cgst_amt + sgst_amt. Otherwise it's
        // inter-state and uses igst_amt instead.
        val invoiceState = etState.text?.toString()?.trim().orEmpty()
        var shopStateCode = ""
        lifecycleScope.launch {
            shopStateCode = withContext(Dispatchers.IO) {
                val db = com.example.easy_billing.db.AppDatabase
                    .getDatabase(this@PurchaseActivity)
                val gst = db.gstProfileDao().get()
                val store = db.storeInfoDao().get()
                gst?.stateCode?.takeIf { it.isNotBlank() }
                    ?: com.example.easy_billing.util.GstEngine
                        .getStateCode(store?.gstin)
            }
        }
        var userOverroteInvoice = false
        etInv.addTextChangedListener {
            // Track whether the user has manually edited the invoice
            // value so we don't keep clobbering their override.
            if (etInv.isFocused) userOverroteInvoice = true
        }

        val recomputeInvoice = recomputeInvoice@{
            if (userOverroteInvoice) return@recomputeInvoice
            val taxable = etTax.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (taxable <= 0) return@recomputeInvoice
            val cgst = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sgst = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
            val igst = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0

            val invoiceStateCode = com.example.easy_billing.util.GstEngine
                .getStateCodeFromName(invoiceState)
            val sameState = shopStateCode.isNotBlank() &&
                    invoiceStateCode != null &&
                    shopStateCode == invoiceStateCode

            val total = if (sameState) {
                taxable + (taxable * cgst / 100.0) + (taxable * sgst / 100.0)
            } else {
                taxable + (taxable * igst / 100.0)
            }
            // Avoid an infinite loop with the watcher above by
            // replacing the text without flipping userOverroteInvoice.
            val rounded = "%.2f".format(total)
            if (etInv.text?.toString() != rounded) {
                val wasFocused = etInv.isFocused
                etInv.setText(rounded)
                if (!wasFocused) userOverroteInvoice = false
            }
        }
        listOf(etTax, etPCgst, etPSgst, etPIgst).forEach {
            it.addTextChangedListener { recomputeInvoice() }
        }

        // Enable the "Add" button only when the required fields
        // (product, quantity, taxable, selling) are populated.
        val recompute = {
            val ok = !etProduct.text.isNullOrBlank() &&
                    (etQty.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0 &&
                    (etTax.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0 &&
                    (etSelling.text?.toString()?.toDoubleOrNull() ?: 0.0) > 0
            btnAdd.isEnabled = ok
        }
        listOf(etProduct, etQty, etTax, etSelling).forEach {
            it.addTextChangedListener { recompute() }
        }
        recompute()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnAdd.setOnClickListener {
            val name = etProduct.text?.toString()?.trim().orEmpty()
            val qty  = etQty.text?.toString()?.toDoubleOrNull() ?: 0.0
            val taxable = etTax.text?.toString()?.toDoubleOrNull() ?: 0.0
            val selling = etSelling.text?.toString()?.toDoubleOrNull() ?: 0.0
            val invoice = etInv.text?.toString()?.toDoubleOrNull()
                ?: (taxable + (taxable * (
                    (etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0) +
                    (etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0) +
                    (etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0)
                ) / 100.0))

            if (name.isEmpty() || qty <= 0 || taxable <= 0 || selling <= 0) {
                Toast.makeText(this,
                    "Fill product, quantity, selling price and taxable",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val variant = etVariant.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            viewModel.addLine(
                PurchaseItemDraft(
                    productName    = name.firstCapital(),
                    variant        = variant?.firstCapital(),
                    hsnCode        = etHsn.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    unit           = etUnit.text?.toString()?.trim()?.ifBlank { null },
                    quantity       = qty,
                    taxableAmount  = taxable,
                    invoiceValue   = invoice,
                    costPrice      = if (qty > 0) invoice / qty else 0.0,
                    sellingPrice   = selling,
                    purchaseCgst   = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                    purchaseSgst   = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                    purchaseIgst   = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                    salesCgst      = etSCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                    salesSgst      = etSSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                    salesIgst      = etSIgst.text?.toString()?.toDoubleOrNull() ?: 0.0
                )
            )
            dialog.dismiss()
        }

        dialog.show()
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
        return Totals(taxable, invoice, cgstAmt, sgstAmt, igstAmt)
    }

    private data class Totals(
        val taxable: Double,
        val invoice: Double,
        val cgstAmt: Double,
        val sgstAmt: Double,
        val igstAmt: Double
    )

    private fun String.firstCapital(): String =
        trim().split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }
}
