package com.example.easy_billing.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper
import kotlin.math.roundToInt

/**
 * Ranked breakdown list for the Sales trend card: best period first, the top
 * three get gold / silver / bronze numbers, every row shows its bills and its
 * share of total sales. Shows the first [COLLAPSED_COUNT] rows until expanded.
 */
class RankedRowAdapter : RecyclerView.Adapter<RankedRowAdapter.VH>() {

    companion object { const val COLLAPSED_COUNT = 5 }

    private var all: List<SalesRow> = emptyList()
    private var total = 0.0
    private var maxRev = 0.0
    var expanded = false
        private set

    val totalCount get() = all.size
    val canExpand get() = all.size > COLLAPSED_COUNT

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val divider: View = view.findViewById(R.id.vRankDivider)
        val rank: TextView = view.findViewById(R.id.tvRank)
        val label: TextView = view.findViewById(R.id.tvRankLabel)
        val sub: TextView = view.findViewById(R.id.tvRankSub)
        val amount: TextView = view.findViewById(R.id.tvRankAmount)
        val barTrack: View = view.findViewById(R.id.rankBarTrack)
        val barFill: View = view.findViewById(R.id.vRankBarFill)
        val barSpacer: View = view.findViewById(R.id.vRankBarSpacer)
    }

    /** [rows] must already be sorted best-first. */
    fun updateData(rows: List<SalesRow>) {
        all = rows
        total = rows.sumOf { it.revenue }
        maxRev = rows.maxOfOrNull { it.revenue } ?: 0.0
        if (!canExpand) expanded = false
        notifyDataSetChanged()
    }

    fun toggleExpanded() {
        expanded = !expanded
        notifyDataSetChanged()
    }

    /** Round medal: gradient disc, darker rim and a light inner ring, number inside. */
    private fun medal(tv: TextView, top: String, bottom: String, rim: String, inner: String, text: String, d: Float) {
        val outer = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(top), Color.parseColor(bottom))).apply {
            shape = GradientDrawable.OVAL
            setStroke((2 * d).toInt().coerceAtLeast(1), Color.parseColor(rim))
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((1 * d).toInt().coerceAtLeast(1), Color.parseColor(inner))
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(outer, ring))
        val inset = (4 * d).toInt()
        layers.setLayerInset(1, inset, inset, inset, inset)
        tv.background = layers
        tv.setTextColor(Color.parseColor(text))
    }

    override fun getItemCount() = if (expanded) all.size else minOf(all.size, COLLAPSED_COUNT)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_ranked_row, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.itemView.context
        val row = all[position]

        h.divider.visibility = if (position == 0) View.GONE else View.VISIBLE
        h.rank.text = (position + 1).toString()

        val d = ctx.resources.displayMetrics.density
        when (position) {
            0 -> medal(h.rank, "#FFE08A", "#E0A21F", "#C98A12", "#FFF3C4", "#5A3B05", d)
            1 -> medal(h.rank, "#F4F6F8", "#B3BCC4", "#98A2AA", "#FFFFFF", "#3F454A", d)
            2 -> medal(h.rank, "#F2CBA2", "#C07638", "#A5612A", "#F9E1C8", "#5C3210", d)
            else -> {
                h.rank.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F3ECDD")) }
                h.rank.setTextColor(Color.parseColor("#8A6526"))
            }
        }

        h.label.text = row.label
        val share = if (total > 0) (row.revenue / total * 100).roundToInt() else -1
        h.sub.text = row.subText
            ?: if (share >= 0) ctx.getString(R.string.reports_row_sub, row.bills, share)
               else ctx.getString(R.string.reports_row_sub_nopct, row.bills)
        h.amount.text = row.valueText ?: CurrencyHelper.format(ctx, row.revenue)
        // Mini bar (relative to the best row), best row in green like the chart
        val best = position == 0
        val pct = if (maxRev > 0) (row.revenue / maxRev * 100).toInt().coerceIn(2, 100) else 2
        (h.barFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (h.barSpacer.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        h.barFill.requestLayout()
        val r = 4f * d
        h.barTrack.background = GradientDrawable().apply { cornerRadius = r; setColor(Color.parseColor(if (best) "#CDEBD8" else "#EFE6D2")) }
        h.barFill.background = GradientDrawable().apply { cornerRadius = r; setColor(Color.parseColor(if (best) "#0F6E56" else "#D8B98C")) }

        h.amount.setTextColor(Color.parseColor(if (position == 0) "#0F6E56" else "#1A1A18"))
    }
}
