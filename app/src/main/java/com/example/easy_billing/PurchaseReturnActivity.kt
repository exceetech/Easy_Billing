package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.util.CreditAdjustmentPrompt
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.viewmodel.PurchaseReturnViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PurchaseReturnActivity — Debit Note creation
 *
 * Receives:
 *   • PURCHASE_ID (Int) — local purchase_table.id
 *
 * Architecture contract:
 *   • Stock is deducted from the **exact purchase batch** (FIFO-safe).
 *   • InventoryValuation.reduceBatches + InventoryManager.reduceStock(skipBatchConsume=true)
 *     are called by [PurchaseReturnViewModel.submitReturn].
 *   • Debit note number is generated atomically inside a Room transaction.
 *   • All writes land in Room first; SyncManager handles backend push.
 */
class PurchaseReturnActivity : BaseActivity() {

    private val viewModel: PurchaseReturnViewModel by viewModels()

    private lateinit var tvSupplierName:    TextView
    private lateinit var tvInvoiceRef:      TextView
    private lateinit var rvReturnItems:     RecyclerView
    private lateinit var tvTotalDebitValue: TextView
    private lateinit var tvItcReclaim:      TextView
    private lateinit var btnConfirmReturn:  MaterialButton
    private lateinit var btnCancelReturn:   MaterialButton

    private lateinit var tvHeaderSubtitle: TextView
    private lateinit var vHeaderDivider:   View
    private lateinit var tvSectionLabel:   TextView
    private lateinit var tvTotalDebitLabel: TextView

    // GSTR-2 Fields
    private lateinit var cvGstr2Container: MaterialCardView
    private lateinit var llGstr2Header: LinearLayout
    private lateinit var llGstr2Details: LinearLayout
    private lateinit var ivGstr2Arrow: ImageView
    private lateinit var swPreGst: SwitchMaterial
    private lateinit var actvDocumentType: AutoCompleteTextView
    private lateinit var actvReason: AutoCompleteTextView
    private lateinit var etVoucherValue: TextInputEditText
    private lateinit var etRate: TextInputEditText
    private lateinit var actvEligibility: AutoCompleteTextView
    private lateinit var etAvailedItcIntegrated: TextInputEditText
    private lateinit var etAvailedItcCentral: TextInputEditText
    private lateinit var etAvailedItcState: TextInputEditText
    private lateinit var etAvailedItcCess: TextInputEditText
    private lateinit var actvInvoiceType: AutoCompleteTextView
    private lateinit var actvPlaceOfSupplyCode: AutoCompleteTextView

    private lateinit var tilAvailedItcIntegrated: TextInputLayout
    private lateinit var tilAvailedItcCentral: TextInputLayout
    private lateinit var tilAvailedItcState: TextInputLayout
    private lateinit var tilAvailedItcCess: TextInputLayout

    private var purchaseId: Int = -1
    private var noteType: String = "D"

