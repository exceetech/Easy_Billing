package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.util.CurrencyHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PURCHASE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<Purchase>() {
    override fun areItemsTheSame(oldItem: Purchase, newItem: Purchase): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Purchase, newItem: Purchase): Boolean =
        oldItem == newItem
}

/**
 * Purchase-history rows in the champagne / khata language: a left status
 * stripe, a supplier monogram, name + invoice·date, and a serif amount with a
 * status caption. Mirrors [R.layout.item_purchase_history_row] / item_credit.
 */
class PurchaseHistoryAdapter(
    initialItems: List<Purchase>,
    private val onItemClick: (Purchase) -> Unit
) : ListAdapter<Purchase, PurchaseHistoryAdapter.ViewHolder>(PURCHASE_DIFF_CALLBACK) {

    init {
        submitList(initialItems)
    }

    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stripe:   View     = view.findViewById(R.id.viewStatusStripe)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvSupplier: TextView = view.findViewById(R.id.tvSupplier)
        val tvSub:    TextView = view.findViewById(R.id.tvSub)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val divider:  View     = view.findViewById(R.id.viewRowDivider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_history_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx  = holder.itemView.context
        val item = getItem(position)

        holder.tvSupplier.text = item.supplierName
        holder.tvAvatar.text   = monogram(item.supplierName)

        val dateMillis = item.invoiceDate ?: item.createdAt
        holder.tvSub.text = "${item.invoiceNumber} · ${dateFmt.format(Date(dateMillis))}"

        holder.tvAmount.text = CurrencyHelper.format(ctx, item.invoiceValue)

        // Status drives the stripe colour and the caption, exactly as the
        // credit-account row's balance direction does.
        val cancelled = item.isCancelled
        val strike = Paint.STRIKE_THRU_TEXT_FLAG
        when {
            cancelled -> {
                holder.stripe.setBackgroundColor(Color.parseColor("#D8D0C0"))
                setAvatar(holder, "#F1EBDD", "#A99E88")
                holder.tvStatus.text = ctx.getString(R.string.purchase_history_status_cancelled)
                holder.tvStatus.setTextColor(Color.parseColor("#A99E88"))
                holder.itemView.alpha = 0.55f
                holder.tvSupplier.paintFlags = holder.tvSupplier.paintFlags or strike
                holder.tvAmount.paintFlags = holder.tvAmount.paintFlags or strike
            }
            item.isCredit -> {
                holder.stripe.setBackgroundColor(Color.parseColor("#B23A3A"))
                setAvatar(holder, "#FBEDED", "#B23A3A")
                holder.tvStatus.text = ctx.getString(R.string.purchase_history_status_credit)
                holder.tvStatus.setTextColor(Color.parseColor("#B23A3A"))
                holder.itemView.alpha = 1f
                holder.tvSupplier.paintFlags = holder.tvSupplier.paintFlags and strike.inv()
                holder.tvAmount.paintFlags = holder.tvAmount.paintFlags and strike.inv()
            }
            else -> {
                holder.stripe.setBackgroundColor(Color.parseColor("#1D6E6E"))
                setAvatar(holder, "#DDEEEE", "#1D6E6E")
                holder.tvStatus.text = ctx.getString(R.string.purchase_history_status_paid_cash)
                holder.tvStatus.setTextColor(Color.parseColor("#1D6E6E"))
                holder.itemView.alpha = 1f
                holder.tvSupplier.paintFlags = holder.tvSupplier.paintFlags and strike.inv()
                holder.tvAmount.paintFlags = holder.tvAmount.paintFlags and strike.inv()
            }
        }

        // Last row in the card must not draw a trailing hairline.
        holder.divider.visibility = if (position == currentList.lastIndex) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    /** Tints the monogram tile and its letters to match the row's status. */
    private fun setAvatar(holder: ViewHolder, bgHex: String, textHex: String) {
        holder.tvAvatar.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgHex))
        holder.tvAvatar.setTextColor(Color.parseColor(textHex))
    }

    /** Kept as a thin wrapper so existing call sites (`adapter.update(list)`) don't need to change. */
    fun update(newItems: List<Purchase>) {
        submitList(newItems)
    }

    /** First letters of the first two words of the supplier name, uppercased. */
    private fun monogram(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
}
