package com.example.easy_billing.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.util.CurrencyHelper

class PeakHourAdapter(
    private var data: List<PeakHourResponse>
) : RecyclerView.Adapter<PeakHourAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHour: TextView = view.findViewById(R.id.tvHour)
        val tvBills: TextView = view.findViewById(R.id.tvBills)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvRank: TextView = view.findViewById(R.id.tvRank)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val cardRoot: CardView = view.findViewById(R.id.cardRoot)
    }

    // 🔥 calculated once per dataset (like ProductAdapter but better)
    private var maxRevenue = 1.0
    private var minRevenue = 0.0

    init {
        recalculateStats()
    }

    fun updateData(newData: List<PeakHourResponse>) {
        data = newData
        recalculateStats()
        notifyDataSetChanged()
    }

    private fun recalculateStats() {
        maxRevenue = data.maxOfOrNull { it.revenue } ?: 1.0
        minRevenue = data.minOfOrNull { it.revenue } ?: 0.0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peak_hour, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = data[position]
        val context = holder.itemView.context

        // ⏰ Hour
        holder.tvHour.text = "${item.hour}:00"

        // 🧾 Bills
        holder.tvBills.text = "${item.bills} bills"

        // 💰 Revenue
        holder.tvRevenue.text =
            CurrencyHelper.format(context, item.revenue)

        // 🏆 Rank (same style as product adapter)
        holder.tvRank.text = "#${position + 1}"

        when (position) {
            0 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_gold)
            1 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_silver)
            2 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_bronze)
            else -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_chip)
        }

        // 📊 PERFORMANCE LOGIC (same pattern)
        val ratio = if (maxRevenue == 0.0) 0.0 else item.revenue / maxRevenue

        val isPeak = ratio >= 0.8
        val isLow = ratio <= 0.3

        val statusText = when {
            isPeak -> "🔥 Peak"
            isLow -> "⚠ Slow"
            else -> "⚖ Avg"
        }

        val statusColor = when {
            isPeak -> context.getColor(R.color.green)
            isLow -> context.getColor(R.color.red)
            else -> context.getColor(R.color.gray)
        }

        holder.tvStatus.text = statusText
        holder.tvStatus.setTextColor(statusColor)

        // 📊 Progress (same logic)
        val range = (maxRevenue - minRevenue).takeIf { it > 0 } ?: 1.0

        val progress = ((item.revenue - minRevenue) / range * 100)
            .toInt()
            .coerceIn(0, 100)

        holder.progressBar.progress = progress
        holder.progressBar.progressTintList =
            ColorStateList.valueOf(statusColor)

        // 🎨 Background
        val bgRes = when {
            isPeak -> R.drawable.bg_card_profit
            isLow -> R.drawable.bg_card_loss
            else -> R.drawable.bg_card_neutral
        }

        holder.cardRoot.setCardBackgroundColor(
            context.getColor(android.R.color.transparent)
        )
        holder.cardRoot.setBackgroundResource(bgRes)

        // ✅ IMPORTANT: reset view state (prevents recycling bugs)
        holder.itemView.alpha = 1f
    }
}