package com.example.easy_billing.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
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

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvProducts = view.findViewById(R.id.rvProducts)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        rvProducts.layoutManager = LinearLayoutManager(requireContext())

        loadProducts()
    }

    // ✅ UPDATED ONLY SIGNATURE (logic same)
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

    private fun loadProducts() {

        lifecycleScope.launch {

            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                // 🔥 YOUR EXACT ORIGINAL LOGIC (UNCHANGED)
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
                        // ✅ ONLY SAFETY CHECK ADDED (no logic change)
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                // 🔥 SAME API CALL (unchanged)
                val products =
                    RetrofitClient.api.getTopProducts("Bearer $token")

                val sorted = products.sortedByDescending { it.revenue }

                rvProducts.adapter = ProductReportAdapter(sorted)

                // ✅ UI FIX ONLY (no logic impact)
                if (sorted.isEmpty()) {
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