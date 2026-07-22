package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Bill
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.repository.CreditNoteRepository
import com.example.easy_billing.util.CreditAdjustmentPrompt
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.viewmodel.SalesReturnViewModel
import com.google.android.material.button.MaterialButton
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SalesReturnActivity — Partial Return / Credit Note
 *
 * Receives:
 *   • BILL_ID (Int)   — local Room bills.id
 *   • BILL_NUMBER (String) — invoice number for display
 *
 * Lets the user choose how many units of each sold product to return,
 * then calls [SalesReturnViewModel.submitReturn] which routes through
 * [CreditNoteRepository] → Room → InventoryManager.
 *
 * Offline-first: all writes land in Room immediately; SyncManager pushes
 * credit notes to the backend during the next sync cycle.
 */
class SalesReturnActivity : AppCompatActivity() {

    private val viewModel: SalesReturnViewModel by viewModels()

    private lateinit var tvInvoiceNumber: TextView
    private lateinit var tvInvoiceDate: TextView
    private lateinit var rvReturnItems: RecyclerView
    private lateinit var tvTotalReturnValue: TextView
    private lateinit var tvGstReversal: TextView
    private lateinit var btnConfirmReturn: MaterialButton
    private lateinit var btnCancelReturn: MaterialButton

    private var billId: Int = -1
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales_return)

        billId     = intent.getIntExtra("BILL_ID", -1)
        billNumber = intent.getStringExtra("BILL_NUMBER") ?: ""

        if (billId == -1) {
            Toast.makeText(this, "Invalid bill ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvInvoiceNumber  = findViewById(R.id.tvInvoiceNumber)
        tvInvoiceDate    = findViewById(R.id.tvInvoiceDate)
        rvReturnItems    = findViewById(R.id.rvReturnItems)
        tvTotalReturnValue = findViewById(R.id.tvTotalReturnValue)
        tvGstReversal    = findViewById(R.id.tvGstReversal)
        btnConfirmReturn = findViewById(R.id.btnConfirmReturn)
        btnCancelReturn  = findViewById(R.id.btnCancelReturn)

        tvInvoiceNumber.text = "Invoice #$billNumber"

        rvReturnItems.layoutManager = LinearLayoutManager(this)

        btnCancelReturn.setOnClickListener { finish() }
        btnConfirmReturn.setOnClickListener { confirmAndSubmit() }

        observeViewModel()
        viewModel.loadBill(billId)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {

        lifecycleScope.launch {
            viewModel.bill.collectLatest { bill ->
                bill ?: return@collectLatest
                if (bill.isCancelled) {
                    Toast.makeText(this@SalesReturnActivity, "Cannot issue a credit note for a cancelled invoice.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@collectLatest
                }
                bindBillHeader(bill)
            }
        }

        lifecycleScope.launch {
            viewModel.billItems.collectLatest { items ->
                if (items.isEmpty()) return@collectLatest
                val adapter = SalesReturnItemAdapter(
                    items            = items,
                    maxReturnableQty = { productId, soldQty ->
                        viewModel.maxReturnableQty(productId, soldQty)
                    },
                    onTotalChanged   = { total, tax ->
                        // BillItem values are already net of any pre-tax bill
                        // discount, so the returned total is the correct refund.
                        tvTotalReturnValue.text = CurrencyHelper.format(this@SalesReturnActivity, total)
                        tvGstReversal.text      = CurrencyHelper.format(this@SalesReturnActivity, tax)
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
                    "Confirm Return & Issue Credit Note"
            }
        }

        lifecycleScope.launch {
            viewModel.result.collectLatest { result ->
                result ?: return@collectLatest
                when (result) {
                    is CreditNoteRepository.Result.Success -> {
                        Toast.makeText(
                            this@SalesReturnActivity,
                            "Credit Note ${result.creditNote.noteNumber} issued successfully.",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                        // Push to backend immediately — don't wait for the next background sync cycle.
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                SyncManager(this@SalesReturnActivity).syncCreditNotes()
                            } catch (_: Exception) {
                                // Sync failed silently; SyncManager marks the row "failed"
                                // and the next background syncAll() will retry.
                            }
                        }
                        // If the original bill was on credit, ask whether this
                        // credit note should come off the customer's balance.
                        // Skips itself for cash bills. Finish only after the
                        // owner has answered.
                        val note = result.creditNote
                        CreditAdjustmentPrompt.handle(
                            activity = this@SalesReturnActivity,
                            billId = note.originalInvoiceId,
                            kind = CreditAdjustmentRepository.Kind.SALE_RETURN,
                            amount = note.totalAmount,
                            documentLocalId = note.id,
                            onDone = { finish() }
                        )
                    }
                    is CreditNoteRepository.Result.ValidationError -> {
                        Toast.makeText(
                            this@SalesReturnActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                    }
                    is CreditNoteRepository.Result.SaveError -> {
                        Toast.makeText(
                            this@SalesReturnActivity,
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

    private fun bindBillHeader(bill: Bill) {
        tvInvoiceNumber.text = "Invoice #${bill.billNumber}"
        try {
            val parsedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .parse(bill.date.substring(0, 19))
            val displayDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(parsedDate ?: Date())
            tvInvoiceDate.text = "Date: $displayDate"
        } catch (e: Exception) {
            tvInvoiceDate.text = "Date: ${bill.date}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmAndSubmit() {
        val adapter = rvReturnItems.adapter as? SalesReturnItemAdapter ?: return
        val lines   = adapter.getReturnLines()

        if (lines.isEmpty()) {
            Toast.makeText(this, "Please select at least one item to return.", Toast.LENGTH_SHORT).show()
            return
        }

        val bill = viewModel.bill.value
        if (bill == null) {
            Toast.makeText(this, "Bill not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Issue Credit Note?")
            .setMessage(
                "You are returning ${lines.sumOf { it.second }.let { "%.2f".format(it) }} unit(s)" +
                " from Invoice #${bill.billNumber}.\n\nThis will adjust inventory and " +
                "generate a GST credit note. Continue?"
            )
            .setPositiveButton("Yes, Issue CN") { d, _ ->
                d.dismiss()
                submitReturn(bill, lines)
            }
            .setNegativeButton("Review") { d, _ -> d.dismiss() }
            .show()
    }

    private fun submitReturn(
        bill: Bill,
        lines: List<Pair<com.example.easy_billing.db.BillItem, Double>>
    ) {
        // Parse bill date to epoch millis
        val billDateMillis = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .parse(bill.date.substring(0, 19))?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        val returnLines = lines.map { (item, qty) ->
            CreditNoteRepository.ReturnLine(billItem = item, returnQty = qty)
        }

        viewModel.submitReturn(
            billId         = bill.id,
            billNumber     = bill.billNumber,
            billDateMillis = billDateMillis,
            customerName   = "",                            // B2C default; extend for B2B
            customerGstin  = bill.customerGstin,
            placeOfSupply  = bill.placeOfSupply,
            reverseCharge  = "N",
            supplyType     = bill.supplyType,
            urType         = if (bill.customerGstin.isNullOrBlank()) "B2CS" else "B2B",
            lines          = returnLines
        )
    }
}
