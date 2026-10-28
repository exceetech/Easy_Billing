package com.example.easy_billing

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.ui.MoneyViews
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.MoneyStatementData
import com.example.easy_billing.util.StmtRow
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Money statement for any date or range, built like the Reports page: transparent top bar with the back
 * arrow, eyebrow + title, date pill, date chips, then white cards with green / champagne headers
 * (Summary, Entries, Still to settle) and the same buttons as the other screens.
 * The numbers come from [MoneyStatementData], the same source as the Excel / PDF / print statement.
 */
class MoneyStatementActivity : BaseActivity() {

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        private const val PAGE = 200
        private val CHIP_PALETTE = listOf(
            "#0F6E56", "#B23A3A", "#8A6526", "#185FA5", "#534AB7", "#D85A30", "#3B6D11", "#993556"
        )
    }

    private enum class Range { TODAY, WEEK, MONTH, YEAR, CUSTOM }

    private var startIso = ""
    private var endIso = ""
    private var range = Range.CUSTOM
    private lateinit var tvPill: TextView
    private lateinit var cardsHost: LinearLayout
    private lateinit var btnShare: MaterialButton
    private val chips = LinkedHashMap<Range, Chip>()
    private var data: MoneyStatementData? = null
    private var shown = PAGE
    private var entriesBody: LinearLayout? = null
    private var lastDay = ""

    // Same palette as the rest of the app
    private val INK = "#1A1A18"; private val MUTED = "#7C8782"; private val SOFT = "#5B6660"
    private val GREEN = "#0F6E56"; private val RED = "#791F1F"; private val AMBER = "#8A6526"
    private val LINE = "#E3E8E4"; private val MINT = "#E3F3EE"; private val MINT_INK = "#123B31"

    private fun dp(v: Int) = MoneyViews.dp(this, v)
    private fun font(res: Int): Typeface? = try { ResourcesCompat.getFont(this, res) } catch (_: Exception) { null }

    private fun tv(s: CharSequence, sp: Float, color: String, semi: Boolean = false, serif: Boolean = false, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; textSize = sp; setTextColor(Color.parseColor(color)); includeFontPadding = false
            if (serif) typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            else if (semi) font(R.font.googlesans_semibold)?.let { typeface = it }
            else if (bold) font(R.font.googlesans_bold)?.let { typeface = it }
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        if (weight > 0f) LinearLayout.LayoutParams(w, h, weight) else LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val today = AppTime.todayIso()
        startIso = intent.getStringExtra(EXTRA_START) ?: today
        endIso = intent.getStringExtra(EXTRA_END) ?: today
        range = matchRange()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_lg_page)
        }
        val toolbar = MaterialToolbar(this).apply {
            id = R.id.toolbar
            elevation = 0f
            setBackgroundColor(Color.TRANSPARENT)
            setTitleTextColor(Color.parseColor(INK))
            setNavigationIconTint(Color.parseColor(INK))
        }
        root.addView(toolbar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val scroll = NestedScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setupToolbar(R.id.toolbar)

        // Header: eyebrow, bold title + serif accent, date pill
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), 0, dp(16), dp(10))
            addView(LinearLayout(this@MoneyStatementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(tv(getString(R.string.mstmt_page_eyebrow), 10f, "#B8895A", semi = true).apply { letterSpacing = 0.16f })
                addView(LinearLayout(this@MoneyStatementActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
                    addView(tv(getString(R.string.mstmt_page_title_bold), 21f, INK, bold = true).apply { letterSpacing = -0.02f; setTypeface(typeface, Typeface.BOLD) })
                    addView(tv(getString(R.string.mstmt_page_title_accent), 21f, GREEN).apply {
                        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                        layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(6) }
                    })
                })
            })
            tvPill = tv("", 11.5f, "#16A34A", semi = true).apply {
                setBackgroundResource(R.drawable.bg_growth_chip)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(8) }
            }
            addView(tvPill)
        })

        // Date chips (same as the Reports page)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(dp(14), dp(16), dp(14), dp(4))
            }
            val items = listOf(
                Range.TODAY to R.string.xml_reports_filter_today, Range.WEEK to R.string.xml_reports_filter_week,
                Range.MONTH to R.string.xml_reports_filter_month, Range.YEAR to R.string.xml_reports_filter_year,
                Range.CUSTOM to R.string.xml_reports_filter_custom
            )
            items.forEachIndexed { i, (r, label) ->
                val c = makeChip(getString(label), i == items.lastIndex)
                c.setOnClickListener { onChip(r) }
                chips[r] = c
                addView(c)
            }
        })

        cardsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        content.addView(cardsHost, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Buttons: green main, white outlined second
        btnShare = MaterialButton(this).apply {
            text = getString(R.string.mstmt_btn_share_full)
            setTextColor(Color.WHITE); isAllCaps = false; textSize = 13.5f
            font(R.font.googlesans_semibold)?.let { typeface = it }
            includeFontPadding = false
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_lg_cta_green_12)
            insetTop = 0; insetBottom = 0
            setOnClickListener { showShareSheet() }
        }
        val btnAdd = MaterialButton(this).apply {
            text = getString(R.string.money_add_entry)
            setTextColor(Color.parseColor(AMBER)); isAllCaps = false; textSize = 13f
            font(R.font.googlesans_semibold)?.let { typeface = it }
            includeFontPadding = false
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_money_btn_outline)
            insetTop = 0; insetBottom = 0
            setOnClickListener { com.example.easy_billing.util.ManualEntryDialog.show(this@MoneyStatementActivity) { load() } }
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(24))
            addView(btnShare, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(btnAdd, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).also { it.topMargin = dp(8) })
        })

        load()
    }

    // ───────────────────────── period and chips ─────────────────────────

    private fun rangeFor(r: Range): Pair<String, String>? {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = AppTime.calendar()
        iso.timeZone = cal.timeZone
        return when (r) {
            Range.TODAY -> iso.format(cal.time).let { it to it }
            Range.WEEK -> {
                cal.add(Calendar.DAY_OF_MONTH, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                val s = iso.format(cal.time); cal.add(Calendar.DAY_OF_MONTH, 6); s to iso.format(cal.time)
            }
            Range.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1); val s = iso.format(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH)); s to iso.format(cal.time)
            }
            Range.YEAR -> {
                cal.set(Calendar.MONTH, Calendar.JANUARY); cal.set(Calendar.DAY_OF_MONTH, 1); val s = iso.format(cal.time)
                cal.set(Calendar.MONTH, Calendar.DECEMBER); cal.set(Calendar.DAY_OF_MONTH, 31); s to iso.format(cal.time)
            }
            Range.CUSTOM -> null
        }
    }

    /** The chip that matches the period we were opened with (the Reports filter); anything else is "Custom". */
    private fun matchRange(): Range =
        listOf(Range.TODAY, Range.WEEK, Range.MONTH, Range.YEAR).firstOrNull { rangeFor(it) == (startIso to endIso) } ?: Range.CUSTOM

    private fun chipAccent(label: CharSequence?): Int =
        Color.parseColor(CHIP_PALETTE[((label?.toString() ?: "").hashCode() and 0x7FFFFFFF) % CHIP_PALETTE.size])

    private fun styleChip(chip: Chip, selected: Boolean) {
        chip.chipBackgroundColor = ColorStateList.valueOf(Color.WHITE)
        if (selected) {
            val accent = chipAccent(chip.text)
            chip.chipStrokeColor = ColorStateList.valueOf(accent)
            chip.setTextColor(accent)
            chip.setTypeface(null, Typeface.BOLD)
        } else {
            chip.chipStrokeColor = ColorStateList.valueOf(Color.parseColor("#D5DDD8"))
            chip.setTextColor(Color.parseColor("#5B6660"))
            chip.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun makeChip(label: String, last: Boolean): Chip = Chip(this).apply {
        text = label
        isCheckable = false
        isCheckedIconVisible = false; isChipIconVisible = false
        chipStrokeWidth = dp(1).toFloat() * 1.5f
        chipCornerRadius = dp(8).toFloat()
        chipStartPadding = 0f; chipEndPadding = 0f; textStartPadding = 0f; textEndPadding = 0f
        setEnsureMinTouchTargetSize(false); chipMinHeight = dp(30).toFloat()
        gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        textSize = 11f
        layoutParams = lp(0, dp(30), 1f).also { if (!last) it.marginEnd = dp(6) }
    }

    private fun selectChips() { chips.forEach { (r, c) -> styleChip(c, r == range) } }

    private fun onChip(r: Range) {
        if (r == Range.CUSTOM) { pickDates(); return }
        val pair = rangeFor(r) ?: return
        range = r; startIso = pair.first; endIso = pair.second
        load()
    }

    private fun periodText(): String {
        val pretty = AppTime.formatter("dd MMM yyyy")
        val iso = AppTime.isoDate()
        val a = pretty.format(iso.parse(startIso)!!)
        val b = pretty.format(iso.parse(endIso)!!)
        return if (startIso == endIso) a else "$a – $b"
    }

    private fun pillText(): String {
        val iso = AppTime.isoDate()
        val s = iso.parse(startIso)!!; val e = iso.parse(endIso)!!
        if (startIso == endIso) return AppTime.formatter("dd MMM yyyy").format(s)
        val sameYear = AppTime.formatter("yyyy").format(s) == AppTime.formatter("yyyy").format(e)
        val a = AppTime.formatter(if (sameYear) "dd MMM" else "dd MMM yyyy").format(s)
        return a + " – " + AppTime.formatter("dd MMM yyyy").format(e)
    }

    private fun pickDates() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.money_stmt_pick)).build()
        picker.show(supportFragmentManager, "STMT_RANGE")
        picker.addOnPositiveButtonClickListener {
            // The picker returns UTC midnight; format in UTC so the day does not shift.
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            startIso = f.format(Date(it.first)); endIso = f.format(Date(it.second))
            range = matchRange()
            load()
        }
    }

    // ───────────────────────── loading and drawing ─────────────────────────

    private fun load() {
        tvPill.text = pillText()
        selectChips()
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { MoneyStatementData.load(this@MoneyStatementActivity, startIso, endIso) }
            data = d
            shown = PAGE
            render(d)
        }
    }

    private fun money(v: Double) = CurrencyHelper.formatNoSymbol(this, v)

    private fun render(d: MoneyStatementData) {
        cardsHost.removeAllViews()

        // ── Summary ──
        val sum = card(true, R.drawable.ic_lc_indian_rupee, getString(R.string.mstmt_sheet_summary), getString(R.string.mstmt_card_summary_sub))
        sum.second.addView(summaryRow("", getString(R.string.money_col_cash), getString(R.string.money_col_bank), getString(R.string.money_col_total), head = true))
        sum.second.addView(summaryRow(getString(R.string.money_stmt_opening), money(d.opening.cashNet), money(d.opening.bankNet), money(d.opening.net)))
        sum.second.addView(summaryRow(getString(R.string.money_row_in), money(d.earnings.inCash), money(d.earnings.inBank), money(d.earnings.inTotal), color = GREEN))
        sum.second.addView(summaryRow(getString(R.string.money_row_out), money(d.earnings.outCash), money(d.earnings.outBank), money(d.earnings.outTotal), color = RED))
        if (kotlin.math.abs(d.openingAdded.cashNet) > 0.004 || kotlin.math.abs(d.openingAdded.bankNet) > 0.004)
            sum.second.addView(summaryRow(getString(R.string.money_reason_opening), money(d.openingAdded.cashNet), money(d.openingAdded.bankNet), money(d.openingAdded.net)))
        sum.second.addView(summaryRow(getString(R.string.money_stmt_closing), money(d.closing.cashNet), money(d.closing.bankNet), money(d.closing.net),
            close = true, neg = d.closing.net < -0.004))
        cardsHost.addView(sum.first)

        // ── Entries ──
        val ent = card(false, R.drawable.ic_lc_file_text, getString(R.string.mstmt_card_entries), getString(R.string.mstmt_card_entries_sub),
            tag = getString(R.string.mstmt_entries_count, d.rows.size))
        entriesBody = ent.second
        lastDay = ""
        if (d.rows.isEmpty()) {
            ent.second.addView(tv(getString(R.string.money_stmt_empty), 13f, MUTED).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(22), dp(16), dp(22))
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } else addEntries(d, 0, minOf(shown, d.rows.size))
        cardsHost.addView(ent.first)

        // ── Still to settle ──
        val set = card(true, R.drawable.ic_lc_clock, getString(R.string.mstmt_to_settle), getString(R.string.mstmt_card_settle_sub))
        fun settleRow(kind: Int, label: String, color: String, icBg: Int, icon: Int, tint: String) {
            val items = d.settle.filter { it.kind == kind }
            val total = round2Money(items.sumOf { it.amount })
            set.second.addView(simpleRow(
                label, if (items.isEmpty()) getString(R.string.mstmt_none) else getString(R.string.mstmt_settle_count, items.size),
                money(total), color, icBg, icon, tint, first = kind == 0
            ))
        }
        settleRow(0, getString(R.string.mstmt_upi_waiting), AMBER, R.drawable.bg_money_ic_wait, R.drawable.ic_lc_clock, AMBER)
        settleRow(1, getString(R.string.mstmt_to_give), RED, R.drawable.bg_money_ic_out, R.drawable.ic_lc_arrow_up_right, RED)
        settleRow(2, getString(R.string.mstmt_to_receive), GREEN, R.drawable.bg_money_ic_in, R.drawable.ic_lc_arrow_down_left, GREEN)
        cardsHost.addView(set.first)
    }

    private fun round2Money(v: Double) = com.example.easy_billing.util.PaymentSplit.round2(v)

    /** A white card with a green or champagne header (ring icon, title, subtitle, optional tag). Returns card + body. */
    private fun card(green: Boolean, icon: Int, title: String, sub: String, tag: String? = null): Pair<LinearLayout, LinearLayout> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_lg_card)
            clipToOutline = true // header band stays inside the rounded corners on every Android version
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(14) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (green) R.drawable.bg_lg_hdr_green else R.drawable.bg_lg_hdr_amber)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(FrameLayout(this@MoneyStatementActivity).apply {
                layoutParams = lp(dp(28), dp(28))
                addView(View(this@MoneyStatementActivity).apply {
                    setBackgroundResource(if (green) R.drawable.bg_step_circle_ring else R.drawable.bg_step_circle_ring_champagne)
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(28))
                })
                addView(ImageView(this@MoneyStatementActivity).apply {
                    setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = null
                    layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
                })
            })
            addView(LinearLayout(this@MoneyStatementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10) }
                addView(tv(title, 15f, if (green) MINT_INK else "#4A3A16", semi = true))
                addView(tv(sub, 11f, if (green) GREEN else AMBER).apply {
                    layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
                })
            })
            if (tag != null) addView(tv(tag, 10.5f, AMBER).apply {
                setBackgroundResource(R.drawable.bg_money_pill)
                setPadding(dp(10), dp(3), dp(10), dp(3))
            })
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(head); card.addView(body)
        return card to body
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor(LINE))
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    /** One row of the Summary table: label | cash | bank | total. */
    private fun summaryRow(
        label: String, cash: String, bank: String, total: String,
        head: Boolean = false, color: String = INK, close: Boolean = false, neg: Boolean = false
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ink = if (close) (if (neg) RED else MINT_INK) else if (head) MUTED else color
        val sp = if (head) 10.5f else 12.5f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(if (head) 12 else 9), dp(16), dp(if (head) 6 else 9))
            if (close) setBackgroundColor(Color.parseColor(if (neg) "#FBEDED" else MINT))
        }
        fun cell(s: String, w: Float, end: Boolean, strong: Boolean, c: String) = tv(s, sp, c, semi = strong || head || close).apply {
            gravity = if (end) Gravity.END else Gravity.START
            layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
            maxLines = 1
        }
        row.addView(cell(label, 1.45f, false, false, if (head) MUTED else if (close) ink else INK))
        row.addView(cell(cash, 1f, true, false, ink))
        row.addView(cell(bank, 1f, true, false, ink))
        row.addView(cell(total, 1.1f, true, true, ink))
        if (!head) box.addView(divider())
        box.addView(row)
        return box
    }

    private fun addEntries(d: MoneyStatementData, from: Int, to: Int) {
        val body = entriesBody ?: return
        // drop the old "show more" button, if any
        body.findViewWithTag<View>("more")?.let { body.removeView(it) }
        val dayFmt = AppTime.formatter("dd MMM yyyy")
        for (i in from until to) {
            val r = d.rows[i]
            val day = dayFmt.format(Date(r.at)).uppercase()
            var afterHeader = false
            if (day != lastDay) {
                lastDay = day
                afterHeader = true
                body.addView(tv(day, 11f, MUTED, semi = true).apply {
                    letterSpacing = 0.06f
                    setPadding(dp(16), dp(12), dp(16), dp(4))
                })
            }
            body.addView(entryRow(r, first = afterHeader))
        }
        if (to < d.rows.size) {
            val more = minOf(PAGE, d.rows.size - to)
            body.addView(tv(getString(R.string.mstmt_show_more, more), 12.5f, GREEN, semi = true).apply {
                tag = "more"; gravity = Gravity.CENTER
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener { shown = to + more; addEntries(d, to, to + more) }
            })
        }
    }

    private fun entryRow(r: StmtRow, first: Boolean): View {
        val isIn = r.moneyIn > 0.0
        val tint = if (r.waiting) AMBER else if (isIn) GREEN else RED
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        row.addView(FrameLayout(this).apply {
            setBackgroundResource(if (r.waiting) R.drawable.bg_money_ic_wait else if (isIn) R.drawable.bg_money_ic_in else R.drawable.bg_money_ic_out)
            layoutParams = lp(dp(30), dp(30))
            addView(ImageView(this@MoneyStatementActivity).apply {
                setImageResource(if (r.waiting) R.drawable.ic_lc_clock else if (isIn) R.drawable.ic_lc_arrow_down_left else R.drawable.ic_lc_arrow_up_right)
                setColorFilter(Color.parseColor(tint)); contentDescription = null
                layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
            })
        })
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10); it.marginEnd = dp(8) }
        }
        mid.addView(tv(r.type + (if (r.ref.isNotBlank()) " · ${r.ref}" else ""), 13f, if (r.waiting) AMBER else INK, semi = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val time = AppTime.formatter("hh:mm a").format(Date(r.at))
        val detail = listOf(r.party, if (r.waiting) getString(R.string.money_stmt_pending) else "", time).filter { it.isNotBlank() }.joinToString(" · ")
        mid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(3) }
            addView(tv(r.mode, 10f, SOFT).apply {
                setBackgroundResource(R.drawable.bg_money_chip)
                setPadding(dp(7), dp(1), dp(7), dp(1))
                layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(5) }
            })
            addView(tv(detail, 11f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        })
        row.addView(mid)
        val amt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val value = if (isIn) r.moneyIn else r.moneyOut
        amt.addView(tv((if (isIn) "+" else "-") + money(value), 14.5f, tint, serif = true).apply { maxLines = 1; gravity = Gravity.END })
        amt.addView(tv(if (r.waiting) getString(R.string.mstmt_not_counted) else getString(R.string.mstmt_bal, money(r.totalBal)), 10f, MUTED).apply {
            gravity = Gravity.END
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
        })
        row.addView(amt)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!first) box.addView(divider())
        box.addView(row)
        r.reverseSid?.let { sid ->
            box.isClickable = true; box.isFocusable = true
            box.setOnClickListener {
                com.example.easy_billing.util.ManualEntryDialog.confirmReverse(this, sid) { load() }
            }
        }
        return box
    }

    /** Icon + title/subtitle on the left, one amount on the right (Still to settle card). */
    private fun simpleRow(title: String, sub: String, amount: String, color: String, icBg: Int, icon: Int, tint: String, first: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!first) box.addView(divider())
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(FrameLayout(this@MoneyStatementActivity).apply {
                setBackgroundResource(icBg); layoutParams = lp(dp(30), dp(30))
                addView(ImageView(this@MoneyStatementActivity).apply {
                    setImageResource(icon); setColorFilter(Color.parseColor(tint)); contentDescription = null
                    layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                })
            })
            addView(LinearLayout(this@MoneyStatementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10); it.marginEnd = dp(8) }
                addView(tv(title, 13f, INK, semi = true))
                addView(tv(sub, 11f, MUTED).apply {
                    layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
                })
            })
            addView(tv(amount, 14.5f, color, serif = true).apply { maxLines = 1; gravity = Gravity.END })
        })
        return box
    }

    /** Share / print the FULL statement (every row, running balances, still-to-settle), not only what is on screen. */
    private fun showShareSheet() {
        val view = layoutInflater.inflate(R.layout.dialog_share_statement, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<TextView>(R.id.tvPeriod).text = periodText()
        fun pick(id: Int, kind: Export) = view.findViewById<View>(id).setOnClickListener { dialog.dismiss(); exportStatement(kind) }
        pick(R.id.optExcel, Export.EXCEL)
        pick(R.id.optPdf, Export.PDF)
        pick(R.id.optPrint, Export.PRINT)
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private enum class Export { EXCEL, PDF, PRINT }

    private fun exportStatement(kind: Export) {
        btnShare.isEnabled = false
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val data = com.example.easy_billing.util.MoneyStatementData.load(this@MoneyStatementActivity, startIso, endIso)
                    val shop = com.example.easy_billing.util.MoneyStatementExport.shop(this@MoneyStatementActivity)
                    if (kind == Export.EXCEL) com.example.easy_billing.util.MoneyStatementExport.xlsx(this@MoneyStatementActivity, data, shop)
                    else com.example.easy_billing.util.MoneyStatementExport.pdf(this@MoneyStatementActivity, data, shop)
                }
                if (kind == Export.PRINT) {
                    val pm = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                    val job = file.nameWithoutExtension
                    pm.print(job, com.example.easy_billing.util.MoneyStatementExport.PdfFilePrintAdapter(file, job),
                        android.print.PrintAttributes.Builder()
                            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4.asLandscape()).build())
                } else {
                    val uri = FileProvider.getUriForFile(this@MoneyStatementActivity, "$packageName.provider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = if (kind == Export.EXCEL) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(this@MoneyStatementActivity, R.string.money_stmt_failed, Toast.LENGTH_LONG).show()
            } finally {
                btnShare.isEnabled = true
            }
        }
    }
}
