package com.example.easy_billing

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.BillResponse
import com.example.easy_billing.util.CurrencyHelper

class BillHistoryAdapter(
    private val onBillClick: (BillResponse) -> Unit
) : RecyclerView.Adapter<BillHistoryAdapter.ViewHolder>() {

    private var bills = listOf<BillResponse>()
    private var searchQuery: String = ""

    fun submitList(list: List<BillResponse>) {
        bills = list
        notifyDataSetChanged()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.lowercase()
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBillInfo: TextView = view.findViewById(R.id.tvBillInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_previous_bill, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = bills.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val bill = bills[position]
        val context = holder.itemView.context

        val text = buildString {
            append("Invoice #${bill.bill_number}\n")
            append("Total: ${CurrencyHelper.format(context, bill.total_amount)}\n")
            append("Payment: ${bill.payment_method}\n")
            append("Date: ${bill.created_at}")
        }

        holder.tvBillInfo.text = highlightText(text)

        // ✅ FINAL CLICK (reliable)
        holder.tvBillInfo.setOnClickListener {
            onBillClick(bill)
        }
    }

    // ================= HIGHLIGHT SEARCH =================

    private fun highlightText(text: String): SpannableString {

        val spannable = SpannableString(text)

        if (searchQuery.isEmpty()) return spannable

        val lowerText = text.lowercase()
        var startIndex = lowerText.indexOf(searchQuery)

        while (startIndex >= 0) {

            spannable.setSpan(
                BackgroundColorSpan(Color.YELLOW),
                startIndex,
                startIndex + searchQuery.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            startIndex = lowerText.indexOf(searchQuery, startIndex + searchQuery.length)
        }

        return spannable
    }
}