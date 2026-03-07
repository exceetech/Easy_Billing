package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
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

    private var allBills: List<BillResponse> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_bill_history)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        rvBills = findViewById(R.id.rvBills)
        etSearch = findViewById(R.id.etSearch)

        tvTodaySales = findViewById(R.id.tvTodaySales)
        tvBillsToday = findViewById(R.id.tvBillsToday)

        chipToday = findViewById(R.id.btnToday)
        chipWeek = findViewById(R.id.btnWeek)
        chipMonth = findViewById(R.id.btnMonth)
        chipSortAmount = findViewById(R.id.btnSortAmount)

        rvBills.layoutManager = LinearLayoutManager(this)

        adapter = BillHistoryAdapter { bill ->

            val intent = Intent(this, BillDetailsActivity::class.java)
            intent.putExtra("BILL_ID", bill.bill_id)
            startActivity(intent)
        }

        rvBills.adapter = adapter

        loadBills()

        setupSearch()
        setupFilters()
    }

    // ================= LOAD BILLS =================

    private fun loadBills() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(
                    this@BillHistoryActivity,
                    "User not logged in",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            try {

                val bills = RetrofitClient.api.getBills(
                    "Bearer $token"
                )

                allBills = bills
                adapter.submitList(bills)

                updateSummary(bills)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    this@BillHistoryActivity,
                    "Failed to load bills",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        etSearch.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {

                val query = text.toString().trim().lowercase()

                adapter.setSearchQuery(query)

                if (query.isEmpty()) {
                    adapter.submitList(allBills)
                    return
                }

                val filtered = allBills.filter { bill ->

                    bill.bill_number.lowercase().contains(query) ||
                            bill.payment_method.lowercase().contains(query) ||
                            bill.total_amount.toString().contains(query) ||
                            bill.created_at.lowercase().contains(query)

                }

                adapter.submitList(filtered)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ================= FILTERS =================

    private fun setupFilters() {

        chipToday.setOnClickListener {
            filterToday()
        }

        chipWeek.setOnClickListener {
            filterWeek()
        }

        chipMonth.setOnClickListener {
            filterMonth()
        }

        chipSortAmount.setOnClickListener {
            sortByAmount()
        }
    }

    // ================= TODAY FILTER =================

    private fun filterToday() {

        val today = java.text.SimpleDateFormat("yyyy-MM-dd")
            .format(java.util.Date())

        val filtered = allBills.filter {
            it.created_at.startsWith(today)
        }

        adapter.submitList(filtered)
        updateSummary(filtered)
    }

    // ================= WEEK FILTER =================

    private fun filterWeek() {

        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -7)

        val weekStart = calendar.time

        val filtered = allBills.filter {

            val date = java.text.SimpleDateFormat("yyyy-MM-dd")
                .parse(it.created_at.substring(0, 10))

            date != null && date.after(weekStart)
        }

        adapter.submitList(filtered)
        updateSummary(filtered)
    }

    // ================= MONTH FILTER =================

    private fun filterMonth() {

        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.MONTH, -1)

        val monthStart = calendar.time

        val filtered = allBills.filter {

            val date = java.text.SimpleDateFormat("yyyy-MM-dd")
                .parse(it.created_at.substring(0, 10))

            date != null && date.after(monthStart)
        }

        adapter.submitList(filtered)
        updateSummary(filtered)
    }

    // ================= SORT =================

    private fun sortByAmount() {

        val sorted = allBills.sortedByDescending {
            it.total_amount
        }

        adapter.submitList(sorted)
    }

    // ================= SUMMARY =================

    private fun updateSummary(bills: List<BillResponse>) {

        val today = java.text.SimpleDateFormat("yyyy-MM-dd")
            .format(java.util.Date())

        val todayBills = bills.filter {
            it.created_at.startsWith(today)
        }

        val totalToday = todayBills.sumOf {
            it.total_amount
        }

        tvTodaySales.text = "₹%.2f".format(totalToday)
        tvBillsToday.text = todayBills.size.toString()
    }
}