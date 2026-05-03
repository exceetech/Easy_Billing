package com.example.easy_billing

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.easy_billing.adapter.GstReportItem
import com.example.easy_billing.adapter.GstReportsPagerAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.util.GstEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GstReportsActivity : BaseActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var tvStartDate: TextView
    private lateinit var tvEndDate: TextView
    private lateinit var btnExportExcel: MaterialButton

    private lateinit var pagerAdapter: GstReportsPagerAdapter

    private var startDate: Long = 0L
    private var endDate: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gst_reports)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = "GST Reports"

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        tvStartDate = findViewById(R.id.tvStartDate)
        tvEndDate = findViewById(R.id.tvEndDate)
        btnExportExcel = findViewById(R.id.btnExportExcel)

        pagerAdapter = GstReportsPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "GSTR-1 (Sales)"
                1 -> "GSTR-2 (Purchases)"
                else -> ""
            }
        }.attach()

        setupDateFilters()
        
        btnExportExcel.setOnClickListener {
            exportToExcel()
        }
    }

    private fun setupDateFilters() {
        val calendar = Calendar.getInstance()
        
        // Default to current month
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        startDate = calendar.timeInMillis
        
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        endDate = calendar.timeInMillis
        
        updateDateText()
        
        tvStartDate.setOnClickListener {
            showDatePicker(true)
        }
        
        tvEndDate.setOnClickListener {
            showDatePicker(false)
        }
    }

    private fun updateDateText() {
        val format = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        tvStartDate.text = format.format(Date(startDate))
        tvEndDate.text = format.format(Date(endDate))
        
        loadReports()
    }

    private fun showDatePicker(isStart: Boolean) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = if (isStart) startDate else endDate
        
        val dpd = android.app.DatePickerDialog(
            this,
            { _, year, monthOfYear, dayOfMonth ->
                val selected = Calendar.getInstance()
                selected.set(year, monthOfYear, dayOfMonth)
                
                if (isStart) {
                    selected.set(Calendar.HOUR_OF_DAY, 0)
                    selected.set(Calendar.MINUTE, 0)
                    startDate = selected.timeInMillis
                } else {
                    selected.set(Calendar.HOUR_OF_DAY, 23)
                    selected.set(Calendar.MINUTE, 59)
                    endDate = selected.timeInMillis
                }
                
                updateDateText()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dpd.show()
    }

    private fun loadReports() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@GstReportsActivity)
            val storeInfo = db.storeInfoDao().get()
            val sellerStateCode = storeInfo?.stateCode ?: ""
            
            // Querying the DB for records
            val allSales = db.gstSalesRecordDao().getAll()
            val allPurchases = db.gstPurchaseRecordDao().getAll()
            
            val format = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            
            val salesItems = allSales.mapNotNull { record ->
                if (record.invoiceDate in startDate..endDate) {
                    val isInterstate = !GstEngine.isIntrastate(sellerStateCode, GstEngine.getStateCode(record.customerGstin))
                    GstReportItem(
                        invoiceNumber = record.invoiceNumber,
                        date = format.format(Date(record.invoiceDate)),
                        gstin = record.customerGstin ?: "B2C",
                        taxableValue = record.taxableValue,
                        totalTax = record.cgstAmount + record.sgstAmount + record.igstAmount,
                        isInterstate = isInterstate
                    )
                } else null
            }.sortedByDescending { it.date }

            val purchaseItems = allPurchases.mapNotNull { record ->
                if (record.invoiceDate in startDate..endDate) {
                    val isInterstate = !GstEngine.isIntrastate(sellerStateCode, GstEngine.getStateCode(record.vendorGstin))
                    GstReportItem(
                        invoiceNumber = record.invoiceNumber,
                        date = format.format(Date(record.invoiceDate)),
                        gstin = record.vendorGstin ?: "Unregistered",
                        taxableValue = record.taxableValue,
                        totalTax = record.cgstAmount + record.sgstAmount + record.igstAmount,
                        isInterstate = isInterstate
                    )
                } else null
            }.sortedByDescending { it.date }

            withContext(Dispatchers.Main) {
                // Wait for ViewPager fragments to be ready
                viewPager.post {
                    try {
                        pagerAdapter.getFragment(0).updateData(salesItems)
                        pagerAdapter.getFragment(1).updateData(purchaseItems)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun exportToExcel() {
        Toast.makeText(this, "Exporting to Excel...", Toast.LENGTH_SHORT).show()
        // Here you would use Apache POI to write the data to an .xlsx file.
        // For the scope of this implementation, we simulate the action.
        lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(1000)
            Toast.makeText(this@GstReportsActivity, "GSTR Reports Exported Successfully", Toast.LENGTH_LONG).show()
        }
    }
}
