package com.example.easy_billing.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/** One product row in the Top Products ("Best Sellers") breakdown list. */
data class ProductRow(
    val name: String,
    val secondary: String,
    val value: String,
    val isBest: Boolean = false,
    /** This row's share of the top seller's metric, 0..100. */
    val pct: Int = 0
)

/** Ranked product breakdown list (rank · ★ name / secondary · value). */
class ProductRowAdapter(
    private var data: List<ProductRow> = emptyList()
) : RecyclerView.Adapter<ProductRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val vDivider: View       = view.findViewById(R.id.vDivider)
        val tvRank: TextView     = view.findViewById(R.id.tvRank)
        val tvStar: TextView     = view.findViewById(R.id.tvStar)
        val tvName: TextView     = view.findViewById(R.id.tvName)
        val tvSecondary: TextView = view.findViewById(R.id.tvSecondary)
        val tvValue: TextView    = view.findViewById(R.id.tvValue)
        val vBarFill: View       = view.findViewById(R.id.vBarFill)
        val vBarSpacer: View     = view.findViewById(R.id.vBarSpacer)
    }

    fun updateData(newData: List<ProductRow>) {
        data = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_row, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = data[position]
        holder.vDivider.visibility = if (position == 0) View.GONE else View.VISIBLE
        holder.tvRank.text = "${position + 1}"
        holder.tvRank.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (position == 0) "#F7E7B8" else "#EEF2F0")
        )
        holder.tvRank.setTextColor(Color.parseColor(if (position == 0) "#8A6526" else "#8A6526"))
        holder.tvStar.visibility = if (row.isBest) View.VISIBLE else View.GONE
        holder.tvName.text = row.name
        holder.tvSecondary.text = row.secondary
        holder.tvValue.text = row.value

        val pct = row.pct.coerceIn(2, 100)
        (holder.vBarFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (holder.vBarSpacer.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        holder.vBarFill.requestLayout()
    }
}
