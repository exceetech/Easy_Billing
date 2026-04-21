package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
        val stock: TextView = view.findViewById(R.id.tvStock)
        val cost: TextView = view.findViewById(R.id.tvCost)
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

        holder.name.text =
            if (variantText.isEmpty()) item.productName
            else "${item.productName} ($variantText)"

        // 🔥 FORMAT STOCK (no decimals if whole)
        val stockText = if (item.stock % 1 == 0.0)
            item.stock.toInt().toString()
        else
            item.stock.toString()

        holder.stock.text = "Stock: $stockText"
        holder.cost.text = "Avg Cost: ₹${"%.2f".format(item.avgCost)}"

        // ================= STOCK STATES =================

        when {
            item.stock <= 0 -> {
                // 🔴 OUT OF STOCK
                holder.stock.text = "Out of Stock"
                holder.stock.setTextColor(Color.parseColor("#DC2626"))

                holder.btnReduce.isEnabled = false
                holder.btnClear.isEnabled = false
            }

            item.stock <= 5 -> {
                // ⚠️ LOW STOCK
                holder.stock.setTextColor(Color.parseColor("#F59E0B"))

                holder.btnReduce.isEnabled = true
                holder.btnClear.isEnabled = true
            }

            else -> {
                // 🟢 NORMAL
                holder.stock.setTextColor(Color.parseColor("#16A34A"))

                holder.btnReduce.isEnabled = true
                holder.btnClear.isEnabled = true
            }
        }

        // ================= CLICK ACTIONS =================

        holder.btnAdd.setOnClickListener {
            onAddStock(item)
        }

        holder.btnReduce.setOnClickListener {
            onReduceStock(item)
        }

        holder.btnClear.setOnClickListener {
            onClearStock(item)
        }
    }

    override fun getItemCount() = items.size

    // 🔥 UPDATE LIST
    fun updateData(newItems: List<InventoryItemUI>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}