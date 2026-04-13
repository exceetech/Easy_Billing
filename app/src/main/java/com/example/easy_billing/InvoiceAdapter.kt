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
        val rate: TextView = view.findViewById(R.id.tvRate)
        val price: TextView = view.findViewById(R.id.tvPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
        return InvoiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {

        val item = items[position]
        val context = holder.itemView.context

        val product = item.product
        val qty = item.quantity
        val unit = product.unit?.lowercase() ?: "unit"

        // ✅ FORMAT QUANTITY
        val formattedQty = if (qty % 1 == 0.0) {
            qty.toInt().toString()
        } else {
            String.format("%.2f", qty).trimEnd('0').trimEnd('.')
        }

        // ✅ NAME
        holder.name.text = buildString {
            append(product.name)
            if (!product.variant.isNullOrBlank()) {
                append(" (${product.variant})")
            }
        }

        // ✅ QUANTITY DISPLAY
        holder.qty.text = when (unit) {
            "kilogram" -> "$formattedQty kg"
            "litre" -> "$formattedQty L"
            "gram" -> "$formattedQty g"
            "millilitre" -> "$formattedQty ml"
            "piece" -> "x$formattedQty"
            else -> "$formattedQty $unit"
        }


        val unitText = when (unit) {
            "kilogram" -> "kg"
            "litre" -> "L"
            "gram" -> "g"
            "millilitre" -> "ml"
            "piece" -> "pc"
            else -> unit
        }

        val formattedUnitPrice = CurrencyHelper.format(context, product.price)
        val formattedSubtotal = CurrencyHelper.format(context, item.subTotal())

        holder.qty.text = "$formattedQty $unitText"
        holder.rate.text = "$formattedUnitPrice/$unitText"
        holder.price.text = formattedSubtotal
    }

    override fun getItemCount() = items.size
}