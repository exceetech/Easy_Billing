package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.TopProductResponse
import com.example.easy_billing.util.CurrencyHelper

class ProductReportAdapter(
    private val data: List<TopProductResponse>
) : RecyclerView.Adapter<ProductReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvProduct: TextView = view.findViewById(R.id.tvProduct)
        val tvQuantity: TextView = view.findViewById(R.id.tvQuantity)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvRank: TextView = view.findViewById(R.id.tvRank)

        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val progressBar: android.widget.ProgressBar =
            view.findViewById(R.id.progressBar)

        val cardRoot: androidx.cardview.widget.CardView =
            view.findViewById(R.id.cardRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_report, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = data[position]
        val context = holder.itemView.context

        // 🔥 CALCULATE HERE (FIX)
        val maxRevenue = data.maxOfOrNull { it.revenue } ?: 1.0
        val minRevenue = data.minOfOrNull { it.revenue } ?: 0.0

        // 🏷 Product name
        val productText = if (!item.variant.isNullOrEmpty()) {
            "${item.product} (${item.variant})"
        } else {
            item.product
        }

        holder.tvProduct.text = productText

        // 🧾 Quantity
        holder.tvQuantity.text = "${item.quantity} ${item.unit} sold • ${item.frequency} orders"

        // 🏆 Rank
        holder.tvRank.text = "#${position + 1}"

        when (position) {
            0 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_gold)
            1 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_silver)
            2 -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_bronze)
            else -> holder.tvRank.setBackgroundResource(R.drawable.bg_rank_chip)
        }

        // 💰 Revenue
        holder.tvRevenue.text =
            CurrencyHelper.format(context, item.revenue)

        // 🔥 PERFORMANCE LOGIC (UNCHANGED)
        val ratio = item.revenue / maxRevenue

        val isBest = ratio >= 0.8
        val isLow = ratio <= 0.3

        val statusText = when {
            isBest -> "🔥 Best"
            isLow -> "⚠ Low"
            else -> "⚖ Avg"
        }

        val statusColor = when {
            isBest -> context.getColor(R.color.green)
            isLow -> context.getColor(R.color.red)
            else -> context.getColor(R.color.gray)
        }

        holder.tvStatus.text = statusText
        holder.tvStatus.setTextColor(statusColor)

        // 📊 PROGRESS (UNCHANGED)
        val range = (maxRevenue - minRevenue).takeIf { it > 0 } ?: 1.0

        val progress = ((item.revenue - minRevenue) / range * 100)
            .toInt()
            .coerceIn(0, 100)

        holder.progressBar.progress = progress

        holder.progressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(statusColor)

        // 🎨 CARD BACKGROUND
        val bgRes = when {
            isBest -> R.drawable.bg_card_profit
            isLow -> R.drawable.bg_card_loss
            else -> R.drawable.bg_card_neutral
        }

        holder.cardRoot.setCardBackgroundColor(
            context.getColor(android.R.color.transparent)
        )
        holder.cardRoot.setBackgroundResource(bgRes)

        // ✨ ANIMATION (UNCHANGED)
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }
}