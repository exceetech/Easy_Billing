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
import com.example.easy_billing.viewmodel.DebitNoteViewModel
import com.google.android.material.button.MaterialButton
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebitNoteActivity : AppCompatActivity() {

    private val viewModel: DebitNoteViewModel by viewModels()

    private lateinit var tvInvoiceNumber: TextView
    private lateinit var tvInvoiceDate: TextView
    private lateinit var rvDebitItems: RecyclerView
    private lateinit var tvTotalDebitValue: TextView
    private lateinit var tvAdditionalGst: TextView
    private lateinit var btnConfirmDebit: MaterialButton
    private lateinit var btnCancelDebit: MaterialButton

    private var billId: Int = -1
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debit_note)

        billId     = intent.getIntExtra("BILL_ID", -1)
        billNumber = intent.getStringExtra("BILL_NUMBER") ?: ""

        if (billId == -1) {
            Toast.makeText(this, "Invalid bill ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvInvoiceNumber  = findViewById(R.id.tvInvoiceNumber)
        tvInvoiceDate    = findViewById(R.id.tvInvoiceDate)
        rvDebitItems    = findViewById(R.id.rvDebitItems)
        tvTotalDebitValue = findViewById(R.id.tvTotalDebitValue)
        tvAdditionalGst    = findViewById(R.id.tvAdditionalGst)
        btnConfirmDebit = findViewById(R.id.btnConfirmDebit)
        btnCancelDebit  = findViewById(R.id.btnCancelDebit)

        tvInvoiceNumber.text = "Invoice #$billNumber"

        rvDebitItems.layoutManager = LinearLayoutManager(this)

        btnCancelDebit.setOnClickListener { finish() }
        btnConfirmDebit.setOnClickListener { confirmAndSubmit() }

        observeViewModel()
        viewModel.loadBill(billId)
    }

    private fun observeViewModel() {

        lifecycleScope.launch {
            viewModel.bill.collectLatest { bill ->
                bill ?: return@collectLatest
                if (bill.isCancelled) {
                    Toast.makeText(this@DebitNoteActivity, "Cannot issue a debit note for a cancelled invoice.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@collectLatest
                }
                bindBillHeader(bill)
            }
        }

        lifecycleScope.launch {
            viewModel.billItems.collectLatest { items ->
                if (items.isEmpty()) return@collectLatest
                val bill = viewModel.bill.value ?: return@collectLatest
                val adapter = DebitNoteItemAdapter(
                    items            = items,
                    supplyType       = bill.supplyType,
                    onTotalChanged   = { total, tax ->
                        tvTotalDebitValue.text = CurrencyHelper.format(this@DebitNoteActivity, total)
                        tvAdditionalGst.text      = CurrencyHelper.format(this@DebitNoteActivity, tax)
                    }
                )
                rvDebitItems.adapter = adapter
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                btnConfirmDebit.isEnabled = !loading
                btnConfirmDebit.text = if (loading)
                    "Processing…"
                else
                    "Confirm & Issue Debit Note"
            }
        }

        lifecycleScope.launch {
            viewModel.result.collectLatest { result ->
                result ?: return@collectLatest
                when (result) {
                    is CreditNoteRepository.Result.Success -> {
                        Toast.makeText(
                            this@DebitNoteActivity,
                            "Debit Note ${result.creditNote.noteNumber} issued successfully.",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                        // Push to backend immediately — don't wait for the next background sync cycle.
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                SyncManager(this@DebitNoteActivity).syncCreditNotes()
                            } catch (_: Exception) {
                            }
                        }
                        // If the original bill was on credit, ask whether this
                        // extra charge should be added to the customer's
                        // balance. Skips itself for cash bills.
                        val note = result.creditNote
                        CreditAdjustmentPrompt.handle(
                            activity = this@DebitNoteActivity,
                            billId = note.originalInvoiceId,
                            kind = CreditAdjustmentRepository.Kind.DEBIT_NOTE,
                            amount = note.totalAmount,
                            documentLocalId = note.id,
                            onDone = { finish() }
                        )
                    }
                    is CreditNoteRepository.Result.ValidationError -> {
                        Toast.makeText(
                            this@DebitNoteActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                    }
                    is CreditNoteRepository.Result.SaveError -> {
                        Toast.makeText(
                            this@DebitNoteActivity,
                            "Failed to save: ${result.cause.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.clearResult()
                    }
                }
            }
        }
    }

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

    private fun confirmAndSubmit() {
        val adapter = rvDebitItems.adapter as? DebitNoteItemAdapter ?: return
        val lines   = adapter.getDebitLines()

        if (lines.isEmpty()) {
            Toast.makeText(this, "Please enter additional quantity for at least one item.", Toast.LENGTH_SHORT).show()
            return
        }

        val bill = viewModel.bill.value
        if (bill == null) {
            Toast.makeText(this, "Bill not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Issue Debit Note?")
            .setMessage(
                "You are issuing a Debit Note for additional value on " +
                "Invoice #${bill.billNumber}.\n\nThis will generate a GST debit note. Continue?"
            )
            .setPositiveButton("Yes, Issue DN") { d, _ ->
                d.dismiss()
                submitDebitNote(bill, lines)
            }
            .setNegativeButton("Review") { d, _ -> d.dismiss() }
            .show()
    }

    private fun submitDebitNote(
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

        val debitLines = lines.map { (item, value) ->
            CreditNoteRepository.DebitLine(billItem = item, additionalQty = value)
        }

        viewModel.submitDebitNote(
            billId         = bill.id,
            billNumber     = bill.billNumber,
            billDateMillis = billDateMillis,
            customerName   = "",                            // B2C default; extend for B2B
            customerGstin  = bill.customerGstin,
            placeOfSupply  = bill.placeOfSupply,
            reverseCharge  = "N",
            supplyType     = bill.supplyType,
            noteSupplyType = "Regular",
            urType         = if (bill.customerGstin.isNullOrBlank()) "B2CS" else "B2B",
            lines          = debitLines
        )
    }
}
