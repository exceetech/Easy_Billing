package com.example.easy_billing

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.db.ProductProfitWithDate
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ProfitActivity : BaseActivity() {

    private lateinit var adapter: ProfitPagerAdapter

    private var currentFilter = FilterType.ALL

    private val formatter =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    private val dateOnlyFormatter =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private var customStartDate: String? = null
    private var customEndDate: String? = null

    private lateinit var btnToday: MaterialButton
    private lateinit var btnWeek: MaterialButton
    private lateinit var btnMonth: MaterialButton
    private lateinit var btnAll: MaterialButton
    private lateinit var btnCustom: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profit)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        adapter = ProfitPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "Charts" else "Top Products"
        }.attach()

        setupFilters()

        updateFilterUI(btnAll)
        applyFilter(FilterType.ALL)
    }

    // ================= FILTER BUTTONS =================

    private fun setupFilters() {

        btnToday = findViewById(R.id.btnToday)
        btnWeek = findViewById(R.id.btnWeek)
        btnMonth = findViewById(R.id.btnMonth)
        btnAll = findViewById(R.id.btnAll)
        btnCustom = findViewById(R.id.btnCustom)

        btnToday.setOnClickListener {
            toggleFilter(FilterType.TODAY, btnToday)
        }

        btnWeek.setOnClickListener {
            toggleFilter(FilterType.WEEK, btnWeek)
        }

        btnMonth.setOnClickListener {
            toggleFilter(FilterType.MONTH, btnMonth)
        }

        btnAll.setOnClickListener {
            toggleFilter(FilterType.ALL, btnAll)
        }

        btnCustom.setOnClickListener {
            openCustomDatePicker()
        }
    }

    private fun updateFilterUI(selectedButton: MaterialButton) {
        val buttons = listOf(btnToday, btnWeek, btnMonth, btnAll, btnCustom)

        buttons.forEach { button ->
            val isSelected = (button == selectedButton)

            // Premium Animation: Scale and Color Transition
            button.animate()
                .scaleX(if (isSelected) 1.05f else 1.0f)
                .scaleY(if (isSelected) 1.05f else 1.0f)
                .setDuration(250)
                .start()

            if (isSelected) {
                button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1A1A1A")) // Dark Charcoal
                button.setTextColor(Color.WHITE)
                button.elevation = 4f
            } else {
                button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F0F0F0")) // Light Gray
                button.setTextColor(Color.parseColor("#6B7280"))
                button.elevation = 0f
            }
        }
    }


    private fun applyPremiumToggle(selectedButton: MaterialButton) {
        val buttons = listOf(btnToday, btnWeek, btnMonth, btnAll, btnCustom)

        // Create a smooth transition for the entire layout
        val transition = AutoTransition().apply {
            duration = 300
            interpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f) // Premium "Swift" Ease
        }
        TransitionManager.beginDelayedTransition(findViewById(R.id.filterContainer), transition)

        buttons.forEach { button ->
            val isSelected = (button == selectedButton)

            if (isSelected) {
                // Selected: Deep Charcoal and slight Lift
                button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#111827"))
                button.setTextColor(Color.WHITE)
                button.strokeWidth = 0
                button.elevation = 8f
                // Subtle "Pop" effect
                button.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
            } else {
                // Unselected: Ghostly Gray with subtle border
                button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F9FAFB"))
                button.setTextColor(Color.parseColor("#6B7280"))
                button.strokeWidth = 1
                button.strokeColor = ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
                button.elevation = 0f
                button.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }
        }
    }
    private fun toggleFilter(filter: FilterType, view: MaterialButton) {
        if (currentFilter == filter) {
            // Toggle off: Go back to 'All'
            currentFilter = FilterType.ALL
            applyPremiumToggle(btnAll)
        } else {
            // Toggle on: Select the clicked button
            currentFilter = filter
            applyPremiumToggle(view)
        }

        // Your calculation function
        applyFilter(currentFilter)

        // Haptic Feedback for a "Physical" feel
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // ================= APPLY FILTER =================

    private fun applyFilter(filter: FilterType) {

        currentFilter = filter

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@ProfitActivity)

            val (grouped, totalLoss, totalExpense) = withContext(Dispatchers.IO) {
                val raw = db.billItemDao().getAllProductProfitRaw()

                val filtered = filterData(raw, filter)

                val groupedResult = filtered
                    .groupBy { it.productName + (it.variant ?: "") }
                    .map { (_, items) ->
                        ProductProfitRaw(
                            productName = items.first().productName,
                            variant = items.first().variant,
                            totalQty = items.sumOf { it.totalQty },
                            revenue = items.sumOf { it.revenue },
                            cost = items.sumOf { it.cost },
                            profit = items.sumOf { it.profit }
                        )
                    }
                    .sortedByDescending { it.profit }

                // 🔥 GET LOSS
                val loss = db.lossDao().getTotalLoss() ?: 0.0
                val expense = db.inventoryLogDao().getTotalExpense() ?: 0.0
                
                Triple(groupedResult, loss, expense)
            }

            // 🔥 FINAL CALCULATIONS
            val totalRevenue = grouped.sumOf { it.revenue }
            val totalCost = grouped.sumOf { it.cost }
            val netProfit = grouped.sumOf { it.profit } - totalLoss

            // 🔥 UPDATE CARDS

            findViewById<TextView>(R.id.tvExpense).text =
                "₹${"%.2f".format(totalExpense)}"

            findViewById<TextView>(R.id.tvRevenue).text =
                "₹${"%.2f".format(totalRevenue)}"

            findViewById<TextView>(R.id.tvCost).text =
                "₹${"%.2f".format(totalCost)}"

            findViewById<TextView>(R.id.tvLoss).text =
                "₹${"%.2f".format(totalLoss)}"

            findViewById<TextView>(R.id.tvNetProfit).text =
                "₹${"%.2f".format(netProfit)}"

            // 🔥 UPDATE FRAGMENTS
            adapter.chartFragment.updateChart(grouped)
            adapter.topFragment.updateList(grouped)
        }
    }

    // ================= CUSTOM DATE =================

    private fun openCustomDatePicker() {

        val constraints = CalendarConstraints.Builder()

            .setValidator(DateValidatorPointBackward.now()) // 🔥 blocks future

            .build()

        val picker = MaterialDatePicker.Builder.dateRangePicker()

            .setTitleText("Select Date Range")

            .setCalendarConstraints(constraints)

            .build()

        picker.show(supportFragmentManager, "DATE_RANGE")

        picker.addOnPositiveButtonClickListener { selection ->

            val startDate = Date(selection.first)

            val endDate = Date(selection.second)

            customStartDate = dateOnlyFormatter.format(startDate)

            customEndDate = dateOnlyFormatter.format(endDate)

            applyFilter(FilterType.CUSTOM)
            updateFilterUI(btnCustom)

        }

    }

    // ================= FILTER ENUM =================

    enum class FilterType {
        TODAY,
        WEEK,
        MONTH,
        ALL,
        CUSTOM
    }

    // ================= FILTER LOGIC =================

    private fun filterData(
        raw: List<ProductProfitWithDate>,
        filter: FilterType
    ): List<ProductProfitWithDate> {

        val now = Calendar.getInstance()

        return raw.filter {

            val date = try {
                formatter.parse(it.billDate)
            } catch (e: Exception) {
                null
            } ?: return@filter false

            val itemCal = Calendar.getInstance()
            itemCal.time = date

            when (filter) {

                // ✅ TODAY
                FilterType.TODAY -> {
                    now.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
                            now.get(Calendar.DAY_OF_YEAR) == itemCal.get(Calendar.DAY_OF_YEAR)
                }

                // ✅ WEEK (Sunday → now)
                FilterType.WEEK -> {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)

                    date >= cal.time && date <= Date()
                }

                // ✅ MONTH
                FilterType.MONTH -> {
                    now.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
                            now.get(Calendar.MONTH) == itemCal.get(Calendar.MONTH)
                }

                // ✅ ALL
                FilterType.ALL -> true

                // ✅ CUSTOM (🔥 FIXED)
                FilterType.CUSTOM -> {

                    if (customStartDate == null || customEndDate == null)
                        return@filter true

                    try {

                        val start = dateOnlyFormatter.parse(customStartDate!!)!!
                        val end = dateOnlyFormatter.parse(customEndDate!!)!!

                        // 🔥 START = 00:00:00
                        val startCal = Calendar.getInstance()
                        startCal.time = start
                        startCal.set(Calendar.HOUR_OF_DAY, 0)
                        startCal.set(Calendar.MINUTE, 0)
                        startCal.set(Calendar.SECOND, 0)
                        startCal.set(Calendar.MILLISECOND, 0)

                        // 🔥 END = 23:59:59
                        val endCal = Calendar.getInstance()
                        endCal.time = end
                        endCal.set(Calendar.HOUR_OF_DAY, 23)
                        endCal.set(Calendar.MINUTE, 59)
                        endCal.set(Calendar.SECOND, 59)
                        endCal.set(Calendar.MILLISECOND, 999)

                        date >= startCal.time && date <= endCal.time

                    } catch (e: Exception) {
                        false
                    }
                }
            }
        }
    }
}
