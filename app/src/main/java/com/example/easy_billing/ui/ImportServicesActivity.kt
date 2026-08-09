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
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.button.MaterialButton
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

    /** Newest FY seen in the data so far — used to spot a record arriving in a
     *  later year than any before it, without fighting the user's own choice. */
    private var lastKnownNewestFy: Int? = null

    /** null = all statuses; else one of "synced" / "pending" / "rejected". */
    private var selectedStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_services)

        rvImportServices = findViewById(R.id.rvImportServices)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        setupToolbar(R.id.toolbar)

        // Tapping a row opens it for editing. This is the only way out for a
        // record the server refused — it will never sync until it is corrected.
        adapter = ImportServiceAdapter(emptyList()) { record ->
            startActivity(
                Intent(this, AddImportServiceActivity::class.java)
                    .putExtra(AddImportServiceActivity.EXTRA_RECORD_ID, record.id)
            )
        }
        rvImportServices.layoutManager = LinearLayoutManager(this)
        rvImportServices.adapter = adapter

        // Rows draw square, so the first and last one overhung the card's
        // rounded corners — most visibly the coloured status stripe, which runs
        // the full height of the row hard against the left edge. Clipping to
        // the background's outline makes the rows follow the card's radius.
        // Set here rather than in XML: android:clipToOutline is API 31+, this
        // works from API 21.
        //
        // Applied to the card container rather than the recycler view
        // directly, since the rounded background lives on cardRecords.
        findViewById<View>(R.id.cardRecords).clipToOutline = true

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, AddImportServiceActivity::class.java))
        }

        // FY switching moved off its own dropdown button and onto the hero
        // count line, which already read "14 records · FY 25–26" — tapping
        // the fact opens the same themed menu that used to live behind
        // btnFyFilter.
        findViewById<View>(R.id.tvHeroCount).setOnClickListener { showFyMenu(it) }

        listOf(
            R.id.chipAll to null,
            R.id.chipSynced to "synced",
            R.id.chipQueued to "pending",
            R.id.chipRefused to "rejected"
        ).forEach { (id, status) ->
            findViewById<View>(id).setOnClickListener {
                selectedStatus = status
                applyFilter()
            }
        }

        // Observed once, here — not in onResume. LiveData already re-delivers
        // on every resume, so observing there registered an extra observer each
        // time the screen was revisited and never removed the old ones.
        loadData()
    }

    private fun loadData() {
        val dao = AppDatabase.getDatabase(this).importServiceDao()
        dao.getAllImportServicesLive().observe(this) { list ->
            val newest = list.maxOfOrNull { fyStartYear(it.invoiceDate) }
            allRecords = list

            // Default to the most recent financial year present in the data.
            if (!fyInitialized) {
                selectedFyStart = newest
                fyInitialized = true
            } else {
                // Follow the data forward when a record appears in a year later
                // than any seen so far. Without this the filter latched on the
                // year it first saw, so an invoice saved into a NEWER year —
                // every April, on exactly the entries most likely to be
                // mis-dated — vanished the moment it was saved and looked like
                // the save had failed.
                //
                // Keyed on lastKnownNewestFy, not on the current selection: if
                // it compared against the selection it would yank the user back
                // to the latest year every refresh, making an older year
                // impossible to sit and read.
                //
                // "All FY" (null) is left alone — nothing can hide from it.
                val previousNewest = lastKnownNewestFy
                if (selectedFyStart != null && newest != null &&
                    (previousNewest == null || newest > previousNewest)) {
                    selectedFyStart = newest
                }
            }
            lastKnownNewestFy = newest
            applyFilter()
        }
    }

    private fun applyFilter() {
        val fyScoped = selectedFyStart?.let { fy ->
            allRecords.filter { fyStartYear(it.invoiceDate) == fy }
        } ?: allRecords

        // Status chips filter within the FY scope, not the search box that
        // used to live here — invoice-number search is gone in this design.
        var filtered = selectedStatus?.let { status ->
            fyScoped.filter { it.syncStatus == status }
        } ?: fyScoped

        // Refused records first — they need someone to act, and burying them in
        // date order is how they stay unnoticed. Everything else newest first,
        // matching the DAO's ordering.
        filtered = filtered.sortedWith(
            compareBy<ImportService> { if (it.syncStatus == "rejected") 0 else 1 }
                .thenByDescending { it.invoiceDate }
        )

        adapter.updateData(filtered)

        // Empty state swaps in for the recycler inside the same card
        // (cardRecords) rather than a separate card taking its place.
        val isEmpty = filtered.isEmpty()
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvImportServices.visibility = if (isEmpty) View.GONE else View.VISIBLE

        // Chips stay visible even with nothing recorded — each reads "· 0"
        // and still lets the user see the status breakdown at a glance,
        // instead of disappearing along with the list. Only the RECORDS
        // label (which introduces a list that no longer has anything under
        // it) hides.
        val nothingRecorded = allRecords.isEmpty()
        findViewById<View?>(R.id.layoutFilters)?.visibility = View.VISIBLE
        findViewById<View?>(R.id.tvRecordsLabel)?.visibility = View.VISIBLE

        findViewById<MaterialButton?>(R.id.fabAdd)?.text =
            if (nothingRecorded) "Add your first record" else getString(R.string.add_record)

        // Chip counts and selected state, counted within the FY scope so a
        // chip's number always matches what tapping it will show.
        findViewById<TextView?>(R.id.chipAll)?.text = "All · ${fyScoped.size}"
        findViewById<TextView?>(R.id.chipSynced)?.text =
            "Synced · ${fyScoped.count { it.syncStatus == "synced" }}"
        findViewById<TextView?>(R.id.chipQueued)?.text =
            "Queued · ${fyScoped.count { it.syncStatus == "pending" }}"
        findViewById<TextView?>(R.id.chipRefused)?.text =
            "Refused · ${fyScoped.count { it.syncStatus == "rejected" }}"

        val selectedChipId = when (selectedStatus) {
            "synced"   -> R.id.chipSynced
            "pending"  -> R.id.chipQueued
            "rejected" -> R.id.chipRefused
            else       -> R.id.chipAll
        }
        listOf(
            R.id.chipAll to "All",
            R.id.chipSynced to "Synced",
            R.id.chipQueued to "Queued",
            R.id.chipRefused to "Refused"
        ).forEach { (id, label) ->
            findViewById<TextView?>(id)?.let { chip ->
                chip.isSelected = (id == selectedChipId)
                styleFilterChip(chip, selected = id == selectedChipId, label = label)
            }
        }

        updateHeroKpis(filtered)
    }

    // Same hashed-accent-per-chip concept as the dashboard's category rail —
    // each status chip gets a stable color from this palette instead of
    // every selected chip looking identically teal.
    private val filterChipPalette = listOf(
        "#0F6E56", "#B23A3A", "#8A6526", "#185FA5",
        "#534AB7", "#D85A30", "#3B6D11", "#993556"
    )

    private fun filterChipColor(label: String): Int =
        android.graphics.Color.parseColor(
            filterChipPalette[(label.hashCode() and 0x7FFFFFFF) % filterChipPalette.size]
        )

    private fun styleFilterChip(chip: TextView, selected: Boolean, label: String) {
        val d = resources.displayMetrics.density
        val accent = filterChipColor(label)
        val strokeColor = if (selected) accent else android.graphics.Color.parseColor("#E4DCC8")
        val textColor = if (selected) accent else android.graphics.Color.parseColor("#6E6A60")

        chip.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.WHITE)
            cornerRadius = 8f * d
            setStroke((1.5f * d).toInt(), strokeColor)
        }
        chip.setTextColor(textColor)
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

        // Only refused records, not everything unsynced. The old tile counted
        // queued and refused as one number, but a queued record resolves itself
        // and a refused one never will — one of those is worth a red tile.
        val needsFixing = list.count { it.syncStatus == "rejected" }

        val scope = selectedFyStart?.let { fyLabel(it) } ?: "All FY"
        findViewById<TextView?>(R.id.tvHeroCount)?.text = "${list.size} records · $scope"
        findViewById<TextView?>(R.id.tvKpiTaxable)?.text = money(this, taxable)
        findViewById<TextView?>(R.id.tvKpiIgst)?.text = money(this, igst)
        findViewById<TextView?>(R.id.tvKpiItc)?.text = money(this, itcAvailed)
        findViewById<TextView?>(R.id.tvKpiUnsynced)?.text = needsFixing.toString()

        // ITC chip only when something has actually been availed.
        findViewById<View?>(R.id.layoutItcChip)?.visibility =
            if (itcAvailed > 0.0) View.VISIBLE else View.GONE
    }

    companion object {
        private val inFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))
        fun money(context: android.content.Context, v: Double): String =
            CurrencyHelper.getCurrencySymbol(context) + inFormat.format(Math.round(v))
    }
}

