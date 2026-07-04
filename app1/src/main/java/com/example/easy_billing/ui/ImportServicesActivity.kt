package com.example.easy_billing.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.BaseActivity
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ImportService
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ImportServicesActivity : BaseActivity() {

    private lateinit var rvImportServices: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var adapter: ImportServiceAdapter

    private var allRecords: List<ImportService> = emptyList()
    private var selectedFyStart: Int? = null   // null = All financial years
    private var fyInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_services)

        rvImportServices = findViewById(R.id.rvImportServices)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        setupToolbar(R.id.toolbar)

        adapter = ImportServiceAdapter(emptyList())
        rvImportServices.layoutManager = LinearLayoutManager(this)
        rvImportServices.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, AddImportServiceActivity::class.java))
        }

        findViewById<View>(R.id.btnFyFilter).setOnClickListener { showFyMenu(it) }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        val dao = AppDatabase.getDatabase(this).importServiceDao()
        dao.getAllImportServicesLive().observe(this) { list ->
            allRecords = list
            // Default to the most recent financial year present in the data.
            if (!fyInitialized) {
                selectedFyStart = list.maxOfOrNull { fyStartYear(it.invoiceDate) }
                fyInitialized = true
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = selectedFyStart?.let { fy ->
            allRecords.filter { fyStartYear(it.invoiceDate) == fy }
        } ?: allRecords

        adapter.updateData(filtered)
        layoutEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView?>(R.id.tvFyLabel)?.text =
            selectedFyStart?.let { fyLabel(it) } ?: "All FY"
        updateHeroKpis(filtered)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Themed dropdown for the FY filter — rounded card, styled rows, check on selection. */
    private fun showFyMenu(anchor: View) {
        // null fy = "All FY"; then each financial year present, most recent first.
        val options: List<Pair<String, Int?>> =
            listOf("All FY" to null) +
            allRecords.map { fyStartYear(it.invoiceDate) }
                .distinct().sortedDescending().map { fyLabel(it) to it }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val width = dp(180)
        val popup = PopupWindow(
            container, width, ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        options.forEach { (label, fy) ->
            val isSel = fy == selectedFyStart
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor(if (isSel) "#185FA5" else "#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (isSel) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(Color.parseColor("#185FA5"))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                selectedFyStart = fy
                applyFilter()
                popup.dismiss()
            }
            container.addView(row)
        }

        // Right-align the card under the chip.
        popup.showAsDropDown(anchor, anchor.width - width, dp(6))
    }

    /** Indian financial year starts 1 April; returns the start calendar year. */
    private fun fyStartYear(epoch: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = epoch }
        return if (c.get(Calendar.MONTH) >= Calendar.APRIL) c.get(Calendar.YEAR)
        else c.get(Calendar.YEAR) - 1
    }

    private fun fyLabel(start: Int): String {
        val a = (start % 100).toString().padStart(2, '0')
        val b = ((start + 1) % 100).toString().padStart(2, '0')
        return "FY $a–$b"
    }

    private fun updateHeroKpis(list: List<ImportService>) {
        val taxable = list.sumOf { it.taxableValue }
        val igst = list.sumOf { it.igstPaid }
        val itcAvailed = list.sumOf { it.availedItcIgst + it.availedItcCess }
        val unsynced = list.count { it.syncStatus != "synced" }

        val scope = selectedFyStart?.let { fyLabel(it) } ?: "All FY"
        findViewById<TextView?>(R.id.tvHeroCount)?.text = "${list.size} records · $scope"
        findViewById<TextView?>(R.id.tvKpiTaxable)?.text = money(taxable)
        findViewById<TextView?>(R.id.tvKpiIgst)?.text = money(igst)
        findViewById<TextView?>(R.id.tvKpiItc)?.text = money(itcAvailed)
        findViewById<TextView?>(R.id.tvKpiUnsynced)?.text = unsynced.toString()

        // ITC chip only when something has actually been availed.
        findViewById<View?>(R.id.layoutItcChip)?.visibility =
            if (itcAvailed > 0.0) View.VISIBLE else View.GONE
    }

    companion object {
        private val inFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))
        fun money(v: Double): String = "₹" + inFormat.format(Math.round(v))
    }
}

class ImportServiceAdapter(private var items: List<ImportService>) :
    RecyclerView.Adapter<ImportServiceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.viewSyncDot)
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tvInvoiceNumber)
        val tvInvoiceDate: TextView = view.findViewById(R.id.tvInvoiceDate)
        val tvInvoiceValue: TextView = view.findViewById(R.id.tvInvoiceValue)
        val tvItcEligibility: TextView = view.findViewById(R.id.tvItcEligibility)
        val tvIgst: TextView = view.findViewById(R.id.tvIgst)
        val tvCess: TextView = view.findViewById(R.id.tvCess)
        val tvItcAvailed: TextView = view.findViewById(R.id.tvItcAvailed)
        val tvPlaceOfSupply: TextView = view.findViewById(R.id.tvPlaceOfSupply)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_import_service, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvInvoiceNumber.text = item.invoiceNumber

        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        holder.tvInvoiceDate.text = sdf.format(Date(item.invoiceDate))

        holder.tvInvoiceValue.text = ImportServicesActivity.money(item.taxableValue)
        holder.tvIgst.text = ImportServicesActivity.money(item.igstPaid)
        holder.tvCess.text = ImportServicesActivity.money(item.cessPaid)
        holder.tvItcAvailed.text =
            ImportServicesActivity.money(item.availedItcIgst + item.availedItcCess)

        val rate = if (item.rate % 1.0 == 0.0) "${item.rate.toInt()}%" else "${item.rate}%"
        holder.tvPlaceOfSupply.text = "POS ${item.placeOfSupply} · $rate"

        // Sync dot
        val synced = item.syncStatus == "synced"
        holder.dot.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (synced) "#639922" else "#EF9F27"))

        // ITC eligibility badge
        bindEligibility(holder.tvItcEligibility, item.eligibilityForItc)
    }

    private fun bindEligibility(view: TextView, eligibility: String) {
        val key = eligibility.trim().lowercase()
        val (bg, text, label) = when {
            key.startsWith("input s") || key.contains("input service") ->
                Triple("#E6F1FB", "#0C447C", eligibility)
            key.startsWith("input") ->
                Triple("#EAF3DE", "#27500A", "Inputs · ITC")
            key.startsWith("capital") ->
                Triple("#FAEEDA", "#633806", eligibility)
            key.isBlank() || key.startsWith("inelig") || key == "none" || key == "no" ->
                Triple("#FCEBEB", "#791F1F", if (key.isBlank()) "Ineligible" else eligibility)
            else ->
                Triple("#EAF3DE", "#27500A", eligibility)
        }
        view.text = label
        view.setTextColor(Color.parseColor(text))
        view.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<ImportService>) {
        items = newItems
        notifyDataSetChanged()
    }
}
