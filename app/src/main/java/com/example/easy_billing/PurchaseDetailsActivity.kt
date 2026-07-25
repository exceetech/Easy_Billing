package com.example.easy_billing

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.repository.PurchaseCancelRepository
import com.example.easy_billing.util.CreditAdjustmentPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PurchaseDetailsActivity
 *
 * Shows the full detail of a single purchase invoice:
 *  • Header card: supplier, invoice number, date
 *  • Summary tile: taxable, GST, total, supplier GSTIN
 *  • Line items section
 *  • Prior debit notes section (if any exist)
 *  • "Raise Return / Debit Note" action button
 *
 * Receives: PURCHASE_ID (Int) — local purchase_table.id
 */
class PurchaseDetailsActivity : BaseActivity() {

    private val viewModel: com.example.easy_billing.viewmodel.PurchaseHistoryViewModel by viewModels()

    private lateinit var tvSupplierName:  TextView
    private lateinit var tvInvoiceInfo:   TextView
    private lateinit var tvTaxableAmount: TextView
    private lateinit var tvGstAmount:     TextView
    private lateinit var tvTotalAmount:   TextView
    private lateinit var tvSupplierGstin: TextView
    private lateinit var llPurchaseItems: LinearLayout
    private lateinit var tvPriorReturnsHeader: TextView
    private lateinit var llPriorReturns:  LinearLayout
    private lateinit var btnDebitNote:    MaterialButton
    private lateinit var btnCreditNote:   MaterialButton
    private lateinit var btnClose:        MaterialButton
    private lateinit var btnCancelPurchase: MaterialButton

    private lateinit var tvStatusPill:    TextView
    private lateinit var tvSyncChip:      TextView
    private lateinit var tvTaxBreakdown:  TextView
    private lateinit var tvPlaceOfSupply: TextView
    private lateinit var layoutOwed:      View
    private lateinit var tvOwed:          TextView
    private lateinit var rowNetReturns:   View
    private lateinit var tvNetAfterReturns: TextView
    private lateinit var tvNetOriginalAmount: TextView
    private lateinit var tvCancelledBanner: TextView
    private lateinit var actionRow:       View

