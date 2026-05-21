package com.example.easy_billing

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
import com.example.easy_billing.sync.SyncManager
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
    private lateinit var btnPrint: Button
    private lateinit var btnClose: Button
    private lateinit var btnCancelBill: MaterialButton

    /** The server-side bill id (used for API calls). */
    private var billId: Int = -1

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
        btnPrint         = findViewById(R.id.btnPrint)
        btnClose         = findViewById(R.id.btnClose)
        btnCancelBill    = findViewById(R.id.btnCancelBill)

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
    }

    private fun loadBillDetails() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

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
                val alreadyCancelled = localBill?.isCancelled == true
                applyBillCancellationState(alreadyCancelled)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    this@BillDetailsActivity,
                    "Failed to load bill details",
                    Toast.LENGTH_SHORT
                ).show()
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
    }

    /**
     * Confirmation dialog before voiding. Proceeds to
     * [performCancellation] on "Yes".
     */
    private fun confirmCancellation() {
        AlertDialog.Builder(this)
            .setTitle("Cancel / Void Invoice")
            .setMessage("Mark this invoice as cancelled for GST reporting? This cannot be undone.")
            .setPositiveButton("Yes, Cancel") { d, _ ->
                d.dismiss()
                performCancellation()
            }
            .setNegativeButton("No") { d, _ -> d.dismiss() }
            .show()
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
                if (localBill != null) {
                    db.billDao().markBillCancelled(localBill.id, now)

                    // 2. Mark gst_sales_invoice_table by bill_id FK.
                    val gstInvoice = db.gstSalesInvoiceDao().getByBillId(localBill.id)
                    if (gstInvoice != null) {
                        db.gstSalesInvoiceDao().markCancelled(gstInvoice.id, now)
                    }
                }

                // 3. Mark gst_sales_records by invoice_number.
                db.gstSalesRecordDao().markCancelledByInvoiceNumber(resolvedBillNumber, now)

                // 4. Best-effort sync of cancellations to backend.
                try {
                    SyncManager(this@BillDetailsActivity).syncGstCancellations()
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
                    subTotal = response.bill.total_amount,
                    gst = response.bill.gst,
                    discount = response.bill.discount,
                    total = response.bill.total_amount,
                    paymentMethod = response.bill.payment_method
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

                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    bill = bill,
                    billItems = billItems,
                    storeInfo = storeInfo
                )

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    "Failed to generate invoice",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