    private var currentIgstReturn = 0.0
    private var currentCgstReturn = 0.0
    private var currentSgstReturn = 0.0
    private var currentCessReturn = 0.0
    private var currentTaxableReturn = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_return)

        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
        noteType = intent.getStringExtra("NOTE_TYPE") ?: "D"
        if (purchaseId == -1) {
            Toast.makeText(this, "Invalid purchase ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvSupplierName    = findViewById(R.id.tvSupplierName)
        tvInvoiceRef      = findViewById(R.id.tvInvoiceRef)
        rvReturnItems     = findViewById(R.id.rvReturnItems)
        tvTotalDebitValue = findViewById(R.id.tvTotalDebitValue)
        tvItcReclaim      = findViewById(R.id.tvItcReclaim)
        btnConfirmReturn  = findViewById(R.id.btnConfirmReturn)
        btnCancelReturn   = findViewById(R.id.btnCancelReturn)

        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle)
        vHeaderDivider   = findViewById(R.id.vHeaderDivider)
        tvSectionLabel   = findViewById(R.id.tvSectionLabel)
        tvTotalDebitLabel = findViewById(R.id.tvTotalDebitLabel)

        // Bind GSTR-2 fields
        cvGstr2Container = findViewById(R.id.cvGstr2Container)
        llGstr2Header = findViewById(R.id.llGstr2Header)
        llGstr2Details = findViewById(R.id.llGstr2Details)
        ivGstr2Arrow = findViewById(R.id.ivGstr2Arrow)
        swPreGst = findViewById(R.id.swPreGst)
        actvDocumentType = findViewById(R.id.actvDocumentType)
        actvReason = findViewById(R.id.actvReason)
        etVoucherValue = findViewById(R.id.etVoucherValue)
        etRate = findViewById(R.id.etRate)
        actvEligibility = findViewById(R.id.actvEligibility)
        etAvailedItcIntegrated = findViewById(R.id.etAvailedItcIntegrated)
        etAvailedItcCentral = findViewById(R.id.etAvailedItcCentral)
        etAvailedItcState = findViewById(R.id.etAvailedItcState)
        etAvailedItcCess = findViewById(R.id.etAvailedItcCess)
        actvInvoiceType = findViewById(R.id.actvInvoiceType)
        actvPlaceOfSupplyCode = findViewById(R.id.actvPlaceOfSupplyCode)

        tilAvailedItcIntegrated = findViewById(R.id.tilAvailedItcIntegrated)
        tilAvailedItcCentral = findViewById(R.id.tilAvailedItcCentral)
        tilAvailedItcState = findViewById(R.id.tilAvailedItcState)
        tilAvailedItcCess = findViewById(R.id.tilAvailedItcCess)

        // Adapt UI colors & labels dynamically
        if (noteType == "C") {
            tvHeaderSubtitle.text = "Receive Credit Note"
            vHeaderDivider.setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            tvSectionLabel.text = "Select items & quantities to receive credit on"
            tvTotalDebitLabel.text = "Total Credit Value"
            tvTotalDebitValue.setTextColor(android.graphics.Color.parseColor("#2563EB"))
            btnConfirmReturn.text = "Confirm & Save Credit Note"
            btnConfirmReturn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
            cvGstr2Container.visibility = View.VISIBLE
            setupGstr2Fields()
        } else {
            tvHeaderSubtitle.text = "Raise Debit Note"
            vHeaderDivider.setBackgroundColor(android.graphics.Color.parseColor("#DC2626"))
            tvSectionLabel.text = "Select items & quantities to return"
            tvTotalDebitLabel.text = "Total Debit Value"
            tvTotalDebitValue.setTextColor(android.graphics.Color.parseColor("#DC2626"))
            btnConfirmReturn.text = "Confirm & Issue Debit Note"
            btnConfirmReturn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
            cvGstr2Container.visibility = View.VISIBLE
            setupGstr2Fields()
        }

        rvReturnItems.layoutManager = LinearLayoutManager(this)

        btnCancelReturn.setOnClickListener { finish() }
        btnConfirmReturn.setOnClickListener { confirmAndSubmit() }

        observeViewModel()
        viewModel.loadPurchase(purchaseId)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {

        lifecycleScope.launch {
            viewModel.purchase.collectLatest { p ->
                p ?: return@collectLatest
                tvSupplierName.text = p.supplierName
                val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                val dateStr = p.invoiceDate?.let { dateFmt.format(Date(it)) }
                    ?: dateFmt.format(Date(p.createdAt))
                tvInvoiceRef.text = "Invoice #${p.invoiceNumber}  ·  $dateStr"
            }
        }

        lifecycleScope.launch {
            viewModel.purchaseItems.collectLatest { items ->
                if (items.isEmpty()) return@collectLatest
                val adapter = PurchaseReturnItemAdapter(
                    items             = items,
                    shopStateCode     = viewModel.shopStateCode.value,
                    supplierGstin     = viewModel.purchase.value?.supplierGstin,
                    supplierStateName = viewModel.purchase.value?.state,
                    noteType          = noteType,
                    maxReturnableQty  = { productId, purchasedQty ->
                        viewModel.maxReturnableQty(productId, purchasedQty)
                    },
                    onTotalChanged   = { totalDebit, gst ->
                        tvTotalDebitValue.text = CurrencyHelper.format(this@PurchaseReturnActivity, totalDebit)
                        tvItcReclaim.text      = CurrencyHelper.format(this@PurchaseReturnActivity, gst)
                        val rTotal = Math.round(totalDebit * 100.0) / 100.0
                        etVoucherValue.setText(rTotal.toString())
                        rederiveItcFromLines()
                    }
                )
                rvReturnItems.adapter = adapter
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                btnConfirmReturn.isEnabled = !loading
                btnConfirmReturn.text = if (loading)
                    "Processing…"
                else if (noteType == "C")
                    "Confirm & Save Credit Note"
                else
                    "Confirm & Issue Debit Note"
            }
        }

        lifecycleScope.launch {
            viewModel.result.collectLatest { result ->
                result ?: return@collectLatest
                when (result) {
                    is PurchaseReturnViewModel.Result.Success -> {
                        val msg = if (noteType == "C")
                            "Credit Note ${result.noteNumber} received. Stock adjusted."
                        else
                            "Debit Note ${result.noteNumber} issued. Stock adjusted."
                        Toast.makeText(
                            this@PurchaseReturnActivity,
                            msg,
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()

                        // If the purchase was on credit, adjust the supplier's
                        // balance now — clamped, and asking cash-vs-advance only
                        // on an overshoot. Skips itself for cash purchases.
                        // Finish only after the owner has answered.
                        val adj = result.creditAdjustment
                        if (adj == null) {
                            finish()
                        } else {
                            val kind = if (adj.isDebitNote)
                                CreditAdjustmentRepository.Kind.PURCHASE_DEBIT_NOTE
                            else
                                CreditAdjustmentRepository.Kind.PURCHASE_CREDIT_NOTE
                            CreditAdjustmentPrompt.handlePurchase(
                                activity = this@PurchaseReturnActivity,
                                purchaseId = adj.purchaseId,
                                kind = kind,
                                amount = adj.amount,
                                documentLocalId = adj.docSeq,
                                onDone = { finish() }
                            )
                        }
                    }
                    is PurchaseReturnViewModel.Result.ValidationError -> {
                        Toast.makeText(
                            this@PurchaseReturnActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                    }
                    is PurchaseReturnViewModel.Result.SaveError -> {
                        Toast.makeText(
                            this@PurchaseReturnActivity,
                            "Failed to save: ${result.cause.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun setupGstr2Fields() {
        // Expand/Collapse
        llGstr2Header.setOnClickListener {
            if (llGstr2Details.visibility == View.VISIBLE) {
                llGstr2Details.visibility = View.GONE
                ivGstr2Arrow.animate().rotation(0f).setDuration(200).start()
            } else {
                llGstr2Details.visibility = View.VISIBLE
                ivGstr2Arrow.animate().rotation(180f).setDuration(200).start()
            }
        }

        // Dropdowns setup
        val docTypes = arrayOf("Debit Note", "Credit Note", "Refund Voucher")
        actvDocumentType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, docTypes))
        if (noteType == "C") {
            actvDocumentType.setText("Credit Note", false)
        } else {
            actvDocumentType.setText("Debit Note", false)
        }

        val reasons = arrayOf("Sales return", "Purchase return", "Post sale discount", "Deficiency in services", "Correction in invoice", "Other")
        actvReason.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, reasons))
        actvReason.setText("Purchase return", false)

        val eligibilities = arrayOf("Inputs", "Capital goods", "Input services", "Ineligible", "None")
        actvEligibility.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, eligibilities))
        actvEligibility.setText("Inputs", false)

        val invoiceTypes = arrayOf("Regular", "SEZ supplies with payment", "SEZ supplies without payment", "Deemed Exp")
        actvInvoiceType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, invoiceTypes))
        actvInvoiceType.setText("Regular", false)

        // State Codes Dropdown
        val statesList = GstEngine.INDIA_STATES.entries.map { "${it.key} - ${it.value}" }.toTypedArray()
        actvPlaceOfSupplyCode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, statesList))

        // Pre-select place of supply based on supplier's state if we can match it
        lifecycleScope.launch {
            viewModel.purchase.collectLatest { p ->
                p ?: return@collectLatest
                val code = GstEngine.getStateCodeFromName(p.state) ?: GstEngine.getStateCode(p.supplierGstin)
                if (code.isNotBlank()) {
                    val matched = statesList.firstOrNull { it.startsWith(code) }
                    if (matched != null) {
                        actvPlaceOfSupplyCode.setText(matched, false)
                    }
                }
            }
        }

        // Eligibility change listener to enable/disable and default ITC amount fields
        actvEligibility.setOnItemClickListener { _, _, position, _ ->
            val selected = eligibilities[position]
            updateItcFieldsState(selected)
        }
    }

    private fun updateItcFieldsState(eligibility: String) {
        val isEligible = eligibility in listOf("Inputs", "Capital goods", "Input services")
        tilAvailedItcIntegrated.isEnabled = isEligible
        tilAvailedItcCentral.isEnabled = isEligible
        tilAvailedItcState.isEnabled = isEligible
        tilAvailedItcCess.isEnabled = isEligible

        if (!isEligible) {
            etAvailedItcIntegrated.setText("0.0")
            etAvailedItcCentral.setText("0.0")
            etAvailedItcState.setText("0.0")
            etAvailedItcCess.setText("0.0")
        } else {
            rederiveItcFromLines()
        }
    }

    private fun rederiveItcFromLines() {
        val adapter = rvReturnItems.adapter as? PurchaseReturnItemAdapter ?: return
        val lines = adapter.getReturnLines()
        val p = viewModel.purchase.value ?: return
        val stateCode = viewModel.shopStateCode.value

        var igst = 0.0
        var cgst = 0.0
        var sgst = 0.0
        var cess = 0.0
        var taxable = 0.0

        for ((item, qty) in lines) {
            val supplierState = GstEngine.getStateCodeFromName(p.state) ?: GstEngine.getStateCode(p.supplierGstin)
            val sameState = if (stateCode.isNotBlank() && supplierState.isNotBlank()) stateCode == supplierState else item.purchaseIgstPercentage <= 0.0
            val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
            val tax = qty * unitTaxable
            val cg = if (sameState) tax * item.purchaseCgstPercentage / 100.0 else 0.0
            val sg = if (sameState) tax * item.purchaseSgstPercentage / 100.0 else 0.0
            val ig = if (!sameState) tax * item.purchaseIgstPercentage / 100.0 else 0.0
            val ce = if (item.quantity > 0.0) (qty / item.quantity) * item.cessAmount else 0.0

            igst += ig
            cgst += cg
            sgst += sg
            cess += ce
            taxable += tax
        }

        currentIgstReturn = Math.round(igst * 100.0) / 100.0
        currentCgstReturn = Math.round(cgst * 100.0) / 100.0
        currentSgstReturn = Math.round(sgst * 100.0) / 100.0
        currentCessReturn = Math.round(cess * 100.0) / 100.0
        currentTaxableReturn = Math.round(taxable * 100.0) / 100.0

        val eligibility = actvEligibility.text.toString()
        val isEligible = eligibility in listOf("Inputs", "Capital goods", "Input services")
        if (isEligible) {
            etAvailedItcIntegrated.setText(currentIgstReturn.toString())
            etAvailedItcCentral.setText(currentCgstReturn.toString())
            etAvailedItcState.setText(currentSgstReturn.toString())
            etAvailedItcCess.setText(currentCessReturn.toString())
        }

        val firstItem = lines.keys.firstOrNull()
        if (firstItem != null) {
            val supplierState = GstEngine.getStateCodeFromName(p.state) ?: GstEngine.getStateCode(p.supplierGstin)
            val sameState = if (stateCode.isNotBlank() && supplierState.isNotBlank()) stateCode == supplierState else firstItem.purchaseIgstPercentage <= 0.0
            val derivedRate = if (!sameState) firstItem.purchaseIgstPercentage else (firstItem.purchaseCgstPercentage + firstItem.purchaseSgstPercentage)
            etRate.setText(derivedRate.toString())
        } else {
            etRate.setText("0.0")
        }
    }

    private fun confirmAndSubmit() {
        val adapter = rvReturnItems.adapter as? PurchaseReturnItemAdapter ?: return
        val lines   = adapter.getReturnLines()

        if (lines.isEmpty()) {
            val msg = if (noteType == "C") "Please select at least one item to receive credit on." else "Please select at least one item to return."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val p = viewModel.purchase.value
        if (p == null) {
            Toast.makeText(this, "Purchase not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        val preGst = if (swPreGst.isChecked) "Y" else "N"
        val docTypeVal = actvDocumentType.text.toString()
        val reasonVal = actvReason.text.toString()
        val voucherValueVal = etVoucherValue.text.toString().toDoubleOrNull() ?: 0.0
        val rateVal = etRate.text.toString().toDoubleOrNull() ?: 0.0
        val eligibilityVal = actvEligibility.text.toString()
        val availedIntegratedVal = etAvailedItcIntegrated.text.toString().toDoubleOrNull() ?: 0.0
        val availedCentralVal = etAvailedItcCentral.text.toString().toDoubleOrNull() ?: 0.0
        val availedStateVal = etAvailedItcState.text.toString().toDoubleOrNull() ?: 0.0
        val availedCessVal = etAvailedItcCess.text.toString().toDoubleOrNull() ?: 0.0
        val invoiceTypeVal = actvInvoiceType.text.toString()
        val placeOfSupplyCodeRaw = actvPlaceOfSupplyCode.text.toString()
        val placeOfSupplyCodeVal = placeOfSupplyCodeRaw.split("-").firstOrNull()?.trim() ?: ""

        // Client-side validations
        if (docTypeVal.isBlank()) {
            Toast.makeText(this, "Document Type is required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (reasonVal.isBlank()) {
            Toast.makeText(this, "Reason is required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (voucherValueVal <= 0.0) {
            Toast.makeText(this, "Note/Refund Voucher Value must be > 0.", Toast.LENGTH_SHORT).show()
            return
        }
        if (voucherValueVal < currentTaxableReturn) {
            Toast.makeText(this, "Note/Refund Voucher Value (₹%.2f) must stay >= taxable value (₹%.2f).".format(voucherValueVal, currentTaxableReturn), Toast.LENGTH_SHORT).show()
            return
        }
        if (rateVal < 0.0) {
            Toast.makeText(this, "Rate must be >= 0.", Toast.LENGTH_SHORT).show()
            return
        }
        if (eligibilityVal.isBlank()) {
            Toast.makeText(this, "Eligibility for ITC is required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (eligibilityVal in listOf("Ineligible", "None")) {
            if (availedIntegratedVal != 0.0 || availedCentralVal != 0.0 || availedStateVal != 0.0 || availedCessVal != 0.0) {
                Toast.makeText(this, "If eligibility is Ineligible/None, availed ITC values must be 0.", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (availedIntegratedVal > currentIgstReturn) {
                Toast.makeText(this, "Availed ITC Integrated Tax cannot exceed IGST (₹%.2f).".format(currentIgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedCentralVal > currentCgstReturn) {
                Toast.makeText(this, "Availed ITC Central Tax cannot exceed CGST (₹%.2f).".format(currentCgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedStateVal > currentSgstReturn) {
                Toast.makeText(this, "Availed ITC State/UT Tax cannot exceed SGST (₹%.2f).".format(currentSgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedCessVal > currentCessReturn) {
                Toast.makeText(this, "Availed ITC Cess cannot exceed Cess (₹%.2f).".format(currentCessReturn), Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (invoiceTypeVal.isBlank()) {
            Toast.makeText(this, "Invoice Type is required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (placeOfSupplyCodeVal.isBlank()) {
            Toast.makeText(this, "Place of Supply Code is required.", Toast.LENGTH_SHORT).show()
            return
        }

        val totalUnits = lines.values.sum()

        val title = if (noteType == "C") "Receive Credit Note?" else "Issue Debit Note?"
        val msg = if (noteType == "C") {
            "You are receiving ${"%.2f".format(totalUnits)} additional unit(s) from ${p.supplierName}" +
            " (Invoice: ${p.invoiceNumber}).\n\n" +
            "Stock will be increased and a Credit Note will be generated. Continue?"
        } else {
            "You are returning ${"%.2f".format(totalUnits)} unit(s) to ${p.supplierName}" +
            " (Invoice: ${p.invoiceNumber}).\n\n" +
            "Stock will be reduced from the exact purchase batch and a " +
            "Debit Note will be generated. Continue?"
        }
        val posBtn = if (noteType == "C") "Yes, Receive CN" else "Yes, Issue DN"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(posBtn) { d, _ ->
                d.dismiss()
                viewModel.submitReturn(
                    lines = lines,
                    noteType = noteType,
                    preGst = preGst,
                    documentType = docTypeVal,
                    reasonForIssuingDocument = reasonVal,
                    noteRefundVoucherValue = voucherValueVal,
                    rate = rateVal,
                    eligibilityForItc = eligibilityVal,
                    availedItcIntegratedTax = availedIntegratedVal,
                    availedItcCentralTax = availedCentralVal,
                    availedItcStateTax = availedStateVal,
                    availedItcCess = availedCessVal,
                    invoiceType = invoiceTypeVal,
                    placeOfSupplyCode = placeOfSupplyCodeVal
                )
            }
            .setNegativeButton("Review") { d, _ -> d.dismiss() }
            .show()
    }
}
