package com.example.easy_billing

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class CustomerTransactionsActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: TransactionAdapter

    // ✅ UI list (with headers)
    private val list = mutableListOf<TransactionUI>()

    // ✅ ORIGINAL DATA (IMPORTANT)
    private val originalList = mutableListOf<CreditTransaction>()

    private val currentList = mutableListOf<CreditTransaction>()

    private val db by lazy { AppDatabase.getDatabase(this) }

    private var accountId: Int = -1
    private var accountName: String = ""
    private var accountPhone: String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_transactions)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE


        rv = findViewById(R.id.rvTransactions)

        accountId = intent.getIntExtra("ACCOUNT_ID", -1)
        accountName = intent.getStringExtra("ACCOUNT_NAME") ?: "Customer"
        accountPhone = intent.getStringExtra("ACCOUNT_PHONE") ?: ""

        if (accountId == -1) {
            Toast.makeText(this, "Invalid account", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        supportActionBar?.title = accountName

        adapter = TransactionAdapter(list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        setupSearch()
        setupOutsideTouch()

        findViewById<Button>(R.id.btnFilter).setOnClickListener {
            showDateFilter()
        }

        findViewById<Button>(R.id.btnSummary).setOnClickListener {
            showSummary()
        }

        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            printReport()
        }

        loadTransactions()
    }

    protected fun setupToolbar(toolbarId: Int, showBack: Boolean = true) {
        val toolbar = findViewById<Toolbar>(toolbarId)
        setSupportActionBar(toolbar)

        if (showBack) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
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

        if (token == null) {
            Toast.makeText(this@CustomerTransactionsActivity, "No token", Toast.LENGTH_SHORT).show()
            return@launch
        }

        try {
            val api = RetrofitClient.api

            val response = api.getTransactions("Bearer $token", accountId)

            originalList.clear()

            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

            response.forEach {

                val time = try {
                    parser.parse(it.created_at)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }

                val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                    .getInt("SHOP_ID", 1)

                originalList.add(
                    CreditTransaction(
                        accountId = it.account_id,
                        shopId = shopId,
                        amount = it.amount,
                        type = it.type,
                        timestamp = time
                    )
                )
            }

            val uiList = prepareUI(originalList)

            list.clear()
            list.addAll(uiList)

            currentList.clear()
            currentList.addAll(originalList)
            adapter.notifyDataSetChanged()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this@CustomerTransactionsActivity, "Failed to load", Toast.LENGTH_SHORT).show()
        }
    }

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
    private fun showDateFilter() {

        val view = layoutInflater.inflate(R.layout.dialog_date_range, null)

        val tvStart = view.findViewById<TextView>(R.id.tvStartDate)
        val tvEnd = view.findViewById<TextView>(R.id.tvEndDate)

        var startCal: Calendar? = null
        var endCal: Calendar? = null

        val today = Calendar.getInstance()

        // 🔹 Start Date Picker
        tvStart.setOnClickListener {

            val picker = android.app.DatePickerDialog(this)
            picker.datePicker.maxDate = System.currentTimeMillis()

            picker.setOnDateSetListener { _, y, m, d ->

                startCal = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                tvStart.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(startCal!!.time)
            }

            picker.show()
        }

        // 🔹 End Date Picker
        tvEnd.setOnClickListener {

            val picker = android.app.DatePickerDialog(this)
            picker.datePicker.maxDate = System.currentTimeMillis()

            picker.setOnDateSetListener { _, y, m, d ->

                endCal = Calendar.getInstance().apply {
                    set(y, m, d, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }

                tvEnd.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(endCal!!.time)
            }

            picker.show()
        }

        // 🔥 Dialog
        AlertDialog.Builder(this)
            .setTitle("Select Date Range")
            .setView(view)
            .setPositiveButton("Apply") { _, _ ->

                if (startCal == null || endCal == null) {
                    Toast.makeText(this, "Select both dates", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (startCal!!.after(endCal)) {
                    Toast.makeText(this, "Invalid range", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val filtered = originalList.filter {
                    it.timestamp in startCal!!.timeInMillis..endCal!!.timeInMillis
                }

                if (filtered.isEmpty()) {
                    Toast.makeText(this, "No data available", Toast.LENGTH_SHORT).show()
                    list.clear()
                    adapter.notifyDataSetChanged()
                    currentList.clear()
                    return@setPositiveButton
                }

                currentList.clear()
                currentList.addAll(filtered)

                val ui = prepareUI(filtered)

                list.clear()
                list.addAll(ui)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= SUMMARY =================
    private fun showSummary() {

        val txns = currentList

        if (txns.isEmpty()) {
            Toast.makeText(this, "No data", Toast.LENGTH_SHORT).show()
            return
        }

        val totalAdd = txns.filter { it.type == "ADD" }.sumOf { it.amount }
        val totalPay = txns.filter { it.type == "PAY" }.sumOf { it.amount }
        val settleCount = txns.count { it.type == "SETTLE" }
        val balance = totalAdd - totalPay

        // 🔥 Create Layout Programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        fun createRow(label: String, value: String, color: Int): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 15, 0, 15)
            }

            val tvLabel = TextView(this).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvValue = TextView(this).apply {
                text = value
                textSize = 16f
                setTextColor(color)
            }

            row.addView(tvLabel)
            row.addView(tvValue)

            return row
        }

        // ✅ Rows
        layout.addView(createRow("Total Added", "₹%.2f".format(totalAdd), android.graphics.Color.parseColor("#2E7D32"))) // Green
        layout.addView(createRow("Total Paid", "₹%.2f".format(totalPay), android.graphics.Color.parseColor("#C62828"))) // Red
        layout.addView(createRow("Settled", settleCount.toString(), android.graphics.Color.DKGRAY))

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 20, 0, 20)
            }
        }
        layout.addView(divider)

        // 🔥 Balance (Highlighted)
        val balanceColor = if (balance >= 0)
            android.graphics.Color.parseColor("#2E7D32")
        else
            android.graphics.Color.parseColor("#C62828")

        val tvBalance = TextView(this).apply {
            text = "Balance : ₹%.2f".format(balance)
            textSize = 20f
            setTextColor(balanceColor)
            setPadding(0, 20, 0, 10)
        }

        layout.addView(tvBalance)

        // ✅ Show Dialog
        AlertDialog.Builder(this)
            .setTitle("Summary")
            .setView(layout)
            .setPositiveButton("OK", null)
            .show()
    }

    // ================= PRINT =================
    private fun printReport() {

        if (originalList.isEmpty()) {
            Toast.makeText(this, "No transactions to print", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                .getInt("SHOP_ID", 1)

            val account = db.creditAccountDao().getById(accountId, shopId)
            val storeInfo = db.storeInfoDao().get()   // ✅ FIXED (correct place)

            var balance = 0.0
            val rows = mutableListOf<List<String>>()

            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            originalList.sortedBy { it.timestamp }.forEach {

                val debit = if (it.type == "ADD") it.amount else 0.0
                val credit = if (it.type == "PAY") it.amount else 0.0

                if (it.type == "SETTLE") {
                    balance = 0.0
                } else {
                    balance += debit
                    balance -= credit
                }

                rows.add(
                    listOf(
                        formatter.format(Date(it.timestamp)),
                        it.type,
                        if (debit > 0) "₹%.2f".format(debit) else "-",
                        if (credit > 0) "₹%.2f".format(credit) else "-",
                        "₹%.2f".format(balance)
                    )
                )
            }

            val totalDebit = originalList
                .filter { it.type == "ADD" }
                .sumOf { it.amount }

            val totalCredit = originalList
                .filter { it.type == "PAY" }
                .sumOf { it.amount }

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
                    Toast.makeText(
                        this@CustomerTransactionsActivity,
                        "Print failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ================= UI PREP =================
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

            when (txn.type) {
                "ADD" -> balance += txn.amount
                "PAY" -> balance -= txn.amount
                "SETTLE" -> balance = 0.0
            }

            result.add(
                TransactionUI(
                    type = txn.type,
                    amount = txn.amount,
                    timestamp = txn.timestamp,
                    runningBalance = balance
                )
            )
        }

        return result.reversed()
    }
}