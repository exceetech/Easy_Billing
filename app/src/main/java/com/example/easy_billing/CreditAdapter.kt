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
        val stripe = view.findViewById<View>(R.id.viewBalanceStripe)
        val name = view.findViewById<TextView>(R.id.tvName)
        val phone = view.findViewById<TextView>(R.id.tvPhone)
        val due = view.findViewById<TextView>(R.id.tvDue)
        val status = view.findViewById<TextView>(R.id.tvStatus)
        val tvAvatar = view.findViewById<TextView>(R.id.tvAvatar)
        val divider = view.findViewById<View>(R.id.viewRowDivider)
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

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
        holder.tvAvatar.text = if (name.isNotEmpty()) name[0].uppercase() else "?"

        // Colour follows the balance, not the row position. Cycling by position
        // meant the same customer changed colour whenever the list was filtered
        // or searched, which read as a different person.
        //
        // The caption says which way the money goes: "owes you" and "in advance"
        // are read faster than a DUE / ADVANCE status word next to a number.
        val stripe: String
        val tile: String
        val ink: String
        when {
            item.dueAmount > 0 -> {
                stripe = "#B23A3A"; tile = "#FCEBEB"; ink = "#791F1F"
                holder.status.text = "owes you"
                holder.due.text = money(item.dueAmount)
                holder.due.setTextColor(android.graphics.Color.parseColor("#B23A3A"))
            }
            item.dueAmount < 0 -> {
                stripe = "#0F6E56"; tile = "#E1F5EE"; ink = "#0F6E56"
                holder.status.text = "in advance"
                holder.due.text = money(-item.dueAmount)
                holder.due.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            }
            else -> {
                stripe = "#E4DBC6"; tile = "#F3ECDD"; ink = "#8A8272"
                holder.status.text = "settled"
                holder.due.text = money(0.0)
                holder.due.setTextColor(android.graphics.Color.parseColor("#8A8272"))
            }
        }

        holder.stripe.setBackgroundColor(android.graphics.Color.parseColor(stripe))
        holder.tvAvatar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(tile))
        holder.tvAvatar.setTextColor(android.graphics.Color.parseColor(ink))

        // Rows sit inside one card, so the last must not draw a hairline
        // against the card's bottom edge.
        holder.divider.visibility =
            if (position == list.lastIndex) View.GONE else View.VISIBLE

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