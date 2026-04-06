package com.example.easy_billing

import android.R.attr.name
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.CreditAccount

class CreditAdapter(
    private var list: MutableList<CreditAccount>,
    private val onClick: (CreditAccount) -> Unit
) : RecyclerView.Adapter<CreditAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name = view.findViewById<TextView>(R.id.tvName)
        val phone = view.findViewById<TextView>(R.id.tvPhone)
        val due = view.findViewById<TextView>(R.id.tvDue)
        val tvAvatar = view.findViewById<TextView>(R.id.tvAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_credit, parent, false)
        return VH(view)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]

        val name = item.name.trim()

        holder.name.text = name
        holder.phone.text = item.phone
        holder.due.text = "₹${item.dueAmount.toFloat()}"

        // ✅ Avatar letter
        holder.tvAvatar.text = if (name.isNotEmpty()) {
            name[0].uppercase()
        } else {
            "?"
        }

        // ✅ Dynamic avatar color (stable per user)
        val colors = listOf(
            "#2563EB", // blue
            "#7C3AED", // purple
            "#059669", // green
            "#DC2626", // red
            "#EA580C"  // orange
        )

        val color = colors[Math.abs(name.hashCode()) % colors.size]

        holder.tvAvatar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(color)
            )

        // ✅ Due color logic
        if (item.dueAmount > 0) {
            holder.due.setTextColor(android.graphics.Color.parseColor("#DC2626")) // red
        } else {
            holder.due.setTextColor(android.graphics.Color.parseColor("#16A34A")) // green
        }

        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    fun update(newList: List<CreditAccount>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }
}