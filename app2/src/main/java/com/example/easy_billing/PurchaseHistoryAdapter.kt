package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.util.CurrencyHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the purchase history list.
 *
 * Each row shows the supplier name, invoice number, date, and total
 * invoice value. Tapping a row calls [onItemClick] with the [Purchase].
 */
class PurchaseHistoryAdapter(
    private var items: List<Purchase>,
    private val onItemClick: (Purchase) -> Unit
) : RecyclerView.Adapter<PurchaseHistoryAdapter.ViewHolder>() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSupplierName:  TextView = view.findViewById(R.id.tvSupplierName)
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tvInvoiceNumber)
        val tvPurchaseDate:  TextView = view.findViewById(R.id.tvPurchaseDate)
        val tvInvoiceValue:  TextView = view.findViewById(R.id.tvInvoiceValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_history_row, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx  = holder.itemView.context
        val item = items[position]

        holder.tvSupplierName.text  = item.supplierName
        holder.tvInvoiceNumber.text = "Invoice: ${item.invoiceNumber}"

        val dateMillis = item.invoiceDate ?: item.createdAt
        holder.tvPurchaseDate.text = dateFmt.format(Date(dateMillis))

        holder.tvInvoiceValue.text = CurrencyHelper.format(ctx, item.invoiceValue)

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    fun update(newItems: List<Purchase>) {
        items = newItems
        notifyDataSetChanged()
    }
}
