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
import java.text.SimpleDateFormat
import java.util.*

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
        val tvAvatar: TextView       = view.findViewById(R.id.tvAvatar)
        val tvBillNumber: TextView   = view.findViewById(R.id.tvBillNumber)
        val tvBillDate: TextView     = view.findViewById(R.id.tvBillDate)
        val tvBillAmount: TextView   = view.findViewById(R.id.tvBillAmount)
        val tvPaymentMethod: TextView = view.findViewById(R.id.tvPaymentMethod)
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

        // Avatar — first character of bill number, uppercased
        val initial = bill.bill_number.take(1).uppercase()
        holder.tvAvatar.text = initial

        // Invoice number with search highlight
        val invoiceLabel = "Invoice #${bill.bill_number}"
        holder.tvBillNumber.text = highlightText(invoiceLabel)

        // Date — format from ISO string (yyyy-MM-dd'T'HH:mm or yyyy-MM-dd …)
        holder.tvBillDate.text = formatDate(bill.created_at)

        // Amount
        holder.tvBillAmount.text = CurrencyHelper.format(context, bill.total_amount)

        // Payment method with search highlight
        holder.tvPaymentMethod.text = highlightText(bill.payment_method)

        // Click
        holder.itemView.setOnClickListener {
            onBillClick(bill)
        }
    }

    // ================= DATE FORMAT =================

    private fun formatDate(raw: String): String {
        return try {
            val parsers = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )
            val date = parsers.firstNotNullOfOrNull { fmt ->
                runCatching { fmt.parse(raw.substring(0, minOf(raw.length, 19))) }.getOrNull()
            }
            if (date != null)
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
            else raw.substring(0, minOf(raw.length, 10))
        } catch (e: Exception) {
            raw.substring(0, minOf(raw.length, 10))
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
