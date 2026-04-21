package com.example.easy_billing

import android.graphics.Color

import android.view.*

import android.widget.*

import androidx.recyclerview.widget.RecyclerView

import androidx.recyclerview.widget.ListAdapter

import androidx.recyclerview.widget.DiffUtil

import com.example.easy_billing.db.AppDatabase

import com.example.easy_billing.db.ProductProfitRaw

import kotlinx.coroutines.*

class ProfitAdapter :

    ListAdapter<ProductProfitRaw, ProfitAdapter.ViewHolder>(Diff()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val name: TextView = view.findViewById(R.id.tvName)

        val qty: TextView = view.findViewById(R.id.tvQty)

        val revenue: TextView = view.findViewById(R.id.tvRevenue)

        val profit: TextView = view.findViewById(R.id.tvProfit)

        // 🔥 NEW UI

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

        val context = holder.itemView.context

        // ================= BASIC DATA =================

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

        // ================= 🔥 ADVANCED ANALYSIS =================

        CoroutineScope(Dispatchers.Main).launch {

            val db = AppDatabase.getDatabase(context)

            val productId = getProductIdFromName(db, item)

            if (productId == null) return@launch

            val added = withContext(Dispatchers.IO) {

                db.inventoryLogDao().getTotalAdded(productId) ?: 0.0

            }

            val sold = withContext(Dispatchers.IO) {

                db.inventoryLogDao().getTotalSold(productId) ?: 0.0

            }

            val lossQty = withContext(Dispatchers.IO) {

                db.inventoryLogDao().getTotalLossQty(productId) ?: 0.0

            }

            val lossAmount = withContext(Dispatchers.IO) {

                db.lossDao().getLossForProduct(productId) ?: 0.0

            }

            val remaining = added - sold - lossQty

            val netProfit = item.profit - lossAmount

            // ================= UI =================

            holder.stockFlow.text =

                "Added: ${added.toInt()} | Sold: ${sold.toInt()} | Loss: ${lossQty.toInt()}"

            holder.remaining.text = "Remaining: ${remaining.toInt()}"

            holder.loss.text = "Loss: ₹${"%.0f".format(lossAmount)}"

            holder.netProfit.text = "Net: ₹${"%.0f".format(netProfit)}"

            holder.netProfit.setTextColor(

                if (netProfit < 0) Color.RED

                else Color.parseColor("#16A34A")

            )

            // ================= INSIGHT =================

            val insightText = when {

                netProfit < 0 -> "⚠ Loss product"

                lossQty > sold -> "📦 High wastage"

                remaining > sold -> "📦 Dead stock"

                else -> "🔥 Good product"

            }

            holder.insight.text = insightText

            holder.insight.setTextColor(

                when {

                    netProfit < 0 -> Color.RED

                    lossQty > sold -> Color.parseColor("#F59E0B")

                    else -> Color.parseColor("#2563EB")

                }

            )

        }

    }

    // 🔥 MAP PRODUCT NAME → PRODUCT ID

    private suspend fun getProductIdFromName(

        db: AppDatabase,

        item: ProductProfitRaw

    ): Int? {

        return withContext(Dispatchers.IO) {

            val products = db.productDao().getAll()

            val match = products.find {

                it.name == item.productName &&

                        (it.variant ?: "") == (item.variant ?: "")

            }

            match?.id

        }

    }

    class Diff : DiffUtil.ItemCallback<ProductProfitRaw>() {

        override fun areItemsTheSame(o: ProductProfitRaw, n: ProductProfitRaw) =

            o.productName == n.productName && o.variant == n.variant

        override fun areContentsTheSame(o: ProductProfitRaw, n: ProductProfitRaw) =

            o == n

    }

}