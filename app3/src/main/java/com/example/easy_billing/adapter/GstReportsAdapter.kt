package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper

data class GstReportItem(
    val invoiceNumber: String,
    val date: String,
    val gstin: String,
    val taxableValue: Double,
    val totalTax: Double,
    val isInterstate: Boolean
)

class GstReportsAdapter(private var items: List<GstReportItem>) :
    RecyclerView.Adapter<GstReportsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tvInvoiceNumber)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvGstin: TextView = view.findViewById(R.id.tvGstin)
        val tvTaxableValue: TextView = view.findViewById(R.id.tvTaxableValue)
        val tvTotalTax: TextView = view.findViewById(R.id.tvTotalTax)
        val tvTaxBreakup: TextView = view.findViewById(R.id.tvTaxBreakup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gst_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvInvoiceNumber.text = item.invoiceNumber
        holder.tvDate.text = item.date
        holder.tvGstin.text = item.gstin.ifBlank { "Unregistered" }
        holder.tvTaxableValue.text = CurrencyHelper.format(holder.itemView.context, item.taxableValue)
        holder.tvTotalTax.text = CurrencyHelper.format(holder.itemView.context, item.totalTax)
        holder.tvTaxBreakup.text = if (item.isInterstate) "IGST" else "CGST/SGST"
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<GstReportItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
