package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.BillResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class BillHistoryActivity : BaseActivity() {

    private lateinit var rvBills: RecyclerView
    private lateinit var adapter: BillHistoryAdapter
    private lateinit var etSearch: EditText

    private lateinit var tvTodaySales: TextView
    private lateinit var tvBillsToday: TextView

    private lateinit var chipToday: Chip
    private lateinit var chipWeek: Chip
    private lateinit var chipMonth: Chip
    private lateinit var chipSortAmount: Chip

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var allBills: List<BillResponse> = emptyList()

    // 🔥 Track active filter
    private var activeFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_history)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        initViews()
        setupRecycler()
        setupSearch()
        setupFilters()

        loadBills()
    }

    // ================= INIT =================

    private fun initViews() {
        rvBills = findViewById(R.id.rvBills)
        etSearch = findViewById(R.id.etSearch)

        tvTodaySales = findViewById(R.id.tvTodaySales)
        tvBillsToday = findViewById(R.id.tvBillsToday)

        chipToday = findViewById(R.id.btnToday)
        chipWeek = findViewById(R.id.btnWeek)
        chipMonth = findViewById(R.id.btnMonth)
        chipSortAmount = findViewById(R.id.btnSortAmount)
    }

    // ================= RECYCLER =================

    private fun setupRecycler() {
        rvBills.layoutManager = LinearLayoutManager(this)

        adapter = BillHistoryAdapter { bill ->
            val intent = Intent(this, BillDetailsActivity::class.java)
            intent.putExtra("BILL_ID", bill.bill_id)
            startActivity(intent)
        }

        rvBills.adapter = adapter
    }

    // ================= LOAD =================

    private fun loadBills() {
        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(this@BillHistoryActivity, "User not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val bills = RetrofitClient.api.getBills("Bearer $token")

                allBills = bills
                adapter.submitList(bills)
                updateSummary(bills)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@BillHistoryActivity, "Failed to load bills", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {
        etSearch.clearFocus()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {

                val query = text?.toString()?.trim()?.lowercase() ?: ""
                adapter.setSearchQuery(query)

                searchRunnable?.let { handler.removeCallbacks(it) }

                searchRunnable = Runnable {

                    val baseList = applyActiveFilter()

                    if (query.isEmpty()) {
                        adapter.submitList(baseList)
                        return@Runnable
                    }

                    val filtered = baseList.filter { bill ->
                        bill.bill_number.contains(query, true) ||
                                bill.payment_method.contains(query, true) ||
                                bill.total_amount.toString().contains(query) ||
                                bill.created_at.contains(query, true)
                    }

                    adapter.submitList(filtered)
                }

                handler.postDelayed(searchRunnable!!, 300)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ================= FILTERS =================

    private fun setupFilters() {

        chipToday.setOnClickListener { toggleFilter("TODAY") }
        chipWeek.setOnClickListener { toggleFilter("WEEK") }
        chipMonth.setOnClickListener { toggleFilter("MONTH") }
        chipSortAmount.setOnClickListener { toggleFilter("SORT") }
    }

    private fun toggleFilter(filter: String) {

        // 🔥 Toggle logic
        activeFilter = if (activeFilter == filter) null else filter

        val result = applyActiveFilter()
        adapter.submitList(result)
        updateSummary(result)
    }

    private fun applyActiveFilter(): List<BillResponse> {

        return when (activeFilter) {

            "TODAY" -> {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd")
                    .format(java.util.Date())

                allBills.filter {
                    it.created_at.startsWith(today)
                }
            }

            "WEEK" -> {
                val calendar = java.util.Calendar.getInstance()
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -7)

                allBills.filter {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd")
                        .parse(it.created_at.substring(0, 10))
                    date != null && date.after(calendar.time)
                }
            }

            "MONTH" -> {
                val calendar = java.util.Calendar.getInstance()
                calendar.add(java.util.Calendar.MONTH, -1)

                allBills.filter {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd")
                        .parse(it.created_at.substring(0, 10))
                    date != null && date.after(calendar.time)
                }
            }

            "SORT" -> {
                allBills.sortedByDescending { it.total_amount }
            }

            else -> allBills
        }
    }

    // ================= SUMMARY =================

    private fun updateSummary(bills: List<BillResponse>) {

        val today = java.text.SimpleDateFormat("yyyy-MM-dd")
            .format(java.util.Date())

        val todayBills = bills.filter {
            it.created_at.startsWith(today)
        }

        val totalToday = todayBills.sumOf { it.total_amount }

        tvTodaySales.text = CurrencyHelper.format(this, totalToday)
        tvBillsToday.text = todayBills.size.toString()
    }
}