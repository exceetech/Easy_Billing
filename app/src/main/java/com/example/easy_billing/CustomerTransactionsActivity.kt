package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditTransaction
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.InvoicePdfGenerator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Deliberately NOT a BaseActivity.
 *
 * BaseActivity puts the window into sticky-immersive mode on every focus
 * change — hiding the system bars and calling setDecorFitsSystemWindows(false).
 * The system print dialog is a system-UI surface, so that fights with it, and
 * this screen is the only one in the app that raises one.
 *
 * The toolbar is set up locally instead, using the same icon and click
 * handling BaseActivity would have applied, so it still matches every other
 * screen.
 */
class CustomerTransactionsActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: TransactionAdapter

    // ✅ UI list (with headers)
    private val list = mutableListOf<TransactionUI>()

    // ✅ ORIGINAL DATA (IMPORTANT)
    private val originalList = mutableListOf<CreditTransaction>()

    private val currentList = mutableListOf<CreditTransaction>()

    private val db by lazy { AppDatabase.getDatabase(this) }

    /** Server id — used only to fetch history from the API. -1 if never synced. */
    private var accountId: Int = -1

    /** This device's row id — matches CreditTransaction.accountId and the local
     *  credit_accounts primary key. Never interchangeable with [accountId]. */
    private var localAccountId: Int = -1

    private var accountName: String = ""
    private var accountPhone: String = ""

    private val shopId by lazy {
        getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", -1)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_transactions)

        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Set here rather than inherited, since this screen doesn't extend
        // BaseActivity — see the note on the class.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE


        rv = findViewById(R.id.rvTransactions)

        accountId = intent.getIntExtra("ACCOUNT_ID", -1)
        localAccountId = intent.getIntExtra("LOCAL_ACCOUNT_ID", -1)
        accountName = intent.getStringExtra("ACCOUNT_NAME") ?: "Customer"
        accountPhone = intent.getStringExtra("ACCOUNT_PHONE") ?: ""

        // Only the LOCAL id is required. A missing server id just means the
        // account hasn't synced yet — its history still exists on this device,
        // and closing the screen with "Invalid account" hid it entirely.
        if (localAccountId == -1) {
            Toast.makeText(this, "Invalid account", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Name split across two views so the last word carries the serif
        // accent, matching the other screens. A single-word name renders plain
        // with an empty accent view — the marginStart on that view would
        // otherwise leave a visible gap, so it collapses instead.
        val parts = accountName.trim().split(" ").filter { it.isNotBlank() }
        val accent = findViewById<TextView>(R.id.tvHeaderNameAccent)
        findViewById<TextView>(R.id.tvHeaderName).text =
            if (parts.size > 1) parts.dropLast(1).joinToString(" ") else accountName
        accent.text = if (parts.size > 1) parts.last() else ""
        accent.visibility = if (parts.size > 1) View.VISIBLE else View.GONE

        // The chip goes entirely when there is no number, rather than showing
        // an empty pill with just the icon in it.
        findViewById<TextView>(R.id.tvHeaderPhone).text = accountPhone
        findViewById<View>(R.id.chipHeaderPhone).visibility =
            if (accountPhone.isNotBlank()) View.VISIBLE else View.GONE

        adapter = TransactionAdapter(list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // Rows and date headers draw square, so the first and last would
        // overhang the card's 18dp corners — most visibly the coloured stripe,
        // which runs the full row height hard against the left edge. Same
        // treatment as the credit accounts list. Set in code because
        // android:clipToOutline is API 31+.
        rv.clipToOutline = true

        setupSearch()
        setupOutsideTouch()

        findViewById<View>(R.id.btnFilter).setOnClickListener {
            showDateFilter()
        }

        findViewById<View>(R.id.btnSummary).setOnClickListener {
            showSummary()
        }

        findViewById<View>(R.id.btnPrint).setOnClickListener {
            printReport()
        }

        loadTransactions()
    }

    /**
     * The same toolbar BaseActivity.setupToolbar produces — custom back icon,
     * tint, and back handling through the dispatcher.
     *
     * Duplicated rather than inherited because this screen can't extend
     * BaseActivity; see the note on the class. The old local copy set neither
     * the icon nor the click handler, which is why this screen showed the
     * stock Material arrow.
     */
    private fun setupToolbar(toolbarId: Int) {
        val toolbar = findViewById<Toolbar>(toolbarId)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowHomeEnabled(false)
            setDisplayHomeAsUpEnabled(true)
        }

        toolbar.setNavigationIcon(R.drawable.ic_back_arrow)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ================= LOAD =================
    private fun loadTransactions() = lifecycleScope.launch {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        val merged = mutableListOf<CreditTransaction>()

        // ── 1. Server history ────────────────────────────────────────────
        // Skipped when there is no token or the account has never synced.
        // A failure here is no longer fatal: the local ledger below still
        // renders, so going offline doesn't blank the screen.
        if (token != null && accountId != -1) {
            try {
                val response = RetrofitClient.api.getTransactions(token, accountId)
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

                response.forEach {
                    val time = try {
                        parser.parse(it.created_at)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    merged.add(
                        CreditTransaction(
                            accountId = localAccountId,
                            shopId = shopId,
                            amount = it.amount,
                            type = it.type,
                            timestamp = time,
                            isSynced = true
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@CustomerTransactionsActivity,
                    "Couldn't reach the server — showing what's on this device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ── 2. Local transactions not yet pushed ─────────────────────────
        // Only the unsent ones: anything already synced is in the server list
        // above, and adding both would show every payment twice.
        val unsent = withContext(Dispatchers.IO) {
            db.creditTransactionDao()
                .getByAccount(localAccountId, shopId)
                .filter { !it.isSynced }
        }
        merged.addAll(unsent)

        originalList.clear()
        originalList.addAll(merged.sortedByDescending { it.timestamp })

        // Through applyDateFilter, so a filter set before a refresh survives
        // it. Rebuilding the list directly here reset the view to everything
        // while the chip still claimed a range was active.
        applyDateFilter()

        updateLedgerSummary()
    }

    /**
     * Balance and the two totals that explain it.
     *
     * All three come from ONE walk of the ledger, so Billed − Received always
     * equals the balance. Summing the totals separately from folding the
     * balance let them disagree, because SETTLE resets the balance to zero
     * instead of subtracting an amount.
     *
     * The write-off is therefore taken as the balance standing at that moment,
     * not the figure stored on the row. That is the amount actually cleared,
     * and it also repairs older settle rows, which recorded 0 — those left
     * Received short while the balance still went to zero.
     */
    data class LedgerTotals(
        /** Goods given on credit, net of goods returned. Cash never moved. */
        val billed: Double,
        /** Money actually collected from the customer. */
        val received: Double,
        /** Debt forgiven. No cash moved — this is a loss, not income. */
        val writtenOff: Double,
        /** The customer's advance handed back. Cash left the shop. */
        val refunded: Double,
        val balance: Double
    )

    /**
     * The four totals and the balance from ONE walk of the ledger.
     *
     * They reconcile as:
     *
     *     balance = billed − received − writtenOff + refunded
     *
     * Four events, four columns. Two of them move cash and two don't, and
     * collapsing that into two buckets is what put a refund in Billed — where
     * it read as a sale — and a write-off in Received, where it read as money
     * collected that was never actually received.
     *
     * Used by the header, the Summary dialog and the printed statement, so all
     * three agree. Computing them separately is what let them disagree before.
     */
    private fun computeTotals(txns: List<CreditTransaction>): LedgerTotals {
        var billed = 0.0
        var received = 0.0
        var writtenOff = 0.0
        var refunded = 0.0
        var balance = 0.0

        for (txn in txns.sortedBy { it.timestamp }) {
            when (txn.type) {
                // All three close the account. The sum involved is taken from
                // the balance standing at that moment, not the stored figure —
                // legacy SETTLE rows may hold 0, and after a settle the stored
                // amount can differ from what was really cleared if the device
                // and server had drifted. The running balance is the truth.
                //
                // Which column it lands in comes from the type where there is
                // one, and from the sign of the balance for legacy rows.
                "WRITE_OFF", "REFUND", "SETTLE" -> {
                    if (balance > 0) writtenOff += balance
                    else if (balance < 0) refunded += -balance
                    balance = 0.0
                }

                else -> {
                    billed += debitOf(txn)
                    received += creditOf(txn)
                    balance = applyToBalance(balance, txn)
                }
            }
        }
        return LedgerTotals(billed, received, writtenOff, refunded, balance)
    }

    private fun updateLedgerSummary() {
        val (billed, received, writtenOff, refunded, balance) = computeTotals(originalList)

        findViewById<TextView>(R.id.tvTotalBilled).text = money(billed)
        findViewById<TextView>(R.id.tvTotalReceived).text = money(received)

        // Adjustments show only when they exist. Almost every customer has
        // neither, so the card keeps the shape it had before.
        val hasWrittenOff = writtenOff > 0.005
        val hasRefunded = refunded > 0.005

        findViewById<TextView>(R.id.tvWrittenOff).text = money(writtenOff)
        findViewById<TextView>(R.id.tvRefunded).text = money(refunded)
        findViewById<View>(R.id.rowWrittenOff).visibility =
            if (hasWrittenOff) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowRefunded).visibility =
            if (hasRefunded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutAdjustments).visibility =
            if (hasWrittenOff || hasRefunded) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvCurrentBalance).text = money(kotlin.math.abs(balance))

        findViewById<TextView>(R.id.tvBalanceCaption).apply {
            when {
                balance > 0 -> { text = "owes you"; setTextColor(android.graphics.Color.parseColor("#B23A3A")) }
                balance < 0 -> { text = "in advance"; setTextColor(android.graphics.Color.parseColor("#0F6E56")) }
                else        -> { text = "settled"; setTextColor(android.graphics.Color.parseColor("#8A8272")) }
            }
        }
        findViewById<TextView>(R.id.tvCurrentBalance).setTextColor(
            android.graphics.Color.parseColor(
                when {
                    balance > 0 -> "#B23A3A"
                    balance < 0 -> "#0F6E56"
                    else -> "#1A1A18"
                }
            )
        )
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

    // ================= SEARCH =================

    private lateinit var etSearch: EditText

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var runnable: Runnable? = null

    private fun setupSearch() {

        etSearch = findViewById(R.id.etSearchTxn)

        // ✅ Disable auto focus at start
        etSearch.apply {
            isFocusable = false
            isFocusableInTouchMode = true
            clearFocus()
        }

        // ✅ Enable focus ONLY when clicked
        etSearch.setOnClickListener {

            etSearch.isFocusableInTouchMode = true
            etSearch.requestFocus()

            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        // ✅ Debounced Search (SMOOTH)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                // Cancel previous search
                runnable?.let { handler.removeCallbacks(it) }

                runnable = Runnable {

                    val query = s?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?.take(50)
                        ?: ""

                    val filtered = if (query.isEmpty()) {
                        currentList   // ✅ IMPORTANT (not originalList)
                    } else {
                        currentList.filter {
                            it.type.lowercase().contains(query) ||
                                    it.amount.toString().contains(query)
                        }
                    }

                    val ui = prepareUI(filtered)

                    list.clear()
                    list.addAll(ui)
                    adapter.notifyDataSetChanged()
                }

                handler.postDelayed(runnable!!, 300) // 🔥 smooth delay
            }
        })
    }

    private fun setupOutsideTouch() {

        val root = findViewById<View>(android.R.id.content)

        root.setOnTouchListener { _, _ ->

            etSearch.clearFocus()

            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

            false
        }
    }

    // ================= DATE FILTER =================
    /** Active date filter. Null on either side means open-ended. */
    private var filterStart: Long? = null
    private var filterEnd: Long? = null

    /** Rows inside the active range. The single definition of what is filtered. */
    private fun withinFilter(txns: List<CreditTransaction>): List<CreditTransaction> =
        txns.filter {
            (filterStart == null || it.timestamp >= filterStart!!) &&
            (filterEnd == null || it.timestamp <= filterEnd!!)
        }

    /**
     * Re-applies the active filter to the ledger and updates the chip.
     *
     * The chip label used to be fixed at "All time" no matter what was applied,
     * so an active filter was invisible — and the summary dialog reads its
     * scope from that label, so it was reporting the wrong scope too.
     */
    private fun applyDateFilter() {
        val filtered = withinFilter(originalList)

        currentList.clear()
        currentList.addAll(filtered)

        list.clear()
        list.addAll(prepareUI(filtered))
        adapter.notifyDataSetChanged()

        findViewById<TextView>(R.id.tvFilterLabel).text = filterLabel()
    }

    private fun filterLabel(): String {
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val s = filterStart
        val e = filterEnd
        return when {
            s == null && e == null -> "All time"
            s != null && e == null -> "From ${fmt.format(Date(s))}"
            s == null && e != null -> "Until ${fmt.format(Date(e))}"
            else -> "${fmt.format(Date(s!!))} – ${fmt.format(Date(e!!))}"
        }
    }

    private fun startOfDay(cal: Calendar): Long = cal.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfDay(cal: Calendar): Long = cal.apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private fun showDateFilter() {

        val view = layoutInflater.inflate(R.layout.dialog_date_range, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvRangeName).text = accountName

        // The dialog opens showing whatever is already applied, rather than
        // blank — otherwise the only way to see the active range was to cancel
        // and read the chip.
        var draftStart: Long? = filterStart
        var draftEnd: Long? = filterEnd

        val tvStart = view.findViewById<TextView>(R.id.tvStartDate)
        val tvEnd = view.findViewById<TextView>(R.id.tvEndDate)
        val tvMatches = view.findViewById<TextView>(R.id.tvRangeMatches)
        val chipAll = view.findViewById<TextView>(R.id.chipAllTime)
        val chipMonth = view.findViewById<TextView>(R.id.chipThisMonth)
        val chipLast3 = view.findViewById<TextView>(R.id.chipLast3)

        val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        // Presets for comparison, so reopening highlights the one in force.
        fun thisMonth(): Pair<Long, Long> {
            val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
            return startOfDay(c) to endOfDay(Calendar.getInstance())
        }
        fun lastThree(): Pair<Long, Long> {
            val c = Calendar.getInstance().apply { add(Calendar.MONTH, -3) }
            return startOfDay(c) to endOfDay(Calendar.getInstance())
        }

        fun refresh() {
            tvStart.text = draftStart?.let { dayFmt.format(Date(it)) } ?: "Pick a date"
            tvEnd.text = draftEnd?.let { dayFmt.format(Date(it)) } ?: "Pick a date"
            tvStart.setTextColor(Color.parseColor(if (draftStart != null) "#1A1A18" else "#A99E88"))
            tvEnd.setTextColor(Color.parseColor(if (draftEnd != null) "#1A1A18" else "#A99E88"))

            // Counted against the same rule Apply will use, so the number can
            // never promise something different from the result.
            val hits = originalList.count {
                (draftStart == null || it.timestamp >= draftStart!!) &&
                (draftEnd == null || it.timestamp <= draftEnd!!)
            }
            tvMatches.text = "$hits of ${originalList.size} entries"

            val (mS, mE) = thisMonth()
            val (lS, lE) = lastThree()
            chipAll.isSelected = draftStart == null && draftEnd == null
            chipMonth.isSelected = draftStart == mS && draftEnd == mE
            chipLast3.isSelected = draftStart == lS && draftEnd == lE
        }

        chipAll.setOnClickListener { draftStart = null; draftEnd = null; refresh() }
        chipMonth.setOnClickListener { thisMonth().let { draftStart = it.first; draftEnd = it.second }; refresh() }
        chipLast3.setOnClickListener { lastThree().let { draftStart = it.first; draftEnd = it.second }; refresh() }

        tvStart.setOnClickListener {
            val c = Calendar.getInstance().apply { draftStart?.let { timeInMillis = it } }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    draftStart = startOfDay(Calendar.getInstance().apply { set(y, m, d) })
                    refresh()
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }

        tvEnd.setOnClickListener {
            val c = Calendar.getInstance().apply { draftEnd?.let { timeInMillis = it } }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    draftEnd = endOfDay(Calendar.getInstance().apply { set(y, m, d) })
                    refresh()
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }

        view.findViewById<MaterialButton>(R.id.btnRangeCancel).setOnClickListener { dialog.dismiss() }

        view.findViewById<MaterialButton>(R.id.btnRangeApply).setOnClickListener {
            val s = draftStart
            val e = draftEnd
            if (s != null && e != null && s > e) {
                Toast.makeText(this, "The From date is after the To date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // An empty result is applied rather than refused: the count above
            // already showed it, and refusing would leave the ledger showing
            // rows the filter excludes.
            filterStart = s
            filterEnd = e
            applyDateFilter()
            dialog.dismiss()
        }

        refresh()
        dialog.show()
    }

    // ================= SUMMARY =================
    private fun showSummary() {

        val txns = currentList

        if (txns.isEmpty()) {
            Toast.makeText(this, "No data", Toast.LENGTH_SHORT).show()
            return
        }

        // The same single walk the header and the printed statement use, so
        // this dialog cannot disagree with the figures on the screen behind it.
        val (billed, received, writtenOff, refunded, balance) = computeTotals(txns)

        val view = layoutInflater.inflate(R.layout.dialog_ledger_summary, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        // The card is the layout's own rounded background, so the window
        // behind it must be transparent.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvSummaryName).text = accountName

        // States its own scope, since the date filter may be narrowing this.
        // Read from the filter chip so the two can't disagree.
        val entryWord = if (txns.size == 1) "entry" else "entries"
        val scope = findViewById<TextView>(R.id.tvFilterLabel).text?.toString()?.lowercase()
            ?: "all time"
        view.findViewById<TextView>(R.id.tvSummaryScope).text =
            "${txns.size} $entryWord · $scope"

        view.findViewById<TextView>(R.id.tvSumBilled).text = money(billed)
        view.findViewById<TextView>(R.id.tvSumReceived).text = "− ${money(received)}"

        // Adjustments show only when they exist, matching the tiles.
        val hasWrittenOff = writtenOff > 0.005
        val hasRefunded = refunded > 0.005
        view.findViewById<View>(R.id.rowSumWrittenOff).visibility =
            if (hasWrittenOff) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.rowSumRefunded).visibility =
            if (hasRefunded) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tvSumWrittenOff).text = "− ${money(writtenOff)}"
        view.findViewById<TextView>(R.id.tvSumRefunded).text = "+ ${money(refunded)}"

        val tvBalance = view.findViewById<TextView>(R.id.tvSumBalance)
        tvBalance.text = money(kotlin.math.abs(balance))
        view.findViewById<TextView>(R.id.tvSumBalanceCaption).text = when {
            balance > 0 -> "owes you"
            balance < 0 -> "in advance"
            else -> "settled"
        }
        tvBalance.setTextColor(
            Color.parseColor(
                when {
                    balance > 0 -> "#B23A3A"
                    balance < 0 -> "#0F6E56"
                    else -> "#1A1A18"
                }
            )
        )

        // Average of the entries that actually billed something, so payments
        // and adjustments don't drag the figure down.
        val billedEntries = txns.filter { debitOf(it) > 0.0 }
        view.findViewById<TextView>(R.id.tvSumAvgBill).text =
            if (billedEntries.isEmpty()) "—"
            else money(billedEntries.sumOf { debitOf(it) } / billedEntries.size)

        val lastPayment = txns.filter { it.type == "PAY" }.maxByOrNull { it.timestamp }
        view.findViewById<TextView>(R.id.tvSumLastPayment).text =
            lastPayment?.let { SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(it.timestamp)) }
                ?: "—"

        view.findViewById<MaterialButton>(R.id.btnSummaryClose)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ================= PRINT =================
    private fun printReport() {

        if (originalList.isEmpty()) {
            Toast.makeText(this, "No transactions to print", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            // getById queries the LOCAL primary key, so it must be given the
            // local id. It was being passed the server id, which matches a
            // different row — or none — so the printed statement carried the
            // wrong customer's details.
            val account = db.creditAccountDao().getById(localAccountId, shopId)
            val storeInfo = db.storeInfoDao().get()   // ✅ FIXED (correct place)

            var balance = 0.0
            val rows = mutableListOf<List<String>>()

            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            originalList.sortedBy { it.timestamp }.forEach {

                // A legacy SETTLE row cleared whatever was standing at that
                // moment, so its columns come from the running balance rather
                // than its stored figure — which is 0 on rows written before
                // that was recorded, and would print two blank columns.
                // Resolved before the balance is cleared — a legacy SETTLE
                // prints as "Written off" or "Refunded" rather than the vague
                // "Settled", which said nothing about which way the money went.
                val shownType = displayType(it, balance)

                val debit: Double
                val credit: Double
                if (it.type == "WRITE_OFF" || it.type == "REFUND" || it.type == "SETTLE") {
                    debit = if (balance < 0) -balance else 0.0
                    credit = if (balance > 0) balance else 0.0
                    balance = 0.0
                } else {
                    debit = debitOf(it)
                    credit = creditOf(it)
                    balance = applyToBalance(balance, it)
                }

                // A credit note / cancelled bill reverses part of a sale, so it
                // sits in the debit (Billed) column as a negative — not in the
                // credit column, which is cash received. Rendering the sign
                // keeps the debit column's own total equal to the net Billed
                // figure printed in the footer.
                val debitStr = when {
                    debit > 0 -> "₹%.2f".format(debit)
                    debit < 0 -> "−₹%.2f".format(-debit)
                    else -> "-"
                }

                rows.add(
                    listOf(
                        formatter.format(Date(it.timestamp)),
                        // The same wording the ledger shows on screen. This
                        // used to print the raw code, so a customer's
                        // statement read "WRITE_OFF" and "PURCHASE_CREDIT".
                        TransactionAdapter.labelFor(shownType),
                        debitStr,
                        if (credit > 0) "₹%.2f".format(credit) else "-",
                        "₹%.2f".format(balance)
                    )
                )
            }

            // Same single walk as the header and the summary dialog, so the
            // printed totals agree with the per-row columns above and with
            // what the user saw on screen.
            val printed = computeTotals(originalList)
            val totalDebit = printed.billed
            val totalCredit = printed.received

            withContext(Dispatchers.Main) {

                if (isFinishing || isDestroyed || !window.decorView.isAttachedToWindow) {
                    Toast.makeText(this@CustomerTransactionsActivity, "Try again", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val customerPhone = when {
                    !account?.phone.isNullOrBlank() -> account!!.phone
                    accountPhone.isNotBlank() -> accountPhone
                    else -> "N/A"
                }
                val customerNameFinal = account?.name?.takeIf { it.isNotBlank() } ?: accountName

                try {
                    InvoicePdfGenerator.generateLedgerPdf(
                        activity = this@CustomerTransactionsActivity,
                        storeInfo = storeInfo,
                        customerName = customerNameFinal,
                        phone = customerPhone,
                        rows = rows,
                        totalDebit = totalDebit,
                        totalCredit = totalCredit,
                        finalBalance = balance
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Names the exception. This said only "Print failed", the
                    // same wording the generator uses for a different failure,
                    // so there was no way to tell them apart.
                    Toast.makeText(
                        this@CustomerTransactionsActivity,
                        "Couldn't build the statement: ${e.message ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= UI PREP =================
    /**
     * The one place that decides how a transaction moves a running balance.
     *
     * It mirrors the server's rules in credit_routes.sync_credit exactly, so a
     * statement printed here agrees with the balance shown on the accounts list
     * and with the server:
     *
     *   ADD / PURCHASE_CREDIT   amount is positive → debt rises
     *   PAY                     amount is positive → debt falls
     *   PURCHASE_RETURN         magnitude lowers debt (sign-agnostic: old rows
     *                           are negative, new rows positive)
     *   SETTLE                  balance goes to zero
     *
     * The three views previously each handled only ADD, PAY and SETTLE, so a
     * customer or supplier account with credit-purchase activity showed a
     * running balance that silently disagreed with their actual due.
     */
    private fun applyToBalance(balance: Double, txn: CreditTransaction): Double =
        when (txn.type) {
            "ADD", "PURCHASE_CREDIT" -> balance + txn.amount
            "PAY" -> balance - txn.amount
            // Sign-agnostic: older rows stored PURCHASE_RETURN negative, newer
            // ones store a positive magnitude. Either way it lowers the payable.
            "PURCHASE_RETURN" -> balance - kotlin.math.abs(txn.amount)
            // Bill adjustments: a debit note raises the debt, a credit note or
            // a cancelled bill lowers it. Deltas, not resets — the amount was
            // already clamped to what the bill still owed when it was written.
            "DEBIT_NOTE" -> balance + txn.amount
            "SALE_RETURN", "BILL_CANCEL" -> balance - txn.amount
            // All three close the account, so all three go to zero. Treating
            // the new types as deltas would leave a drifted balance drifted;
            // an absolute zero is what re-synchronises it, and it is what
            // "settle" means to the shopkeeper.
            "WRITE_OFF", "REFUND", "SETTLE" -> 0.0
            else -> balance
        }

    /**
     * Net effect on the Billed total.
     *
     * A debit note adds to what was billed. A credit note or a cancelled bill
     * *reverses* part of a sale, so they reduce Billed rather than appearing as
     * money received — no cash came in. Returning them as negative here keeps
     * the identity billed − received − writtenOff + refunded = balance exact,
     * with Received untouched.
     */
    private fun debitOf(txn: CreditTransaction): Double = when (txn.type) {
        "ADD", "PURCHASE_CREDIT" -> txn.amount
        "DEBIT_NOTE" -> txn.amount
        "SALE_RETURN", "BILL_CANCEL" -> -txn.amount
        // A purchase return reduces what's owed to the supplier — it's a
        // credit, never a debit. (Handled in creditOf, sign-agnostic.)
        "PURCHASE_RETURN" -> 0.0
        else -> 0.0
    }

    /**
     * Money reducing what they owe — the statement's credit column.
     *
     * The three adjustment types are handled by the caller from the running
     * balance, not here, because what they cleared is the balance standing at
     * that moment rather than their stored amount.
     */
    private fun creditOf(txn: CreditTransaction): Double = when (txn.type) {
        "PAY" -> txn.amount
        // Sign-agnostic magnitude: lowers what's owed whether the row was
        // stored negative (old) or positive (new).
        "PURCHASE_RETURN" -> kotlin.math.abs(txn.amount)
        else -> 0.0
    }

    /**
     * The type a row should be *shown* as.
     *
     * A legacy SETTLE says nothing about which event it was — a debt forgiven
     * or an advance handed back — so it reads as the vague "Settled". The
     * balance standing before it does say, so it is resolved to WRITE_OFF or
     * REFUND for display. Nothing is written back; only the label changes.
     *
     * @param balanceBefore the running balance immediately before this row.
     */
    private fun displayType(txn: CreditTransaction, balanceBefore: Double): String =
        if (txn.type == "SETTLE") {
            if (balanceBefore < 0) "REFUND" else "WRITE_OFF"
        } else {
            txn.type
        }

    private fun prepareUI(transactions: List<CreditTransaction>): List<TransactionUI> {

        val result = mutableListOf<TransactionUI>()
        var balance = 0.0

        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val today = sdf.format(Date())

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(cal.time)

        var lastHeader = ""

        transactions.sortedBy { it.timestamp }.forEach { txn ->

            val txnDate = sdf.format(Date(txn.timestamp))

            val header = when (txnDate) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(Date(txn.timestamp))
            }

            if (header != lastHeader) {
                result.add(
                    TransactionUI(
                        type = "",
                        amount = 0.0,
                        timestamp = txn.timestamp,
                        runningBalance = balance,
                        isHeader = true,
                        headerTitle = header
                    )
                )
                lastHeader = header
            }

            // Every adjustment shows what it actually cleared — the balance
            // standing before it — rather than its stored figure. Legacy
            // SETTLE rows may hold 0, and a stored amount can differ from the
            // sum really cleared if the device and server had drifted.
            val shownAmount =
                if (txn.type == "WRITE_OFF" || txn.type == "REFUND" || txn.type == "SETTLE")
                    kotlin.math.abs(balance)
                else txn.amount

            // Resolved from the balance before it is cleared, so the row reads
            // "Written off" or "Refunded" — the same wording the statement
            // prints. Leaving it as SETTLE made the two disagree.
            val shownType = displayType(txn, balance)

            balance = applyToBalance(balance, txn)

            result.add(
                TransactionUI(
                    type = shownType,
                    amount = shownAmount,
                    timestamp = txn.timestamp,
                    runningBalance = balance,
                    // Lets the row say "not yet synced" and name the invoice it
                    // came from — both already stored, neither previously shown.
                    isSynced = txn.isSynced,
                    reference = txn.referenceInvoice
                )
            )
        }

        return result.reversed()
    }
}