class ImportServiceAdapter(
    private var items: List<ImportService>,
    private val onClick: (ImportService) -> Unit = {}
) : RecyclerView.Adapter<ImportServiceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.viewSyncDot)
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tvInvoiceNumber)
        val tvInvoiceValue: TextView = view.findViewById(R.id.tvInvoiceValue)
        val tvItcEligibility: TextView = view.findViewById(R.id.tvItcEligibility)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
        val tvIgst: TextView = view.findViewById(R.id.tvIgst)
        val tvCess: TextView = view.findViewById(R.id.tvCess)
        val layoutCess: View = view.findViewById(R.id.layoutCess)
        val tvItcAvailed: TextView = view.findViewById(R.id.tvItcAvailed)
        val tvPlaceOfSupply: TextView = view.findViewById(R.id.tvPlaceOfSupply)
        val divider: View = view.findViewById(R.id.viewRowDivider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_import_service, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.itemView.setOnClickListener { onClick(item) }

        holder.tvInvoiceNumber.text = item.invoiceNumber

        holder.tvInvoiceValue.text = ImportServicesActivity.money(holder.itemView.context, item.taxableValue)
        holder.tvIgst.text = ImportServicesActivity.money(holder.itemView.context, item.igstPaid)
        holder.tvItcAvailed.text =
            ImportServicesActivity.money(holder.itemView.context, item.availedItcIgst + item.availedItcCess)

        // Cess is zero on almost every import record — only give it a column
        // when there is something to show.
        holder.tvCess.text = ImportServicesActivity.money(holder.itemView.context, item.cessPaid)
        holder.layoutCess.visibility = if (item.cessPaid > 0.0) View.VISIBLE else View.GONE

        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val rate = if (item.rate % 1.0 == 0.0) "${item.rate.toInt()}%" else "${item.rate}%"
        holder.tvPlaceOfSupply.text =
            "${sdf.format(Date(item.invoiceDate))} · POS ${item.placeOfSupply} · $rate"

        // Status stripe: green synced, red refused by the server, amber queued.
        // "rejected" needs its own colour — it will never turn green on its own,
        // so showing it as merely pending would leave the user waiting for a
        // sync that is never going to happen.
        holder.stripe.setBackgroundColor(
            Color.parseColor(
                when (item.syncStatus) {
                    "synced"   -> "#639922"
                    "rejected" -> "#B23A3A"
                    else       -> "#EF9F27"
                }
            )
        )

        // Eligibility always shows on its own badge now. A refused or queued
        // record gets a second badge alongside it instead of losing the
        // eligibility fact — both matter, and hiding one to show the other
        // was the old row's compromise.
        bindEligibility(holder.tvItcEligibility, item.eligibilityForItc)
        when (item.syncStatus) {
            "rejected" -> {
                holder.tvStatusBadge.visibility = View.VISIBLE
                bindBadge(holder.tvStatusBadge, "#FCEBEB", "#791F1F", holder.itemView.context.getString(R.string.refused_chip))
            }
            "pending" -> {
                holder.tvStatusBadge.visibility = View.VISIBLE
                bindBadge(holder.tvStatusBadge, "#FAEEDA", "#633806", holder.itemView.context.getString(R.string.queued_chip))
            }
            else -> holder.tvStatusBadge.visibility = View.GONE
        }

        // Rows sit inside one card, so the last one must not draw a hairline
        // against the card's bottom edge.
        holder.divider.visibility =
            if (position == items.lastIndex) View.GONE else View.VISIBLE
    }

    private fun bindBadge(view: TextView, bg: String, text: String, label: String) {
        view.text = label
        view.setTextColor(Color.parseColor(text))
        view.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
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
