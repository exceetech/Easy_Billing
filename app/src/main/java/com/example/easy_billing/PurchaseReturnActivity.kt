package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var tvHeaderDate:      TextView
    private lateinit var rvReturnItems:     RecyclerView
    private lateinit var tvTotalDebitValue: TextView
    private lateinit var tvItcReclaim:      TextView
    private lateinit var btnConfirmReturn:  MaterialButton
    private lateinit var btnCancelReturn:   MaterialButton

    private lateinit var tvHeaderSubtitle: TextView
    private lateinit var tvHeaderSubtitleAccent: TextView
    private lateinit var vHeaderDivider:   View
    private lateinit var tvSectionLabel:   TextView
    private lateinit var tvTotalDebitLabel: TextView

    // GSTR-2 Fields
    private lateinit var cvGstr2Container: LinearLayout
    private lateinit var llGstr2Header: LinearLayout
    private lateinit var llGstr2Details: LinearLayout
    private lateinit var ivGstr2Arrow: ImageView
    private lateinit var swPreGst: MaterialSwitch
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

    private lateinit var tilAvailedItcIntegrated: FrameLayout
    private lateinit var tilAvailedItcCentral: FrameLayout
    private lateinit var tilAvailedItcState: FrameLayout
    private lateinit var tilAvailedItcCess: FrameLayout

    private lateinit var tvLabelDocumentType: TextView
    private lateinit var tvLabelReason: TextView
    private lateinit var tvLabelVoucherValue: TextView
    private lateinit var tvLabelRate: TextView
    private lateinit var tvLabelEligibility: TextView
    private lateinit var tvLabelItcIntegrated: TextView
    private lateinit var tvLabelItcCentral: TextView
    private lateinit var tvLabelItcState: TextView
    private lateinit var tvLabelItcCess: TextView
    private lateinit var tvLabelInvoiceType: TextView
    private lateinit var tvLabelPlaceOfSupply: TextView

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

        setupToolbar(R.id.toolbar)

        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
        noteType = intent.getStringExtra("NOTE_TYPE") ?: "D"
        if (purchaseId == -1) {
            Toast.makeText(this, R.string.purchase_return_invalid_id, Toast.LENGTH_SHORT).show()
            // Was calling finish() in the same instant as the toast, which
            // tears the toast down with the activity before it's readable.
            // Rare path (bad intent extra), but still worth a beat to read.
            android.os.Handler(mainLooper).postDelayed({ finish() }, 600)
            return
        }

        tvSupplierName    = findViewById(R.id.tvSupplierName)
        tvInvoiceRef      = findViewById(R.id.tvInvoiceRef)
        tvHeaderDate      = findViewById(R.id.tvHeaderDate)
        rvReturnItems     = findViewById(R.id.rvReturnItems)
        tvTotalDebitValue = findViewById(R.id.tvTotalDebitValue)
        tvItcReclaim      = findViewById(R.id.tvItcReclaim)
        btnConfirmReturn  = findViewById(R.id.btnConfirmReturn)
        btnCancelReturn   = findViewById(R.id.btnCancelReturn)

        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle)
        tvHeaderSubtitleAccent = findViewById(R.id.tvHeaderSubtitleAccent)
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

        tvLabelDocumentType = findViewById(R.id.tvLabelDocumentType)
        tvLabelReason = findViewById(R.id.tvLabelReason)
        tvLabelVoucherValue = findViewById(R.id.tvLabelVoucherValue)
        tvLabelRate = findViewById(R.id.tvLabelRate)
        tvLabelEligibility = findViewById(R.id.tvLabelEligibility)
        tvLabelItcIntegrated = findViewById(R.id.tvLabelItcIntegrated)
        tvLabelItcCentral = findViewById(R.id.tvLabelItcCentral)
        tvLabelItcState = findViewById(R.id.tvLabelItcState)
        tvLabelItcCess = findViewById(R.id.tvLabelItcCess)
        tvLabelInvoiceType = findViewById(R.id.tvLabelInvoiceType)
        tvLabelPlaceOfSupply = findViewById(R.id.tvLabelPlaceOfSupply)

        // Adapt UI colors & labels dynamically
        if (noteType == "C") {
            tvHeaderSubtitle.text = getString(R.string.purchase_return_receive_credit)
            tvHeaderSubtitleAccent.text = getString(R.string.purchase_return_note_word)
            tvHeaderSubtitleAccent.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            tvSectionLabel.text = getString(R.string.purchase_return_section_credit)
            tvTotalDebitLabel.text = getString(R.string.purchase_return_total_credit_value)
            btnConfirmReturn.text = getString(R.string.purchase_return_confirm_credit_note)
            cvGstr2Container.visibility = View.VISIBLE
            setupGstr2Fields()
        } else {
            tvHeaderSubtitle.text = getString(R.string.purchase_return_raise_debit)
            tvHeaderSubtitleAccent.text = getString(R.string.purchase_return_note_word)
            tvHeaderSubtitleAccent.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            tvSectionLabel.text = getString(R.string.purchase_return_section_debit)
            tvTotalDebitLabel.text = getString(R.string.purchase_return_total_debit_value)
            btnConfirmReturn.text = getString(R.string.purchase_return_confirm_debit_note)
            cvGstr2Container.visibility = View.VISIBLE
            setupGstr2Fields()
        }

        rvReturnItems.layoutManager = LinearLayoutManager(this)
        rvReturnItems.clipToOutline = true

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
                tvInvoiceRef.text = "Invoice ${p.invoiceNumber}"
                val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                tvHeaderDate.text = p.invoiceDate?.let { dateFmt.format(Date(it)) }
                    ?: dateFmt.format(Date(p.createdAt))
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
                    getString(R.string.purchase_return_processing)
                else if (noteType == "C")
                    getString(R.string.purchase_return_confirm_credit_note)
                else
                    getString(R.string.purchase_return_confirm_debit_note)
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
                            "Failed to save: ${result.cause.message}", // interpolation — skip resource replace
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

        // Dropdowns setup — uses the same hand-built "chooser sheet" popup
        // as activity_invoice.xml's GST option pickers (showChooserPopup in
        // InvoiceActivity), not the native AutoCompleteTextView dropdown.
        val dropdownLabels = mapOf(
            actvDocumentType to tvLabelDocumentType,
            actvReason to tvLabelReason,
            actvEligibility to tvLabelEligibility,
            actvInvoiceType to tvLabelInvoiceType,
            actvPlaceOfSupplyCode to tvLabelPlaceOfSupply
        )
        for ((field, label) in dropdownLabels) {
            field.inputType = android.text.InputType.TYPE_NULL
            field.setOnFocusChangeListener { _, _ -> updateFloatingLabel(label, field, animate = true) }
        }

        // Plain amount fields: hint-only when empty & unfocused, label fades/
        // slides in on focus or once a value is entered — same treatment.
        val plainFieldLabels = mapOf(
            etVoucherValue to tvLabelVoucherValue,
            etRate to tvLabelRate,
            etAvailedItcIntegrated to tvLabelItcIntegrated,
            etAvailedItcCentral to tvLabelItcCentral,
            etAvailedItcState to tvLabelItcState,
            etAvailedItcCess to tvLabelItcCess
        )
        for ((field, label) in plainFieldLabels) {
            field.setOnFocusChangeListener { _, _ -> updateFloatingLabel(label, field, animate = true) }
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    updateFloatingLabel(label, field, animate = true)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val docTypes = arrayOf(
            getString(R.string.purchase_return_doctype_debit),
            getString(R.string.purchase_return_doctype_credit),
            getString(R.string.purchase_return_doctype_refund)
        )
        if (noteType == "C") {
            actvDocumentType.setText(getString(R.string.purchase_return_doctype_credit), false)
        } else {
            actvDocumentType.setText(getString(R.string.purchase_return_doctype_debit), false)
        }
        actvDocumentType.setOnClickListener {
            showGstr2ChooserPopup(actvDocumentType, docTypes.toList(), tvLabelDocumentType)
        }

        val reasons = arrayOf(
            getString(R.string.purchase_return_reason_sales_return),
            getString(R.string.reason_purchase_return),
            getString(R.string.purchase_return_reason_discount),
            getString(R.string.purchase_return_reason_deficiency),
            getString(R.string.purchase_return_reason_correction),
            getString(R.string.purchase_return_reason_other)
        )
        actvReason.setText(getString(R.string.reason_purchase_return), false)
        actvReason.setOnClickListener {
            showGstr2ChooserPopup(actvReason, reasons.toList(), tvLabelReason)
        }

        val eligibilities = arrayOf(
            getString(R.string.purchase_eligibility_inputs),
            getString(R.string.purchase_eligibility_capital_goods),
            getString(R.string.purchase_eligibility_input_services),
            getString(R.string.purchase_eligibility_ineligible),
            getString(R.string.purchase_eligibility_none)
        )
        actvEligibility.setText(getString(R.string.purchase_eligibility_inputs), false)
        actvEligibility.setOnClickListener {
            showGstr2ChooserPopup(actvEligibility, eligibilities.toList(), tvLabelEligibility) { selected ->
                updateItcFieldsState(selected)
            }
        }

        val invoiceTypes = arrayOf(
            getString(R.string.purchase_invoice_type_regular),
            getString(R.string.purchase_invoice_type_sez_with_payment),
            getString(R.string.purchase_invoice_type_sez_without_payment),
            getString(R.string.purchase_invoice_type_deemed_exp)
        )
        actvInvoiceType.setText(getString(R.string.purchase_invoice_type_regular), false)
        actvInvoiceType.setOnClickListener {
            showGstr2ChooserPopup(actvInvoiceType, invoiceTypes.toList(), tvLabelInvoiceType)
        }

        // State Codes Dropdown
        val statesList = GstEngine.INDIA_STATES.entries.map { "${it.key} - ${it.value}" }.toTypedArray()
        actvPlaceOfSupplyCode.setOnClickListener {
            showGstr2ChooserPopup(actvPlaceOfSupplyCode, statesList.toList(), tvLabelPlaceOfSupply)
        }

        // Pre-select place of supply based on supplier's state if we can match it
        lifecycleScope.launch {
            viewModel.purchase.collectLatest { p ->
                p ?: return@collectLatest
                val code = GstEngine.getStateCodeFromName(p.state) ?: GstEngine.getStateCode(p.supplierGstin)
                if (code.isNotBlank()) {
                    val matched = statesList.firstOrNull { it.startsWith(code) }
                    if (matched != null) {
                        actvPlaceOfSupplyCode.setText(matched, false)
                        updateFloatingLabel(tvLabelPlaceOfSupply, actvPlaceOfSupplyCode, animate = true)
                    }
                }
            }
        }

        // These four already carry a default selection at this point, so
        // their label should be visible immediately — no animation, since
        // this isn't a user-driven focus event.
        for ((field, label) in dropdownLabels) {
            updateFloatingLabel(label, field, animate = false)
        }
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * The exact "chooser sheet" popup used by activity_invoice.xml's GST
     * option pickers (InvoiceActivity.showChooserPopup) — a PopupWindow
     * anchored below the field box, bg_pos_dropdown sheet background,
     * 44dp rows, and the selected row highlighted with bg_pos_row_selected
     * + blue text + a check mark.
     */
    private fun showGstr2ChooserPopup(
        anchor: TextView,
        options: List<String>,
        label: TextView? = null,
        onPick: (String) -> Unit = {}
    ) {
        val current = anchor.text?.toString()?.trim().orEmpty()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dpPx(5), dpPx(5), dpPx(5), dpPx(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        // Anchor to the field BOX (parent) so the sheet drops from the
        // full-width field, not the inner value view.
        val box = (anchor.parent as? View) ?: anchor

        val sheetHeight = minOf(options.size * dpPx(44) + dpPx(10), dpPx(320))

        val popup = android.widget.PopupWindow(
            scroll, box.width, sheetHeight, true
        ).apply {
            elevation = dpPx(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        options.forEach { opt ->
            val isSel = opt.equals(current, ignoreCase = true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpPx(44))
                setPadding(dpPx(12), 0, dpPx(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val label = TextView(this).apply {
                text = opt
                textSize = 14f
                setTextColor(Color.parseColor(if (isSel) "#185FA5" else "#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            if (isSel) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(Color.parseColor("#185FA5"))
                    layoutParams = LinearLayout.LayoutParams(dpPx(16), dpPx(16))
                })
            }
            row.setOnClickListener {
                anchor.text = opt
                if (label != null) updateFloatingLabel(label, anchor, animate = true)
                popup.dismiss()
                onPick(opt)
            }
            container.addView(row)
        }

        popup.showAsDropDown(box, 0, dpPx(6))
    }

    /**
     * Labels are now static and always visible (matching the rest of the
     * app's form pages) — this used to fade/slide [label] in on focus or
     * content and null out the field's hint while it did. Kept as a no-op
     * function rather than removed, since call sites throughout this file
     * still invoke it after every dropdown pick / focus change.
     */
    private fun updateFloatingLabel(label: TextView, field: TextView, animate: Boolean) {
        label.alpha = 1f
        label.translationY = 0f
    }

    private fun updateItcFieldsState(eligibility: String) {
        val isEligible = eligibility in listOf(
            getString(R.string.purchase_eligibility_inputs),
            getString(R.string.purchase_eligibility_capital_goods),
            getString(R.string.purchase_eligibility_input_services)
        )
        val fieldAlpha = if (isEligible) 1f else 0.5f

        tilAvailedItcIntegrated.isEnabled = isEligible
        tilAvailedItcIntegrated.alpha = fieldAlpha
        tilAvailedItcCentral.isEnabled = isEligible
        tilAvailedItcCentral.alpha = fieldAlpha
        tilAvailedItcState.isEnabled = isEligible
        tilAvailedItcState.alpha = fieldAlpha
        tilAvailedItcCess.isEnabled = isEligible
        tilAvailedItcCess.alpha = fieldAlpha

        etAvailedItcIntegrated.isEnabled = isEligible
        etAvailedItcCentral.isEnabled = isEligible
        etAvailedItcState.isEnabled = isEligible
        etAvailedItcCess.isEnabled = isEligible

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
        val isEligible = eligibility in listOf(
            getString(R.string.purchase_eligibility_inputs),
            getString(R.string.purchase_eligibility_capital_goods),
            getString(R.string.purchase_eligibility_input_services)
        )
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
            val msg = if (noteType == "C") getString(R.string.purchase_return_select_item_credit) else getString(R.string.purchase_return_select_item_debit)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val p = viewModel.purchase.value
        if (p == null) {
            Toast.makeText(this, R.string.purchase_return_not_loaded, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.purchase_return_doctype_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (reasonVal.isBlank()) {
            Toast.makeText(this, R.string.purchase_return_reason_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (voucherValueVal <= 0.0) {
            Toast.makeText(this, R.string.purchase_return_voucher_value_positive, Toast.LENGTH_SHORT).show()
            return
        }
        if (voucherValueVal < currentTaxableReturn) {
            Toast.makeText(this, getString(R.string.purchase_return_voucher_value_min).format(voucherValueVal, currentTaxableReturn), Toast.LENGTH_SHORT).show()
            return
        }
        if (rateVal < 0.0) {
            Toast.makeText(this, R.string.purchase_return_rate_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (eligibilityVal.isBlank()) {
            Toast.makeText(this, R.string.purchase_return_eligibility_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (eligibilityVal in listOf(getString(R.string.purchase_eligibility_ineligible), getString(R.string.purchase_eligibility_none))) {
            if (availedIntegratedVal != 0.0 || availedCentralVal != 0.0 || availedStateVal != 0.0 || availedCessVal != 0.0) {
                Toast.makeText(this, R.string.purchase_return_itc_must_be_zero, Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (availedIntegratedVal > currentIgstReturn) {
                Toast.makeText(this, getString(R.string.purchase_return_itc_integrated_exceeds).format(currentIgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedCentralVal > currentCgstReturn) {
                Toast.makeText(this, getString(R.string.purchase_return_itc_central_exceeds).format(currentCgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedStateVal > currentSgstReturn) {
                Toast.makeText(this, getString(R.string.purchase_return_itc_state_exceeds).format(currentSgstReturn), Toast.LENGTH_SHORT).show()
                return
            }
            if (availedCessVal > currentCessReturn) {
                Toast.makeText(this, getString(R.string.purchase_return_itc_cess_exceeds).format(currentCessReturn), Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (invoiceTypeVal.isBlank()) {
            Toast.makeText(this, R.string.purchase_return_invoice_type_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (placeOfSupplyCodeVal.isBlank()) {
            Toast.makeText(this, R.string.purchase_return_place_of_supply_required, Toast.LENGTH_SHORT).show()
            return
        }

        val totalUnits = lines.values.sum()

        val isCredit = noteType == "C"
        val eyebrow = "${p.invoiceNumber} · ${p.supplierName}".uppercase()
        val titleAccent = if (isCredit) getString(R.string.purchase_return_title_accent_credit) else getString(R.string.purchase_return_title_accent_debit)
        val msg = if (isCredit) {
            "You are receiving ${"%.2f".format(totalUnits)} additional unit(s) from ${p.supplierName}" +
            " (Invoice: ${p.invoiceNumber}). Stock will be increased and a credit note will be generated."
        } else {
            "You are returning ${"%.2f".format(totalUnits)} unit(s) to ${p.supplierName}" +
            " (Invoice: ${p.invoiceNumber}). Stock will be reduced from the exact purchase batch and a " +
            "debit note will be generated."
        }
        val posBtn = if (isCredit) getString(R.string.purchase_return_confirm_yes_cn) else getString(R.string.purchase_return_confirm_yes_dn)

        val view = layoutInflater.inflate(R.layout.dialog_confirm_purchase_note, null)

        view.findViewById<TextView>(R.id.tvNoteEyebrow).text = eyebrow
        view.findViewById<TextView>(R.id.tvNoteTitleLead).text = if (isCredit) getString(R.string.purchase_return_title_lead_receive) else getString(R.string.purchase_return_title_lead_issue)
        view.findViewById<TextView>(R.id.tvNoteTitleAccent).text = titleAccent
        view.findViewById<TextView>(R.id.tvNoteMessage).text = msg

        val badgeFrame = view.findViewById<FrameLayout>(R.id.badgeNoteFrame)
        val badgeIcon = view.findViewById<ImageView>(R.id.ivNoteBadge)
        val confirmBtn = view.findViewById<MaterialButton>(R.id.btnConfirmNote)
        val reviewBtn = view.findViewById<MaterialButton>(R.id.btnReviewNote)

        if (isCredit) {
            badgeFrame.setBackgroundResource(R.drawable.bg_circle_soft_teal)
            badgeIcon.setImageResource(R.drawable.ic_lc_check)
            badgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F6E56"))
            confirmBtn.setBackgroundResource(R.drawable.bg_login_cta_green)
            confirmBtn.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_lc_check)
        }
        confirmBtn.text = posBtn

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        confirmBtn.setOnClickListener {
            dialog.dismiss()
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
        reviewBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
