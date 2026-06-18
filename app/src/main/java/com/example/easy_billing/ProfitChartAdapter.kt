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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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

        holder.name.text =
            if (item.variant.isNullOrBlank()) item.productName
            else "${item.productName} (${item.variant})"

        val isLoss = item.profit < 0
        holder.value.text = "${if (isLoss) "−" else ""}₹${"%,.2f".format(abs(item.profit))}"
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
