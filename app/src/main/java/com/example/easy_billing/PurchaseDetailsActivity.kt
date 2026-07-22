package com.example.easy_billing

import android.content.Intent
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
    private lateinit var btnRaiseReturn:  MaterialButton
    private lateinit var btnClose:        MaterialButton
    private lateinit var btnCancelPurchase: MaterialButton

    private var purchaseId: Int = -1
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_details)

        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
        if (purchaseId == -1) {
            Toast.makeText(this, "Invalid purchase ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvSupplierName       = findViewById(R.id.tvSupplierName)
        tvInvoiceInfo        = findViewById(R.id.tvInvoiceInfo)
        tvTaxableAmount      = findViewById(R.id.tvTaxableAmount)
        tvGstAmount          = findViewById(R.id.tvGstAmount)
        tvTotalAmount        = findViewById(R.id.tvTotalAmount)
        tvSupplierGstin      = findViewById(R.id.tvSupplierGstin)
        llPurchaseItems      = findViewById(R.id.llPurchaseItems)
        tvPriorReturnsHeader = findViewById(R.id.tvPriorReturnsHeader)
        llPriorReturns       = findViewById(R.id.llPriorReturns)
        btnRaiseReturn       = findViewById(R.id.btnRaiseReturn)
        btnClose             = findViewById(R.id.btnClose)
        btnCancelPurchase    = findViewById(R.id.btnCancelPurchase)

        btnClose.setOnClickListener { finish() }
        btnRaiseReturn.setOnClickListener { openPurchaseReturn() }
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

        val dateStr = p.invoiceDate?.let { dateFmt.format(Date(it)) }
            ?: dateFmt.format(Date(p.createdAt))
        tvInvoiceInfo.text = "Invoice: ${p.invoiceNumber}  ·  $dateStr"

        val gst = p.cgstAmount + p.sgstAmount + p.igstAmount
        tvTaxableAmount.text = CurrencyHelper.format(this, p.taxableAmount)
        tvGstAmount.text     = CurrencyHelper.format(this, gst)
        tvTotalAmount.text   = CurrencyHelper.format(this, p.invoiceValue)
        tvSupplierGstin.text = if (!p.supplierGstin.isNullOrBlank()) p.supplierGstin else "—"
    }

    private fun buildItemsList(items: List<PurchaseItem>) {
        val p = viewModel.selectedPurchase.value ?: return
        val shopStateCode = viewModel.shopStateCode.value
        val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(p.state)
            ?: com.example.easy_billing.util.GstEngine.getStateCode(p.supplierGstin)

        llPurchaseItems.removeAllViews()
        for (item in items) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_purchase_detail_row, llPurchaseItems, false)

            row.findViewById<TextView>(R.id.tvProductName).text = buildString {
                append(item.productName)
                if (!item.variant.isNullOrBlank()) append("  ·  ${item.variant}")
            }
            row.findViewById<TextView>(R.id.tvHsnQty).text = buildString {
                if (!item.hsnCode.isNullOrBlank()) append("HSN: ${item.hsnCode}  ")
                append("Qty: ${formatQty(item.quantity)} ${item.unit ?: ""}")
            }
            val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
            
            val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank()) {
                shopStateCode == supplierState
            } else {
                item.purchaseIgstPercentage <= 0.0
            }

            val gstStr = if (sameState) {
                val totalSgstCgst = item.purchaseCgstPercentage + item.purchaseSgstPercentage
                if (totalSgstCgst > 0) "CGST ${item.purchaseCgstPercentage.toInt()}% + SGST ${item.purchaseSgstPercentage.toInt()}%" else "0%"
            } else {
                if (item.purchaseIgstPercentage > 0) "IGST ${item.purchaseIgstPercentage.toInt()}%" else "0%"
            }
            row.findViewById<TextView>(R.id.tvCostAndGst).text =
                "Base Cost: ${CurrencyHelper.format(this, unitTaxable)}  GST: $gstStr"
            row.findViewById<TextView>(R.id.tvLineTotal).text =
                CurrencyHelper.format(this, item.invoiceValue)

            llPurchaseItems.addView(row)
        }
    }

    private fun buildPriorReturns(returns: List<PurchaseReturn>) {
        if (returns.isEmpty()) {
            tvPriorReturnsHeader.visibility = View.GONE
            llPriorReturns.removeAllViews()
            return
        }

        tvPriorReturnsHeader.visibility = View.VISIBLE
        llPriorReturns.removeAllViews()

        for (ret in returns) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_debit_note_row, llPriorReturns, false)

            card.findViewById<TextView>(R.id.tvNoteNumber).text =
                ret.noteNumber ?: "Legacy Return"
            card.findViewById<TextView>(R.id.tvNoteDate).text =
                ret.noteDate?.let { dateFmt.format(Date(it)) } ?: "—"
            card.findViewById<TextView>(R.id.tvProductName).text = ret.productName
            card.findViewById<TextView>(R.id.tvReturnedQty).text =
                "Qty: ${formatQty(ret.quantityReturned)}"
            card.findViewById<TextView>(R.id.tvReturnValue).text =
                CurrencyHelper.format(this, ret.invoiceValue)

            llPriorReturns.addView(card)
        }
    }

    private fun openPurchaseReturn() {
        val p = viewModel.selectedPurchase.value
        if (p == null) {
            Toast.makeText(this, "Purchase not loaded yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("Raise Debit Note (Return Goods)", "Receive Credit Note (Additional Stock/Value)")
        AlertDialog.Builder(this)
            .setTitle("Select Transaction Type")
            .setItems(options) { dialog, which ->
                val noteType = if (which == 0) "D" else "C"
                val intent = Intent(this, PurchaseReturnActivity::class.java).apply {
                    putExtra("PURCHASE_ID", p.id)
                    putExtra("NOTE_TYPE", noteType)
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
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
                    AlertDialog.Builder(this@PurchaseDetailsActivity)
                        .setTitle("Can't cancel")
                        .setMessage(check.reason)
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .setOnDismissListener { btnCancelPurchase.isEnabled = true }
                        .show()
                }
                is PurchaseCancelRepository.CancelCheck.Allowed -> {
                    AlertDialog.Builder(this@PurchaseDetailsActivity)
                        .setTitle("Cancel this purchase?")
                        .setMessage(
                            "All remaining stock from this purchase will be returned to the " +
                                "supplier and the purchase marked cancelled. This can't be undone."
                        )
                        .setPositiveButton("Cancel Purchase") { d, _ ->
                            d.dismiss()
                            runCancel()
                        }
                        .setNegativeButton("Keep") { d, _ ->
                            d.dismiss()
                            btnCancelPurchase.isEnabled = true
                        }
                        .setOnCancelListener { btnCancelPurchase.isEnabled = true }
                        .show()
                }
            }
        }
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
        btnCancelPurchase.isEnabled = false
        btnCancelPurchase.text = "Cancelled"
        btnRaiseReturn.isEnabled = false
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun formatQty(q: Double) =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(q)
}
