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
        val name: TextView = view.findViewById(R.id.tvName)
        val qty: TextView = view.findViewById(R.id.tvQty)
        val price: TextView = view.findViewById(R.id.tvPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.product_name
        holder.qty.text = "x${item.quantity}"
        // GROSS line amount (price × qty). The bill discount is shown once,
        // as its own bill-level line — not baked into each row.
        holder.price.text = CurrencyHelper.format(holder.itemView.context, item.price * item.quantity)
    }

    override fun getItemCount() = items.size
}
