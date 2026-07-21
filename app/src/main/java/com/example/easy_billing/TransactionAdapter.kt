package com.example.easy_billing

import android.content.res.ColorStateList
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

        /**
         * Plain-English name for a transaction type.
         *
         * Shared with the printed statement, which used to put the raw code in
         * its Type column — so a customer's statement read "WRITE_OFF" and
         * "PURCHASE_CREDIT" while the same rows on screen read "Written off"
         * and "Purchase on credit".
         *
         * Anything unrecognised falls back to the raw value rather than being
         * hidden, so a type added later is visible instead of silently blank.
         */
        fun labelFor(type: String): String = when (type) {
            "ADD"              -> "Credit sale"
            "PAY"              -> "Payment received"
            "PURCHASE_CREDIT"  -> "Purchase on credit"
            "PURCHASE_RETURN"  -> "Purchase return"
            "WRITE_OFF"        -> "Written off"
            "REFUND"           -> "Refunded"
            // Legacy rows, from before the two events were told apart.
            "SETTLE"           -> "Settled"
            else               -> type
        }
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

            // Raw type codes mean nothing to someone reading their own khata.
            // Shared with the printed statement so the two can't drift apart.
            holder.tvType.text = labelFor(item.type)
            holder.tvType.setTextColor(Color.parseColor("#1A1A18"))

            // Time, plus whatever else is worth knowing about this entry: the
            // invoice it came from, or that it hasn't reached the server yet.
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            val extras = buildList {
                item.reference?.takeIf { it.isNotBlank() && it.isNotBlank() }?.let { add(it) }
                if (!item.isSynced) add("not yet synced")
                // The label already says "Written off" / "Refunded" for the new
                // types; only the legacy one needs spelling out.
                if (item.type == "SETTLE") add("no cash moved")
            }
            holder.tvDate.text =
                (listOf(sdf.format(Date(item.timestamp))) + extras).joinToString(" · ")

            val formatted = money(kotlin.math.abs(item.amount))

            // Colour follows the direction the balance moves, matching the
            // accounts list where money owed is red. The old mapping had ADD
            // green and PAY red — backwards, since a credit sale increases what
            // the customer owes and a payment reduces it.
            val stripe: String
            val tile: String
            val icon: Int
            when (item.type) {
                "ADD", "PURCHASE_CREDIT" -> {
                    stripe = "#B23A3A"; tile = "#FCEBEB"; icon = R.drawable.ic_lc_arrow_up_right
                    holder.tvAmount.text = "+ $formatted"
                    holder.tvAmount.setTextColor(Color.parseColor("#B23A3A"))
                }
                "PAY", "PURCHASE_RETURN" -> {
                    stripe = "#0F6E56"; tile = "#E1F5EE"; icon = R.drawable.ic_lc_arrow_down_left
                    holder.tvAmount.text = "− $formatted"
                    holder.tvAmount.setTextColor(Color.parseColor("#0F6E56"))
                }
                // Gold for the two adjustments — neither is a sale or a
                // payment, and colouring them like one is what made a refund
                // read as revenue.
                "WRITE_OFF", "SETTLE" -> {
                    stripe = "#8A6526"; tile = "#F3ECDD"; icon = R.drawable.ic_lucide_check
                    holder.tvAmount.text = "− $formatted"
                    holder.tvAmount.setTextColor(Color.parseColor("#8A6526"))
                }
                "REFUND" -> {
                    stripe = "#8A6526"; tile = "#F3ECDD"; icon = R.drawable.ic_lc_arrow_up_right
                    holder.tvAmount.text = "+ $formatted"
                    holder.tvAmount.setTextColor(Color.parseColor("#8A6526"))
                }
                else -> {
                    stripe = "#E4DBC6"; tile = "#F3ECDD"; icon = R.drawable.ic_lc_arrow_up_right
                    holder.tvAmount.text = formatted
                    holder.tvAmount.setTextColor(Color.parseColor("#1A1A18"))
                }
            }

            holder.stripe.setBackgroundColor(Color.parseColor(stripe))
            holder.icon.setImageResource(icon)
            holder.icon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tile))
            holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor(stripe))

            // Where the balance stood after this entry — already computed by
            // prepareUI and previously never shown.
            holder.tvBalance.text = "${money(kotlin.math.abs(item.runningBalance))} left"

            // Rows are grouped under a date header, so the last one before the
            // next header must not draw a trailing hairline.
            val nextIsHeaderOrEnd =
                position == list.lastIndex || list[position + 1].isHeader
            holder.divider.visibility = if (nextIsHeaderOrEnd) View.GONE else View.VISIBLE
        }
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

    // ================= VIEW HOLDERS =================

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.viewTxnStripe)
        val icon: android.widget.ImageView = view.findViewById(R.id.ivTxnIcon)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvBalance: TextView = view.findViewById(R.id.tvBalance)
        val divider: View = view.findViewById(R.id.viewTxnDivider)
    }


    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tvHeader)
    }
}