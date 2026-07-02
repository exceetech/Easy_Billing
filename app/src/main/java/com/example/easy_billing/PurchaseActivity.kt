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
    private lateinit var tilAvailedItcIntegrated: TextInputLayout
    private lateinit var tilAvailedItcCentral: TextInputLayout
    private lateinit var tilAvailedItcState: TextInputLayout
    private lateinit var tilAvailedItcCess: TextInputLayout
    private var shopStateCode: String = ""

    // Imported Goods
    private lateinit var switchImportedGoods: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var layoutImportedGoods: LinearLayout
    private lateinit var etPortCode: TextInputEditText
    private lateinit var etBillOfEntryNumber: TextInputEditText
    private lateinit var etBillOfEntryDate: TextInputEditText
    private lateinit var etBillOfEntryValue: TextInputEditText
    private lateinit var tilSezSupplierGstin: TextInputLayout
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

        fetchShopStateCode()
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
        etSezSupplierGstin = findViewById(R.id.etSezSupplierGstin)

        boeDateProvider = InvoiceDatePicker.bind(etBillOfEntryDate)

        etCessPaid.setText("0.0")

        setupStateSuggestions()
        setupGstr2Dropdowns()
    }

    private fun setupStateSuggestions() {
        val states = com.example.easy_billing.util.GstEngine.INDIA_STATES.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, states)
        etState.setAdapter(adapter)
        
        // Show dropdown on click
        etState.setOnClickListener { etState.showDropDown() }
    }

    private fun setupGstr2Dropdowns() {
        // Place of Supply Code: "code - state name"
        val stateCodesList = com.example.easy_billing.util.GstEngine.INDIA_STATES.map { "${it.key} - ${it.value}" }
        val posAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, stateCodesList)
        etPlaceOfSupplyCode.setAdapter(posAdapter)
        etPlaceOfSupplyCode.setOnClickListener { etPlaceOfSupplyCode.showDropDown() }

        // Invoice Type
        val invoiceTypes = listOf("Regular", "SEZ supplies with payment", "SEZ supplies without payment", "Deemed Exp", "From Composition Taxable Person")
        val invTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, invoiceTypes)
        etInvoiceType.setAdapter(invTypeAdapter)
        etInvoiceType.setText("Regular", false)
        etInvoiceType.setOnClickListener { etInvoiceType.showDropDown() }

        // Supply Type
        val supplyTypes = listOf("intrastate", "interstate")
        val supplyTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, supplyTypes)
        etSupplyType.setAdapter(supplyTypeAdapter)
        etSupplyType.setText("intrastate", false)
        etSupplyType.setOnClickListener { etSupplyType.showDropDown() }

        // Eligibility For ITC
        val eligibilityTypes = listOf("Inputs", "Capital goods", "Input services", "Ineligible", "None")
        val eligibilityAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, eligibilityTypes)
        etEligibilityForItc.setAdapter(eligibilityAdapter)
        etEligibilityForItc.setText("Inputs", false)
        etEligibilityForItc.setOnClickListener { etEligibilityForItc.showDropDown() }
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

        if (eligibility == "Ineligible" || eligibility == "None") {
            etAvailedItcIntegrated.setText("0.0")
            etAvailedItcCentral.setText("0.0")
            etAvailedItcState.setText("0.0")
            etAvailedItcCess.setText("0.0")

            etAvailedItcIntegrated.isEnabled = false
            etAvailedItcCentral.isEnabled = false
            etAvailedItcState.isEnabled = false
            etAvailedItcCess.isEnabled = false
        } else {
            etAvailedItcIntegrated.isEnabled = true
            etAvailedItcCentral.isEnabled = true
            etAvailedItcState.isEnabled = true
            etAvailedItcCess.isEnabled = true

            // Default to paid tax amounts
            etAvailedItcIntegrated.setText(totals.igstAmt.toString())
            etAvailedItcCentral.setText(totals.cgstAmt.toString())
            etAvailedItcState.setText(totals.sgstAmt.toString())
            etAvailedItcCess.setText(cess.toString())
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
            }
            detectSupplyType()
            recomputeHeaderValid()
        }

        etPlaceOfSupplyCode.addTextChangedListener {
            detectSupplyType()
        }

        etCessPaid.addTextChangedListener {
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
                tilSezSupplierGstin.visibility = View.VISIBLE
            } else {
                tilSezSupplierGstin.visibility = View.GONE
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
            val pickedInvoiceDate = invoiceDateProvider()
            if (pickedInvoiceDate == null) {
                etInvoiceDate.error = "Pick the invoice date"
                Toast.makeText(this, "Invoice date is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }


            // GSTR-2 validation
            val placeOfSupplyCodeText = etPlaceOfSupplyCode.text?.toString()?.trim().orEmpty()
            val placeOfSupplyCode = placeOfSupplyCodeText.split(" - ").firstOrNull()?.trim() ?: ""
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
        val switchTaxInclusive = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchTaxInclusive)
        val etQty      = view.findViewById<TextInputEditText>(R.id.etQuantity)
        val etTax      = view.findViewById<TextInputEditText>(R.id.etTaxable)
        val etInv      = view.findViewById<TextInputEditText>(R.id.etInvoiceValue)

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

        val btnHelp   = view.findViewById<MaterialButton>(R.id.btnHsnHelp)
        val btnAdd    = view.findViewById<MaterialButton>(R.id.btnLineAdd)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnLineCancel)

        // ── GSTR-1 product master fields ──
        val spinnerUqcPurchase = view.findViewById<AutoCompleteTextView>(R.id.spinnerOfficialUqcPurchase)
        val etHsnDescPurchase  = view.findViewById<TextInputEditText>(R.id.etHsnDescriptionPurchase)
        val etCessRatePurchase = view.findViewById<TextInputEditText>(R.id.etCessRatePurchase)
        val spinnerSupplyClassPurchase = view.findViewById<AutoCompleteTextView>(R.id.spinnerSupplyClassificationPurchase)
        spinnerUqcPurchase.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, UqcMapper.ALL_UQC_DISPLAY)
        )
        spinnerSupplyClassPurchase.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST"))
        )

        // ── Category dropdown (predefined ∪ shop custom) ──
        val etCategoryPurchase = view.findViewById<AutoCompleteTextView>(R.id.etCategoryPurchase)
        lifecycleScope.launch {
            val prefs = getSharedPreferences("auth", MODE_PRIVATE)
            val shopIdStr = try {
                prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
            } catch (e: ClassCastException) { prefs.getInt("SHOP_ID", 0).toString() }
            val cats = com.example.easy_billing.util.ProductCategories.dropdownFor(
                this@PurchaseActivity, shopIdStr
            )
            etCategoryPurchase.setAdapter(
                ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_list_item_1, cats)
            )
        }
        etCategoryPurchase.setOnClickListener { etCategoryPurchase.showDropDown() }

        // ── GSTR-2 product master / transaction fields ──
        val llGstr2HeaderToggle = view.findViewById<LinearLayout>(R.id.llGstr2HeaderToggle)
        val ivGstr2ToggleArrow  = view.findViewById<ImageView>(R.id.ivGstr2ToggleArrow)
        val llGstr2ItemDetails  = view.findViewById<LinearLayout>(R.id.llGstr2ItemDetails)

        llGstr2HeaderToggle.setOnClickListener {
            if (llGstr2ItemDetails.visibility == View.VISIBLE) {
                llGstr2ItemDetails.visibility = View.GONE
                ivGstr2ToggleArrow.rotation = 0f
            } else {
                llGstr2ItemDetails.visibility = View.VISIBLE
                ivGstr2ToggleArrow.rotation = 180f
            }
        }

        val etCessAmountPurchase = view.findViewById<TextInputEditText>(R.id.etCessAmountPurchase)
        val spinnerEligibilityItemPurchase = view.findViewById<AutoCompleteTextView>(R.id.spinnerEligibilityItemPurchase)
        val etAvailedItcIgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcIgstPurchase)
        val etAvailedItcCgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcCgstPurchase)
        val etAvailedItcSgstPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcSgstPurchase)
        val etAvailedItcCessPurchase = view.findViewById<TextInputEditText>(R.id.etAvailedItcCessPurchase)

        val eligibilityOptions = listOf("Inputs", "Capital goods", "Input services", "Ineligible", "None")
        spinnerEligibilityItemPurchase.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, eligibilityOptions)
        )
        spinnerEligibilityItemPurchase.setText("Inputs", false)

        var userOverroteCess = false
        etCessAmountPurchase.addTextChangedListener {
            if (etCessAmountPurchase.isFocused) userOverroteCess = true
        }

        var userOverroteAvailedIgst = false
        var userOverroteAvailedCgst = false
        var userOverroteAvailedSgst = false
        var userOverroteAvailedCess = false

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

                // Disable fields visually
                listOf(etAvailedItcIgstPurchase, etAvailedItcCgstPurchase, etAvailedItcSgstPurchase, etAvailedItcCessPurchase).forEach {
                    it.isEnabled = false
                    it.alpha = 0.5f
                    (it.parent.parent as? TextInputLayout)?.isEnabled = false
                }
            } else {
                // Enable fields
                listOf(etAvailedItcIgstPurchase, etAvailedItcCgstPurchase, etAvailedItcSgstPurchase, etAvailedItcCessPurchase).forEach {
                    it.isEnabled = true
                    it.alpha = 1.0f
                    (it.parent.parent as? TextInputLayout)?.isEnabled = true
                }

                if (!userOverroteAvailedIgst) {
                    etAvailedItcIgstPurchase.setText("%.2f".format(igstAmt))
                }
                if (!userOverroteAvailedCgst) {
                    etAvailedItcCgstPurchase.setText("%.2f".format(cgstAmt))
                }
                if (!userOverroteAvailedSgst) {
                    etAvailedItcSgstPurchase.setText("%.2f".format(sgstAmt))
                }
                if (!userOverroteAvailedCess) {
                    etAvailedItcCessPurchase.setText("%.2f".format(cessAmountVal))
                }
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

        val productRepo = ProductRepository.get(this)
        val verifyRepo  = ProductVerificationRepository.get(this)

        var lastProductName = ""
        var lastVariantName = ""

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
                
                var parent = it.parent
                while (parent != null) {
                    if (parent is TextInputLayout) {
                        parent.isEnabled = enabled
                        break
                    }
                    parent = parent.parent
                }
            }
            if (!enabled) {
                view.findViewById<TextInputLayout>(R.id.tilUnit)?.endIconMode = TextInputLayout.END_ICON_NONE
                view.findViewById<TextInputLayout>(R.id.tilOfficialUqcPurchase)?.endIconMode = TextInputLayout.END_ICON_NONE
                view.findViewById<TextInputLayout>(R.id.tilSupplyClassificationPurchase)?.endIconMode = TextInputLayout.END_ICON_NONE
            } else {
                view.findViewById<TextInputLayout>(R.id.tilUnit)?.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                view.findViewById<TextInputLayout>(R.id.tilOfficialUqcPurchase)?.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                view.findViewById<TextInputLayout>(R.id.tilSupplyClassificationPurchase)?.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }
        }

        val onVariantSettled = {
            val vName = etVariant.text.toString().trim()
            val pName = etProduct.text.toString().trim()
            
            if (vName != lastVariantName || pName != lastProductName) {
                lastVariantName = vName
                lastProductName = pName
                
                if (pName.isNotBlank()) {
                    lifecycleScope.launch {
                        val match = withContext(Dispatchers.IO) {
                            productRepo.getByNameAndVariant(pName, vName)
                        }
                        if (match != null && match.isActive) {
                            withContext(Dispatchers.Main) {
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

                                setProductMasterFieldsEnabled(false)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                spinnerSupplyClassPurchase.setText("TAXABLE", false)
                                setProductMasterFieldsEnabled(true)
                            }
                        }
                    }
                } else {
                    setProductMasterFieldsEnabled(true)
                }
            }
        }

        // Reveal variant + autofill when the user picks / settles
        // on a product name.
        val onProductSettled = {
            val name = etProduct.text?.toString()?.trim().orEmpty()
            
            if (name != lastProductName) {
                lastProductName = name
                lastVariantName = ""
                etVariant.setText("")
                
                // 🔥 Reset fields whenever name changes to avoid stale data
                etHsn.setText("")
                etSCgst.setText("")
                etSSgst.setText("")
                etSIgst.setText("")
                etSelling.setText("")
                switchTaxInclusive.isChecked = false
                setProductMasterFieldsEnabled(true)
            }
            
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
                                    val token = verifyRepo.tokenProvider()
                                    if (!token.isNullOrEmpty()) {
                                        verifyRepo.api.getHsn(token, verify.matched_global_id).hsn_code
                                    } else null
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

        etVariant.setOnItemClickListener { _, _, _, _ -> onVariantSettled() }
        etVariant.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onVariantSettled()
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
                    onVariantSettled()
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
                            .getUnits(token).units
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

            val cessPercent = etCessRatePurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val cessAmt = etCessAmountPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val eligibility = spinnerEligibilityItemPurchase.text?.toString()?.trim().orEmpty()
            val availedIgst = etAvailedItcIgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedCgst = etAvailedItcCgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedSgst = etAvailedItcSgstPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val availedCess = etAvailedItcCessPurchase.text?.toString()?.toDoubleOrNull() ?: 0.0
            val officialUqc = UqcMapper.displayToCode(spinnerUqcPurchase.text?.toString())

            // Purchase tax amounts
            val purchaseCgstAmt = taxable * (etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0
            val purchaseSgstAmt = taxable * (etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0
            val purchaseIgstAmt = taxable * (etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0) / 100.0

            if (cessPercent < 0 || cessAmt < 0 || availedIgst < 0 || availedCgst < 0 || availedSgst < 0 || availedCess < 0) {
                Toast.makeText(this, "Negative GSTR-2 values are not allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (eligibility.isEmpty()) {
                Toast.makeText(this, "Eligibility for ITC is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (officialUqc.isNullOrBlank()) {
                Toast.makeText(this, "Official UQC (GST Unit) is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val eps = 0.011
            if (availedIgst > purchaseIgstAmt + eps) {
                Toast.makeText(this, "Availed ITC IGST cannot exceed purchase IGST amount (${"%.2f".format(purchaseIgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedCgst > purchaseCgstAmt + eps) {
                Toast.makeText(this, "Availed ITC CGST cannot exceed purchase CGST amount (${"%.2f".format(purchaseCgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedSgst > purchaseSgstAmt + eps) {
                Toast.makeText(this, "Availed ITC SGST cannot exceed purchase SGST amount (${"%.2f".format(purchaseSgstAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (availedCess > cessAmt + eps) {
                Toast.makeText(this, "Availed ITC Cess cannot exceed cess amount (${"%.2f".format(cessAmt)})", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (eligibility in listOf("Ineligible", "None")) {
                if (availedIgst > 0.01 || availedCgst > 0.01 || availedSgst > 0.01 || availedCess > 0.01) {
                    Toast.makeText(this, "Availed ITC must be 0 when Ineligible or None", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            val finalHsnDesc = etHsnDescPurchase.text?.toString()?.trim().orEmpty().ifBlank { name }
            val variant = etVariant.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            val draft = PurchaseItemDraft(
                productName    = name.firstCapital(),
                variant        = variant?.firstCapital(),
                hsnCode        = etHsn.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                unit           = etUnit.text?.toString()?.trim()?.ifBlank { null },
                quantity       = qty,
                taxableAmount  = taxable,
                invoiceValue   = invoice,
                costPrice      = if (qty > 0) invoice / qty else 0.0,
                sellingPrice   = selling,
                isTaxInclusive = switchTaxInclusive.isChecked,
                purchaseCgst   = etPCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                purchaseSgst   = etPSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                purchaseIgst   = etPIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesCgst      = etSCgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesSgst      = etSSgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                salesIgst      = etSIgst.text?.toString()?.toDoubleOrNull() ?: 0.0,
                officialUqc    = officialUqc,
                hsnDescription = finalHsnDesc,
                cessRate       = cessPercent,
                cessPercentage = cessPercent,
                cessAmount     = cessAmt,
                eligibilityForItc = eligibility,
                availedItcIgst = availedIgst,
                availedItcCgst = availedCgst,
                availedItcSgst = availedSgst,
                availedItcCess = availedCess,
                supplyClassification = spinnerSupplyClassPurchase.text?.toString()?.trim()?.ifBlank { "TAXABLE" } ?: "TAXABLE",
                category = etCategoryPurchase.text?.toString()?.trim().orEmpty()
            )

            // Remember a brand-new custom category for future dropdowns.
            run {
                val catName = etCategoryPurchase.text?.toString()?.trim().orEmpty()
                if (catName.isNotEmpty() &&
                    com.example.easy_billing.util.ProductCategories.PREDEFINED.none { it.equals(catName, true) } &&
                    !catName.equals(com.example.easy_billing.util.ProductCategories.UNCATEGORIZED, true)
                ) {
                    lifecycleScope.launch {
                        val db = com.example.easy_billing.db.AppDatabase.getDatabase(this@PurchaseActivity)
                        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
                        val shopIdStr = try {
                            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
                        } catch (e: ClassCastException) { prefs.getInt("SHOP_ID", 0).toString() }
                        if (db.productCategoryDao().getByName(catName, shopIdStr) == null) {
                            db.productCategoryDao().insertIgnore(
                                com.example.easy_billing.db.ProductCategory(shopId = shopIdStr, name = catName)
                            )
                        }
                    }
                }
            }

            // Check for existing products with the same name+variant
            // BEFORE adding the line.
            lifecycleScope.launch {
                val db = com.example.easy_billing.db.AppDatabase.getDatabase(this@PurchaseActivity)
                val productRepo = com.example.easy_billing.repository.ProductRepository.get(this@PurchaseActivity)
                val validShopIds = productRepo.getValidShopIds()

                val existingMatch = withContext(Dispatchers.IO) {
                    db.productDao().getByNameAndVariant(
                        draft.productName,
                        draft.variant,
                        validShopIds
                    )
                }

                withContext(Dispatchers.Main) {
                    if (existingMatch != null) {
                        if (existingMatch.isActive) {
                            if (!existingMatch.isPurchased) {
                                // BLOCK: Active Manual Product
                                Toast.makeText(
                                    this@PurchaseActivity,
                                    "${existingMatch.name} is a MANUAL product. Deactivate it first before purchasing.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                // ALLOW: Active Purchased Product
                                viewModel.addLine(draft)
                                dialog.dismiss()
                            }
                        } else {
                            // SHOW DIALOG: Inactive product (either Manual or Purchased)
                            showPurchaseRestoreDialog(
                                inactive  = existingMatch,
                                qty       = qty,
                                draft     = draft,
                                lineDlg   = dialog
                            )
                        }
                    } else {
                        // Brand new product
                        viewModel.addLine(draft)
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    /* ------------------------------------------------------------------
     *  Purchase restore dialog
     *  Shown when the user adds a line whose product name+variant
     *  matches a previously deactivated shop_product row.
     * ------------------------------------------------------------------ */

    private fun showPurchaseRestoreDialog(
        inactive: Product,
        qty: Double,
        draft: com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft,
        lineDlg: AlertDialog
    ) {
        val customView = layoutInflater.inflate(R.layout.dialog_product_exists, null)
        val restoreDialog = AlertDialog.Builder(this)
            .setView(customView)
            .create()
        restoreDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvMessage = customView.findViewById<TextView>(R.id.tvMessage)
        val tvDetails = customView.findViewById<TextView>(R.id.tvDetails)
        val btnCancel  = customView.findViewById<Button>(R.id.btnCancel)
        val btnRestore = customView.findViewById<Button>(R.id.btnUpdate)   // blue = Restore
        val btnNew     = customView.findViewById<Button>(R.id.btnReplace)  // red  = Create New
        
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

        val detailsText = buildString {
            append("Last price: ₹${inactive.price}\n")
            if (!inactive.hsnCode.isNullOrBlank()) append("HSN: ${inactive.hsnCode}\n")
            if (inactive.cgstPercentage > 0 || inactive.sgstPercentage > 0)
                append("CGST: ${inactive.cgstPercentage}%  SGST: ${inactive.sgstPercentage}%\n")
            if (inactive.igstPercentage > 0)
                append("IGST: ${inactive.igstPercentage}%\n")
            append("Unit: ${inactive.unit ?: "piece"}")
        }
        tvDetails.text = detailsText

        // ── btnRestore: Restore with New Values (or just Restore if manual) ──
        btnRestore.setOnClickListener {
            viewModel.addLine(draft)   // forceCreate=false → PurchaseRepository.upsert reactivates
            restoreDialog.dismiss()
            lineDlg.dismiss()
        }

        // ── btnNew: Restore Old (only visible when isPurchased = true) ──
        btnNew.setOnClickListener {
            val oldValuesDraft = draft.copy(
                sellingPrice   = inactive.price,
                hsnCode        = inactive.hsnCode,
                salesCgst      = inactive.cgstPercentage,
                salesSgst      = inactive.sgstPercentage,
                salesIgst      = inactive.igstPercentage,
                officialUqc    = inactive.officialUqc,
                hsnDescription = inactive.hsnDescription,
                cessRate       = inactive.cessRate,
                supplyClassification = inactive.supplyClassification,
                category       = inactive.category
            )
            viewModel.addLine(oldValuesDraft)
            restoreDialog.dismiss()
            lineDlg.dismiss()
        }

        btnCancel.setOnClickListener {
            restoreDialog.dismiss()
        }

        restoreDialog.show()
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

    private fun String.firstCapital(): String =
        trim().split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }
}
