package com.example.easy_billing

import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.network.BillResponse
import com.example.easy_billing.util.CurrencyHelper
import java.text.SimpleDateFormat
import java.util.*

private val BILL_DIFF_CALLBACK = object : DiffUtil.ItemCallback<BillResponse>() {
    override fun areItemsTheSame(oldItem: BillResponse, newItem: BillResponse): Boolean =
        oldItem.bill_id == newItem.bill_id

    override fun areContentsTheSame(oldItem: BillResponse, newItem: BillResponse): Boolean =
        oldItem == newItem
}

class BillHistoryAdapter(
    private val onBillClick: (BillResponse) -> Unit
) : ListAdapter<BillResponse, BillHistoryAdapter.ViewHolder>(BILL_DIFF_CALLBACK) {

    private var searchQuery: String = ""

    // Random row colours (stripe + avatar tile), champagne-safe palette.
    // Picked per row from a stable hash of the bill number so colours don't
    // jump around on scroll/rebind, but still read as "random" across rows —
    // same pattern the user wants instead of a fixed status-colour mapping.
    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#1D6E6E"), Color.parseColor("#DDEEEE"), Color.parseColor("#1D6E6E")), // teal
        RowColor(Color.parseColor("#B23A3A"), Color.parseColor("#FBEDED"), Color.parseColor("#B23A3A")), // red
        RowColor(Color.parseColor("#8A6526"), Color.parseColor("#FAEEDA"), Color.parseColor("#8A6526")), // gold
        RowColor(Color.parseColor("#3A5FB2"), Color.parseColor("#E5EBFA"), Color.parseColor("#3A5FB2")), // blue
        RowColor(Color.parseColor("#7A4FA3"), Color.parseColor("#EFE5F7"), Color.parseColor("#7A4FA3")), // purple
        RowColor(Color.parseColor("#B2673A"), Color.parseColor("#FAEBE1"), Color.parseColor("#B2673A")), // rust
        RowColor(Color.parseColor("#3A8F6E"), Color.parseColor("#E1F2EA"), Color.parseColor("#3A8F6E")), // green
        RowColor(Color.parseColor("#B23A85"), Color.parseColor("#FAE1F0"), Color.parseColor("#B23A85"))  // pink
    )

    private fun colorFor(bill: BillResponse): RowColor {
        val key = "${bill.bill_number}${bill.created_at}"
        val index = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[index]
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.lowercase()
        // Highlighting is derived from searchQuery, not list identity/content,
        // so DiffUtil won't pick this up — force a full rebind explicitly.
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View             = view.findViewById(R.id.viewStatusStripe)
        val divider: View            = view.findViewById(R.id.viewRowDivider)
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

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val bill = getItem(position)
        val context = holder.itemView.context

        // Avatar — last 3 digits of the bill's own running number. Bill
        // numbers often carry a year prefix (e.g. "2026-045" or "INV/26/045"),
        // so we take the LAST digit run in the string (the bill number
        // itself), not just every digit, to avoid the year bleeding in.
        val lastDigitRun = Regex("\\d+").findAll(bill.bill_number).lastOrNull()?.value.orEmpty()
        val avatarText = when {
            lastDigitRun.length >= 3 -> lastDigitRun.takeLast(3)
            lastDigitRun.isNotEmpty() -> lastDigitRun
            else -> bill.bill_number.take(1).uppercase()
        }
        holder.tvAvatar.text = avatarText

        // Invoice number with search highlight
        val invoiceLabel = "Invoice ${bill.bill_number}"
        holder.tvBillNumber.text = highlightText(invoiceLabel)

        // Caption — short date + payment method, e.g. "12 Apr · Cash"
        val caption = "${formatDate(bill.created_at)} · ${bill.payment_method}"
        holder.tvBillDate.text = highlightText(caption)

        // Amount
        holder.tvBillAmount.text = CurrencyHelper.format(context, bill.total_amount)

        val isCredit = bill.payment_method.contains("credit", ignoreCase = true)

        // Status caption text mirrors purchase-history's plain lowercase style.
        holder.tvPaymentMethod.text = when {
            bill.is_cancelled -> context.getString(R.string.bill_history_status_cancelled)
            isCredit -> context.getString(R.string.bill_history_status_on_credit)
            else -> context.getString(R.string.bill_history_status_paid)
        }

        // N1: cancelled (voided) bills stay visible, clearly marked, muted grey
        // regardless of their palette colour. Non-cancelled rows get a random
        // colour from the palette instead of a fixed status colour.
        if (bill.is_cancelled) {
            holder.stripe.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D8D0C0"))
            holder.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F1EBDD"))
            holder.tvAvatar.setTextColor(Color.parseColor("#A99E88"))
            holder.tvPaymentMethod.setTextColor(Color.parseColor("#A99E88"))
            holder.tvBillAmount.paintFlags =
                holder.tvBillAmount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.tvBillNumber.paintFlags =
                holder.tvBillNumber.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.itemView.alpha = 0.55f
        } else {
            val rowColor = colorFor(bill)
            holder.stripe.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.stripe)
            holder.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.avatarBg)
            holder.tvAvatar.setTextColor(rowColor.avatarText)
            holder.tvPaymentMethod.setTextColor(Color.parseColor("#A99E88"))
            holder.tvBillAmount.paintFlags =
                holder.tvBillAmount.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.tvBillNumber.paintFlags =
                holder.tvBillNumber.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.itemView.alpha = 1f
        }

        holder.divider.visibility = if (position == currentList.lastIndex) View.GONE else View.VISIBLE

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
                SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
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
