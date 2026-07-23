package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.util.CreditAdjustmentPrompt
import com.example.easy_billing.util.InvoicePdfGenerator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper
import java.util.Locale

class BillDetailsActivity : AppCompatActivity() {

    private lateinit var tvBillInfo: TextView
    private lateinit var tvCancelledBadge: TextView

    private lateinit var tvStoreName: TextView
    private lateinit var tvSubTotal: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvDiscount: TextView
    private lateinit var tvTotal: TextView
    private lateinit var rvBillItems: RecyclerView
    private lateinit var progressBillDetails: android.widget.ProgressBar
    private lateinit var btnPrint: Button
    private lateinit var btnClose: Button
    private lateinit var btnCancelBill: MaterialButton
    private lateinit var btnCreditNote: MaterialButton
    private lateinit var btnDebitNote: MaterialButton

    /** The server-side bill id (used for API calls). */
    private var billId: Int = -1

    /** The local Room bills.id — resolved from the bill number after load. */
    private var localBillId: Int = -1

    /**
     * The bill_number resolved after [loadBillDetails] — used as the
     * stable cross-reference when marking local DB records cancelled.
     */
    private var resolvedBillNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_details)

        tvBillInfo       = findViewById(R.id.tvBillInfo)
        tvCancelledBadge = findViewById(R.id.tvCancelledBadge)
        tvStoreName      = findViewById(R.id.tvStoreName)
        tvSubTotal       = findViewById(R.id.tvSubTotal)
        tvGst            = findViewById(R.id.tvGst)
        tvDiscount       = findViewById(R.id.tvDiscount)
        tvTotal          = findViewById(R.id.tvTotal)
        rvBillItems      = findViewById(R.id.rvBillItems)
        progressBillDetails = findViewById(R.id.progressBillDetails)
        btnPrint         = findViewById(R.id.btnPrint)
        btnClose         = findViewById(R.id.btnClose)
        btnCancelBill    = findViewById(R.id.btnCancelBill)
        btnCreditNote    = findViewById(R.id.btnCreditNote)
        btnDebitNote     = findViewById(R.id.btnDebitNote)

        billId = intent.getIntExtra("BILL_ID", -1)

        if (billId == -1) {
            Toast.makeText(this, "Invalid Bill ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        rvBillItems.layoutManager = LinearLayoutManager(this)

        loadBillDetails()

        btnPrint.setOnClickListener { generatePdfAndPrint() }
        btnClose.setOnClickListener { finish() }
        btnCancelBill.setOnClickListener { confirmCancellation() }
        btnCreditNote.setOnClickListener { openSalesReturn() }
        btnDebitNote.setOnClickListener { openDebitNote() }
    }

    /** Cheap connectivity check — used only to pick a more useful error message. */
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadBillDetails() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            progressBillDetails.visibility = View.VISIBLE

            try {

                val response = RetrofitClient.api.getBillDetails(
                    token,
                    billId
                )

                val bill = response.bill
                val items = response.items

                lifecycleScope.launch {

                    val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                    val store = db.storeInfoDao().get()

                    tvStoreName.text = store?.name ?: "My Store"
                }

                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val outputFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

                val cleanDate = try {
                    val raw = bill.created_at.substring(0, 19)
                    val date = inputFormat.parse(raw)
                    outputFormat.format(date!!)
                } catch (e: Exception) {
                    bill.created_at // fallback
                }

                tvBillInfo.text = "Invoice: #${bill.bill_number}\nDate: $cleanDate"
                resolvedBillNumber = bill.bill_number

                val subtotal = bill.total_amount - bill.gst + bill.discount

                tvSubTotal.text = "${CurrencyHelper.format(this@BillDetailsActivity, subtotal)}"
                tvGst.text = "${CurrencyHelper.format(this@BillDetailsActivity, bill.gst)}"
                tvDiscount.text = "${CurrencyHelper.format(this@BillDetailsActivity, bill.discount)}"
                tvTotal.text = "${CurrencyHelper.format(this@BillDetailsActivity, bill.total_amount)}"

                rvBillItems.adapter = BillDetailsAdapter(items)

                // Check if this bill is already cancelled in the local DB.
                val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                val localBill = withContext(Dispatchers.IO) {
                    db.billDao().getByBillNumber(bill.bill_number)
                }
                // N1: server flag too — covers bills voided from another
                // device or after a reinstall, where Room has no record.
                val alreadyCancelled =
                    bill.is_cancelled || localBill?.isCancelled == true
                localBillId = localBill?.id ?: -1
                applyBillCancellationState(alreadyCancelled)

            } catch (e: Exception) {

                e.printStackTrace()

                val isHttp404 = (e as? retrofit2.HttpException)?.code() == 404
                val message = when {
                    isHttp404 -> "This bill couldn't be found on the server."
                    !isOnline() -> "No internet connection — check your network and try again."
                    else -> "Couldn't load bill details. Tap Retry to try again."
                }

                AlertDialog.Builder(this@BillDetailsActivity)
                    .setTitle("Couldn't load bill")
                    .setMessage(message)
                    .setPositiveButton("Retry") { d, _ -> d.dismiss(); loadBillDetails() }
                    .setNegativeButton("Close") { d, _ -> d.dismiss(); finish() }
                    .setCancelable(false)
                    .show()
            } finally {
                progressBillDetails.visibility = View.GONE
            }
        }
    }

    // ===== Cancellation flow =====

    /**
     * Toggles UI to reflect whether this bill is already cancelled.
     * Called both after load (existing state) and after a successful
     * cancel action.
     */
    private fun applyBillCancellationState(cancelled: Boolean) {
        tvCancelledBadge.visibility = if (cancelled) View.VISIBLE else View.GONE
        btnCancelBill.visibility    = if (cancelled) View.GONE   else View.VISIBLE
        btnCreditNote.isEnabled     = !cancelled
        btnDebitNote.isEnabled      = !cancelled
    }

    /**
     * Confirmation dialog before voiding. Proceeds to
     * [performCancellation] on "Yes".
     */
    private fun confirmCancellation() {
        if (localBillId == -1) {
            Toast.makeText(this, "Bill not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val creditNotes = db.creditNoteDao().getByOriginalInvoice(localBillId)
            val hasPartialReturns = creditNotes.isNotEmpty()

            withContext(Dispatchers.Main) {
                val message = if (hasPartialReturns) {
                    "This invoice has partial returns. Cancelling it will mark the invoice as void and restore ONLY the remaining (non-returned) items to inventory. This cannot be undone."
                } else {
                    "Mark this invoice as cancelled for GST reporting? This will also restore all billed items to your inventory. This cannot be undone."
                }

                AlertDialog.Builder(this@BillDetailsActivity)
                    .setTitle("Cancel / Void Invoice")
                    .setMessage(message)
                    .setPositiveButton("Yes, Cancel") { d, _ ->
                        d.dismiss()
                        performCancellation()
                    }
                    .setNegativeButton("No") { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }

    /**
     * Soft-deletes all three local tables that hold GST-relevant data
     * for this invoice (bills, gst_sales_invoice_table, gst_sales_records),
     * then attempts a best-effort sync push.
     * Never deletes rows — only sets is_cancelled flags.
     */
    private fun performCancellation() {
        if (resolvedBillNumber.isBlank()) {
            Toast.makeText(this, "Bill number not resolved yet", Toast.LENGTH_SHORT).show()
            return
        }
        btnCancelBill.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db   = AppDatabase.getDatabase(this@BillDetailsActivity)
                val now  = System.currentTimeMillis()

                // 1. Mark the legacy bills row.
                val localBill = db.billDao().getByBillNumber(resolvedBillNumber)

                // INV-8 fix: markBillCancelled is now a conditional UPDATE
                // (WHERE is_cancelled = 0) that returns how many rows it
                // actually changed. 1 means this call is the one genuinely
                // cancelling the bill for the first time; 0 means someone
                // already cancelled it (a rotated screen, a process
                // restart, or two near-simultaneous taps bypassing the
                // disabled-button guard). Only the former should ever
                // restock inventory — restocking on a 0 would silently
                // double-credit stock for a bill that was already voided.
                val didCancelNow = localBill != null &&
                    db.billDao().markBillCancelled(localBill.id, now) > 0

                if (localBill != null && didCancelNow) {
                    // 2. Mark gst_sales_invoice_table by bill_id FK.
                    val gstInvoice = db.gstSalesInvoiceDao().getByBillId(localBill.id)
                    if (gstInvoice != null) {
                        db.gstSalesInvoiceDao().markCancelled(gstInvoice.id, now)
                    }
                }

                // 3. Legacy gst_sales_records cancel leg — REMOVED (Report 3, C3/D-5).
                // The table this updated was dropped (MIGRATION_52_53); step 2
                // above (gst_sales_invoice_table) is the sole cancel signal now.

                // 3.5. Restore inventory stock for cancelled items — only
                // when this call actually performed the cancellation.
                if (localBill != null && didCancelNow) {
                    val items = db.billItemDao().getItemsForBill(localBill.id)
                    for (bi in items) {
                        val product = db.productDao().getById(bi.productId) ?: continue
                        if (!product.trackInventory) continue

                        val returnedQty = db.creditNoteDao().getTotalReturnedQty(localBill.id, bi.productId)
                        val debitedQty = db.creditNoteItemDao().getTotalDebitedForBillProduct(localBill.id, bi.productId)
                        val qtyToRestore = bi.quantity + debitedQty - returnedQty

                        if (qtyToRestore > 0.0) {
                            val unitCost = if (bi.quantity > 0.0) bi.costPriceUsed / bi.quantity else 0.0

                            // Report 1 F-5: the restock batch previously carried
                            // gstPercent/cgst/sgst/igst = 0, so if these units were
                            // later returned to the supplier or re-sold, batch-precise
                            // GST valuation was lost. The bill item that originally
                            // sold this stock recorded exactly what rate applied to
                            // it (bi.gstRate + the cgst/sgst/igst split, from the
                            // billing calculator) — carry that forward rather than
                            // chasing the original purchase batch(es), which FIFO may
                            // have drawn this unit from more than one of.
                            val isInterstate = bi.igstAmount > 0.0
                            val restockCgstPercent = if (!isInterstate) bi.gstRate / 2.0 else 0.0
                            val restockSgstPercent = if (!isInterstate) bi.gstRate / 2.0 else 0.0
                            val restockIgstPercent = if (isInterstate) bi.gstRate else 0.0

                            InventoryManager.addStock(
                                db        = db,
                                productId = bi.productId,
                                quantity  = qtyToRestore,
                                costPrice = unitCost,
                                batchMeta = InventoryManager.StockBatchMeta(
                                    purchaseInvoiceId    = null,
                                    supplierName         = null,
                                    supplierGstin        = null,
                                    invoiceNumber        = null,
                                    batchCode            = "CANCELLED_INVOICE-${localBill.id}",
                                    unitCostExcludingTax = unitCost,
                                    gstPercent           = bi.gstRate,
                                    cgstPercent          = restockCgstPercent,
                                    sgstPercent          = restockSgstPercent,
                                    igstPercent          = restockIgstPercent,
                                    invoiceValue         = unitCost * qtyToRestore,
                                    taxableValue         = unitCost * qtyToRestore
                                ), logType = InventoryManager.LogType.CANCEL_RESTOCK
                            )
                        }
                    }
                }

                // 4. Best-effort sync of cancellations to backend.
                try {
                    val sync = SyncManager(this@BillDetailsActivity)
                    sync.syncGstCancellations()
                    // Also void the analytics bills row so reports
                    // exclude this invoice (covers non-GST bills too).
                    sync.syncBillCancellations()
                } catch (e: Exception) {
                    e.printStackTrace() // will retry on next sync cycle
                }

                withContext(Dispatchers.Main) {
                    applyBillCancellationState(cancelled = true)
                    Toast.makeText(
                        this@BillDetailsActivity,
                        "Invoice voided. Cancellation will sync automatically.",
                        Toast.LENGTH_LONG
                    ).show()

                    // If this was a credit bill, ask whether the void should
                    // also come off the customer's balance. Skips itself for
                    // cash bills. localBill is the row we just cancelled —
                    // guarded by didCancelNow so an already-cancelled bill
                    // (see the markBillCancelled fix above) doesn't prompt
                    // to adjust the customer's balance a second time.
                    if (didCancelNow) localBill?.let { b ->
                        CreditAdjustmentPrompt.handle(
                            activity = this@BillDetailsActivity,
                            billId = b.id,
                            kind = CreditAdjustmentRepository.Kind.BILL_CANCEL,
                            amount = b.total,
                            documentLocalId = b.id,
                            onDone = { }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    btnCancelBill.isEnabled = true
                    Toast.makeText(
                        this@BillDetailsActivity,
                        "Cancellation failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openSalesReturn() {
        if (localBillId == -1) {
            Toast.makeText(this, "Bill not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(localBillId)
            withContext(Dispatchers.Main) {
                if (bill.isCancelled) {
                    Toast.makeText(this@BillDetailsActivity, "Cannot issue a credit note for a cancelled invoice.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val intent = Intent(this@BillDetailsActivity, SalesReturnActivity::class.java).apply {
                    putExtra("BILL_ID", localBillId)
                    putExtra("BILL_NUMBER", resolvedBillNumber)
                }
                startActivity(intent)
            }
        }
    }

    private fun openDebitNote() {
        if (localBillId == -1) {
            Toast.makeText(this, "Bill not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(localBillId)
            withContext(Dispatchers.Main) {
                if (bill.isCancelled) {
                    Toast.makeText(this@BillDetailsActivity, "Cannot issue a debit note for a cancelled invoice.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val intent = Intent(this@BillDetailsActivity, DebitNoteActivity::class.java).apply {
                    putExtra("BILL_ID", localBillId)
                    putExtra("BILL_NUMBER", resolvedBillNumber)
                }
                startActivity(intent)
            }
        }
    }

    private fun generatePdfAndPrint() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val db = AppDatabase.getDatabase(this@BillDetailsActivity)

                val response = RetrofitClient.api.getBillDetails(
                    token,
                    billId
                )

                val bill = Bill(
                    id = response.bill.bill_id,
                    billNumber = response.bill.bill_number,
                    date = response.bill.created_at,
                    // GROSS (pre-discount) subtotal — derived as total − gst +
                    // discount, matching how locally-created bills store it and
                    // how this screen displays the Subtotal line.
                    subTotal = response.bill.total_amount - response.bill.gst + response.bill.discount,
                    gst = response.bill.gst,
                    discount = response.bill.discount,
                    total = response.bill.total_amount,
                    paymentMethod = response.bill.payment_method,
                    // Carry the saved invoice type so a reprint of a B2B
                    // bill never silently falls back to the "B2C" default.
                    customerType = response.bill.invoice_type ?: "B2C",
                    placeOfSupply = response.bill.customer_state_code ?: "",
                    supplyType = response.bill.supply_type ?: "intrastate"
                )

                val billItems = response.items.map {

                    val safeUnit = when (it.unit?.lowercase()) {
                        "kilogram" -> "kg"
                        "gram" -> "g"
                        "litre" -> "l"
                        "millilitre" -> "ml"
                        else -> it.unit ?: "unit"
                    }

                    BillItem(
                        billId = response.bill.bill_id,
                        productId = it.shop_product_id,

                        productName = it.product_name,

                        variant = it.variant ?: "",
                        unit = safeUnit,

                        price = it.price,
                        quantity = it.quantity,
                        subTotal = it.subtotal
                    )
                }

                val storeInfo = db.storeInfoDao().get()

                // ── Historical accuracy ──────────────────────────────
                // Reprint must use the GST mode + tax breakdown that were
                // saved when THIS invoice was created, never the current
                // shop settings. The local DB holds the full per-line GST
                // data and the per-invoice scheme; the server response is
                // a sparse fallback only. Prefer local when present.
                val localBill = if (localBillId != -1)
                    db.billDao().getBillById(localBillId) else null
                val localItems = if (localBillId != -1)
                    db.billItemDao().getItemsForBill(localBillId) else emptyList()
                val savedInvoice = if (localBillId != -1)
                    db.gstSalesInvoiceDao().getByBillId(localBillId) else null

                val printBill = if (localBill != null) localBill else bill
                val printItems = if (localBill != null && localItems.isNotEmpty())
                    localItems else billItems

                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    bill = printBill,
                    billItems = printItems,
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme,
                    gstInvoice = savedInvoice
                )

            } catch (e: SecurityException) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    "Couldn't save the invoice — storage permission is needed",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    "Couldn't generate the invoice PDF: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
