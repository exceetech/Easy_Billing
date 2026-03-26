package com.example.easy_billing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.DailyReportResponse
import com.example.easy_billing.util.CurrencyHelper

class DailyReportAdapter(
    private val data: List<DailyReportResponse>
) : RecyclerView.Adapter<DailyReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvBills: TextView = view.findViewById(R.id.tvBills)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_report, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = data[position]
        holder.tvDate.text = r.date
        val context = holder.itemView.context
        holder.tvRevenue.text = CurrencyHelper.format(context, r.revenue)
        holder.tvBills.text = "${r.bills} bills"
    }
}