package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.util.CurrencyHelper

/**
 * Each row expands in place on tap instead of opening a popup dialog --
 * the breakdown (earned/cost/loss, a "what you kept" box, and a simple
 * stock-flow bar) grows inside the same card, and a second tap collapses
 * it. Only one row is ever open at a time. See the Overview-page
 * simplification audit this replaces dialog_product_detail.xml for.
 */
class ProfitAdapter : ListAdapter<ProductProfitRaw, ProfitAdapter.VH>(Diff()) {

    // Same stable, random-looking per-row accent as InventoryAdapter's
    // rowPalette — stripe and avatar tile share one hash-picked color
    // instead of a profit/loss-status color.
    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#0F6E56"), Color.parseColor("#E1F5EE"), Color.parseColor("#085041")), // teal
        RowColor(Color.parseColor("#B23A3A"), Color.parseColor("#FCEBEB"), Color.parseColor("#791F1F")), // red
        RowColor(Color.parseColor("#8A6526"), Color.parseColor("#FAEEDA"), Color.parseColor("#633806")), // gold
        RowColor(Color.parseColor("#185FA5"), Color.parseColor("#E6F1FB"), Color.parseColor("#0C447C")), // blue
        RowColor(Color.parseColor("#534AB7"), Color.parseColor("#EEEDFE"), Color.parseColor("#3C3489")), // purple
        RowColor(Color.parseColor("#D85A30"), Color.parseColor("#FAECE7"), Color.parseColor("#993C1D")), // rust
        RowColor(Color.parseColor("#3B6D11"), Color.parseColor("#EAF3DE"), Color.parseColor("#27500A")), // green
        RowColor(Color.parseColor("#993556"), Color.parseColor("#FBEAF0"), Color.parseColor("#72243E"))  // pink
    )

    private fun colorFor(item: ProductProfitRaw): RowColor {
        val key = "${item.productName}${item.variant ?: ""}"
        val idx = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[idx]
    }

    // Only one row open at a time; RecyclerView.NO_POSITION means none.
    private var expandedPosition: Int = RecyclerView.NO_POSITION

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val stripe: View = v.findViewById(R.id.viewStripe)
        val rowHead: View = v.findViewById(R.id.layoutRowHead)
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val name: TextView = v.findViewById(R.id.tvName)
        val qty: TextView = v.findViewById(R.id.tvQty)
        val profit: TextView = v.findViewById(R.id.tvProfit)
        val chevron: ImageView = v.findViewById(R.id.ivChevron)

        val detail: View = v.findViewById(R.id.layoutDetail)
        val earnedLine: TextView = v.findViewById(R.id.tvEarnedLine)
        val earnedCaption: TextView = v.findViewById(R.id.tvEarnedCaption)
        val costLine: TextView = v.findViewById(R.id.tvCostLine)
        val lossLine: TextView = v.findViewById(R.id.tvLossLine)
        val lossCaption: TextView = v.findViewById(R.id.tvLossCaption)
        val boxKept: View = v.findViewById(R.id.boxKept)
        val keptLabel: TextView = v.findViewById(R.id.tvKeptLabel)
        val keptCaption: TextView = v.findViewById(R.id.tvKeptCaption)
        val keptValue: TextView = v.findViewById(R.id.tvKeptValue)
        val segSold: View = v.findViewById(R.id.segSold)
        val segLoss: View = v.findViewById(R.id.segLoss)
        val segRemaining: View = v.findViewById(R.id.segRemaining)
        val legendSold: TextView = v.findViewById(R.id.tvLegendSold)
        val legendLoss: TextView = v.findViewById(R.id.tvLegendLoss)
        val legendRemaining: TextView = v.findViewById(R.id.tvLegendRemaining)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profit_simple, parent, false)
        return VH(view)
    }

    private fun qtyFormat(q: Double): String =
        if (q % 1 == 0.0) q.toInt().toString() else "%.2f".format(q)

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = getItem(position)
        val netPositive = item.profit >= 0
        val rowColor = colorFor(item)

        val profitColor = if (netPositive) "#085041" else "#791F1F"

        // ================= NAME =================
        val fullName =
            if (item.variant.isNullOrBlank())
                item.productName
            else "${item.productName} (${item.variant})"
        holder.name.text = fullName

        // ================= AVATAR INITIALS =================
        val words = item.productName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        holder.avatar.text = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.isNotEmpty() -> words[0].filter { it.isLetterOrDigit() }.take(2).uppercase()
            else -> "#"
        }
        holder.avatar.setTextColor(rowColor.avatarText)
        holder.avatar.backgroundTintList = ColorStateList.valueOf(rowColor.avatarBg)

        // ================= QTY + UNIT =================
        val qtyFormatted = if (item.totalQty % 1 == 0.0) {
            item.totalQty.toInt().toString()
        } else {
            String.format("%.2f", item.totalQty)
                .trimEnd('0')
                .trimEnd('.')
        }
        holder.qty.text = "$qtyFormatted ${item.unit ?: ""} sold".trim()

        // ================= PROFIT (collapsed row) =================
        val currencySymbol = CurrencyHelper.getCurrencySymbol(holder.itemView.context)
        fun money(v: Double) = "$currencySymbol%,.2f".format(v)
        holder.profit.text = "$currencySymbol%.2f".format(item.profit)
        holder.profit.setTextColor(Color.parseColor(profitColor))

        // ================= STRIPE =================
        holder.stripe.setBackgroundColor(rowColor.stripe)

        // ================= EXPAND / COLLAPSE =================
        val isExpanded = position == expandedPosition
        holder.detail.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.chevron.rotation = if (isExpanded) 180f else 0f

        holder.rowHead.setOnClickListener {
            val wasExpanded = position == expandedPosition
            val previousExpanded = expandedPosition
            expandedPosition = if (wasExpanded) RecyclerView.NO_POSITION else position
            if (previousExpanded != RecyclerView.NO_POSITION) notifyItemChanged(previousExpanded)
            if (!wasExpanded) notifyItemChanged(position)
        }

        // ================= EXPANDED DETAIL CONTENT =================
        // Plain-language breakdown, same three lines every time, then a
        // highlighted "what you kept" / "you lost money" box, then a
        // simple stock-flow bar -- see the design this replaces the old
        // dialog_product_detail.xml popup with.
        holder.earnedLine.text = money(item.revenue)
        holder.earnedCaption.text = "${qtyFormat(item.sold)} sold"

        holder.costLine.text = "− ${money(item.cost)}"

        holder.lossLine.text = "− ${money(item.lossAmount)}"
        holder.lossCaption.text = if (item.lossAmount > 0)
            "${qtyFormat(item.lossQty)} items"
        else
            holder.itemView.context.getString(R.string.xml_profit_detail_loss_none)

        val netProfit = item.profit - item.lossAmount
        val margin = if (item.revenue != 0.0) (netProfit / item.revenue * 100) else 0.0
        val keptNetPositive = netProfit >= 0

        holder.keptLabel.text = holder.itemView.context.getString(
            if (keptNetPositive) R.string.xml_profit_detail_kept_label
            else R.string.xml_profit_detail_lost_label
        )
        holder.keptCaption.text = if (keptNetPositive)
            "${Math.round(margin)}% kept"
        else if (item.lossAmount > 0)
            "mostly damage/loss"
        else
            "cost more than earned"
        holder.keptValue.text = if (keptNetPositive) money(netProfit) else "− ${money(-netProfit)}"

        val keptBg = if (keptNetPositive) R.drawable.bg_inv_subcard_accent else R.drawable.bg_inv_subcard_accent_red
        holder.boxKept.setBackgroundResource(keptBg)
        val keptTextColor = if (keptNetPositive) "#0B4B3C" else "#7A2424"
        val keptCaptionColor = if (keptNetPositive) "#3E6E62" else "#9B5A5A"
        val keptValueColor = if (keptNetPositive) "#0F6E56" else "#B23A3A"
        holder.keptLabel.setTextColor(Color.parseColor(keptTextColor))
        holder.keptCaption.setTextColor(Color.parseColor(keptCaptionColor))
        holder.keptValue.setTextColor(Color.parseColor(keptValueColor))

        // ================= STOCK FLOW BAR =================
        val total = if (item.added > 0) item.added
            else (item.sold + item.lossQty + item.remaining).coerceAtLeast(1.0)

        fun setSegWeight(seg: View, v: Double) {
            val lp = seg.layoutParams as LinearLayout.LayoutParams
            lp.weight = (v / total).toFloat().coerceAtLeast(0f)
            seg.layoutParams = lp
        }
        setSegWeight(holder.segSold, item.sold)
        setSegWeight(holder.segLoss, item.lossQty)
        setSegWeight(holder.segRemaining, item.remaining)

        holder.legendSold.text = "Sold ${item.sold.toInt()}"
        holder.legendLoss.text = "Lost ${item.lossQty.toInt()}"
        holder.legendRemaining.text = "Left ${item.remaining.toInt()}"
    }

    class Diff : DiffUtil.ItemCallback<ProductProfitRaw>() {

        override fun areItemsTheSame(
            oldItem: ProductProfitRaw,
            newItem: ProductProfitRaw
        ): Boolean {
            return oldItem.productName == newItem.productName &&
                    oldItem.variant == newItem.variant
        }

        override fun areContentsTheSame(
            oldItem: ProductProfitRaw,
            newItem: ProductProfitRaw
        ): Boolean {
            return oldItem == newItem
        }
    }
}
