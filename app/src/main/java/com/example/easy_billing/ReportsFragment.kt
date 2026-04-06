package com.example.easy_billing.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.Filterable
import com.example.easy_billing.R
import com.example.easy_billing.ReportFilter
import com.example.easy_billing.DailyReportAdapter
import com.example.easy_billing.adapter.MonthlyReportAdapter
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReportsFragment : Fragment(R.layout.fragment_reports), Filterable {

    private lateinit var rvDaily: RecyclerView
    private lateinit var rvMonthly: RecyclerView

    private var currentFilter = ReportFilter.TODAY
    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvDaily = view.findViewById(R.id.rvDaily)
        rvMonthly = view.findViewById(R.id.rvMonthly)

        rvDaily.layoutManager = LinearLayoutManager(requireContext())
        rvMonthly.layoutManager = LinearLayoutManager(requireContext())

        // ✅ IMPORTANT FIX FOR SCROLLVIEW
        rvDaily.isNestedScrollingEnabled = false
        rvMonthly.isNestedScrollingEnabled = false

        loadReports()
    }

    // ✅ ONLY signature updated (logic same)
    override fun onFilterChanged(
        filter: ReportFilter,
        startDate: String?,
        endDate: String?
    ) {
        currentFilter = filter
        customStartDate = startDate
        customEndDate = endDate
        loadReports()
    }

    private fun loadReports() {

        lifecycleScope.launch {

            val token = requireActivity()
                .getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val calendar = Calendar.getInstance()

                var start: String? = null
                var end: String? = null
                var type = ""

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
                        // ✅ SAFETY ONLY (no logic change)
                        if (customStartDate != null && customEndDate != null) {
                            start = customStartDate
                            end = customEndDate
                            type = "custom"
                        } else {
                            return@launch
                        }
                    }
                }

                // 🔥 SAME APIs (UNCHANGED)
                val daily =
                    RetrofitClient.api.getDailyReport("Bearer $token")

                val monthly =
                    RetrofitClient.api.getMonthlyReport("Bearer $token")

                rvDaily.adapter = DailyReportAdapter(daily)
                rvMonthly.adapter = MonthlyReportAdapter(monthly)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    requireContext(),
                    "Failed to load reports",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}