    private var purchaseId: Int = -1
    private var currentInvoiceNumber: String = ""
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    /** Champagne-compatible (tile, ink) pairs cycled across line-item avatars. */
    private val itemPalette = listOf(
        "#F1E4CE" to "#8A6526",  // gold
        "#E4F1EC" to "#0F6E56",  // green
        "#F5E6DF" to "#B5623A",  // terracotta
        "#E7EDF3" to "#37618A",  // slate blue
        "#F0E6F1" to "#7A4A7E",  // plum
        "#DDEEEE" to "#1D6E6E"   // deep teal
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_details)

        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
        if (purchaseId == -1) {
            Toast.makeText(this, "Invalid purchase ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar(R.id.toolbar)

        tvSupplierName       = findViewById(R.id.tvSupplierName)
        tvInvoiceInfo        = findViewById(R.id.tvInvoiceInfo)
        tvTaxableAmount      = findViewById(R.id.tvTaxableAmount)
        tvGstAmount          = findViewById(R.id.tvGstAmount)
        tvTotalAmount        = findViewById(R.id.tvTotalAmount)
        tvSupplierGstin      = findViewById(R.id.tvSupplierGstin)
        llPurchaseItems      = findViewById(R.id.llPurchaseItems)
        tvPriorReturnsHeader = findViewById(R.id.tvPriorReturnsHeader)
        llPriorReturns       = findViewById(R.id.llPriorReturns)
        btnDebitNote         = findViewById(R.id.btnDebitNote)
        btnCreditNote        = findViewById(R.id.btnCreditNote)
        btnClose             = findViewById(R.id.btnClose)
        btnCancelPurchase    = findViewById(R.id.btnCancelPurchase)

        tvStatusPill         = findViewById(R.id.tvStatusPill)
        tvSyncChip           = findViewById(R.id.tvSyncChip)
        tvTaxBreakdown       = findViewById(R.id.tvTaxBreakdown)
        tvPlaceOfSupply      = findViewById(R.id.tvPlaceOfSupply)
        layoutOwed           = findViewById(R.id.layoutOwed)
        tvOwed               = findViewById(R.id.tvOwed)
        rowNetReturns        = findViewById(R.id.rowNetReturns)
        tvNetAfterReturns    = findViewById(R.id.tvNetAfterReturns)
        tvNetOriginalAmount  = findViewById(R.id.tvNetOriginalAmount)
        tvCancelledBanner    = findViewById(R.id.tvCancelledBanner)
        actionRow            = findViewById(R.id.actionRow)

        // Clip the row stripes to the card's rounded corners, like the credit
        // accounts list.
        llPurchaseItems.clipToOutline = true
        llPriorReturns.clipToOutline = true

        btnClose.setOnClickListener { finish() }
        btnDebitNote.setOnClickListener { openNote("D") }
        btnCreditNote.setOnClickListener { openNote("C") }
        btnCancelPurchase.setOnClickListener { confirmCancelPurchase() }

        observeViewModel()
        viewModel.loadPurchaseDetail(purchaseId)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.selectedPurchase.collectLatest { p ->
                p ?: return@collectLatest
                bindHeader(p)
                // A purchase opened after it was cancelled shows its state and
                // can't be cancelled or returned again.
                if (p.isCancelled) applyCancelledState()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedItems.collectLatest { items ->
                if (items.isEmpty()) return@collectLatest
                buildItemsList(items)
            }
        }

        lifecycleScope.launch {
            viewModel.returnsForSelected.collectLatest { returns ->
                buildPriorReturns(returns)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun bindHeader(p: Purchase) {
        tvSupplierName.text = p.supplierName
        currentInvoiceNumber = p.invoiceNumber

        val dateStr = p.invoiceDate?.let { dateFmt.format(Date(it)) }
            ?: dateFmt.format(Date(p.createdAt))
        tvInvoiceInfo.text = "${p.invoiceNumber}  ·  $dateStr"

        val gst = p.cgstAmount + p.sgstAmount + p.igstAmount
        tvTaxableAmount.text = CurrencyHelper.format(this, p.taxableAmount)
        tvGstAmount.text     = CurrencyHelper.format(this, gst)
        tvTotalAmount.text   = CurrencyHelper.format(this, p.invoiceValue)
        tvSupplierGstin.text = if (!p.supplierGstin.isNullOrBlank()) p.supplierGstin else "—"

        // Status pill.
        when {
            p.isCancelled -> setPill("Cancelled", "#8A8272", "#F1EBDD")
            p.isCredit    -> setPill("On credit", "#A32D2D", "#FBEDED")
            else          -> setPill("Cash", "#0F6E56", "#E4F1EC")
        }

        // "not synced" chip.
        tvSyncChip.visibility = if (p.isSynced) View.GONE else View.VISIBLE

        // Tax breakdown + place of supply. sameState decides CGST+SGST vs IGST.
        val shopStateCode = viewModel.shopStateCode.value
        val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(p.state)
            ?: com.example.easy_billing.util.GstEngine.getStateCode(p.supplierGstin)
        val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank())
            shopStateCode == supplierState else p.igstAmount <= 0.0

        tvTaxBreakdown.text = if (sameState)
            "CGST ${CurrencyHelper.format(this, p.cgstAmount)} + SGST ${CurrencyHelper.format(this, p.sgstAmount)}"
        else
            "IGST ${CurrencyHelper.format(this, p.igstAmount)}"
        tvPlaceOfSupply.text =
            "${p.state.ifBlank { "—" }} (${if (sameState) "intra-state" else "inter-state"})"

        loadOwed(p)
    }

    private fun setPill(text: String, textHex: String, bgHex: String) {
        tvStatusPill.text = text
        tvStatusPill.setTextColor(Color.parseColor(textHex))
        tvStatusPill.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgHex))
    }

    /** Shows the supplier's outstanding balance for credit purchases. */
    private fun loadOwed(p: Purchase) {
        val accId = p.creditAccountId
        if (!p.isCredit || accId == null) {
            layoutOwed.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val owed = withContext(Dispatchers.IO) {
                val shop = getSharedPreferences("auth", MODE_PRIVATE)
                    .getInt("SHOP_ID", -1).takeIf { it > 0 } ?: return@withContext null
                com.example.easy_billing.db.AppDatabase.getDatabase(this@PurchaseDetailsActivity)
                    .creditAccountDao().getById(accId, shop)?.dueAmount
            }
            if (owed != null && owed > 0.005) {
                tvOwed.text = CurrencyHelper.format(this@PurchaseDetailsActivity, owed)
                layoutOwed.visibility = View.VISIBLE
            } else {
                layoutOwed.visibility = View.GONE
            }
        }
    }

    private fun buildItemsList(items: List<PurchaseItem>) {
        val p = viewModel.selectedPurchase.value ?: return
        val shopStateCode = viewModel.shopStateCode.value
        val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(p.state)
            ?: com.example.easy_billing.util.GstEngine.getStateCode(p.supplierGstin)

        // Net returned quantity per product (debit notes minus credit notes),
        // so a line can show a "N returned" chip.
        val returnedByProduct = viewModel.returnsForSelected.value
            .filter { it.productId != null }
            .groupBy { it.productId }
            .mapValues { (_, rows) ->
                rows.sumOf { if (it.noteType == "D") it.quantityReturned else -it.quantityReturned }
            }

        llPurchaseItems.removeAllViews()
        for ((index, item) in items.withIndex()) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_purchase_detail_row, llPurchaseItems, false)

            row.findViewById<TextView>(R.id.tvAvatar).text = monogram(item.productName)

            // A stable colour per product — same item, same colour every time.
            val (tileHex, inkHex) = itemPalette[
                Math.floorMod(item.productName.hashCode(), itemPalette.size)
            ]
            row.findViewById<TextView>(R.id.tvAvatar).apply {
                backgroundTintList = ColorStateList.valueOf(Color.parseColor(tileHex))
                setTextColor(Color.parseColor(inkHex))
            }
            row.findViewById<View>(R.id.viewItemStripe)
                .setBackgroundColor(Color.parseColor(inkHex))

            row.findViewById<TextView>(R.id.tvProductName).text = buildString {
                append(item.productName)
                if (!item.variant.isNullOrBlank()) append("  ·  ${item.variant}")
            }

            val returnedQty = returnedByProduct[item.productId] ?: 0.0
            row.findViewById<TextView>(R.id.tvReturnedChip).apply {
                if (returnedQty > 0.005) {
                    text = "${formatQty(returnedQty)} returned"
                    visibility = View.VISIBLE
                } else visibility = View.GONE
            }

            val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank()) {
                shopStateCode == supplierState
            } else {
                item.purchaseIgstPercentage <= 0.0
            }
            val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0

