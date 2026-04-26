package com.example.easy_billing.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.ProductReportAdapter
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ProductsFragment : Fragment(R.layout.fragment_products), Filterable {

    private lateinit var rvProducts: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var spSort: Spinner   // ✅ now used

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private var sortBy = "quantity"   // default

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvProducts = view.findViewById(R.id.rvProducts)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        spSort = view.findViewById(R.id.spSort)   // 🔥 FIX

        rvProducts.layoutManager = LinearLayoutManager(requireContext())

        setupSpinner()   // 🔥 ADD THIS

        loadProducts()
    }

    // ================= SORT =================

    private fun setupSpinner() {

        val options = listOf(
            "Fast Moving",
            "Revenue",
            "Popular",
            "Smart"
        )

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        spSort.adapter = adapter

        spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {

                sortBy = when (position) {
                    0 -> "quantity"
                    1 -> "revenue"
                    2 -> "frequency"
                    3 -> "smart"
                    else -> "quantity"
                }

                loadProducts()   // 🔥 reload on change
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ================= FILTER =================

    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate

        loadProducts()
    }

    // ================= API =================

    private fun loadProducts() {

        lifecycleScope.launch {

            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                var type = "today"
                var start: String? = null
                var end: String? = null

                val calendar = Calendar.getInstance()

                when (currentFilter) {

                    ReportFilter.TODAY -> {
                        type = "today"
                    }

                    ReportFilter.WEEK -> {
                        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                        start = sdf.format(calendar.time)

                        calendar.add(Calendar.DAY_OF_WEEK, 6)
                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.MONTH -> {
                        calendar.set(Calendar.DAY_OF_MONTH, 1)
                        start = sdf.format(calendar.time)

                        calendar.set(
                            Calendar.DAY_OF_MONTH,
                            calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                        )
                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.YEAR -> {
                        calendar.set(Calendar.MONTH, Calendar.JANUARY)
                        calendar.set(Calendar.DAY_OF_MONTH, 1)
                        start = sdf.format(calendar.time)

                        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
                        calendar.set(Calendar.DAY_OF_MONTH, 31)
                        end = sdf.format(calendar.time)

                        type = "custom"
                    }

                    ReportFilter.CUSTOM -> {
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                val products = RetrofitClient.api.getTopProducts(
                    "Bearer $token",
                    type,
                    start,
                    end,
                    sortBy   // ✅ now working
                )

                rvProducts.adapter = ProductReportAdapter(products)

                if (products.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvProducts.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rvProducts.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}