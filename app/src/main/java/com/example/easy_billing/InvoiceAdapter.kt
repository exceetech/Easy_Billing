package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.util.CurrencyHelper

class InvoiceAdapter(
    private val items: List<CartItem>
) : RecyclerView.Adapter<InvoiceAdapter.InvoiceViewHolder>() {

    class InvoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val qty: TextView = view.findViewById(R.id.tvQty)
        val price: TextView = view.findViewById(R.id.tvPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
        return InvoiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {

        val item = items[position
        ]

        val context = holder.itemView.context

        holder.name.text = item.product.name
        holder.qty.text = "x${item.quantity}"

        // ✅ FIXED: dynamic currency
        holder.price.text = CurrencyHelper.format(context, item.subTotal())
    }

    override fun getItemCount() = items.size
}