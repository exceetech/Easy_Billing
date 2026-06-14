package com.example.easy_billing.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

/**
 * Adapter for the "ALL HOURS" list (positions 4+ after podium takes top 3).
 *
 * @param startRank    The rank label to begin at — pass 4 so the first row shows "#4".
 * @param globalMax    Max revenue across the *entire* dataset (including podium items),
 *                     so progress bars are relative to the overall peak, not the subset.
 * @param globalMin    Min revenue across the entire dataset.
 */
class PeakHourAdapter(
    private var data: List<PeakHourResponse>,
    private var startRank: Int = 1,
    private var globalMax: Double = 0.0,
    private var globalMin: Double = 0.0
) : RecyclerView.Adapter<PeakHourAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHour: TextView         = view.findViewById(R.id.tvHour)
        val tvBills: TextView        = view.findViewById(R.id.tvBills)
        val tvRevenue: TextView      = view.findViewById(R.id.tvRevenue)
        val tvRank: TextView         = view.findViewById(R.id.tvRank)
        val tvStatus: TextView       = view.findViewById(R.id.tvStatus)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val cardRoot: CardView         = view.findViewById(R.id.cardRoot)
        val vAccent: View            = view.findViewById(R.id.vAccent)
    }

    // Local subset stats (used as fallback if globalMax not provided)
    private var localMax = 1.0
    private var localMin = 0.0

    init { recalculateLocal() }

    fun updateData(
        newData: List<PeakHourResponse>,
        newStartRank: Int = startRank,
        newGlobalMax: Double = globalMax,
        newGlobalMin: Double = globalMin
    ) {
        data        = newData
        startRank   = newStartRank
        globalMax   = newGlobalMax
        globalMin   = newGlobalMin
        recalculateLocal()
        notifyDataSetChanged()
    }

    private fun recalculateLocal() {
        localMax = data.maxOfOrNull { it.revenue } ?: 1.0
        localMin = data.minOfOrNull { it.revenue } ?: 0.0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peak_hour, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item    = data[position]
        val context = holder.itemView.context

        // ── Rank — plain muted text (no chip; matches mockup ALL HOURS style) ──
        holder.tvRank.text = "#${position + startRank}"
        holder.tvRank.background = null
        holder.tvRank.setTextColor(Color.parseColor("#CBD5E1"))

        // ── Hour ──
        holder.tvHour.text = formatHour(item.hour)

        // ── Bills ──
        holder.tvBills.text = "${item.bills} bills"

        // ── Revenue ──
        holder.tvRevenue.text = CurrencyHelper.format(context, item.revenue)

        // ── Performance vs full dataset ──
        val effectiveMax = if (globalMax > 0) globalMax else localMax
        val ratio = if (effectiveMax == 0.0) 0.0 else item.revenue / effectiveMax
        val isPeak = ratio >= 0.8
        val isLow  = ratio <= 0.3

        val textHex   = when { isPeak -> "#059669"; isLow -> "#DC2626"; else -> "#475569" }
        val bgHex     = when { isPeak -> "#ECFDF5"; isLow -> "#FEF2F2"; else -> "#F1F5F9" }
        val accentHex = when { isPeak -> "#22C55E"; isLow -> "#EF4444"; else -> "#1B3A8A" }
        val statusTxt = when { isPeak -> "🔥 Peak"; isLow -> "⚠ Slow"; else -> "⚖ Avg" }

        // Status badge
        holder.tvStatus.text = statusTxt
        holder.tvStatus.setTextColor(Color.parseColor(textHex))
        holder.tvStatus.background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 100f
            setColor(Color.parseColor(bgHex))
        }

        // Status dot — filled circle
        holder.vAccent.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(accentHex))
        }

        // Progress bar — relative to global range so bars match overall scale
        val effectiveMin = if (globalMax > 0) globalMin else localMin
        val range    = (effectiveMax - effectiveMin).takeIf { it > 0 } ?: 1.0
        val progress = ((item.revenue - effectiveMin) / range * 100).toInt().coerceIn(0, 100)

        holder.progressBar.progress = progress
        holder.progressBar.progressTintList =
            ColorStateList.valueOf(Color.parseColor(accentHex))

        // Card — pure white, plain CardView so elevation = real shadow, no tint
        holder.cardRoot.setCardBackgroundColor(Color.WHITE)

        holder.itemView.alpha = 1f
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "12:00 AM"
        hour < 12  -> "${hour}:00 AM"
        hour == 12 -> "12:00 PM"
        else       -> "${hour - 12}:00 PM"
    }
}
