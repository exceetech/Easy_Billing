package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.util.CurrencyHelper
import kotlin.math.abs

/**
 * Ranked horizontal "leaderboard" of product profit — name on the left, a bar sized
 * relative to the highest profit, and the ₹ value on the right. Avoids the rotated,
 * truncated x-axis labels of a vertical bar chart.
 */
class ProfitChartAdapter(
    private val items: List<ProductProfitRaw>
) : RecyclerView.Adapter<ProfitChartAdapter.VH>() {

    private val maxAbs = (items.maxOfOrNull { abs(it.profit) } ?: 1.0).coerceAtLeast(1.0)

    // Same rotating, champagne-safe avatar palette as Bill History
    // (BillHistoryAdapter.rowPalette) -- picked per row from a stable hash
    // of the product so colors read as "random" across the list but never
    // jump around on scroll/rebind, matching the rest of the app's theme
    // instead of this row's old plain gold/gray split.
    private data class RowColor(val bg: Int, val text: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#DDEEEE"), Color.parseColor("#1D6E6E")), // teal
        RowColor(Color.parseColor("#FBEDED"), Color.parseColor("#B23A3A")), // red
        RowColor(Color.parseColor("#FAEEDA"), Color.parseColor("#8A6526")), // gold
        RowColor(Color.parseColor("#E5EBFA"), Color.parseColor("#3A5FB2")), // blue
        RowColor(Color.parseColor("#EFE5F7"), Color.parseColor("#7A4FA3")), // purple
        RowColor(Color.parseColor("#FAEBE1"), Color.parseColor("#B2673A")), // rust
        RowColor(Color.parseColor("#E1F2EA"), Color.parseColor("#3A8F6E")), // green
        RowColor(Color.parseColor("#FAE1F0"), Color.parseColor("#B23A85"))  // pink
    )

    private fun colorFor(item: ProductProfitRaw): RowColor {
        val key = "${item.productName}${item.variant.orEmpty()}"
        val index = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[index]
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val name: TextView = v.findViewById(R.id.tvName)
        val value: TextView = v.findViewById(R.id.tvValue)
        val fill: View = v.findViewById(R.id.barFill)
        val rest: View = v.findViewById(R.id.barRest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profit_chart_row, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // Rank badge -- same themed, rotating palette as Bill History's
        // avatar tiles, instead of a flat gold/gray split.
        holder.rank.text = "${position + 1}"
        val rowColor = colorFor(item)
        holder.rank.backgroundTintList = ColorStateList.valueOf(rowColor.bg)
        holder.rank.setTextColor(rowColor.text)

        holder.name.text =
            if (item.variant.isNullOrBlank()) item.productName
            else "${item.productName} (${item.variant})"

        val isLoss = item.profit < 0
        val symbol = CurrencyHelper.getCurrencySymbol(holder.itemView.context)
        holder.value.text = "${if (isLoss) "−" else ""}$symbol${"%,.2f".format(abs(item.profit))}"
        holder.value.setTextColor(Color.parseColor(if (isLoss) "#A32D2D" else "#0F6E56"))

        // Bar length relative to the biggest absolute profit (min 4% so tiny bars show).
        val frac = (abs(item.profit) / maxAbs).toFloat().coerceIn(0.04f, 1f)
        (holder.fill.layoutParams as LinearLayout.LayoutParams).also {
            it.weight = frac; holder.fill.layoutParams = it
        }
        (holder.rest.layoutParams as LinearLayout.LayoutParams).also {
            it.weight = 1f - frac; holder.rest.layoutParams = it
        }

        // Best (top) profit bar = deeper green; other profits = green; losses = red.
        val barColor = when {
            isLoss -> "#E24B4A"
            position == 0 -> "#0F6E56"
            else -> "#1D9E75"
        }
        holder.fill.backgroundTintList = ColorStateList.valueOf(Color.parseColor(barColor))
    }
}
