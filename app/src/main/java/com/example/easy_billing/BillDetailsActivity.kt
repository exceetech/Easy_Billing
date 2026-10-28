package com.example.easy_billing

import com.example.easy_billing.R

import android.content.Intent
import androidx.room.withTransaction
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillDetailsActivity : AppCompatActivity() {

    companion object {
    }

    /** Same champagne-compatible (tile, ink) pairs Purchase Details cycles
     *  across its line-item avatars — kept identical so a product looks the
     *  same colour whether you're looking at a purchase or a bill. */
    private val itemPalette = listOf(
        "#F1E4CE" to "#8A6526",  // gold
        "#E4F1EC" to "#0F6E56",  // green
        "#F5E6DF" to "#B5623A",  // terracotta
        "#E7EDF3" to "#37618A",  // slate blue
        "#F0E6F1" to "#7A4A7E",  // plum
        "#DDEEEE" to "#1D6E6E"   // deep teal
    )

    private lateinit var tvBillInfo: TextView
    private lateinit var tvBillDate: TextView
    private lateinit var tvCancelledBadge: TextView
    private lateinit var tvCustomerNameTitle: TextView

    private lateinit var tvInvoiceTypeBadge: TextView
    private lateinit var tvCustomerAvatar: TextView
    private lateinit var tvCustomerInfo: TextView
    private lateinit var tvCustomerPhone: TextView
    private lateinit var tvPaidThrough: TextView
    private lateinit var llPaidBreakdown: LinearLayout
    private lateinit var tvSubTotal: TextView
    private lateinit var tvGst: TextView
    private lateinit var rowCess: View
    private lateinit var tvCess: TextView
    private lateinit var tvDiscount: TextView
    private lateinit var tvTotal: TextView
    private lateinit var llBillItems: LinearLayout
    private lateinit var progressBillDetails: android.widget.ProgressBar
    private lateinit var btnCreditNote: MaterialButton
    private lateinit var btnDebitNote: MaterialButton
    private lateinit var btnMarkAsPaid: MaterialButton
    private lateinit var btnCancelBill: MaterialButton
    private lateinit var btnSendToCustomer: MaterialButton
    private lateinit var btnPrint: MaterialButton
    private lateinit var cardBillNotes: View
    private lateinit var llBillNotes: LinearLayout
    private lateinit var rowNetBillNotes: View
    private lateinit var tvNetOriginalBillAmount: TextView
    private lateinit var tvNetAfterBillNotes: TextView
    private lateinit var layoutOwed: View
    private lateinit var tvOwed: TextView
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar

    /** The server-side bill id (used for API calls). */
    private var billId: Int = -1

    /** The local Room bills.id — resolved from the bill number after load. */
    private var localBillId: Int = -1
    private var extraBillNumber: String = ""
    private var quietPullDone = false
    private var billLoaded = false

    /** Why a phone-only action (cancel, return, debit note) can't run right now. */
    private fun notOnPhoneMessage(): String = getString(
        if (billLoaded) R.string.bill_only_on_server else R.string.billdetailsactivity_bill_not_loaded_yet_1
    )

    /**
     * The bill number can arrive after this screen opened (the bill syncs meanwhile), so read it
     * fresh from the phone's database when an action needs it.
     */
    private suspend fun freshBillNumber(): String {
        val local = findLocalBill(AppDatabase.getDatabase(this))
        val n = local?.billNumber.orEmpty()
        if (n.isNotBlank()) resolvedBillNumber = n
        return resolvedBillNumber
    }

    /**
     * The bill_number resolved after [loadBillDetails] — used as the
     * stable cross-reference when marking local DB records cancelled.
     */
    private var resolvedBillNumber: String = ""

    private val shopId by lazy {
        getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", -1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_details)
        com.example.easy_billing.util.UserEventLogger.logAction("BillDetails", "opened")

        toolbar          = findViewById(R.id.toolbar)
        tvBillInfo       = findViewById(R.id.tvBillInfo)
        tvBillDate       = findViewById(R.id.tvBillDate)
        tvCancelledBadge = findViewById(R.id.tvCancelledBadge)
        tvCustomerNameTitle = findViewById(R.id.tvCustomerNameTitle)
        tvInvoiceTypeBadge = findViewById(R.id.tvInvoiceTypeBadge)
        tvCustomerAvatar = findViewById(R.id.tvCustomerAvatar)
        tvCustomerInfo   = findViewById(R.id.tvCustomerInfo)
        tvCustomerPhone  = findViewById(R.id.tvCustomerPhone)
        tvPaidThrough    = findViewById(R.id.tvPaidThrough)
        llPaidBreakdown  = findViewById(R.id.llPaidBreakdown)
        tvSubTotal       = findViewById(R.id.tvSubTotal)
        tvGst            = findViewById(R.id.tvGst)
        rowCess          = findViewById(R.id.rowCess)
        tvCess           = findViewById(R.id.tvCess)
        tvDiscount       = findViewById(R.id.tvDiscount)
        tvTotal          = findViewById(R.id.tvTotal)
        llBillItems      = findViewById(R.id.llBillItems)
        progressBillDetails = findViewById(R.id.progressBillDetails)
        btnCreditNote    = findViewById(R.id.btnCreditNote)
        btnDebitNote     = findViewById(R.id.btnDebitNote)
        btnMarkAsPaid    = findViewById(R.id.btnMarkAsPaid)
        btnCancelBill    = findViewById(R.id.btnCancelBill)
        btnSendToCustomer = findViewById(R.id.btnSendToCustomer)
        btnPrint         = findViewById(R.id.btnPrint)
        cardBillNotes    = findViewById(R.id.cardBillNotes)
        llBillNotes      = findViewById(R.id.llBillNotes)
        rowNetBillNotes  = findViewById(R.id.rowNetBillNotes)
        tvNetOriginalBillAmount = findViewById(R.id.tvNetOriginalBillAmount)
        tvNetAfterBillNotes     = findViewById(R.id.tvNetAfterBillNotes)
        layoutOwed       = findViewById(R.id.layoutOwed)
        tvOwed           = findViewById(R.id.tvOwed)

        // Toolbar back arrow replaces the old standalone "Close" button —
        // same setup BaseActivity.setupToolbar does, duplicated here
        // rather than inherited, since this screen deliberately doesn't
        // extend BaseActivity (its forced landscape re-orientation used to
        // race with this screen's async loads).
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowHomeEnabled(false)
            setDisplayHomeAsUpEnabled(true)
        }
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener { finish() }

        billId = intent.getIntExtra("BILL_ID", -1)
        // Offline-first: the bill may exist only on this phone (no server id yet),
        // so the caller can pass the phone's own id and/or the bill number.
        localBillId = intent.getIntExtra("LOCAL_BILL_ID", -1)
        extraBillNumber = intent.getStringExtra("BILL_NUMBER").orEmpty()

        if (billId == -1 && localBillId == -1 && extraBillNumber.isBlank()) {
            Toast.makeText(this, getString(R.string.billdetailsactivity_invalid_bill_id), Toast.LENGTH_SHORT).show()
            // Was calling finish() in the same instant as the toast, which
            // tears the toast down with the activity before it's readable.
            // Rare path (bad intent extra), but still worth a beat to read.
            android.os.Handler(mainLooper).postDelayed({ finish() }, 600)
            return
        }

        loadBillDetails()

        // WhatsApp share needs no runtime permission or SIM, so the
        // button just stays wired — CustomerShareHelper itself shows a
        // toast if WhatsApp turns out not to be installed.
        btnMarkAsPaid.setOnClickListener { confirmMarkAsPaid() }
        btnCancelBill.setOnClickListener { confirmCancellation() }
        btnCreditNote.setOnClickListener { openSalesReturn() }
        btnDebitNote.setOnClickListener { openDebitNote() }
        // "More options" opens a bottom sheet with Send to Customer and
        // Print, since Add return/refund and Add extra charge are now
        // the two big buttons in the main row instead.
        btnSendToCustomer.setOnClickListener { showSendToCustomerOptions() }
        btnPrint.setOnClickListener { generatePdfAndPrint() }
    }

    // Offline-session-timeout coverage (see SessionTimeoutGuard for why this
    // isn't done via extending BaseActivity instead).
    override fun onResume() {
        super.onResume()
        com.example.easy_billing.util.SessionTimeoutGuard.start(this)
        refreshDueBanners()
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

    // Immersive mode — hide status + navigation bars, matching the
    // chromeless look used on the Credit/Debit Note screens. Applied
    // directly here (not via BaseActivity) to avoid BaseActivity's forced
    // landscape re-orientation, which previously raced with async loads on
    // other screens and caused a spurious "not loaded yet" error.
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

    /** Cheap connectivity check — used only to pick a more useful error message. */
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The bill row on THIS phone, if any: by the phone's id first, then by bill number. */
    private suspend fun findLocalBill(db: AppDatabase): Bill? = withContext(Dispatchers.IO) {
        if (localBillId != -1) {
            try { db.billDao().getBillById(localBillId) } catch (_: Exception) { null }
                ?.let { return@withContext it }
        }
        val number = extraBillNumber.ifBlank { resolvedBillNumber }
        if (number.isNotBlank()) db.billDao().getByBillNumber(number) else null
    }

    /** Same shape the server returns, built from the phone's own data. */
    private suspend fun localToResponse(db: AppDatabase, b: Bill): com.example.easy_billing.network.BillDetailResponse =
        withContext(Dispatchers.IO) {
            val items = db.billItemDao().getItemsForBill(b.id).map {
                com.example.easy_billing.network.BillItemResponse(
                    shop_product_id = it.productId,
                    product_name = it.productName,
                    variant = it.variant,
                    unit = it.unit,
                    quantity = it.quantity,
                    price = it.price,
                    subtotal = it.subTotal
                )
            }
            com.example.easy_billing.network.BillDetailResponse(
                bill = com.example.easy_billing.network.BillResponse(
                    bill_id = b.id,
                    bill_number = b.billNumber,
                    total_amount = b.total,
                    gst = b.gst,
                    discount = b.discount,
                    payment_method = b.paymentMethod,
                    created_at = b.date,
                    invoice_type = b.customerType,
                    customer_state_code = b.placeOfSupply.ifBlank { null },
                    supply_type = b.supplyType,
                    is_cancelled = b.isCancelled,
                    payment_status = b.paymentStatus
                ),
                items = items
            )
        }

    /** Phone data first; the server is asked only when this bill is not on the phone. */
    private suspend fun loadDetailResponse(token: String): com.example.easy_billing.network.BillDetailResponse {
        val db = AppDatabase.getDatabase(this)
        val local = findLocalBill(db)
        return if (local != null) localToResponse(db, local)
        else RetrofitClient.api.getBillDetails(token, billId)
    }

    private fun loadBillDetails() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            progressBillDetails.visibility = View.VISIBLE

            try {

                val response = loadDetailResponse(token)

                val bill = response.bill
                val items = response.items

                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val outputFormat = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

                val cleanDate = try {
                    val raw = bill.created_at.substring(0, 19)
                    val date = inputFormat.parse(raw)
                    outputFormat.format(date!!)
                } catch (e: Exception) {
                    bill.created_at // fallback
                }

                tvBillInfo.text = "Bill · ${bill.bill_number}"
                tvBillDate.text = cleanDate
                resolvedBillNumber = bill.bill_number

                tvPaidThrough.text = com.example.easy_billing.util.PaymentMethodText.label(bill.payment_method)
                tvPaidThrough.visibility = View.VISIBLE
                tvPaidThrough.setTextColor(Color.parseColor("#1A1A18"))
                showPaidBreakdown(null)

                // Check if this bill is already cancelled in the local DB.
                val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                val localBill = findLocalBill(db)
                // Split bill: show the amounts ("Cash ₹300 + UPI ₹200"), not just the modes.
                localBill?.let { lb ->
                    withContext(Dispatchers.IO) {
                        com.example.easy_billing.util.BillPaymentText.breakdown(this@BillDetailsActivity, lb)
                    }?.let { showPaidBreakdown(it.split("\n")) }
                }
                // Customer — the GSTR-1 invoice snapshot (gst_sales_invoice)
                // carries the name/phone/type entered at checkout for EVERY
                // bill, not just credit sales, so it's the primary source.
                // The linked credit account (if any) is the fallback for
                // older bills saved before that snapshot existed.
                val gstInvoice = if (localBill != null) {
                    withContext(Dispatchers.IO) {
                        db.gstSalesInvoiceDao().getByBillId(localBill.id)
                    }
                } else null

                val totalCess = if (gstInvoice != null) {
                    withContext(Dispatchers.IO) {
                        db.gstSalesInvoiceItemDao().getByInvoice(gstInvoice.id).sumOf { it.cessAmount }
                    }
                } else 0.0

                val actualGst = if (gstInvoice != null) {
                    gstInvoice.totalCgst + gstInvoice.totalSgst + gstInvoice.totalIgst
                } else {
                    (bill.gst - totalCess).coerceAtLeast(0.0)
                }

                val subtotal = bill.total_amount - (actualGst + totalCess) + bill.discount

                tvSubTotal.text = "${CurrencyHelper.format(this@BillDetailsActivity, subtotal)}"
                tvGst.text = "${CurrencyHelper.format(this@BillDetailsActivity, actualGst)}"
                if (totalCess > 0.0) {
                    rowCess.visibility = View.VISIBLE
                    tvCess.text = "${CurrencyHelper.format(this@BillDetailsActivity, totalCess)}"
                } else {
                    rowCess.visibility = View.GONE
                }
                tvDiscount.text = "${CurrencyHelper.format(this@BillDetailsActivity, bill.discount)}"
                tvTotal.text = "${CurrencyHelper.format(this@BillDetailsActivity, bill.total_amount)}"

                buildBillItemsList(items)

                // N1: server flag too — covers bills voided from another
                // device or after a reinstall, where Room has no record.
                val alreadyCancelled =
                    bill.is_cancelled || localBill?.isCancelled == true
                localBillId = localBill?.id ?: -1
                // A bill cancelled before the money notes were saved (app closed half-way, or cancelled by an
                // older version) gets them now: pending UPI closed, a refund note for money still held.
                if (localBill != null && localBill.isCancelled) {
                    withContext(Dispatchers.IO) {
                        try { com.example.easy_billing.util.CancelMoney.ensureRefundNote(db, localBill.id) }
                        catch (e: Exception) { Log.e("BillDetailsActivity", "refund note repair failed", e) }
                    }
                    finishCancelledCreditIfNeeded(localBill)
                }
                refreshDueBanners()

                val isUpiBill = com.example.easy_billing.util.PaymentMethodText.hasUpi(bill.payment_method)
                lastPaymentMethod = bill.payment_method
                // Server is the source of truth for payment_status (it's
                // set only by the Razorpay webhook); localBill's synced
                // copy is the fallback for an offline reopen.
                val upiPaid = bill.payment_status == "paid" || localBill?.paymentStatus == "paid"
                applyBillCancellationState(alreadyCancelled, isUpiBill, upiPaid)

                // Opened from phone data: if online, quietly ask the server whether a UPI
                // payment arrived, and refresh once if it did. Never blocks the screen.
                if (localBill != null && localBill.isSynced && isUpiBill && !upiPaid &&
                    !alreadyCancelled && !quietPullDone && isOnline()) {
                    quietPullDone = true
                    lifecycleScope.launch {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(4000L) { SyncManager(this@BillDetailsActivity).pullPaymentStatus() }
                        } catch (_: Exception) { }
                        val fresh = findLocalBill(db)
                        if (fresh != null && (fresh.paymentStatus.equals("paid", ignoreCase = true) || fresh.isCancelled)) {
                            loadBillDetails()
                        }
                    }
                }

                // Manual override — only relevant while there's actually
                // something to override: a UPI bill, not cancelled, and
                // not already confirmed paid.
                btnMarkAsPaid.visibility =
                    if (isUpiBill && !upiPaid && !alreadyCancelled) View.VISIBLE else View.GONE


                if (localBillId != -1) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val notes = db.creditNoteDao().getByOriginalInvoice(localBillId)
                        withContext(Dispatchers.Main) {
                            buildBillNotes(notes, bill.total_amount)
                        }
                    }
                }

                val creditAccountId = localBill?.creditAccountId
                val creditAccount = if (creditAccountId != null) {
                    withContext(Dispatchers.IO) {
                        db.creditAccountDao().getById(creditAccountId, shopId)
                    }
                } else null

                val customerName = gstInvoice?.customerName?.takeIf { it.isNotBlank() }
                    ?: creditAccount?.name?.takeIf { it.isNotBlank() }
                val displayName = customerName ?: "Walk-in customer"
                tvCustomerInfo.text = displayName
                tvCustomerNameTitle.text = displayName

                // "Still owed" banner: this bill's own credit (sale + extra charges - returns/cancel),
                // never more than the customer's balance. Redrawn on every resume too.
                refreshOwedBanner()
                billLoaded = true

                // Avatar monogram — first letters of the first two words.
                val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                tvCustomerAvatar.text = when {
                    words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                    words.size == 1 && words[0].length >= 2 -> words[0].substring(0, 2)
                    words.size == 1 -> words[0]
                    else -> "?"
                }.uppercase()

                val customerPhone = gstInvoice?.customerPhone?.takeIf { it.isNotBlank() }
                    ?: creditAccount?.phone?.takeIf { it.isNotBlank() }
                if (customerPhone != null) {
                    tvCustomerPhone.text = customerPhone
                    tvCustomerPhone.visibility = View.VISIBLE
                } else {
                    tvCustomerPhone.visibility = View.GONE
                }

                val invoiceType = gstInvoice?.invoiceType?.takeIf { it.isNotBlank() }
                    ?: bill.invoice_type ?: "B2C"
                tvInvoiceTypeBadge.text = invoiceType
                if (invoiceType.equals("B2B", ignoreCase = true)) {
                    tvInvoiceTypeBadge.setTextColor(android.graphics.Color.parseColor("#8A6526"))
                    tvInvoiceTypeBadge.background = androidx.core.content.ContextCompat.getDrawable(
                        this@BillDetailsActivity, R.drawable.bg_type_badge_gold
                    )
                    tvCustomerAvatar.setTextColor(android.graphics.Color.parseColor("#8A6526"))
                    tvCustomerAvatar.background = androidx.core.content.ContextCompat.getDrawable(
                        this@BillDetailsActivity, R.drawable.bg_type_badge_gold
                    )
                } else {
                    tvInvoiceTypeBadge.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
                    tvInvoiceTypeBadge.background = androidx.core.content.ContextCompat.getDrawable(
                        this@BillDetailsActivity, R.drawable.bg_type_badge_teal
                    )
                    tvCustomerAvatar.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
                    tvCustomerAvatar.background = androidx.core.content.ContextCompat.getDrawable(
                        this@BillDetailsActivity, R.drawable.bg_type_badge_teal
                    )
                }

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

    // Remembered so the post-cancellation call site (which has no fresh
    // bill data to hand) can still render the pill correctly.
    private var lastIsUpiBill = false
    private var lastPaymentMethod = ""

    /** Shows / hides the "refund not recorded yet" and "extra not collected yet" banners for this bill. */
    /**
     * What this credit bill still has sitting as debt. Payments are saved on the customer
     * account, not on a bill, so the figure is capped by the customer's current balance:
     * a customer who has paid up (balance 0) no longer shows as owing on any bill.
     */
    private fun refreshOwedBanner() {
        if (localBillId == -1) { layoutOwed.visibility = View.GONE; return }
        lifecycleScope.launch {
            val owed: com.example.easy_billing.util.BillCredit.Owed? = withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                val b = try { db.billDao().getBillById(localBillId) } catch (_: Exception) { null }
                    ?: return@withContext null
                com.example.easy_billing.util.BillCredit.owedForBill(db, b, shopId)
            }
            if (owed == null || owed.amount <= 0.005) {
                layoutOwed.visibility = View.GONE
                layoutOwed.setOnClickListener(null)
                return@launch
            }
            tvOwed.text = CurrencyHelper.format(this@BillDetailsActivity, owed.amount)
            layoutOwed.visibility = View.VISIBLE
            val acc = owed.account
            layoutOwed.setOnClickListener {
                if (acc != null) {
                    startActivity(
                        Intent(this@BillDetailsActivity, CustomerTransactionsActivity::class.java)
                            .putExtra("ACCOUNT_ID", acc.serverId ?: -1)
                            .putExtra("LOCAL_ACCOUNT_ID", acc.id)
                            .putExtra("ACCOUNT_NAME", acc.name)
                            .putExtra("ACCOUNT_PHONE", acc.phone)
                    )
                } else startActivity(Intent(this@BillDetailsActivity, CreditAccountsActivity::class.java))
            }
        }
    }

    private var cancelCreditChecked = false

    /**
     * A cancelled credit bill whose "take it off the customer's balance" step never finished (app closed, or the
     * question was dismissed) leaves the debt on the customer. Ask again, once per opening of the screen.
     */
    private suspend fun finishCancelledCreditIfNeeded(b: com.example.easy_billing.db.Bill) {
        if (cancelCreditChecked) return
        cancelCreditChecked = true
        if (!com.example.easy_billing.util.PaymentMethodText.hasCredit(b.paymentMethod)) return
        val shop = getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", -1)
        if (shop <= 0) return
        val open = withContext(Dispatchers.IO) {
            val doc = CreditAdjustmentRepository.docId(CreditAdjustmentRepository.Kind.BILL_CANCEL, b.id)
            AppDatabase.getDatabase(this@BillDetailsActivity).creditTransactionDao().countForDoc(doc, shop) == 0 &&
                CreditAdjustmentRepository.creditShareOf(
                    this@BillDetailsActivity, CreditAdjustmentRepository.Source.Bill(b.id), b.total
                ) > 0.005
        }
        if (!open) return
        CreditAdjustmentPrompt.handle(
            activity = this,
            billId = b.id,
            kind = CreditAdjustmentRepository.Kind.BILL_CANCEL,
            amount = b.total,
            documentLocalId = b.id,
            onDone = { refreshDueBanners() }
        )
    }

    private fun refreshDueBanners() {
        refreshOwedBanner()
        val billId = localBillId
        val layoutRefund = findViewById<View>(R.id.layoutRefundDue)
        val layoutExtra = findViewById<View>(R.id.layoutExtraDue)
        if (billId == -1) { layoutRefund.visibility = View.GONE; layoutExtra.visibility = View.GONE; return }
        lifecycleScope.launch {
            val dues = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@BillDetailsActivity).moneyEntryDao()
                    .openDues(com.example.easy_billing.util.MoneySource.BILL, billId)
            }
            val refund = dues.filter { it.direction == com.example.easy_billing.util.MoneyDirection.OUT }.sumOf { it.amount }
            val extra = dues.filter { it.direction == com.example.easy_billing.util.MoneyDirection.IN }.sumOf { it.amount }
            // A payment that arrived after the bill was cancelled gets its own wording.
            val late = dues.any {
                it.direction == com.example.easy_billing.util.MoneyDirection.OUT &&
                    it.sourceType == com.example.easy_billing.util.MoneySource.LATE_PAYMENT
            }
            findViewById<TextView>(R.id.tvRefundDueTitle).setText(if (late) R.string.due_banner_late_title else R.string.due_banner_give)
            findViewById<TextView>(R.id.tvRefundDueHint).setText(if (late) R.string.due_banner_late_hint else R.string.due_banner_action)
            layoutRefund.visibility = if (refund > 0.0) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.tvRefundDue).text = CurrencyHelper.format(this@BillDetailsActivity, refund)
            layoutRefund.setOnClickListener {
                com.example.easy_billing.util.SalesRefundPrompt.settleDue(this@BillDetailsActivity, billId) { refreshDueBanners() }
            }
            layoutExtra.visibility = if (extra > 0.0) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.tvExtraDue).text = CurrencyHelper.format(this@BillDetailsActivity, extra)
            layoutExtra.setOnClickListener {
                com.example.easy_billing.util.SalesExtraPrompt.settleDue(this@BillDetailsActivity, billId) { refreshDueBanners() }
            }
        }
    }
    private var lastUpiPaid = false

    /**
     * Toggles UI to reflect whether this bill is already cancelled — and,
     * for UPI bills only, whether the Razorpay payment link has actually
     * been paid. Called both after load (existing state) and after a
     * successful cancel action.
     */
    private fun applyBillCancellationState(
        cancelled: Boolean,
        isUpiBill: Boolean = lastIsUpiBill,
        upiPaid: Boolean = lastUpiPaid
    ) {
        lastIsUpiBill = isUpiBill
        lastUpiPaid = upiPaid

        // Status pill — back in its original top-right spot, with its
        // own tinted background again (not just plain text).
        when {
            cancelled -> {
                tvCancelledBadge.text = getString(R.string.bill_history_status_cancelled).uppercase()
                tvCancelledBadge.setTextColor(android.graphics.Color.parseColor("#76867C"))
                tvCancelledBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#EAEEEC"))
            }
            // UPI bills get a real payment-status label instead of the
            // generic "not cancelled" one — this is the only payment
            // method with an actual webhook-confirmed paid/unpaid state.
            isUpiBill && !upiPaid -> {
                tvCancelledBadge.text = getString(R.string.bill_history_status_pending_payment).uppercase()
                tvCancelledBadge.setTextColor(android.graphics.Color.parseColor("#8A6526"))
                tvCancelledBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F5EBD8"))
            }
            isUpiBill -> {
                tvCancelledBadge.text = getString(R.string.bill_history_status_paid).uppercase()
                tvCancelledBadge.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
                tvCancelledBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E7F3EE"))
            }
            else -> {
                // Renamed from "PAID" — this badge has only ever meant "not
                // cancelled," never anything about actual payment collection.
                // Kept it distinct from the real UPI-paid badge above so
                // the two don't read as the same signal.
                tvCancelledBadge.text = getString(R.string.bill_badge_active)
                tvCancelledBadge.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
                tvCancelledBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E7F3EE"))
            }
        }
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
            Toast.makeText(this, notOnPhoneMessage(), Toast.LENGTH_LONG).show()
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

                // Champagne dialog card (soft-red circle + ban icon) instead
                // of the plain system alert, matching
                // dialog_cancel_purchase_confirm.xml's pattern.
                val view = layoutInflater.inflate(R.layout.dialog_cancel_void_invoice, null)

                val dialog = AlertDialog.Builder(this@BillDetailsActivity)
                    .setView(view)
                    .create()

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                view.findViewById<TextView>(R.id.tvCancelInvoiceEyebrow).text =
                    "Invoice $resolvedBillNumber".takeIf { resolvedBillNumber.isNotBlank() }
                        ?: "Invoice —"
                view.findViewById<TextView>(R.id.tvCancelInvoiceMessage).text = message

                view.findViewById<MaterialButton>(R.id.btnConfirmCancelInvoice).setOnClickListener {
                    dialog.dismiss()
                    performCancellation()
                }
                view.findViewById<MaterialButton>(R.id.btnKeepInvoice).setOnClickListener {
                    dialog.dismiss()
                }

                dialog.show()
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
        com.example.easy_billing.util.UserEventLogger.logAction(
            "BillDetails", "cancel_bill_confirmed: bill_number=${resolvedBillNumber.ifBlank { "-" }}"
        )
        // A bill saved offline has no number yet; it is found by the phone's own id instead.
        if (resolvedBillNumber.isBlank() && localBillId == -1) {
            Toast.makeText(this, getString(R.string.billdetailsactivity_bill_number_not_resolved), Toast.LENGTH_SHORT).show()
            return
        }
        btnCancelBill.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db   = AppDatabase.getDatabase(this@BillDetailsActivity)
                val now  = System.currentTimeMillis()

                // 0. A UPI payment may have landed that this phone has not heard about yet. Pull it first
                // (best effort, short wait), so a bill that was really paid is treated as paid when cancelled.
                val before = findLocalBill(db)
                if (before != null && com.example.easy_billing.util.PaymentMethodText.hasUpi(before.paymentMethod) &&
                    !before.paymentStatus.equals("paid", ignoreCase = true) && isOnline()) {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(4000L) { SyncManager(this@BillDetailsActivity).pullPaymentStatus() }
                    } catch (_: Exception) { /* offline / slow: cancel anyway; a late payment is caught on the next sync */ }
                }

                // 1. Mark the legacy bills row.
                val localBill = findLocalBill(db)

                // INV-8 fix: markBillCancelled is now a conditional UPDATE
                // (WHERE is_cancelled = 0) that returns how many rows it
                // actually changed. 1 means this call is the one genuinely
                // cancelling the bill for the first time; 0 means someone
                // already cancelled it (a rotated screen, a process
                // restart, or two near-simultaneous taps bypassing the
                // disabled-button guard). Only the former should ever
                // restock inventory — restocking on a 0 would silently
                // double-credit stock for a bill that was already voided.
                // The void, the GST mark, the restock and the refund note are ONE transaction:
                // either the bill is fully cancelled with its money note, or nothing changed.
                val didCancelNow = db.withTransaction {
                val cancelledNow = localBill != null &&
                    db.billDao().markBillCancelled(localBill.id, now) > 0

                if (localBill != null && cancelledNow) {
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
                if (localBill != null && cancelledNow) {
                    val items = db.billItemDao().getItemsForBill(localBill.id)
                    val gstInvoice = db.gstSalesInvoiceDao().getByBillId(localBill.id)
                    val gstItems = if (gstInvoice != null) db.gstSalesInvoiceItemDao().getByInvoice(gstInvoice.id) else emptyList()

                    for (bi in items) {
                        val product = db.productDao().getById(bi.productId) ?: continue
                        if (!product.trackInventory) continue

                        val returnedQty = db.creditNoteDao().getTotalReturnedQty(localBill.id, bi.productId)
                        val debitedQty = db.creditNoteItemDao().getTotalDebitedForBillProduct(localBill.id, bi.productId)
                        val qtyToRestore = bi.quantity + debitedQty - returnedQty

                        if (qtyToRestore > 0.0) {
                            val gstItem = gstItems.find { it.productId == bi.productId }
                            val itemCessRate = gstItem?.cessRate ?: product.cessRate
                            val totalTaxRate = bi.gstRate + itemCessRate

                            val unitCostGross = if (bi.quantity > 0.0) bi.costPriceUsed / bi.quantity else 0.0
                            val unitCostNet = if (totalTaxRate > 0.0) unitCostGross / (1.0 + totalTaxRate / 100.0) else unitCostGross

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

                            val batchInvoice = Math.round(unitCostGross * qtyToRestore * 100.0) / 100.0
                            val batchTaxable = Math.round(unitCostNet * qtyToRestore * 100.0) / 100.0

                            com.example.easy_billing.repository.SalesRestock.restock(
                                db        = db,
                                billItemId = bi.id,
                                productId = bi.productId,
                                quantity  = qtyToRestore,
                                costPrice = unitCostGross,
                                batchMeta = InventoryManager.StockBatchMeta(
                                    purchaseInvoiceId    = null,
                                    supplierName         = null,
                                    supplierGstin        = null,
                                    invoiceNumber        = null,
                                    batchCode            = "CANCELLED_INVOICE-${localBill.id}",
                                    unitCostExcludingTax = unitCostNet,
                                    gstPercent           = bi.gstRate,
                                    cgstPercent          = restockCgstPercent,
                                    sgstPercent          = restockSgstPercent,
                                    igstPercent          = restockIgstPercent,
                                    invoiceValue         = batchInvoice,
                                    taxableValue         = batchTaxable
                                ), logType = InventoryManager.LogType.CANCEL_RESTOCK
                            )
                        }
                    }
                }

                // 4. Money side of the void: close unpaid UPI / uncollected extra charges and save a
                // "refund still due" note for every rupee received and not handed back. Same transaction.
                if (localBill != null) com.example.easy_billing.util.CancelMoney.ensureRefundNote(db, localBill.id)
                cancelledNow
                }

                withContext(Dispatchers.Main) {
                    applyBillCancellationState(cancelled = true)
                    refreshOwedBanner()
                    Toast.makeText(
                        this@BillDetailsActivity,
                        getString(R.string.billdetailsactivity_invoice_voided_cancellation_will),
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
                            onDone = { refreshDueBanners() }
                        )
                    }
                }

                // 5. Best-effort sync of cancellations to the backend. Done AFTER the cancel is saved and
                // shown, so a slow connection can never hold back the refund questions or lose them.
                try {
                    val sync = SyncManager(this@BillDetailsActivity)
                    sync.syncGstCancellations()
                    // Also void the analytics bills row so reports
                    // exclude this invoice (covers non-GST bills too).
                    sync.syncBillCancellations()
                } catch (e: Exception) {
                    e.printStackTrace() // will retry on next sync cycle
                }
            } catch (e: Exception) {
                Log.e("BillDetailsActivity", "Bill cancellation failed", e)
                com.example.easy_billing.util.UserEventLogger.logError(
                    "BillDetailsActivity", "bill_cancellation_failed: ${e.javaClass.simpleName}"
                )
                withContext(Dispatchers.Main) {
                    btnCancelBill.isEnabled = true
                    Toast.makeText(
                        this@BillDetailsActivity,
                        R.string.billdetailsactivity_cancellation_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openSalesReturn() {
        if (localBillId == -1) {
            Toast.makeText(this, notOnPhoneMessage(), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(localBillId)
            withContext(Dispatchers.Main) {
                if (bill.isCancelled) {
                    Toast.makeText(this@BillDetailsActivity, getString(R.string.billdetailsactivity_cannot_issue_a_credit), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, notOnPhoneMessage(), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(localBillId)
            withContext(Dispatchers.Main) {
                if (bill.isCancelled) {
                    Toast.makeText(this@BillDetailsActivity, getString(R.string.billdetailsactivity_cannot_issue_a_debit), Toast.LENGTH_SHORT).show()
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

    /**
     * Inline "DEBIT & CREDIT NOTES" list, right under the line items —
     * same card-list pattern as PurchaseDetailsActivity.buildPriorReturns,
     * reusing item_debit_note_row.xml. Credit notes ("C") reduce what the
     * customer owes (gold, minus, "returned" caption); debit notes ("D")
     * add to it (teal, plus, "issued" caption).
     */
    private fun buildBillNotes(notes: List<com.example.easy_billing.db.CreditNote>, originalTotal: Double) {
        if (notes.isEmpty()) {
            // Whole card hidden together, not just its header — same rule
            // Purchase Details' Returns card uses, so an ordinary bill
            // with no notes never shows a stray half-empty third card.
            cardBillNotes.visibility = View.GONE
            llBillNotes.removeAllViews()
            rowNetBillNotes.visibility = View.GONE
            return
        }

        cardBillNotes.visibility = View.VISIBLE
        llBillNotes.removeAllViews()

        // Net after notes — same rollup as Purchase Details' Card 3:
        // credit notes ("C") reduce what the customer owes, debit notes
        // ("D") add to it.
        val delta = notes.sumOf { if (it.noteType == "C") -it.totalAmount else it.totalAmount }
        val creditNotes = notes.filter { it.noteType == "C" }
        val debitNotes = notes.filter { it.noteType != "C" }
        val creditTotal = creditNotes.sumOf { it.totalAmount }
        val debitTotal = debitNotes.sumOf { it.totalAmount }

        fun noteWord(count: Int) = if (count == 1)
            getString(R.string.bill_details_note_word)
        else
            getString(R.string.bill_details_notes_word)

        tvNetAfterBillNotes.text = CurrencyHelper.format(this, originalTotal + delta)

        val parts = mutableListOf(
            "${CurrencyHelper.format(this, originalTotal)} ${getString(R.string.bill_details_billed_suffix)}"
        )
        if (creditNotes.isNotEmpty()) {
            parts += "${CurrencyHelper.format(this, creditTotal)} ${getString(R.string.bill_details_returned_across)} " +
                "${creditNotes.size} ${noteWord(creditNotes.size)}"
        }
        if (debitNotes.isNotEmpty()) {
            parts += "${CurrencyHelper.format(this, debitTotal)} ${getString(R.string.bill_details_added_across)} " +
                "${debitNotes.size} ${noteWord(debitNotes.size)}"
        }
        tvNetOriginalBillAmount.text = parts.joinToString(" · ")
        rowNetBillNotes.visibility = View.VISIBLE

        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        for ((index, note) in notes.withIndex()) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_debit_note_row, llBillNotes, false)

            val isCredit = note.noteType == "C"
            val hex = if (isCredit) "#8A6526" else "#0F6E56"

            card.findViewById<TextView>(R.id.tvNoteNumber).text = note.noteNumber
            card.findViewById<TextView>(R.id.tvProductName).text =
                note.customerName.ifBlank { "against ${note.originalInvoiceNumber}" }
            card.findViewById<TextView>(R.id.tvReturnedQty).text = ""
            card.findViewById<TextView>(R.id.tvNoteDate).text =
                "· ${dateFmt.format(Date(note.noteDate))}"

            card.findViewById<View>(R.id.viewNoteStripe).setBackgroundColor(Color.parseColor(hex))
            card.findViewById<ImageView>(R.id.ivNoteIcon).apply {
                setImageResource(
                    if (isCredit) R.drawable.ic_lc_arrow_up_right
                    else R.drawable.ic_lc_arrow_down_left
                )
                imageTintList = ColorStateList.valueOf(Color.parseColor(hex))
                backgroundTintList = ColorStateList.valueOf(
                    Color.parseColor(if (isCredit) "#EEF2F0" else "#E4F1EC")
                )
            }
            card.findViewById<TextView>(R.id.tvReturnValue).apply {
                text = (if (isCredit) "− " else "+ ") + CurrencyHelper.format(
                    this@BillDetailsActivity, note.totalAmount
                )
                setTextColor(Color.parseColor(hex))
            }
            card.findViewById<TextView>(R.id.tvNoteCaption).text =
                if (isCredit) "returned" else "added"
            card.findViewById<TextView>(R.id.tvValuationVariance).visibility = View.GONE

            card.findViewById<View>(R.id.viewNoteDivider).visibility =
                if (index == notes.lastIndex) View.GONE else View.VISIBLE

            llBillNotes.addView(card)
        }
    }

    private fun generatePdfAndPrint() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val db = AppDatabase.getDatabase(this@BillDetailsActivity)

                val response = loadDetailResponse(token)

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
                    supplyType = response.bill.supply_type ?: "intrastate",
                    isCancelled = response.bill.is_cancelled
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
                val printerLayout = db.billingSettingsDao().get()?.printerLayout ?: "80mm"

                val payBreakdown = com.example.easy_billing.util.BillPaymentText.breakdown(this@BillDetailsActivity, printBill)
                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    payBreakdown = payBreakdown,
                    bill = printBill,
                    billItems = printItems,
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme,
                    gstInvoice = savedInvoice,
                    printerLayout = printerLayout
                )

            } catch (e: SecurityException) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    getString(R.string.billdetailsactivity_couldnt_save_the_invoice),
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

    // Opens WhatsApp directly (wa.me deep link) — see CustomerShareHelper's
    // doc comment for why this needs one extra tap from the cashier
    // (WhatsApp's own Send button) instead of a fully silent send.

    /** Opens WhatsApp with the message ready to send — no runtime permission needed. */
    private fun showSendToCustomerOptions() {
        sendToCustomer()
    }

    // Deliberately its own fetch, not reusing generatePdfAndPrint() —
    // that function ends in a print call, this one ends in a share
    // intent; keeping them independent means a change to either can
    // never accidentally alter the other's behavior.
    private fun sendToCustomer() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null) ?: return@launch

            Toast.makeText(this@BillDetailsActivity, getString(R.string.send_to_customer_sending), Toast.LENGTH_SHORT).show()

            try {
                val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                // The invoice link and any payment link live on the server, so a bill
                // that has not synced yet cannot be sent from here.
                val pending = findLocalBill(db)
                if (pending != null && pending.billNumber.isNotBlank()) resolvedBillNumber = pending.billNumber
                if (pending != null && pending.billNumber.isBlank()) {
                    Toast.makeText(this@BillDetailsActivity, getString(R.string.bill_needs_internet_sync), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val response = loadDetailResponse(token)
                if (response.bill.is_cancelled || pending?.isCancelled == true) {
                    Toast.makeText(this@BillDetailsActivity, getString(R.string.send_cancelled_blocked), Toast.LENGTH_LONG).show()
                    return@launch
                }

                val bill = Bill(
                    id = response.bill.bill_id,
                    billNumber = response.bill.bill_number,
                    date = response.bill.created_at,
                    subTotal = response.bill.total_amount - response.bill.gst + response.bill.discount,
                    gst = response.bill.gst,
                    discount = response.bill.discount,
                    total = response.bill.total_amount,
                    paymentMethod = response.bill.payment_method,
                    customerType = response.bill.invoice_type ?: "B2C",
                    placeOfSupply = response.bill.customer_state_code ?: "",
                    supplyType = response.bill.supply_type ?: "intrastate",
                    paymentStatus = response.bill.payment_status
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

                val localBill = if (localBillId != -1) db.billDao().getBillById(localBillId) else null
                val localItems = if (localBillId != -1) db.billItemDao().getItemsForBill(localBillId) else emptyList()
                val savedInvoice = if (localBillId != -1) db.gstSalesInvoiceDao().getByBillId(localBillId) else null

                // Server (`bill`, just fetched above) is the source of
                // truth for payment_status, but a local mark-as-paid can
                // beat it to the punch before the next sync pulls the
                // server's own copy forward — same "either wins" merge
                // loadBillDetails() uses, so this can't miss a paid
                // status and accidentally attach a stale payment link.
                val isPaid = bill.paymentStatus.equals("paid", ignoreCase = true) ||
                    localBill?.paymentStatus.equals("paid", ignoreCase = true)
                val sendBill = (localBill ?: bill).let {
                    if (isPaid) it.copy(paymentStatus = "paid") else it
                }
                val sendItems = if (localBill != null && localItems.isNotEmpty()) localItems else billItems
                val printerLayout = db.billingSettingsDao().get()?.printerLayout ?: "80mm"

                val customerName = savedInvoice?.customerName
                val customerPhone = savedInvoice?.customerPhone

                val totalCess = if (savedInvoice != null) {
                    db.gstSalesInvoiceItemDao().getByInvoice(savedInvoice.id).sumOf { it.cessAmount }
                } else 0.0

                com.example.easy_billing.util.CustomerShareHelper.sendToCustomer(
                    context = this@BillDetailsActivity,
                    bill = sendBill,
                    billItems = sendItems,
                    storeInfo = storeInfo,
                    gstScheme = savedInvoice?.gstScheme,
                    gstInvoice = savedInvoice,
                    printerLayout = printerLayout,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    totalCess = totalCess
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    "Couldn't send the invoice: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Split bill: one tidy line per payment method (method left, amount right) in a
     * small white box, instead of one long "Cash + Card + Bank transfer" sentence.
     * [lines] are "Method amount" strings; null clears the box.
     */
    private fun showPaidBreakdown(lines: List<String>?) {
        val dp = resources.displayMetrics.density
        fun row(left: String, right: String?, color: Int, last: Boolean) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (9 * dp).toInt(), 0, (9 * dp).toInt())
            }
            r.addView(TextView(this).apply {
                text = left; textSize = 12.5f; setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (right != null) r.addView(TextView(this).apply {
                text = right; textSize = 12.5f; setTextColor(Color.parseColor("#1A1A18"))
                gravity = android.view.Gravity.END
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@BillDetailsActivity, R.font.googlesans_semibold)
            })
            llPaidBreakdown.addView(r)
            if (!last) llPaidBreakdown.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#E3E8E4"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
            })
        }
        llPaidBreakdown.removeAllViews()
        if (lines.isNullOrEmpty()) { llPaidBreakdown.visibility = View.GONE; return }
        lines.forEachIndexed { i, line ->
            val cut = line.lastIndexOf(' ')
            val method = if (cut > 0) line.substring(0, cut) else line
            val amount = if (cut > 0) line.substring(cut + 1) else null
            row(method, amount, Color.parseColor("#5E6962"), i == lines.lastIndex)
        }
        llPaidBreakdown.visibility = View.VISIBLE
        tvPaidThrough.visibility = View.GONE
    }

    // ===== Manual "mark as paid" override =====

    private fun confirmMarkAsPaid() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mark_as_paid, null)

        val tvBillInfo = dialogView.findViewById<TextView>(R.id.tvMarkPaidBillInfo)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnMarkPaidConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnMarkPaidCancel)

        // Reuses the total already rendered on screen (tvTotal) rather
        // than re-deriving it — this dialog is purely a confirmation
        // over what's already showing, no new data fetch needed.
        var amountText = tvTotal.text?.toString().orEmpty()
        fun showInfo() {
            tvBillInfo.text = when {
                resolvedBillNumber.isNotBlank() && amountText.isNotBlank() -> "$resolvedBillNumber · $amountText"
                resolvedBillNumber.isNotBlank() -> resolvedBillNumber
                else -> amountText
            }
        }
        showInfo()
        // Split bill: only the UPI part is being confirmed, so show that amount, not the whole bill.
        if (localBillId != -1) lifecycleScope.launch {
            val upi = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@BillDetailsActivity).moneyEntryDao()
                    .upiAmountForSource(com.example.easy_billing.util.MoneySource.BILL, localBillId)
            }
            val whole = tvTotal.text?.toString().orEmpty()
            if (upi > 0.0 && com.example.easy_billing.util.PaymentMethodText.isSplit(lastPaymentMethod)) {
                amountText = CurrencyHelper.format(this@BillDetailsActivity, upi) + " (UPI part)"
                showInfo()
            } else amountText = whole
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            markAsPaid()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun markAsPaid() {
        if (resolvedBillNumber.isBlank() && localBillId == -1) {
            Toast.makeText(this, getString(R.string.bill_needs_internet_sync), Toast.LENGTH_LONG).show()
            return
        }
        if (localBillId == -1) { markAsPaidNow(0.0); return }
        // A pay link made before a return still asks for the older, larger amount. If so, ask what really came in.
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val (owed, asked) = withContext(Dispatchers.IO) {
                try {
                    com.example.easy_billing.util.UpiReceipt.linkAboveOwed(
                        this@BillDetailsActivity, db, db.billDao().getBillById(localBillId)
                    )
                } catch (_: Exception) { 0.0 to 0.0 }
            }
            if (asked <= 0.004) { markAsPaidNow(0.0); return@launch }
            val fmt = { v: Double -> CurrencyHelper.format(this@BillDetailsActivity, v) }
            AlertDialog.Builder(this@BillDetailsActivity)
                .setTitle(getString(R.string.mark_paid_amount_title))
                .setMessage(getString(R.string.mark_paid_amount_msg, fmt(asked), fmt(owed)))
                .setPositiveButton(getString(R.string.mark_paid_amount_owed_btn, fmt(owed))) { d, _ ->
                    d.dismiss(); markAsPaidNow(0.0)
                }
                .setNegativeButton(getString(R.string.mark_paid_amount_link_btn, fmt(asked))) { d, _ ->
                    d.dismiss(); markAsPaidNow(asked)
                }
                .setNeutralButton(android.R.string.cancel) { d, _ -> d.dismiss() }
                .show()
        }
    }

    /** [paidFull] > 0 means the customer paid that whole older link amount; the part above what is owed is noted as a refund due. */
    private fun markAsPaidNow(paidFull: Double) {
        lifecycleScope.launch {
            val billNumber = freshBillNumber()
            // Bill is on this phone: mark it paid here first (works offline), then tell the server.
            if (localBillId != -1) {
                val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        db.billDao().markPaidOnPhone(localBillId)
                        db.moneyEntryDao().markReceived(
                            com.example.easy_billing.util.MoneySource.BILL, localBillId, com.example.easy_billing.util.MoneyMode.UPI,
                            com.example.easy_billing.util.appNow()
                        )
                        if (paidFull > 0.004) {
                            val got = db.moneyEntryDao().receivedUpiForBill(localBillId)
                            com.example.easy_billing.util.UpiReceipt.recordOverpay(
                                db, db.billDao().getBillById(localBillId),
                                com.example.easy_billing.util.PaymentSplit.round2(paidFull - got)
                            )
                        }
                    }
                }
                var told = false
                if (billNumber.isNotBlank() && isOnline()) {
                    val st = com.example.easy_billing.repository.PosPaymentRepository.markPaid(this@BillDetailsActivity, billNumber)
                    if (st == "paid") {
                        withContext(Dispatchers.IO) { db.billDao().clearPaidPush(localBillId) }
                        told = true
                    }
                }
                Toast.makeText(
                    this@BillDetailsActivity,
                    getString(if (told) R.string.mark_as_paid_success else R.string.mark_as_paid_saved_offline),
                    Toast.LENGTH_LONG
                ).show()
                loadBillDetails()
                return@launch
            }

            val status = com.example.easy_billing.repository.PosPaymentRepository.markPaid(
                this@BillDetailsActivity, billNumber
            )

            if (status == "paid") {
                // Mirror the same local write the webhook-driven sync path
                // uses, so the pill/badge stay correct even before the
                // next full sync pass.
                withContext(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@BillDetailsActivity)
                    db.billDao().markPaymentStatus(billNumber, "paid", null)
                    // Payment tracking: the UPI money for this bill is now received.
                    db.billDao().getByBillNumber(billNumber)?.let { b ->
                        db.moneyEntryDao().markReceived(
                            com.example.easy_billing.util.MoneySource.BILL, b.id, com.example.easy_billing.util.MoneyMode.UPI,
                            com.example.easy_billing.util.appNow()
                        )
                    }
                }
                Toast.makeText(this@BillDetailsActivity, getString(R.string.mark_as_paid_success), Toast.LENGTH_SHORT).show()
                loadBillDetails()
            } else {
                Toast.makeText(this@BillDetailsActivity, getString(R.string.mark_as_paid_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Builds the "Items sold" card's rows by inflating item_purchase_detail_row.xml
     * directly into llBillItems — the same manual-inflate pattern Purchase
     * Details uses for its own item list (PurchaseDetailsActivity.buildItemsList),
     * not a RecyclerView. A RecyclerView measured wrap_content inside this
     * screen's outer ScrollView could end up showing only the first row;
     * this sidesteps that entirely since every row is a real, already-measured
     * child view the moment it's added.
     */
    private fun buildBillItemsList(items: List<com.example.easy_billing.network.BillItemResponse>) {
        llBillItems.removeAllViews()
        for ((index, item) in items.withIndex()) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_purchase_detail_row, llBillItems, false)

            // Avatar — first letters of the first two words, uppercased.
            val words = item.product_name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val initials = when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                words.size == 1 && words[0].length >= 2 -> words[0].substring(0, 2)
                words.size == 1 -> words[0]
                else -> "?"
            }.uppercase()
            row.findViewById<TextView>(R.id.tvAvatar).text = initials

            // A stable colour per product — same item, same colour every
            // time, same palette Purchase Details uses for its own rows.
            val (tileHex, inkHex) = itemPalette[
                Math.floorMod(item.product_name.hashCode(), itemPalette.size)
            ]
            row.findViewById<TextView>(R.id.tvAvatar).apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(tileHex))
                setTextColor(android.graphics.Color.parseColor(inkHex))
            }
            row.findViewById<View>(R.id.viewItemStripe)
                .setBackgroundColor(android.graphics.Color.parseColor(inkHex))

            // Variant ("type") shown inline right after the product name —
            // e.g. "Rice . 1kg" — instead of as a separate secondary line,
            // so the two read together as one name.
            row.findViewById<TextView>(R.id.tvProductName).text =
                if (!item.variant.isNullOrBlank()) "${item.product_name} . ${item.variant}"
                else item.product_name

            // Bill items don't carry a "returned" state here (that's tracked
            // separately via credit/debit notes), so this chip stays hidden.
            row.findViewById<TextView>(R.id.tvReturnedChip).visibility = View.GONE

            val qtyLabel = if (item.quantity == item.quantity.toLong().toDouble())
                item.quantity.toLong().toString() else item.quantity.toString()
            val unitLabel = item.unit?.let { " $it" } ?: ""
            row.findViewById<TextView>(R.id.tvHsnQty).text =
                "$qtyLabel$unitLabel × ${CurrencyHelper.format(this, item.price)}"

            // GROSS line amount (price × qty). The bill discount is shown once,
            // as its own bill-level line — not baked into each row.
            row.findViewById<TextView>(R.id.tvLineTotal).text =
                CurrencyHelper.format(this, item.price * item.quantity)

            // No per-item GST/cost breakdown exists for bill items, and the
            // variant now shows inline with the product name above instead
            // of here, so this secondary line always stays hidden.
            row.findViewById<TextView>(R.id.tvCostAndGst).visibility = View.GONE

            // No trailing hairline on the last line of the card.
            row.findViewById<View>(R.id.viewItemDivider).visibility =
                if (index == items.lastIndex) View.GONE else View.VISIBLE

            llBillItems.addView(row)
        }
    }
}
