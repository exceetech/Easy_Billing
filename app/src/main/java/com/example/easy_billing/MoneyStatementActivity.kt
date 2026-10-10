package com.example.easy_billing

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.ui.MoneyViews
import com.example.easy_billing.util.AppTime
import com.example.easy_billing.util.MoneyDirection
import com.example.easy_billing.util.MoneyReport
import com.example.easy_billing.util.MoneyStatementPdf
import com.example.easy_billing.util.MoneyStatus
import com.example.easy_billing.util.PaymentLinesSheet
import com.example.easy_billing.util.StatementLine
import com.example.easy_billing.util.StatementSummary
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.TimeZone

/**
 * Money statement for any date or range: opening balance, money in and out (cash and bank),
 * closing balance, and every movement in between. Can be shared as a PDF.
 */
class MoneyStatementActivity : BaseActivity() {

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
    }

    private var startIso = ""
    private var endIso = ""
    private lateinit var tvPeriod: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: LineAdapter
    private lateinit var btnShare: MaterialButton
    private var summary: StatementSummary? = null
    private var lines: List<StatementLine> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val today = AppTime.todayIso()
        startIso = intent.getStringExtra(EXTRA_START) ?: today
        endIso = intent.getStringExtra(EXTRA_END) ?: today

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F0E6"))
        }
        val toolbar = MaterialToolbar(this).apply {
            id = R.id.toolbar
            setBackgroundColor(Color.TRANSPARENT)
            setTitleTextColor(Color.parseColor("#1A1A18"))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, MoneyViews.dp(this, 56)))
        setContentView(root)
        setupToolbar(R.id.toolbar)

        val pad = MoneyViews.dp(this, 16)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, MoneyViews.dp(this@MoneyStatementActivity, 8))
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBox.addView(MoneyViews.text(this, getString(R.string.money_statement_title), 20f, MoneyViews.INK, true))
        tvPeriod = MoneyViews.text(this, "", 12f, MoneyViews.MUTED)
        titleBox.addView(tvPeriod)
        head.addView(titleBox)
        head.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.money_stmt_change)
            setOnClickListener { pickDates() }
        })
        root.addView(head)

        summaryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_addp_card)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(summaryBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(pad, 0, pad, MoneyViews.dp(this, 8))
        })

        tvEmpty = MoneyViews.text(this, getString(R.string.money_stmt_empty), 13f, MoneyViews.MUTED, gravity = Gravity.CENTER)
        tvEmpty.setPadding(pad, pad, pad, pad)
        root.addView(tvEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        adapter = LineAdapter { sid ->
            com.example.easy_billing.util.ManualEntryDialog.confirmReverse(this, sid) { load() }
        }
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MoneyStatementActivity)
            this.adapter = this@MoneyStatementActivity.adapter
            clipToPadding = false
            setPadding(pad, 0, pad, 0)
        }
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btnAdd = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.money_add_entry)
            setOnClickListener { com.example.easy_billing.util.ManualEntryDialog.show(this@MoneyStatementActivity) { load() } }
        }
        root.addView(btnAdd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(pad, MoneyViews.dp(this, 8), pad, 0)
        })
        btnShare = MaterialButton(this).apply {
            text = getString(R.string.money_stmt_share)
            setOnClickListener { sharePdf() }
        }
        root.addView(btnShare, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(pad, MoneyViews.dp(this, 8), pad, pad)
        })

        load()
    }

    private fun periodText(): String {
        val pretty = AppTime.formatter("dd MMM yyyy")
        val iso = AppTime.isoDate()
        val a = pretty.format(iso.parse(startIso)!!)
        val b = pretty.format(iso.parse(endIso)!!)
        return if (startIso == endIso) a else "$a – $b"
    }

    private fun pickDates() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.money_stmt_pick)).build()
        picker.show(supportFragmentManager, "STMT_RANGE")
        picker.addOnPositiveButtonClickListener {
            // The picker returns UTC midnight; format in UTC so the day does not shift.
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            startIso = f.format(Date(it.first)); endIso = f.format(Date(it.second))
            load()
        }
    }

    private fun load() {
        tvPeriod.text = periodText()
        lifecycleScope.launch {
            val ctx = this@MoneyStatementActivity
            val (sum, rows) = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(ctx).moneyEntryDao()
                val (from, to) = MoneyReport.dayBounds(startIso, endIso)
                val opening = MoneyReport.totals(dao.sumsBetween(0L, from, emptyList()))
                val within = MoneyReport.totals(dao.sumsBetween(from, to, emptyList()))
                val entries = dao.between(from, to).filter { it.status != MoneyStatus.CANCELLED }
                val fmt = AppTime.formatter("dd MMM, hh:mm a")
                val out = entries.map { e ->
                    val manual = e.sourceType == com.example.easy_billing.util.MoneySource.MANUAL
                    val ref = if (e.refText.isNotBlank()) " · ${e.refText}" else ""
                    val title = if (manual && e.refText.isNotBlank()) e.refText else MoneyViews.reasonLabel(ctx, e.reason) + ref
                    val canReverse = manual && e.status == MoneyStatus.RECEIVED &&
                        !e.refText.startsWith(com.example.easy_billing.repository.MoneyBook.REVERSAL_PREFIX)
                    val detail = listOf(PaymentLinesSheet.modeLabel(ctx, e.mode), e.partyName)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    StatementLine(
                        fmt.format(Date(e.createdAt)),
                        title,
                        detail,
                        if (e.direction == MoneyDirection.IN) e.amount else -e.amount,
                        e.status == MoneyStatus.PENDING,
                        if (canReverse) e.sourceId else null
                    )
                }
                StatementSummary(opening, within) to out
            }
            summary = sum; lines = rows
            bindSummary(sum)
            adapter.submit(rows)
            tvEmpty.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun bindSummary(s: StatementSummary) {
        summaryBox.removeAllViews()
        fun m(v: Double) = MoneyViews.money(this, v)
        summaryBox.addView(MoneyViews.tableRow(this, "", getString(R.string.money_col_cash),
            getString(R.string.money_col_bank), getString(R.string.money_col_total), header = true))
        summaryBox.addView(MoneyViews.tableRow(this, getString(R.string.money_stmt_opening),
            m(s.opening.cashNet), m(s.opening.bankNet), m(s.opening.net)))
        summaryBox.addView(MoneyViews.tableRow(this, getString(R.string.money_row_in),
            m(s.within.inCash), m(s.within.inBank), m(s.within.inTotal), color = MoneyViews.GREEN))
        summaryBox.addView(MoneyViews.tableRow(this, getString(R.string.money_row_out),
            m(s.within.outCash), m(s.within.outBank), m(s.within.outTotal), color = MoneyViews.RED))
        summaryBox.addView(MoneyViews.divider(this))
        val c = s.closing
        summaryBox.addView(MoneyViews.tableRow(this, getString(R.string.money_stmt_closing),
            m(c.cashNet), m(c.bankNet), m(c.net), bold = true))
    }

    private fun sharePdf() {
        val s = summary ?: return
        btnShare.isEnabled = false
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    MoneyStatementPdf.generate(
                        this@MoneyStatementActivity, periodText(), s, lines,
                        MoneyStatementPdf.Labels(
                            title = getString(R.string.money_statement_title),
                            cash = getString(R.string.money_col_cash), bank = getString(R.string.money_col_bank),
                            total = getString(R.string.money_col_total),
                            opening = getString(R.string.money_stmt_opening), moneyIn = getString(R.string.money_row_in),
                            moneyOut = getString(R.string.money_row_out), closing = getString(R.string.money_stmt_closing),
                            empty = getString(R.string.money_stmt_empty), pending = getString(R.string.money_stmt_pending)
                        )
                    )
                }
                val uri = FileProvider.getUriForFile(this@MoneyStatementActivity, "$packageName.provider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(this@MoneyStatementActivity, R.string.money_stmt_failed, Toast.LENGTH_LONG).show()
            } finally {
                btnShare.isEnabled = true
            }
        }
    }

    private class LineAdapter(private val onReverse: (Int) -> Unit) : RecyclerView.Adapter<LineAdapter.VH>() {
        private var items: List<StatementLine> = emptyList()
        class VH(val box: LinearLayout, val title: TextView, val detail: TextView, val amount: TextView) :
            RecyclerView.ViewHolder(box)

        fun submit(l: List<StatementLine>) { items = l; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, MoneyViews.dp(ctx, 10), 0, MoneyViews.dp(ctx, 10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = MoneyViews.text(ctx, "", 14f, MoneyViews.INK, true)
            val detail = MoneyViews.text(ctx, "", 11f, MoneyViews.MUTED)
            left.addView(title); left.addView(detail)
            val amount = MoneyViews.text(ctx, "", 15f, MoneyViews.INK, true, Gravity.END)
            box.addView(left); box.addView(amount)
            return VH(box, title, detail, amount)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val l = items[position]
            val ctx = h.box.context
            h.title.text = l.title
            val pend = if (l.pending) " · " + ctx.getString(R.string.money_stmt_pending) else ""
            h.detail.text = l.whenText + (if (l.detail.isNotBlank()) " · ${l.detail}" else "") + pend
            h.amount.text = (if (l.signed >= 0) "+" else "-") + MoneyViews.money(ctx, kotlin.math.abs(l.signed))
            h.box.setOnClickListener(l.reverseSid?.let { sid -> android.view.View.OnClickListener { onReverse(sid) } })
            h.box.isClickable = l.reverseSid != null
            h.amount.setTextColor(Color.parseColor(
                if (l.pending) MoneyViews.MUTED else if (l.signed >= 0) MoneyViews.GREEN else MoneyViews.RED))
        }
    }
}
