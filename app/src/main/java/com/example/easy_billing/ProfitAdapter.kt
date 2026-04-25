package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw

class ProfitAdapter(
    private val onClick: (ProductProfitRaw) -> Unit
) : ListAdapter<ProductProfitRaw, ProfitAdapter.VH>(Diff()) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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

        // ================= NAME =================
        holder.name.text =
            if (item.variant.isNullOrBlank())
                item.productName
            else "${item.productName} (${item.variant})"

        // ================= QTY + UNIT =================
        val qtyFormatted = if (item.totalQty % 1 == 0.0) {
            item.totalQty.toInt().toString()
        } else {
            String.format("%.2f", item.totalQty)
                .trimEnd('0')
                .trimEnd('.')
        }

        holder.qty.text = "$qtyFormatted ${item.unit}"

        // ================= PROFIT =================
        val profitFormatted = "₹%.2f".format(item.profit)
        holder.profit.text = profitFormatted

        holder.profit.setTextColor(
            if (item.profit < 0)
                Color.RED
            else
                Color.parseColor("#16A34A") // green
        )

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