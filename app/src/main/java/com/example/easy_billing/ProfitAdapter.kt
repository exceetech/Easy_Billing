package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw
import com.example.easy_billing.util.CurrencyHelper

class ProfitAdapter(
    private val onClick: (ProductProfitRaw) -> Unit
) : ListAdapter<ProductProfitRaw, ProfitAdapter.VH>(Diff()) {

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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val stripe: View = v.findViewById(R.id.viewStripe)
        val avatar: TextView = v.findViewById(R.id.tvAvatar)
        val name: TextView = v.findViewById(R.id.tvName)
        val qty: TextView = v.findViewById(R.id.tvQty)
        val profit: TextView = v.findViewById(R.id.tvProfit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profit_simple, parent, false)
        return VH(view)
    }

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

        // ================= PROFIT =================
        holder.profit.text = "${CurrencyHelper.getCurrencySymbol(holder.itemView.context)}%.2f".format(item.profit)
        holder.profit.setTextColor(Color.parseColor(profitColor))

        // ================= STRIPE =================
        holder.stripe.setBackgroundColor(rowColor.stripe)

        // ================= CLICK =================
        holder.itemView.setOnClickListener {
            onClick(item)
        }
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