            // Sub-line: HSN · qty · base cost. Right caption: the GST rate.
            row.findViewById<TextView>(R.id.tvHsnQty).text = buildString {
                if (!item.hsnCode.isNullOrBlank()) append("HSN ${item.hsnCode}  ·  ")
                append("Qty ${formatQty(item.quantity)} ${item.unit ?: ""}".trim())
                append("  ·  ${CurrencyHelper.format(this@PurchaseDetailsActivity, unitTaxable)}")
            }
            row.findViewById<TextView>(R.id.tvCostAndGst).text = if (sameState) {
                val pct = (item.purchaseCgstPercentage + item.purchaseSgstPercentage)
                if (pct > 0) "GST ${pct.toInt()}%" else "0%"
            } else {
                if (item.purchaseIgstPercentage > 0) "IGST ${item.purchaseIgstPercentage.toInt()}%" else "0%"
            }
            row.findViewById<TextView>(R.id.tvLineTotal).text =
                CurrencyHelper.format(this, item.invoiceValue)

            // No trailing hairline on the last line of the card.
            row.findViewById<View>(R.id.viewItemDivider).visibility =
                if (index == items.lastIndex) View.GONE else View.VISIBLE

            llPurchaseItems.addView(row)
        }
    }

    private fun buildPriorReturns(returns: List<PurchaseReturn>) {
        if (returns.isEmpty()) {
            tvPriorReturnsHeader.visibility = View.GONE
            llPriorReturns.removeAllViews()
            llPriorReturns.visibility = View.GONE
            rowNetReturns.visibility = View.GONE
            return
        }
        llPriorReturns.visibility = View.VISIBLE

        // Effective landed cost: invoice minus debit-note returns plus any
        // credit-note additions.
        viewModel.selectedPurchase.value?.let { p ->
            val delta = returns.sumOf {
                if (it.noteType == "D") -it.invoiceValue else it.invoiceValue
            }
            val debitNotes = returns.filter { it.noteType == "D" }
            val creditNotes = returns.filter { it.noteType != "D" }
            val debitTotal = debitNotes.sumOf { it.invoiceValue }
            val creditTotal = creditNotes.sumOf { it.invoiceValue }

            fun noteWord(count: Int) = if (count == 1) "note" else "notes"

            tvNetAfterReturns.text = CurrencyHelper.format(this, p.invoiceValue + delta)

            val parts = mutableListOf("${CurrencyHelper.format(this, p.invoiceValue)} invoiced")
            if (debitNotes.isNotEmpty()) {
                parts += "${CurrencyHelper.format(this, debitTotal)} returned across " +
                    "${debitNotes.size} ${noteWord(debitNotes.size)}"
            }
            if (creditNotes.isNotEmpty()) {
                parts += "${CurrencyHelper.format(this, creditTotal)} credited across " +
                    "${creditNotes.size} ${noteWord(creditNotes.size)}"
            }

            // Moving-average redesign, Phase 5: roll up the total inventory
            // gain/loss across all debit notes on this invoice. Positive =
            // net loss (removed more shelf value than the supplier
            // refunded), negative = net gain. Omitted entirely when zero —
            // true for every Credit Note and for legacy rows that predate
            // this field, so old purchases show exactly what they always did.
            val totalVariance = debitNotes.sumOf { it.inventoryValuationVariance }
            if (kotlin.math.abs(totalVariance) >= 0.01) {
                val word = if (totalVariance > 0) "loss" else "gain"
                parts += "${CurrencyHelper.format(this, kotlin.math.abs(totalVariance))} " +
                    "inventory $word on returns"
            }

            tvNetOriginalAmount.text = parts.joinToString(" · ")
            rowNetReturns.visibility = View.VISIBLE
        }

        tvPriorReturnsHeader.visibility = View.VISIBLE
        llPriorReturns.removeAllViews()

        for ((index, ret) in returns.withIndex()) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_debit_note_row, llPriorReturns, false)

            card.findViewById<TextView>(R.id.tvNoteNumber).text =
                ret.noteNumber ?: "Return"
            card.findViewById<TextView>(R.id.tvProductName).text = ret.productName
            card.findViewById<TextView>(R.id.tvReturnedQty).text =
                "· Qty ${formatQty(ret.quantityReturned)}"
            card.findViewById<TextView>(R.id.tvNoteDate).text =
                "· ${ret.noteDate?.let { dateFmt.format(Date(it)) } ?: "—"}"

            // Debit note "D" = goods returned (money off, gold); credit note
            // "C" = extra received (money on, green).
            val isReturn = ret.noteType == "D"
            val amtHex = if (isReturn) "#8A6526" else "#0F6E56"
            card.findViewById<View>(R.id.viewNoteStripe)
                .setBackgroundColor(Color.parseColor(amtHex))
            card.findViewById<android.widget.ImageView>(R.id.ivNoteIcon).apply {
                setImageResource(
                    if (isReturn) R.drawable.ic_lc_arrow_up_right
                    else R.drawable.ic_lc_arrow_down_left
                )
                imageTintList = ColorStateList.valueOf(Color.parseColor(amtHex))
                backgroundTintList = ColorStateList.valueOf(
                    Color.parseColor(if (isReturn) "#F3ECDD" else "#E4F1EC")
                )
            }
            card.findViewById<TextView>(R.id.tvReturnValue).apply {
                text = (if (isReturn) "− " else "+ ") + CurrencyHelper.format(
                    this@PurchaseDetailsActivity, ret.invoiceValue
                )
                setTextColor(Color.parseColor(amtHex))
            }
            card.findViewById<TextView>(R.id.tvNoteCaption).text =
                if (isReturn) "returned" else "added"

            // Moving-average redesign, Phase 5: per-row inventory gain/loss.
            // Only meaningful for a Debit Note; zero for Credit Notes and
            // legacy rows, in which case the view stays hidden.
            val variance = ret.inventoryValuationVariance
            val tvVariance = card.findViewById<TextView>(R.id.tvValuationVariance)
            if (isReturn && kotlin.math.abs(variance) >= 0.01) {
                val isLoss = variance > 0
                val varianceHex = if (isLoss) "#B04A3B" else "#0F6E56"
                tvVariance.text = (if (isLoss) "Inventory loss " else "Inventory gain ") +
                    CurrencyHelper.format(this@PurchaseDetailsActivity, kotlin.math.abs(variance))
                tvVariance.setTextColor(Color.parseColor(varianceHex))
                tvVariance.visibility = View.VISIBLE
            } else {
                tvVariance.visibility = View.GONE
            }

            card.findViewById<View>(R.id.viewNoteDivider).visibility =
                if (index == returns.lastIndex) View.GONE else View.VISIBLE

            llPriorReturns.addView(card)
        }
    }

    /** Opens the note screen directly. "D" = debit note (return goods),
     *  "C" = credit note (additional stock / value). */
    private fun openNote(noteType: String) {
        val p = viewModel.selectedPurchase.value
        if (p == null) {
            Toast.makeText(this, "Purchase not loaded yet.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PurchaseReturnActivity::class.java)
                .putExtra("PURCHASE_ID", p.id)
                .putExtra("NOTE_TYPE", noteType)
        )
    }

    /**
     * Cancel = return everything still on hand from this purchase, in one go.
     * Refused if any unit has been sold. On confirm, the bulk return runs, the
     * purchase is flagged cancelled, and the supplier balance is adjusted
     * through the shared prompt (clamped, cash-vs-advance on overshoot).
     */
    private fun confirmCancelPurchase() {
        btnCancelPurchase.isEnabled = false
        lifecycleScope.launch {
            when (val check = PurchaseCancelRepository.canCancel(this@PurchaseDetailsActivity, purchaseId)) {
                is PurchaseCancelRepository.CancelCheck.NotFound -> {
                    toast("Purchase not found")
                    btnCancelPurchase.isEnabled = true
                }
                is PurchaseCancelRepository.CancelCheck.AlreadyCancelled -> {
                    toast("This purchase is already cancelled")
                    applyCancelledState()
                }
                is PurchaseCancelRepository.CancelCheck.Blocked -> {
                    showCantCancelDialog(check.reason)
                }
                is PurchaseCancelRepository.CancelCheck.Allowed -> {
                    showCancelPurchaseConfirmDialog()
                }
            }
        }
    }

    /** Blocked-cancel popup — champagne card shell (soft gold circle + lock
     * icon) instead of the plain system alert, same tone as the delete /
     * cancel confirmation dialogs elsewhere in the app. */
    private fun showCantCancelDialog(reason: String) {
        val view = layoutInflater.inflate(R.layout.dialog_cant_cancel_purchase, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        // The card is the layout's own rounded background, so the dialog
        // window behind it must be transparent — otherwise its default white
        // sheet shows through as a square behind the rounded corners.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvCantCancelEyebrow).text =
            currentInvoiceNumber.ifBlank { "PURCHASE #$purchaseId" }
        view.findViewById<TextView>(R.id.tvCantCancelMessage).text = reason

        view.findViewById<MaterialButton>(R.id.btnCantCancelGotIt).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { btnCancelPurchase.isEnabled = true }

        dialog.show()
    }

    /** "Cancel this purchase?" confirmation — champagne card shell (soft-red
     * circle + reverse icon) instead of the plain system alert; confirming
     * runs the actual cancellation, and dismissing either way re-enables
     * the trigger button. */
    private fun showCancelPurchaseConfirmDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_cancel_purchase_confirm, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvCancelPurchaseEyebrow).text =
            currentInvoiceNumber.ifBlank { "PURCHASE #$purchaseId" }

        view.findViewById<MaterialButton>(R.id.btnConfirmCancelPurchase).setOnClickListener {
            dialog.dismiss()
            runCancel()
        }
        view.findViewById<MaterialButton>(R.id.btnKeepPurchase).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { btnCancelPurchase.isEnabled = true }

        dialog.show()
    }

    private fun runCancel() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PurchaseCancelRepository.cancel(this@PurchaseDetailsActivity, purchaseId)
            }
            if (result == null) {
                toast("Couldn't cancel the purchase")
                btnCancelPurchase.isEnabled = true
                return@launch
            }
            applyCancelledState()
            toast("Purchase cancelled. Stock returned to supplier.")

            // Push the void, the returns and the balance change.
            com.example.easy_billing.sync.SyncCoordinator
                .get(this@PurchaseDetailsActivity).requestSync()

            // Adjust the supplier balance for the swept-back stock — clamped,
            // asking cash-vs-advance only on an overshoot. No-ops for a cash
            // purchase. Finish after the owner answers.
            CreditAdjustmentPrompt.handlePurchase(
                activity = this@PurchaseDetailsActivity,
                purchaseId = purchaseId,
                kind = CreditAdjustmentRepository.Kind.PURCHASE_CANCEL,
                amount = result.remainingValue,
                documentLocalId = purchaseId,
                onDone = { }
            )
        }
    }

    private fun applyCancelledState() {
        // Show the cancelled banner and hide the actions — a voided purchase
        // can't be returned or cancelled again.
        tvCancelledBanner.visibility = View.VISIBLE
        actionRow.visibility = View.GONE
        setPill("Cancelled", "#8A8272", "#F1EBDD")
    }

    /** First letters of the first two words, uppercased — the row monogram. */
    private fun monogram(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun formatQty(q: Double) =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(q)
}
