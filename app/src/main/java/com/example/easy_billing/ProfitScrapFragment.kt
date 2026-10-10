package com.example.easy_billing

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.RankedRowAdapter
import com.example.easy_billing.adapter.SalesRow
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.BarBubbleMarker
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.RoundedBarChartRenderer
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private typealias Row = ScrapRow

/**
 * Profit Analytics > "Scrap" tab. Cards: money lost, loss over time, how often the shop
 * scraps (last 30 days vs the 30 before), products scrapped most (reason dropdown) and the
 * latest entries, plus a share-on-WhatsApp button. Data comes from [ScrapData] (backend, with
 * this phone's unsynced entries); it reloads every time the tab comes back on screen. Follows
 * the shared date chips via [applyFilter], like the other Profit tabs.
 */
class ProfitScrapFragment : Fragment(R.layout.fragment_profit_scrap) {

    private var all: List<Row> = emptyList()
    private var loaded = false
    private var filter = "all"
    private var startDate: String? = null
    private var endDate: String? = null

    private var revenue: Double? = null
    private var loading = false
    private var skipNextResume = false   // returning from the share sheet: nothing changed
    private var profitJob: Job? = null

    // How many periods back the chart is showing (0 = current), like the Sales trend card.
    private var chartOffset = 0

    // Reason chip picked in "Products scrapped most" (null = All).
    private var reasonFilter: String? = null

    // "How often you scrap" card expanded to show each product.
    private var freqExpanded = false

    // "Recent scrap" card expanded beyond the latest 5.
    private var recentExpanded = false

    private val adapter = RankedRowAdapter()

    private val red = Color.parseColor("#B23A3A")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<RecyclerView>(R.id.rvScrapProducts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProfitScrapFragment.adapter
            isNestedScrollingEnabled = false
        }
        view.findViewById<View>(R.id.tvScrapMore).setOnClickListener {
            adapter.toggleExpanded(); updateMoreButton()
        }
        setupChartChrome(view.findViewById(R.id.scrapChart))
        view.findViewById<View>(R.id.btnScrapPrev).setOnClickListener { stepChart(+1) }   // older
        view.findViewById<View>(R.id.btnScrapNext).setOnClickListener { stepChart(-1) }   // newer
        view.findViewById<View>(R.id.btnScrapShare).setOnClickListener { shareReport() }
        // Don't let the tab swipe steal the drag while the chart is zoomed in.
        view.findViewById<BarChart>(R.id.scrapChart).setOnTouchListener { v, e ->
            val zoomed = (v as BarChart).scaleX > 1.01f
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(zoomed)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /** First time on screen, and every time the tab comes back (e.g. after scrapping stock). */
    override fun onResume() {
        super.onResume()
        if (skipNextResume) { skipNextResume = false; return }
        loadEntries()
    }

    /** Called by [ProfitActivity] when the shared date chips change. */
    fun applyFilter(newFilter: String, newStart: String? = null, newEnd: String? = null) {
        filter = newFilter
        startDate = newStart
        endDate = newEnd
        chartOffset = 0
        if (view != null && loaded) { render(); loadSales() }
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private fun loadEntries() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val ctx = context
            if (ctx == null) { loading = false; return@launch }
            val result = try { ScrapData.load(ctx) } finally { loading = false }
            all = result.rows
            loaded = true
            view?.let {
                // Server not reachable: say so, so the numbers aren't mistaken for the full picture.
                it.findViewById<View>(R.id.tvScrapOffline).visibility =
                    if (result.backendOk) View.GONE else View.VISIBLE
                render(); loadSales()
            }
        }
    }

    /** Sales for the same period (profit report), for the "share of sales" box. */
    private fun loadSales() {
        profitJob?.cancel()
        profitJob = lifecycleScope.launch {
            val ctx = context ?: return@launch
            val token = ctx.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                .getString("TOKEN", null)
            revenue = null
            if (!token.isNullOrEmpty()) {
                try {
                    val r = RetrofitClient.api.getProfit(token, filter, startDate, endDate)
                    revenue = r.summary.revenue
                } catch (_: Exception) { }
            }
            if (view != null) renderFlow()
        }
    }

