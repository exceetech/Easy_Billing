package com.example.easy_billing

import com.example.easy_billing.R

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
    private lateinit var tvTaxableTile: TextView
    private lateinit var btnConfirmReturn: MaterialButton
    private lateinit var btnCancelReturn: MaterialButton

    private var billId: Int = -1
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales_return)
        com.example.easy_billing.util.UserEventLogger.logAction("SalesReturn", "opened")

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { finish() }
        }

        billId     = intent.getIntExtra("BILL_ID", -1)
        billNumber = intent.getStringExtra("BILL_NUMBER") ?: ""

        if (billId == -1) {
            Toast.makeText(this, getString(R.string.salesreturnactivity_invalid_bill_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvInvoiceNumber  = findViewById(R.id.tvInvoiceNumber)
        tvInvoiceDate    = findViewById(R.id.tvInvoiceDate)
        rvReturnItems    = findViewById(R.id.rvReturnItems)
        tvTotalReturnValue = findViewById(R.id.tvTotalReturnValue)
        tvGstReversal    = findViewById(R.id.tvGstReversal)
        tvTaxableTile    = findViewById(R.id.tvTaxableTile)
        btnConfirmReturn = findViewById(R.id.btnConfirmReturn)
        btnCancelReturn  = findViewById(R.id.btnCancelReturn)

        tvInvoiceNumber.text = "Invoice $billNumber"

        rvReturnItems.layoutManager = LinearLayoutManager(this)

        btnCancelReturn.setOnClickListener { finish() }
        btnConfirmReturn.setOnClickListener { confirmAndSubmit() }

        observeViewModel()
        viewModel.loadBill(billId)
    }

    // Offline-session-timeout coverage (see SessionTimeoutGuard for why this
    // isn't done via extending BaseActivity instead).
    override fun onResume() {
        super.onResume()
        com.example.easy_billing.util.SessionTimeoutGuard.start(this)
    }

    override fun onPause() {
        super.onPause()
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
    }

    // Defensive backstop in case onPause is ever skipped by a future edit —
    // stop() is safe to call even if the guard was already stopped.
    override fun onDestroy() {
        com.example.easy_billing.util.SessionTimeoutGuard.stop(this)
        super.onDestroy()
    }

    // Immersive mode — hide status + navigation bars, matching
    // activity_debit_note.xml's chromeless look. Applied only here (not via
    // BaseActivity) to avoid BaseActivity's forced landscape re-orientation,
    // which was racing with the bill load and causing a spurious
    // "Bill not loaded yet" error.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {

        lifecycleScope.launch {
            viewModel.bill.collectLatest { bill ->
                bill ?: return@collectLatest
                if (bill.isCancelled) {
                    Toast.makeText(this@SalesReturnActivity, getString(R.string.salesreturnactivity_cannot_issue_a_credit), Toast.LENGTH_SHORT).show()
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
                        tvTaxableTile.text      = CurrencyHelper.format(this@SalesReturnActivity, (total - tax).coerceAtLeast(0.0))
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
                    "Confirm and issue credit note"
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
        tvInvoiceNumber.text = "Invoice ${bill.billNumber}"
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
        val linesDetail = lines.joinToString("; ") { (item, qty) -> "${item.productName}=$qty" }
        com.example.easy_billing.util.UserEventLogger.logAction(
            "SalesReturn",
            "submit_clicked: lines_selected=${lines.size}, total_value=${tvTotalReturnValue.text}, items=[$linesDetail]"
        )

        if (lines.isEmpty()) {
            Toast.makeText(this, getString(R.string.salesreturnactivity_please_select_at_least), Toast.LENGTH_SHORT).show()
            com.example.easy_billing.util.UserEventLogger.logValidationFailed("SalesReturn", "no_items_selected")
            return
        }

        val bill = viewModel.bill.value
        if (bill == null) {
            Toast.makeText(this, getString(R.string.salesreturnactivity_bill_not_loaded_yet), Toast.LENGTH_SHORT).show()
            return
        }

        // Champagne dialog card (soft-teal circle + return icon) instead of
        // the plain system alert, matching dialog_cancel_void_invoice.xml's
        // pattern.
        val view = layoutInflater.inflate(R.layout.dialog_confirm_credit_note, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val unitCount = lines.sumOf { it.second }.let { "%.2f".format(it) }
        view.findViewById<TextView>(R.id.tvConfirmCreditEyebrow).text =
            "Invoice ${bill.billNumber}"
        view.findViewById<TextView>(R.id.tvConfirmCreditMessage).text =
            "You're returning $unitCount unit(s) from Invoice #${bill.billNumber}. This will adjust inventory and generate a GST credit note."
        view.findViewById<TextView>(R.id.tvConfirmCreditValue).text = tvTotalReturnValue.text

        view.findViewById<MaterialButton>(R.id.btnConfirmIssueCredit).setOnClickListener {
            dialog.dismiss()
            submitReturn(bill, lines)
        }
        view.findViewById<MaterialButton>(R.id.btnReviewCredit).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
