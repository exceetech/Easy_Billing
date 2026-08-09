package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
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

private val GST_REPORT_DIFF_CALLBACK = object : DiffUtil.ItemCallback<GstReportItem>() {
    override fun areItemsTheSame(oldItem: GstReportItem, newItem: GstReportItem): Boolean =
        oldItem.invoiceNumber == newItem.invoiceNumber

    override fun areContentsTheSame(oldItem: GstReportItem, newItem: GstReportItem): Boolean =
        oldItem == newItem
}

class GstReportsAdapter(initialItems: List<GstReportItem>) :
    ListAdapter<GstReportItem, GstReportsAdapter.ViewHolder>(GST_REPORT_DIFF_CALLBACK) {

    init {
        submitList(initialItems)
    }

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
        val item = getItem(position)
        holder.tvInvoiceNumber.text = item.invoiceNumber
        holder.tvDate.text = item.date
        holder.tvGstin.text = item.gstin.ifBlank { holder.itemView.context.getString(R.string.gst_reports_unregistered) }
        holder.tvTaxableValue.text = CurrencyHelper.format(holder.itemView.context, item.taxableValue)
        holder.tvTotalTax.text = CurrencyHelper.format(holder.itemView.context, item.totalTax)
        holder.tvTaxBreakup.text = if (item.isInterstate) holder.itemView.context.getString(R.string.invoice_igst_label) else holder.itemView.context.getString(R.string.gst_reports_cgst_sgst)
    }

    fun updateData(newItems: List<GstReportItem>) {
        submitList(newItems)
    }
}