    // ── Dates ───────────────────────────────────────────────────────────────

    private fun parse(d: String): Date = AppTime.isoDate().parse(d)!!
    private fun fmt(c: Calendar): String = AppTime.isoDate().format(c.time)
    private fun addDays(d: String, n: Int): String =
        AppTime.calendar().apply { time = parse(d); add(Calendar.DAY_OF_MONTH, n) }.let { fmt(it) }
    private fun daysBetween(a: String, b: String): Int =
        ((parse(b).time - parse(a).time) / 86_400_000.0).roundToInt() + 1
    private fun today(): String = fmt(AppTime.calendar())

    /** Current range; null = all time. */
    private fun range(): Pair<String, String>? {
        val t = today()
        return when (filter) {
            "today" -> t to t
            "week" -> fmt(AppTime.calendar().apply { set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY) }) to t
            "month" -> fmt(AppTime.calendar().apply { set(Calendar.DAY_OF_MONTH, 1) }) to t
            "custom" -> if (startDate != null && endDate != null) startDate!! to endDate!! else null
            else -> null
        }
    }

    private fun previousRange(cur: Pair<String, String>): Pair<String, String> {
        val len = daysBetween(cur.first, cur.second)
        if (filter == "month") {
            val first = AppTime.calendar().apply {
                time = parse(cur.first); add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1)
            }
            val last = AppTime.calendar().apply {
                time = first.time; set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            }
            val s = fmt(first)
            val e = minOf(addDays(s, len - 1), fmt(last))
            return s to e
        }
        return addDays(cur.first, -len) to addDays(cur.first, -1)
    }

    private fun inRange(rows: List<Row>, r: Pair<String, String>?) =
        if (r == null) rows else rows.filter { it.date >= r.first && it.date <= r.second }

    private fun events(rows: List<Row>) = ScrapData.events(rows)

    // ── Render ──────────────────────────────────────────────────────────────

    private fun render() {
        val root = view ?: return
        val ctx = requireContext()
        val cur = range()
        val rows = inRange(all, cur)
        val total = rows.sumOf { it.loss }
        val has = rows.isNotEmpty()

        fun show(id: Int, on: Boolean) { root.findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
        show(R.id.tvScrapEmpty, !has)
        show(R.id.cardTime, has)
        show(R.id.cardProducts, has)
        show(R.id.btnScrapShare, has)
        show(R.id.cardFreq, all.isNotEmpty())

        // ── Card 1: money lost
        val products = rows.groupBy { it.name }
        root.findViewById<TextView>(R.id.tvScrapTotal).text = CurrencyHelper.format(ctx, total)
        root.findViewById<TextView>(R.id.tvScrapSub).text =
            getString(R.string.scrap_sub, products.size)
        val delta = root.findViewById<TextView>(R.id.tvScrapDelta)
        if (cur != null) {
            val prevTotal = inRange(all, previousRange(cur)).sumOf { it.loss }
            delta.visibility = View.VISIBLE
            when {
                total == 0.0 && prevTotal == 0.0 -> delta.visibility = View.GONE
                prevTotal == 0.0 -> pill(delta, getString(R.string.scrap_delta_up_new), "#FBE1E1", "#8E2A2A")
                abs(total - prevTotal) / prevTotal < 0.005 ->
                    pill(delta, getString(R.string.scrap_delta_same), "#FBF1DE", "#8A6526")
                total > prevTotal -> pill(delta,
                    getString(R.string.scrap_delta_up, ((total - prevTotal) / prevTotal * 100).roundToInt()),
                    "#FBE1E1", "#8E2A2A")
                else -> pill(delta,
                    getString(R.string.scrap_delta_down, ((prevTotal - total) / prevTotal * 100).roundToInt()),
                    "#DCFCE7", "#085041")
            }
        } else delta.visibility = View.GONE
        root.findViewById<TextView>(R.id.tvEventsValue).text = events(rows).toString()
        root.findViewById<TextView>(R.id.tvEventsSub).text = getString(R.string.scrap_events_sub)
        updateShare(total)

        renderFrequency(root)
        renderRecent(root)
        if (!has) return

        // ── Chart
        drawChart(root.findViewById(R.id.scrapChart), cur)

        renderProducts(root, rows)

        renderFlow()
    }

    /** "Recent scrap" card: last 3 years (not tied to the date chips), newest first. */
    private fun renderRecent(root: View) {
        val ctx = requireContext()
        // ── Recent (timeline): day label, then time | dot on a line | name + qty/reason | loss
        val ll = root.findViewById<LinearLayout>(R.id.llRecent)
        ll.removeAllViews()
        val t = today(); val y = addDays(t, -1)
        val dayFmt = AppTime.formatter("dd MMM")
        val dayYearFmt = AppTime.formatter("dd MMM yyyy")
        // With a chip (Today / Week / Month / Custom): that period. All time: the last 3 years.
        val chipRange = range()
        val since = fmt(AppTime.calendar().apply { add(Calendar.YEAR, -3) })
        val all3y = (if (chipRange != null) inRange(all, chipRange) else all.filter { it.date >= since })
            .sortedByDescending { it.dateTime }
        root.findViewById<TextView>(R.id.tvRecentCaption).text =
            getString(if (chipRange != null) R.string.scrap_recent_caption_period else R.string.scrap_recent_caption)
        root.findViewById<View>(R.id.cardRecent).visibility = if (all3y.isEmpty()) View.GONE else View.VISIBLE
        val recent = if (recentExpanded) all3y.take(100) else all3y.take(5)
        val moreBtn = root.findViewById<View>(R.id.btnRecentMore)
        moreBtn.visibility = if (all3y.size > 5) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvRecentMoreText).text =
            if (recentExpanded) getString(R.string.scrap_show_less)
            else getString(R.string.scrap_recent_see_all, minOf(all3y.size, 100))
        root.findViewById<View>(R.id.ivRecentMoreChevron).rotation = if (recentExpanded) 180f else 0f
        moreBtn.setOnClickListener { recentExpanded = !recentExpanded; renderRecent(root) }
        var lastDay: String? = null
        recent.forEachIndexed { idx, r ->
            if (r.date != lastDay) {
                lastDay = r.date
                val dv = LayoutInflater.from(ctx).inflate(R.layout.item_scrap_recent_day, ll, false)
                dv.findViewById<TextView>(R.id.tvRecDay).text = when (r.date) {
                    t -> getString(R.string.scrap_today)
                    y -> getString(R.string.reports_nav_yesterday)
                    else -> (if (r.date.take(4) == t.take(4)) dayFmt else dayYearFmt).format(parse(r.date))
                }.uppercase()
                if (idx == 0) dv.findViewById<View>(R.id.vRecDayLine).visibility = View.GONE
                ll.addView(dv)
            }
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_scrap_recent, ll, false)
            val h24 = r.dateTime.getOrNull(13)?.let { r.dateTime.substring(11, 13).toIntOrNull() } ?: 0
            val min = r.dateTime.getOrNull(15)?.let { r.dateTime.substring(14, 16) } ?: "00"
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            v.findViewById<TextView>(R.id.tvRecTime).text = "$h12:$min"
            v.findViewById<TextView>(R.id.tvRecAmPm).text = getString(if (h24 < 12) R.string.scrap_am else R.string.scrap_pm)
            v.findViewById<TextView>(R.id.tvRecName).text = r.name
            v.findViewById<TextView>(R.id.tvRecSub).text = getString(R.string.scrap_recent_sub, qty(r.qty), reasonLabel(r.reason))
            v.findViewById<TextView>(R.id.tvRecAmount).text = "−" + CurrencyHelper.format(ctx, r.loss)
            val (dot, ring) = when (r.reason) {
                "Expired" -> "#B23A3A" to "#F1C4C4"
                "Damaged" -> "#D8A23A" to "#F3DDA8"
                "Spoiled" -> "#5B7FD1" to "#C9D6F3"
                else -> "#7A4FA3" to "#E0D2EE"
            }
            val d = resources.displayMetrics.density
            v.findViewById<View>(R.id.vRecDot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(dot))
                setStroke((3 * d).toInt(), Color.parseColor(ring))
            }
            if (idx == recent.lastIndex) {
                v.findViewById<View>(R.id.vRecLine).visibility = View.GONE
                v.findViewById<View>(R.id.llRecText).setPadding(0, 0, 0, 0)
            }
            ll.addView(v)
        }
    }

    /** "Products scrapped most": reason dropdown (with how many times) filters the ranked list. */
    private fun renderProducts(root: View, rows: List<Row>) {
        val ctx = requireContext()
        // A reason with nothing in this period can't stay selected.
        if (reasonFilter != null && rows.none { it.reason == reasonFilter }) reasonFilter = null

        // Reason dropdown: shows the chosen reason and how many times; tap to pick another.
        val keys = listOf<String?>(null, "Expired", "Damaged", "Spoiled", "Other")
            .filter { it == null || rows.any { r -> r.reason == it } }
        fun countOf(key: String?) = events(if (key == null) rows else rows.filter { it.reason == key })
        fun reasonName(key: String?) = if (key == null) getString(R.string.scrap_all_reasons) else reasonLabel(key)
        root.findViewById<TextView>(R.id.tvScrapReasonValue).text = reasonName(reasonFilter)
        root.findViewById<TextView>(R.id.tvScrapReasonCount).text =
            getString(R.string.scrap_times_count, countOf(reasonFilter))
        root.findViewById<View>(R.id.rowScrapReason).setOnClickListener { anchor ->
            com.example.easy_billing.ui.ThemedDropdown.show(
                anchor,
                keys.map { "${reasonName(it)}  ·  ${countOf(it)}" },
                keys.indexOf(reasonFilter).coerceAtLeast(0),
                rightAlign = false,
                minWidthDp = 240
            ) { idx ->
                reasonFilter = keys[idx]
                renderProducts(root, rows)
            }
        }

        val shown = if (reasonFilter == null) rows else rows.filter { it.reason == reasonFilter }
        root.findViewById<TextView>(R.id.tvProductsCaption).text =
            if (reasonFilter == null) getString(R.string.scrap_products_caption)
            else getString(R.string.scrap_products_caption_filtered,
                reasonLabel(reasonFilter!!), CurrencyHelper.format(ctx, shown.sumOf { it.loss }))

        val risingNames = risingProducts().toSet()
        val ranked = shown.groupBy { it.name }.map { (name, list) ->
            val loss = list.sumOf { it.loss }
            val ev = events(list)
            val sub = getString(R.string.scrap_row_sub, qty(list.sumOf { it.qty }), ev) +
                if (name in risingNames) getString(R.string.scrap_row_rising) else ""
            SalesRow(label = name, revenue = loss, bills = ev, subText = sub,
                valueText = CurrencyHelper.format(ctx, loss))
        }.sortedByDescending { it.revenue }
        adapter.updateData(ranked)
        updateMoreButton()
    }

    private fun reasonLabel(r: String) = getString(when (r) {
        "Expired" -> R.string.scrap_reason_expired
        "Damaged" -> R.string.scrap_reason_damaged
        "Spoiled" -> R.string.scrap_reason_spoiled
        else -> R.string.scrap_reason_other
    })

    private fun qty(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

    private fun pill(tv: TextView, text: String, bg: String, fg: String) {
        tv.text = text
        tv.setTextColor(Color.parseColor(fg))
        tv.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg)); cornerRadius = 100f * resources.displayMetrics.density
        }
    }

    private fun updateShare(total: Double) {
        val root = view ?: return
        val rev = revenue
        root.findViewById<TextView>(R.id.tvShareValue).text =
            if (rev != null && rev > 0) "%.1f%%".format(total / rev * 100) else getString(R.string.dash)
        root.findViewById<TextView>(R.id.tvShareSub).text = getString(R.string.scrap_share_sub)
    }

    /** Sales figure arrived: refresh the "share of sales" box. */
    private fun renderFlow() {
        updateShare(inRange(all, range()).sumOf { it.loss })
    }

    /** Builds this period's scrap report as a PDF (same look as the profit print) and shares it. */
    private fun shareReport() {
        val act = activity ?: return
        val cur = range()
        val rows = inRange(all, cur)
        if (rows.isEmpty()) return
        val periodText = if (cur == null) getString(R.string.invoice_pdf_all_time_report) else {
            val pretty = AppTime.formatter("dd MMM yyyy")
            val a = pretty.format(parse(cur.first)); val b = pretty.format(parse(cur.second))
            if (cur.first == cur.second) a else "$a – $b"
        }
        // Every entry from the last 3 months (whatever the date chip says), newest first.
        val since = fmt(AppTime.calendar().apply { add(Calendar.MONTH, -3) })
        val recent = all.filter { it.date >= since }.sortedByDescending { it.dateTime }
        val btn = view?.findViewById<View>(R.id.btnScrapShare)
        btn?.isEnabled = false
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    com.example.easy_billing.util.ScrapPdfGenerator.generate(act, rows, periodText, revenue, recent)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    act, "${act.packageName}.provider", file)
                skipNextResume = true
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null))
            } catch (e: Exception) {
                android.widget.Toast.makeText(act, getString(R.string.scrap_share_failed),
                    android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                btn?.isEnabled = true
            }
        }
    }

    // ── Frequency: the chosen period vs the same-length period before it
    //    (All time: the last 30 days vs the 30 days before) ──────────────────

    private fun windowEvents(rows: List<Row>, from: String, to: String) =
        events(rows.filter { it.date >= from && it.date <= to })

    /** (this period, the period before it) as date ranges. */
    private fun freqWindows(): Pair<Pair<String, String>, Pair<String, String>> {
        val r = range()
        if (r != null) return r to previousRange(r)
        val t = today()
        return (addDays(t, -29) to t) to (addDays(t, -59) to addDays(t, -30))
    }

    /** (this period, the period before it). */
    private fun freqCounts(): Pair<Int, Int> {
        val (c, p) = freqWindows()
        return windowEvents(all, c.first, c.second) to windowEvents(all, p.first, p.second)
    }

    private fun risingProducts(): List<String> {
        val (c, p) = freqWindows()
        return all.groupBy { it.name }.filter { (_, l) ->
            val now = windowEvents(l, c.first, c.second)
            now >= 2 && now > windowEvents(l, p.first, p.second)
        }.keys.toList()
    }

    private fun renderFrequency(root: View) {
        val (now, prev) = freqCounts()
        val rising = now > prev
        val falling = now < prev

        val face: String
        val headline: Int
        val color: String
        when {
            now == 0 && prev == 0 -> { face = "🙂"; headline = R.string.scrap_freq_head_none; color = "#16734A" }
            rising -> { face = "😟"; headline = R.string.scrap_freq_head_up; color = "#8E2A2A" }
            falling -> { face = "🙂"; headline = R.string.scrap_freq_head_down; color = "#16734A" }
            else -> { face = "😐"; headline = R.string.scrap_freq_head_same; color = "#8A6526" }
        }
        root.findViewById<TextView>(R.id.tvFreqFace).text = face
        root.findViewById<TextView>(R.id.tvFreqHeadline).apply {
            text = getString(headline); setTextColor(Color.parseColor(color))
        }
        val byChip = range() != null
        root.findViewById<TextView>(R.id.tvFreqCaption).text =
            getString(if (byChip) R.string.scrap_freq_caption_period else R.string.scrap_freq_caption)
        root.findViewById<TextView>(R.id.tvFreqSummary).text =
            getString(if (byChip) R.string.scrap_freq_summary_period else R.string.scrap_freq_summary, now, prev)

        // Mood meter: marker sits left (less) .. middle (same) .. right (more).
        val frac = when {
            now == 0 && prev == 0 -> 0.06f
            else -> (0.5f + 0.5f * (now - prev).toFloat() / maxOf(now, prev, 1)).coerceIn(0.04f, 0.96f)
        }
        val d = resources.displayMetrics.density
        val marker = root.findViewById<View>(R.id.vFreqMarker)
        marker.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((3 * d).toInt(), Color.parseColor(color))
        }
        val meter = root.findViewById<View>(R.id.flFreqMeter)
        meter.post {
            val lp = marker.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.marginStart = ((meter.width - lp.width) * frac).toInt()
            marker.layoutParams = lp
        }

        // Per-product frequency (this 30 days vs the 30 before), shown when expanded.
        val ctx = requireContext()
        val t = today()
        val (cw, pw) = freqWindows()
        data class P(val name: String, val now: Int, val before: Int, val list: List<Row>)
        val prods = all.groupBy { it.name }.map { (n, l) ->
            P(n, windowEvents(l, cw.first, cw.second), windowEvents(l, pw.first, pw.second), l)
        }.filter { it.now > 0 || it.before > 0 }
            .sortedWith(compareByDescending<P> { it.now - it.before }.thenByDescending { it.now })
            .take(10)

        val ll = root.findViewById<LinearLayout>(R.id.llFreqProducts)
        ll.removeAllViews()
        prods.forEachIndexed { idx, p ->
            if (idx > 0) ll.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())
                setBackgroundColor(Color.parseColor("#F0EADC"))
            })
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_scrap_freq_row, ll, false)
            v.findViewById<TextView>(R.id.tvFreqProdName).text = p.name
            v.findViewById<TextView>(R.id.tvFreqProdSub).text =
                getString(if (byChip) R.string.scrap_freq_row_sub_period else R.string.scrap_freq_row_sub, p.now, p.before)
            val dots = v.findViewById<LinearLayout>(R.id.llFreqDots)
            for (w in 6 downTo 0) {
                val end = addDays(t, -7 * w)
                val on = windowEvents(p.list, addDays(end, -6), end) > 0
                dots.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((9 * d).toInt(), (9 * d).toInt())
                        .also { it.marginStart = (3 * d).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (on) red else Color.parseColor("#EFE6D2"))
                    }
                })
            }
            val tr = v.findViewById<TextView>(R.id.tvFreqProdTrend)
            val diff = p.now - p.before
            when {
                diff > 0 -> pill(tr, getString(R.string.scrap_freq_more, diff), "#FBE1E1", "#8E2A2A")
                diff < 0 -> pill(tr, getString(R.string.scrap_freq_less, -diff), "#DCFCE7", "#085041")
                else -> pill(tr, getString(R.string.scrap_freq_same), "#FBF1DE", "#8A6526")
            }
            ll.addView(v)
        }

        val hint = root.findViewById<View>(R.id.tvFreqHint)
        val btnText = root.findViewById<TextView>(R.id.tvFreqBtnText)
        fun applyExpanded() {
            val show = freqExpanded && prods.isNotEmpty()
            ll.visibility = if (show) View.VISIBLE else View.GONE
            hint.visibility = if (show) View.VISIBLE else View.GONE
            btnText.text = getString(
                if (freqExpanded) R.string.scrap_freq_hide_products else R.string.scrap_freq_see_products
            )
        }
        applyExpanded()
        root.findViewById<View>(R.id.btnFreqProducts).setOnClickListener {
            freqExpanded = !freqExpanded
            applyExpanded()
        }
    }

    // ── Products list "See all" ─────────────────────────────────────────────

    private fun updateMoreButton() {
        val root = view ?: return
        val btn = root.findViewById<View>(R.id.tvScrapMore)
        btn.visibility = if (adapter.canExpand) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvScrapMoreText).text =
            if (adapter.expanded) getString(R.string.scrap_show_less)
            else getString(R.string.scrap_see_all, adapter.totalCount)
        root.findViewById<ImageView>(R.id.ivScrapMoreChevron).rotation = if (adapter.expanded) 180f else 0f
    }

    // ── Chart ───────────────────────────────────────────────────────────────

    private fun maxOffset() = when (filter) {
        "today" -> 7
        "week" -> 4
        "month" -> 12
        else -> 0
    }

    private fun stepChart(delta: Int) {
        chartOffset = (chartOffset + delta).coerceIn(0, maxOffset())
        val root = view ?: return
        if (inRange(all, range()).isEmpty()) return
        drawChart(root.findViewById(R.id.scrapChart), range())
    }

    /** The dates the chart shows for the current chip + offset; null = all time (monthly). */
    private fun chartRange(): Pair<String, String>? {
        val t = today()
        return when (filter) {
            "today" -> { val d = addDays(t, -chartOffset); d to d }
            "week" -> {
                val s = AppTime.calendar().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY); add(Calendar.DAY_OF_YEAR, -7 * chartOffset)
                }
                fmt(s) to addDays(fmt(s), 6)
            }
            "month" -> {
                val s = AppTime.calendar().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -chartOffset) }
                val e = (s.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) }
                fmt(s) to fmt(e)
            }
            "custom" -> if (startDate != null && endDate != null) startDate!! to endDate!! else null
            else -> null
        }
    }

    private fun updateChartNav(root: View) {
        val ddMmm = AppTime.formatter("dd MMM")
        val r = chartRange()
        val title: String
        val sub: String
        when (filter) {
            "today" -> {
                title = when (chartOffset) {
                    0 -> getString(R.string.reports_nav_today)
                    1 -> getString(R.string.reports_nav_yesterday)
                    else -> AppTime.formatter("EEEE").format(parse(r!!.first))
                }
                sub = AppTime.formatter("EEE, dd MMM yyyy").format(parse(r!!.first))
            }
            "week" -> {
                title = when (chartOffset) {
                    0 -> getString(R.string.reports_week_label_this)
                    1 -> getString(R.string.reports_week_label_last)
                    else -> getString(R.string.scrap_weeks_ago, chartOffset)
                }
                sub = ddMmm.format(parse(r!!.first)) + " – " + ddMmm.format(parse(r.second))
            }
            "month" -> {
                title = when (chartOffset) {
                    0 -> getString(R.string.reports_nav_this_month)
                    1 -> getString(R.string.reports_nav_last_month)
                    else -> AppTime.formatter("MMMM yyyy").format(parse(r!!.first))
                }
                sub = ddMmm.format(parse(r!!.first)) + " – " + ddMmm.format(parse(r.second))
            }
            "custom" -> {
                title = getString(R.string.reports_nav_custom)
                sub = if (r != null) ddMmm.format(parse(r.first)) + " – " + ddMmm.format(parse(r.second)) else ""
            }
            else -> {
                title = getString(R.string.scrap_nav_all)
                val first = all.minOfOrNull { it.date }
                sub = if (first != null) ddMmm.format(parse(first)) + " – " + ddMmm.format(parse(today())) else ""
            }
        }
        root.findViewById<TextView>(R.id.tvScrapPeriodTitle).text = title
        root.findViewById<TextView>(R.id.tvScrapPeriodSub).text = sub
        val max = maxOffset()
        val prev = root.findViewById<TextView>(R.id.btnScrapPrev)
        val next = root.findViewById<TextView>(R.id.btnScrapNext)
        val olderOk = chartOffset < max
        val newerOk = chartOffset > 0
        prev.visibility = if (max == 0) View.INVISIBLE else View.VISIBLE
        next.visibility = if (max == 0) View.INVISIBLE else View.VISIBLE
        prev.alpha = if (olderOk) 1f else 0.3f; prev.isEnabled = olderOk
        next.alpha = if (newerOk) 1f else 0.3f; next.isEnabled = newerOk
    }

    private data class Bucket(val short: String, val full: String, val value: Double)

    private fun buckets(cur: Pair<String, String>?): List<Bucket> {
        val t = today()
        val dayShort = AppTime.formatter("d")
        val wd = AppTime.formatter("EEE")
        val full = AppTime.formatter("EEE, dd MMM")
        val mon = AppTime.formatter("MMM")
        val monFull = AppTime.formatter("MMMM yyyy")
        val r = chartRange()
        // One day (Today chip, or a custom range of a single day): hour by hour.
        if (r != null && (filter == "today" || (filter == "custom" && r.first == r.second))) {
            // One day, hour by hour (00:00 - 23:00).
            val perHour = DoubleArray(24)
            all.filter { it.date == r.first }.forEach { e ->
                val h = e.dateTime.getOrNull(11)?.let { e.dateTime.substring(11, 13).toIntOrNull() } ?: return@forEach
                if (h in 0..23) perHour[h] += e.loss
            }
            return (0..23).map { h ->
                Bucket(String.format(java.util.Locale.US, "%02d:00", h),
                    String.format(java.util.Locale.US, "%02d:00 – %02d:00", h, (h + 1) % 24), perHour[h])
            }
        }
        val byDay = all.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.loss } }

        val monthly = r == null || daysBetween(r.first, r.second) > 62
        if (!monthly && r != null) {
            val n = daysBetween(r.first, r.second)
            return (0 until n).map { i ->
                val d = addDays(r.first, i)
                val dt = parse(d)
                Bucket(if (n <= 7) wd.format(dt) else dayShort.format(dt), full.format(dt), byDay[d] ?: 0.0)
            }
        }
        // monthly: from the first entry (or range start) to now, at most 24 months
        val endCal = AppTime.calendar().apply { if (r != null) time = parse(r.second); set(Calendar.DAY_OF_MONTH, 1) }
        val firstDate = r?.first ?: (all.minOfOrNull { it.date } ?: t)
        val startCal = AppTime.calendar().apply { time = parse(firstDate); set(Calendar.DAY_OF_MONTH, 1) }
        while (monthsBetween(startCal, endCal) > 23) startCal.add(Calendar.MONTH, 1)
        val out = ArrayList<Bucket>()
        val c = startCal.clone() as Calendar
        while (!c.after(endCal)) {
            val key = AppTime.formatter("yyyy-MM").format(c.time)
            out.add(Bucket(mon.format(c.time), monFull.format(c.time),
                all.filter { it.date.startsWith(key) }.sumOf { it.loss }))
            c.add(Calendar.MONTH, 1)
        }
        return out
    }

    private fun monthsBetween(a: Calendar, b: Calendar) =
        (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH))

    private fun drawChart(chart: BarChart, cur: Pair<String, String>?) {
        view?.let { updateChartNav(it) }
        val pts = buckets(cur)
        if (pts.isEmpty()) return
        val max = pts.maxOf { it.value }.toFloat().takeIf { it > 0f } ?: 1f
        val best = pts.indexOfFirst { it.value.toFloat() == max }
        val entries = pts.mapIndexed { i, p -> BarEntry(i.toFloat(), p.value.toFloat(), Pair(p.full, 0)) }
        val ds = BarDataSet(entries, "").apply {
            colors = pts.indices.map { if (it == best && max > 0f) red else Color.parseColor("#E5B8B8") }
            setDrawValues(false)
            highLightAlpha = 0
        }
        chart.data = BarData(ds).apply { barWidth = if (pts.size > 14) 0.7f else 0.6f }
        chart.axisLeft.axisMaximum = max * 1.30f
        chart.axisRight.axisMaximum = max * 1.30f
        chart.xAxis.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.roundToInt()
                    val p = pts.getOrNull(i) ?: return ""
                    // Labels thin out when zoomed out and fill in as you zoom in.
                    val visible = (chart.highestVisibleX - chart.lowestVisibleX + 1f).roundToInt()
                        .coerceIn(1, pts.size.coerceAtLeast(1))
                    val step = ceil(visible / 7.0).toInt().coerceAtLeast(1)
                    return if (i % step == 0) p.short else ""
                }
            }
            textSize = if (pts.size > 7) 9.5f else 11f
            setLabelCount(pts.size, false)
        }
        chart.fitScreen()     // new period -> back to the full view
        chart.setFitBars(true)
        chart.renderer = RoundedBarChartRenderer(chart, chart.animator, chart.viewPortHandler, 4f)
        chart.animateY(500)
        chart.invalidate()
    }

    private fun setupChartChrome(chart: BarChart) {
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            // Zoom along time only (pinch, double-tap), drag to move when zoomed in.
            setScaleXEnabled(true)
            setScaleYEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = true
            isDragEnabled = true
            isDragXEnabled = true
            isDragYEnabled = false
            isHighlightPerDragEnabled = false
            setDrawGridBackground(false)
            setTouchEnabled(true)
            isHighlightPerTapEnabled = true
            setNoDataText("")
            marker = BarBubbleMarker(requireContext())
            setExtraOffsets(4f, 18f, 4f, 4f)
            axisRight.isEnabled = false
            axisLeft.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisRight.axisMinimum = 0f
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(true)
                axisLineColor = Color.parseColor("#E4DCC8")
                granularity = 1f
                textColor = Color.parseColor("#9A8F79")
                yOffset = 6f
            }
        }
    }
}
