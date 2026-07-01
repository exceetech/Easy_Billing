package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(private val list: List<TransactionUI>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (list[position].isHeader) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transaction_header, parent, false)
            HeaderVH(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transaction, parent, false)
            ItemVH(view)
        }
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        val item = list[position]

        if (holder is HeaderVH) {
            holder.tvHeader.text = item.headerTitle
            return
        }

        if (holder is ItemVH) {

            // TYPE
            holder.tvType.text = item.type

            // TIME
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            holder.tvDate.text = sdf.format(Date(item.timestamp))

            val formatted = "₹%.2f".format(item.amount)

            // ✅ AMOUNT + COLOR
            when (item.type) {
                "ADD" -> {
                    holder.tvAmount.setTextColor(Color.parseColor("#16A34A")) // Green
                    holder.tvAmount.text = "+$formatted"
                }
                "PAY" -> {
                    holder.tvAmount.setTextColor(Color.parseColor("#DC2626")) // Red
                    holder.tvAmount.text = "-$formatted"
                }
                "SETTLE" -> {
                    holder.tvAmount.setTextColor(Color.parseColor("#2563EB")) // Blue
                    holder.tvAmount.text = "₹0"
                }
                else -> {
                    holder.tvAmount.setTextColor(Color.BLACK)
                    holder.tvAmount.text = formatted
                }
            }

            // ✅ BALANCE
            holder.tvBalance.text = "Bal: ₹%.2f".format(item.runningBalance)

            // 🔥 BONUS: TYPE COLOR ALSO
            when (item.type) {
                "ADD" -> holder.tvType.setTextColor(Color.parseColor("#16A34A"))
                "PAY" -> holder.tvType.setTextColor(Color.parseColor("#DC2626"))
                "SETTLE" -> holder.tvType.setTextColor(Color.parseColor("#2563EB"))
                else -> holder.tvType.setTextColor(Color.BLACK)
            }
        }
    }

    // ================= VIEW HOLDERS =================

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvBalance: TextView = view.findViewById(R.id.tvBalance)
    }


    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tvHeader)
    }
}