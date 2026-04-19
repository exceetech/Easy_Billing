package com.example.easy_billing

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReportsActivity : BaseActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        // ✅ Toolbar
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        // ✅ Views
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        setupViewPager()
        setupFilters()
        setupSendReportButton()
    }

    // ---------------- VIEWPAGER ----------------

    private fun setupViewPager() {

        viewPager.adapter = ReportsPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Overview"
                1 -> "Charts"
                2 -> "Peak Hours"
                3 -> "Reports"
                4 -> "Products"
                else -> ""
            }
        }.attach()
    }

    // ---------------- FILTERS ----------------

    private fun setupFilters() {

        val chipToday = findViewById<Chip>(R.id.chipToday)
        val chipWeek = findViewById<Chip>(R.id.chipWeek)
        val chipMonth = findViewById<Chip>(R.id.chipMonth)
        val chipYear = findViewById<Chip>(R.id.chipYear)
        val chipCustom = findViewById<Chip>(R.id.chipCustom)

        chipToday?.setOnClickListener {
            selectChip(chipToday)
            notifyFragments(ReportFilter.TODAY)
        }

        chipWeek?.setOnClickListener {
            selectChip(chipWeek)
            notifyFragments(ReportFilter.WEEK)
        }

        chipMonth?.setOnClickListener {
            selectChip(chipMonth)
            notifyFragments(ReportFilter.MONTH)
        }

        chipYear?.setOnClickListener {
            selectChip(chipYear)
            notifyFragments(ReportFilter.YEAR)
        }

        chipCustom?.setOnClickListener {
            selectChip(chipCustom)
            openCustomDatePicker()
        }
    }

    // ---------------- bold for tab ----------------------
    private fun selectChip(selectedChip: Chip) {

        val chips = listOf(
            findViewById<Chip>(R.id.chipToday),
            findViewById<Chip>(R.id.chipWeek),
            findViewById<Chip>(R.id.chipMonth),
            findViewById<Chip>(R.id.chipYear),
            findViewById<Chip>(R.id.chipCustom)
        )

        chips.forEach { chip ->
            chip?.apply {
                setTypeface(null, if (this == selectedChip) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    // ---------------- CUSTOM DATE PICKER ----------------

    private fun openCustomDatePicker() {

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .build()

        picker.show(supportFragmentManager, "DATE_RANGE")

        picker.addOnPositiveButtonClickListener {

            val start = sdf.format(Date(it.first))
            val end = sdf.format(Date(it.second))

            notifyFragments(ReportFilter.CUSTOM, start, end)
        }
    }

    // ---------------- SEND REPORT ----------------

    private fun setupSendReportButton() {

        findViewById<MaterialButton>(R.id.btnSendReport)?.setOnClickListener {
            showReportOptions()
        }
    }

    private fun showReportOptions() {

        val options = arrayOf(
            "Today's Bills",
            "Weekly Bills",
            "Monthly Bills",
            "Custom Report"
        )

        AlertDialog.Builder(this)
            .setTitle("Send Report")
            .setItems(options) { _, which ->

                when (which) {

                    0 -> sendEmailReport("today")
                    1 -> sendEmailReport("weekly")
                    2 -> sendEmailReport("monthly")
                    3 -> openEmailDatePicker()
                }

            }
            .show()
    }

    private fun openEmailDatePicker() {

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Report Range")
            .build()

        picker.show(supportFragmentManager, "EMAIL_RANGE")

        picker.addOnPositiveButtonClickListener {

            val start = sdf.format(Date(it.first))
            val end = sdf.format(Date(it.second))

            sendEmailReport(
                type = "custom",
                startDate = start,
                endDate = end
            )
        }
    }

    private fun sendEmailReport(
        type: String,
        startDate: String? = null,
        endDate: String? = null
    ) {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token == null) {
                Toast.makeText(
                    this@ReportsActivity,
                    "Authentication error. Please login again.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            try {

                val response = RetrofitClient.api.sendEmailReport(
                    token = "Bearer $token",
                    type = type,
                    startDate = startDate,
                    endDate = endDate
                )

                Toast.makeText(
                    this@ReportsActivity,
                    response.message ?: "Report sent successfully 📧",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Toast.makeText(
                    this@ReportsActivity,
                    "Failed to send report",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------- FILTER COMMUNICATION ----------------

    private fun notifyFragments(
        filter: ReportFilter,
        start: String? = null,
        end: String? = null
    ) {

        supportFragmentManager.fragments.forEach { fragment ->

            // 🔥 Handle ViewPager fragments
            fragment.childFragmentManager.fragments.forEach { child ->

                if (child is Filterable) {
                    child.onFilterChanged(filter, start, end)
                }
            }

            // fallback (if directly attached)
            if (fragment is Filterable) {
                fragment.onFilterChanged(filter, start, end)
            }
        }
    }
}