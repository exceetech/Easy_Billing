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
class PurchaseDetailsActivity : AppCompatActivity() {

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

        btnClose.setOnClickListener { finish() }
        btnRaiseReturn.setOnClickListener { openPurchaseReturn() }

        observeViewModel()
        viewModel.loadPurchaseDetail(purchaseId)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.selectedPurchase.collectLatest { p ->
                p ?: return@collectLatest
                bindHeader(p)
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
            val gstStr = if (item.purchaseIgstPercentage > 0) {
                "IGST ${item.purchaseIgstPercentage.toInt()}%"
            } else if (item.purchaseCgstPercentage > 0 || item.purchaseSgstPercentage > 0) {
                "CGST ${item.purchaseCgstPercentage.toInt()}% + SGST ${item.purchaseSgstPercentage.toInt()}%"
            } else {
                "0%"
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
        val intent = Intent(this, PurchaseReturnActivity::class.java).apply {
            putExtra("PURCHASE_ID", p.id)
        }
        startActivity(intent)
    }

    private fun formatQty(q: Double) =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(q)
}
