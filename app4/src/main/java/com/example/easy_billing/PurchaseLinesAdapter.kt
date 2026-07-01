package com.example.easy_billing

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft

/**
 * Adapter for the line-item list inside [PurchaseActivity]. Each
 * row shows the product name + quantity/HSN/value summary and lets
 * the user remove the line.
 */
class PurchaseLinesAdapter(
    private var items: List<PurchaseItemDraft>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PurchaseLinesAdapter.VH>() {

    fun submit(newItems: List<PurchaseItemDraft>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_line, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.productName
        holder.tvDetail.text = buildString {
            append("Qty ${item.quantity}")
            item.hsnCode?.takeIf { it.isNotBlank() }?.let { append("  •  HSN $it") }
            append("  •  ₹${"%.2f".format(item.invoiceValue)}")
        }
        holder.btnRemove.setOnClickListener { onRemove(holder.adapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvDetail: TextView = view.findViewById(R.id.tvDetail)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
