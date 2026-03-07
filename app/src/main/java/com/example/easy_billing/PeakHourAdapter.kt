package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.network.PeakHourResponse

class PeakHourAdapter(
    private val data: List<PeakHourResponse>
) : RecyclerView.Adapter<PeakHourAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHour: TextView = view.findViewById(R.id.tvHour)
        val tvBills: TextView = view.findViewById(R.id.tvBills)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peak_hour, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val r = data[position]

        holder.tvHour.text = "${r.hour}:00"
        holder.tvBills.text = "${r.bills} bills"
        holder.tvRevenue.text = "₹ %.2f".format(r.revenue)
    }
}