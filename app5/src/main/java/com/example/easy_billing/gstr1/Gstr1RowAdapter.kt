package com.example.easy_billing.gstr1

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/** Generic row adapter — takes list of (primary, secondary) string pairs. */
class Gstr1RowAdapter(
    private val rows: List<Pair<String, String>>
) : RecyclerView.Adapter<Gstr1RowAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvPrimary: TextView   = view.findViewById(R.id.tvPrimary)
        val tvSecondary: TextView = view.findViewById(R.id.tvSecondary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr1_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvPrimary.text   = rows[position].first
        holder.tvSecondary.text = rows[position].second
    }
}
