package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.DailyReportResponse
import com.example.easy_billing.util.CurrencyHelper
import kotlin.math.abs

class DailyReportAdapter(
    private val data: List<DailyReportResponse>
) : RecyclerView.Adapter<DailyReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvBills: TextView = view.findViewById(R.id.tvBills)


        val tvPercent: TextView = view.findViewById(R.id.tvPercent)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val progressBar: ProgressBar? = view.findViewById(R.id.progressBar)

        // 🔥 NEW
        val tvMin: TextView? = view.findViewById(R.id.tvMin)
        val tvMax: TextView? = view.findViewById(R.id.tvMax)

        val cardRoot: CardView = view.findViewById(R.id.cardRoot)
    }

    // 🔥 BASE VALUES
    private val maxRevenue = data.maxOfOrNull { it.revenue } ?: 1.0
    private val minRevenue = data.minOfOrNull { it.revenue } ?: 0.0

    // 🔥 MEDIAN (still used for spike detection only)
    private val medianRevenue: Double = run {
        val sorted = data.map { it.revenue }.sorted()
        if (sorted.isEmpty()) 1.0 else sorted[sorted.size / 2]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_sales, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val current = data[position]
        val context = holder.itemView.context

        // ✅ DATE
        holder.tvDate.text = try {
            current.date.substring(5)
        } catch (e: Exception) {
            current.date
        }

        // 💰 DATA
        holder.tvRevenue.text =
            CurrencyHelper.format(context, current.revenue)

        holder.tvBills.text = "${current.bills} bills"

        // 📈 GROWTH
        val prevRevenue = if (position > 0)
            data[position - 1].revenue
        else current.revenue

        val change = if (prevRevenue == 0.0) 0.0
        else ((current.revenue - prevRevenue) / prevRevenue) * 100

        val isPositive = change >= 0

        // 🔥 SPIKE / DROP
        val ratio = current.revenue / medianRevenue
        val isSpike = ratio > 1.5
        val isDrop = ratio < 0.5

        // 🔥 GROWTH TEXT
        holder.tvStatus?.let {

            // 🔥 PERCENT (BIG TEXT)
            val percentText = if (isPositive)
                "↑ ${"%.1f".format(change)}%"
            else
                "↓ ${"%.1f".format(abs(change))}%"

            holder.tvPercent.text = percentText

            holder.tvPercent.setTextColor(
                if (isPositive)
                    context.getColor(R.color.green)
                else
                    context.getColor(R.color.red)
            )

// 🔥 STATUS CHIP
            when {
                isSpike -> {
                    holder.tvStatus.text = "🔥 Spike"
                    holder.tvStatus.setTextColor(context.getColor(R.color.green))
                    holder.tvStatus.setBackgroundResource(R.drawable.bg_chip_green)
                }

                isDrop -> {
                    holder.tvStatus.text = "⚠ Drop"
                    holder.tvStatus.setTextColor(context.getColor(R.color.red))
                    holder.tvStatus.setBackgroundResource(R.drawable.bg_chip_red)
                }

                else -> {
                    holder.tvStatus.text = "⚖ Balanced"
                    holder.tvStatus.setTextColor(context.getColor(R.color.gray))
                    holder.tvStatus.setBackgroundResource(R.drawable.bg_chip_neutral)
                }
            }
        }

        // 🏆 BEST / WORST
        when (current.revenue) {
            maxRevenue -> holder.tvDate.text =
                holder.tvDate.text.toString() + " 🏆"

            minRevenue -> holder.tvDate.text =
                holder.tvDate.text.toString() + " ⚠"
        }

        // 🔥🔥 MIN / MAX LABELS (NEW)
        holder.tvMin?.text = CurrencyHelper.format(context, minRevenue)
        holder.tvMax?.text = CurrencyHelper.format(context, maxRevenue)

        // 🔥🔥 PROGRESS (BASED ON RANGE — EASY TO UNDERSTAND)
        holder.progressBar?.let {

            val range = (maxRevenue - minRevenue).takeIf { it > 0 } ?: 1.0

            val progress =
                ((current.revenue - minRevenue) / range * 100)
                    .toInt()
                    .coerceIn(0, 100)

            it.progress = progress

            it.progressTintList =
                android.content.res.ColorStateList.valueOf(
                    when {
                        isSpike -> context.getColor(R.color.green)
                        isDrop -> context.getColor(R.color.red)
                        else -> context.getColor(R.color.primaryColor)
                    }
                )
        }

        // 🎨 CARD BACKGROUND
        val bgRes = when {
            isSpike -> R.drawable.bg_card_profit
            isDrop -> R.drawable.bg_card_loss
            else -> R.drawable.bg_card_neutral
        }

        holder.cardRoot.setCardBackgroundColor(
            context.getColor(android.R.color.transparent)
        )
        holder.cardRoot.setBackgroundResource(bgRes)

        // ✨ ANIMATION
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }
}