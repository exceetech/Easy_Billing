package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.InventoryItemUI

class InventoryAdapter(
    private var items: List<InventoryItemUI>,
    private val onAddStock: (InventoryItemUI) -> Unit,
    private val onReduceStock: (InventoryItemUI) -> Unit,
    private val onClearStock: (InventoryItemUI) -> Unit
) : RecyclerView.Adapter<InventoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val cost: TextView = view.findViewById(R.id.tvCost)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val gst: TextView = view.findViewById(R.id.tvGst)
        val status: TextView = view.findViewById(R.id.tvStatus)
        val category: TextView = view.findViewById(R.id.tvCategory)
        val barTrack: FrameLayout = view.findViewById<View>(R.id.barFill).parent as FrameLayout
        val barFill: View = view.findViewById(R.id.barFill)
        val btnAdd: Button = view.findViewById(R.id.btnAddStock)
        val btnReduce: Button = view.findViewById(R.id.btnReduceStock)
        val btnClear: Button = view.findViewById(R.id.btnClearStock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = items[position]

        val variantText = item.variant?.takeIf { it.isNotBlank() } ?: ""

        holder.name.text = item.productName

        holder.cost.text = "₹${"%.2f".format(item.avgCost)}"

        // Meta line: show variant if present, otherwise hide. GST unknown → hide chip.
        if (variantText.isEmpty()) {
            holder.meta.visibility = View.GONE
        } else {
            holder.meta.visibility = View.VISIBLE
            holder.meta.text = variantText
        }
        holder.gst.visibility = View.GONE

        // Category pill (hide when product has no category)
        val categoryText = item.category.trim()
        if (categoryText.isEmpty()) {
            holder.category.visibility = View.GONE
        } else {
            holder.category.visibility = View.VISIBLE
            holder.category.text = categoryText
        }

        // Stock badge number (no decimals if whole)
        val stockText = if (item.stock % 1 == 0.0)
            item.stock.toInt().toString()
        else
            item.stock.toString()

        // ================= STOCK STATES =================
        when {
            item.stock <= 0 -> {
                // OUT OF STOCK
                holder.status.text = "✕ Out of stock"
                holder.status.setTextColor(Color.parseColor("#A32D2D"))
                holder.barFill.setBackgroundResource(R.drawable.bg_inv_bar_out)
                setBarPct(holder, 3)
                holder.btnReduce.isEnabled = false
                holder.btnClear.isEnabled = false
            }

            item.stock <= 5 -> {
                // LOW STOCK
                holder.status.text = "▲ Low · $stockText left"
                holder.status.setTextColor(Color.parseColor("#854F0B"))
                holder.barFill.setBackgroundResource(R.drawable.bg_inv_bar_low)
                setBarPct(holder, 16)
                holder.btnReduce.isEnabled = true
                holder.btnClear.isEnabled = true
            }

            else -> {
                // IN STOCK
                holder.status.text = "● In stock · $stockText"
                holder.status.setTextColor(Color.parseColor("#3B6D11"))
                holder.barFill.setBackgroundResource(R.drawable.bg_inv_bar_ok)
                // Simple fill heuristic: 100 units = full bar
                val pct = ((item.stock / 100.0) * 100).toInt().coerceIn(8, 100)
                setBarPct(holder, pct)
                holder.btnReduce.isEnabled = true
                holder.btnClear.isEnabled = true
            }
        }

        // ================= CLICK ACTIONS =================
        holder.btnAdd.setOnClickListener { onAddStock(item) }
        holder.btnReduce.setOnClickListener { onReduceStock(item) }
        holder.btnClear.setOnClickListener { onClearStock(item) }
    }

    private fun setBarPct(holder: ViewHolder, pct: Int) {
        holder.barTrack.post {
            val full = holder.barTrack.width
            if (full <= 0) return@post
            val lp = holder.barFill.layoutParams
            lp.width = (full * pct / 100).coerceAtLeast(1)
            holder.barFill.layoutParams = lp
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<InventoryItemUI>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
