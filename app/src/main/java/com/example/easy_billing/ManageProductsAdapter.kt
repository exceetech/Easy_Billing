package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product

/**
 * Single-row adapter for [ManageProductsActivity].
 *
 * Each tile renders product name + a one-line subtitle of variant /
 * HSN / price, plus a chip-style badge that indicates whether the
 * product was created via a purchase invoice or manually.
 */
class ManageProductsAdapter(
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ManageProductsAdapter.VH>() {

    private var items: List<Product> = emptyList()

    fun submit(newItems: List<Product>) {
        val diff = DiffUtil.calculateDiff(Diff(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_product, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvSubtitle.text = buildString {
            item.variant?.takeIf { it.isNotBlank() }?.let { append("Variant: $it") }
            item.hsnCode?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("  •  ")
                append("HSN $it")
            }
            if (isNotEmpty()) append("  •  ")
            append("₹${"%.2f".format(item.price)}")
        }
        if (item.isPurchased) {
            holder.badge.setText(R.string.badge_purchased)
        } else {
            holder.badge.setText(R.string.badge_manual)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val badge: TextView = view.findViewById(R.id.badge)
    }

    private class Diff(
        private val old: List<Product>,
        private val new: List<Product>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }
}
