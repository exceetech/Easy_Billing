package com.example.easy_billing.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper

/** One row of the Sales breakdown (a day or a month). */
data class SalesRow(
    val label: String,
    val revenue: Double,
    val bills: Int,
    val isBest: Boolean = false
)

/**
 * Calm "Daily / Monthly breakdown" list for the redesigned Sales screen:
 * day + bills · relative bar · revenue, with a star on the best period.
 * Bars are sized relative to the best (max) revenue.
 */
class SalesRowAdapter(
    private var data: List<SalesRow> = emptyList(),
    private var maxRevenue: Double = 0.0
) : RecyclerView.Adapter<SalesRowAdapter.ViewHolder>() {

    private val blue = Color.parseColor("#378ADD")
    private val teal = Color.parseColor("#1D9E75")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val vDivider: View      = view.findViewById(R.id.vDivider)
        val tvStar: TextView    = view.findViewById(R.id.tvStar)
        val tvDay: TextView     = view.findViewById(R.id.tvDay)
        val tvBills: TextView   = view.findViewById(R.id.tvBills)
        val vBarFill: View      = view.findViewById(R.id.vBarFill)
        val vBarSpacer: View    = view.findViewById(R.id.vBarSpacer)
        val tvRevenue: TextView = view.findViewById(R.id.tvRevenue)
    }

    fun updateData(newData: List<SalesRow>, newMax: Double) {
        data = newData
        maxRevenue = newMax
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sales_row, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = holder.itemView.context
        val row  = data[position]

        holder.vDivider.visibility = if (position == 0) View.GONE else View.VISIBLE
        holder.tvStar.visibility   = if (row.isBest) View.VISIBLE else View.GONE
        holder.tvDay.text          = row.label
        holder.tvBills.text        = "${row.bills} bills"
        holder.tvRevenue.text      = CurrencyHelper.format(item, row.revenue)

        val pct = if (maxRevenue > 0) (row.revenue / maxRevenue * 100).toInt().coerceIn(2, 100) else 2
        (holder.vBarFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (holder.vBarSpacer.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        holder.vBarFill.requestLayout()

        holder.vBarFill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 3f * item.resources.displayMetrics.density
            setColor(if (row.isBest) teal else blue)
        }
    }
}
