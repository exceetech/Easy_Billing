package com.example.easy_billing

import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.ProductProfitRaw

class ProfitAdapter :
    ListAdapter<ProductProfitRaw, ProfitAdapter.ViewHolder>(Diff()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val name: TextView = view.findViewById(R.id.tvName)
        val qty: TextView = view.findViewById(R.id.tvQty)
        val revenue: TextView = view.findViewById(R.id.tvRevenue)
        val profit: TextView = view.findViewById(R.id.tvProfit)

        val stockFlow: TextView = view.findViewById(R.id.tvStockFlow)
        val remaining: TextView = view.findViewById(R.id.tvRemaining)
        val loss: TextView = view.findViewById(R.id.tvLoss)
        val netProfit: TextView = view.findViewById(R.id.tvNetProfit)
        val insight: TextView = view.findViewById(R.id.tvInsight)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = getItem(position)

        // ================= BASIC =================
        holder.name.text =
            if (item.variant.isNullOrBlank())
                item.productName
            else "${item.productName} (${item.variant})"

        holder.qty.text = "Qty: ${item.totalQty}"
        holder.revenue.text = "Revenue: ₹${"%.2f".format(item.revenue)}"
        holder.profit.text = "Profit: ₹${"%.2f".format(item.profit)}"

        holder.profit.setTextColor(
            if (item.profit < 0) Color.RED
            else Color.parseColor("#16A34A")
        )

        // ================= ADVANCED (FROM BACKEND) =================
        val netProfit = item.profit - item.lossAmount

        holder.stockFlow.text =
            "Added: ${item.added.toInt()} | Sold: ${item.sold.toInt()} | Loss: ${item.lossQty.toInt()}"

        holder.remaining.text = "Remaining: ${item.remaining.toInt()}"
        holder.loss.text = "Loss: ₹${"%.0f".format(item.lossAmount)}"
        holder.netProfit.text = "Net: ₹${"%.0f".format(netProfit)}"

        holder.netProfit.setTextColor(
            if (netProfit < 0) Color.RED
            else Color.parseColor("#16A34A")
        )

        // ================= INSIGHT =================
        val insightText = when {
            netProfit < 0 -> "⚠ Loss product"
            item.lossQty > item.sold -> "📦 High wastage"
            item.remaining > item.sold -> "📦 Dead stock"
            else -> "🔥 Good product"
        }

        holder.insight.text = insightText

        holder.insight.setTextColor(
            when {
                netProfit < 0 -> Color.RED
                item.lossQty > item.sold -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#2563EB")
            }
        )
    }

    class Diff : DiffUtil.ItemCallback<ProductProfitRaw>() {
        override fun areItemsTheSame(o: ProductProfitRaw, n: ProductProfitRaw) =
            o.productName == n.productName && o.variant == n.variant

        override fun areContentsTheSame(o: ProductProfitRaw, n: ProductProfitRaw) =
            o == n
    }
}