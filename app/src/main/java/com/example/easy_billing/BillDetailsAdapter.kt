package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.BillItemResponse
import com.example.easy_billing.util.CurrencyHelper

class BillDetailsAdapter(
    private val items: List<BillItemResponse>
) : RecyclerView.Adapter<BillDetailsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView = view.findViewById(R.id.tvName)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val price: TextView = view.findViewById(R.id.tvPrice)
        val divider: View = view.findViewById(R.id.viewRowDivider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Avatar — first letters of the first two words, uppercased.
        val words = item.product_name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val initials = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 && words[0].length >= 2 -> words[0].substring(0, 2)
            words.size == 1 -> words[0]
            else -> "?"
        }.uppercase()
        holder.avatar.text = initials

        holder.name.text = item.product_name

        val unitLabel = item.unit?.let { " $it" } ?: ""
        holder.meta.text = "${qtyLabel(item.quantity)}$unitLabel × ${CurrencyHelper.format(context, item.price)}"

        // GROSS line amount (price × qty). The bill discount is shown once,
        // as its own bill-level line — not baked into each row.
        holder.price.text = CurrencyHelper.format(context, item.price * item.quantity)

        holder.divider.visibility = if (position == items.lastIndex) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = items.size

    private fun qtyLabel(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) qty.toLong().toString() else qty.toString()
    }
}
