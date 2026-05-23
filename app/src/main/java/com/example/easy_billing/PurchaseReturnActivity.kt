package com.example.easy_billing

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.viewmodel.PurchaseReturnViewModel
import com.google.android.material.button.MaterialButton
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
class PurchaseReturnActivity : AppCompatActivity() {

    private val viewModel: PurchaseReturnViewModel by viewModels()

    private lateinit var tvSupplierName:    TextView
    private lateinit var tvInvoiceRef:      TextView
    private lateinit var rvReturnItems:     RecyclerView
    private lateinit var tvTotalDebitValue: TextView
    private lateinit var tvItcReclaim:      TextView
    private lateinit var btnConfirmReturn:  MaterialButton
    private lateinit var btnCancelReturn:   MaterialButton

    private var purchaseId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase_return)

        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
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
                    items            = items,
                    shopStateCode    = viewModel.shopStateCode.value,
                    supplierGstin    = viewModel.purchase.value?.supplierGstin,
                    maxReturnableQty = { productId, purchasedQty ->
                        viewModel.maxReturnableQty(productId, purchasedQty)
                    },
                    onTotalChanged   = { totalDebit, gst ->
                        tvTotalDebitValue.text = CurrencyHelper.format(this@PurchaseReturnActivity, totalDebit)
                        tvItcReclaim.text      = CurrencyHelper.format(this@PurchaseReturnActivity, gst)
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
                else
                    "Confirm & Issue Debit Note"
            }
        }

        lifecycleScope.launch {
            viewModel.result.collectLatest { result ->
                result ?: return@collectLatest
                when (result) {
                    is PurchaseReturnViewModel.Result.Success -> {
                        Toast.makeText(
                            this@PurchaseReturnActivity,
                            "Debit Note ${result.noteNumber} issued. Stock adjusted.",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                        finish()
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

    private fun confirmAndSubmit() {
        val adapter = rvReturnItems.adapter as? PurchaseReturnItemAdapter ?: return
        val lines   = adapter.getReturnLines()

        if (lines.isEmpty()) {
            Toast.makeText(this, "Please select at least one item to return.", Toast.LENGTH_SHORT).show()
            return
        }

        val p = viewModel.purchase.value
        if (p == null) {
            Toast.makeText(this, "Purchase not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        val totalUnits = lines.values.sum()

        AlertDialog.Builder(this)
            .setTitle("Issue Debit Note?")
            .setMessage(
                "You are returning ${"%.2f".format(totalUnits)} unit(s) to ${p.supplierName}" +
                " (Invoice: ${p.invoiceNumber}).\n\n" +
                "Stock will be reduced from the exact purchase batch and a " +
                "Debit Note will be generated. Continue?"
            )
            .setPositiveButton("Yes, Issue DN") { d, _ ->
                d.dismiss()
                viewModel.submitReturn(lines)
            }
            .setNegativeButton("Review") { d, _ -> d.dismiss() }
            .show()
    }
}
