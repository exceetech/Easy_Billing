package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.network.MonthlyReportResponse

class MonthlyReportAdapter(
    private val data: List<MonthlyReportResponse>
) : RecyclerView.Adapter<MonthlyReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMonth: TextView = view.findViewById(R.id.tvMonth)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
        val tvBills: TextView = view.findViewById(R.id.tvBills)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_monthly_report, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val r = data[position]

        holder.tvMonth.text = r.month
        holder.tvRevenue.text = "₹ %.2f".format(r.revenue)
        holder.tvBills.text = "${r.bills} bills"
    }
}