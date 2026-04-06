package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.network.MonthlyReportResponse
import com.example.easy_billing.util.CurrencyHelper

class MonthlyReportAdapter(
    private val data: List<MonthlyReportResponse>
) : RecyclerView.Adapter<MonthlyReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMonth: TextView = view.findViewById(R.id.tvMonth)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvBills: TextView = view.findViewById(R.id.tvBills)

        val tvPercent: TextView = view.findViewById(R.id.tvPercent)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)

        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val tvMin: TextView = view.findViewById(R.id.tvMin)
        val tvMax: TextView = view.findViewById(R.id.tvMax)

        val cardRoot: CardView = view.findViewById(R.id.cardRoot)
    }

    private val maxRevenue = data.maxOfOrNull { it.revenue } ?: 1.0
    private val minRevenue = data.minOfOrNull { it.revenue } ?: 0.0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_monthly_sales, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val current = data[position]
        val context = holder.itemView.context

        // 📅 Month formatting
        var monthDisplay = current.month.substring(0, 7) // or format nicely

        // 💰 Data
        holder.tvRevenue.text =
            CurrencyHelper.format(context, current.revenue)

        holder.tvBills.text = "${current.bills} bills"

        // 📈 Growth (previous month)
        val prevRevenue = if (position > 0)
            data[position - 1].revenue
        else current.revenue

        val change = if (prevRevenue == 0.0) 0.0
        else ((current.revenue - prevRevenue) / prevRevenue) * 100

        val isPositive = change >= 0

        // 🎯 Percent
        holder.tvPercent.text = if (isPositive)
            "↑ ${"%.1f".format(change)}%"
        else
            "↓ ${"%.1f".format(kotlin.math.abs(change))}%"

        holder.tvPercent.setTextColor(
            if (isPositive)
                context.getColor(R.color.green)
            else
                context.getColor(R.color.red)
        )

        // 🔥 Status logic (simple & understandable)
        val ratio = current.revenue / (maxRevenue.takeIf { it > 0 } ?: 1.0)

        val status = when {
            ratio > 0.8 -> "🔥 High"
            ratio < 0.4 -> "⚠ Low"
            else -> "⚖ Normal"
        }

        holder.tvStatus.text = status

        holder.tvStatus.setTextColor(
            when (status) {
                "🔥 High" -> context.getColor(R.color.green)
                "⚠ Low" -> context.getColor(R.color.red)
                else -> context.getColor(R.color.gray)
            }
        )

        // 🏆 Best / Worst
        when (current.revenue) {
            maxRevenue -> monthDisplay += " 🏆"
            minRevenue -> monthDisplay += " ⚠"
        }
        holder.tvMonth.text = monthDisplay

        // 📊 Min / Max labels
        holder.tvMin.text = CurrencyHelper.format(context, minRevenue)
        holder.tvMax.text = CurrencyHelper.format(context, maxRevenue)

        // 📊 Progress (VERY SIMPLE RANGE BASED)
        val range = (maxRevenue - minRevenue).takeIf { it > 0 } ?: 1.0

        val progress =
            ((current.revenue - minRevenue) / range * 100)
                .toInt()
                .coerceIn(0, 100)

        holder.progressBar.progress = progress

        holder.progressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(
                when (status) {
                    "🔥 High" -> context.getColor(R.color.green)
                    "⚠ Low" -> context.getColor(R.color.red)
                    else -> context.getColor(R.color.primaryColor)
                }
            )

        // 🎨 Background
        val bgRes = when (status) {
            "🔥 High" -> R.drawable.bg_card_profit
            "⚠ Low" -> R.drawable.bg_card_loss
            else -> R.drawable.bg_card_neutral
        }

        holder.cardRoot.setCardBackgroundColor(
            context.getColor(android.R.color.transparent)
        )
        holder.cardRoot.setBackgroundResource(bgRes)

        // ✨ Animation
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }
}
