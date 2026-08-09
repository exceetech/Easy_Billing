package com.example.easy_billing.gstr1

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper

/**
 * CDNR (GSTR-1 Table 9B, registered) row adapter.
 *
 * The one thing this section must never blur is **credit vs debit**, because
 * they move tax in opposite directions: a credit note reduces your output tax,
 * a debit note increases it. So the type is stated twice over — a CREDIT /
 * DEBIT badge, and a signed amount (credit notes render negative in red) —
 * rather than relying on a colour convention alone.
 *
 * Note the stored taxable value is a magnitude; the sign shown here is derived
 * from noteType, matching how the portal treats the two.
 */
class Gstr1CdnrAdapter(
    private val rows: List<CdnrRow>
) : RecyclerView.Adapter<Gstr1CdnrAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View      = view.findViewById(R.id.vStripe)
        val tvBadge: TextView  = view.findViewById(R.id.tvBadge)
        val tvTitle: TextView  = view.findViewById(R.id.tvTitle)
        val tvMeta: TextView   = view.findViewById(R.id.tvMeta)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvTax: TextView    = view.findViewById(R.id.tvTax)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr_ledger_row, parent, false))

    override fun getItemCount() = rows.size

    /** Keeps the minus outside the rupee symbol: -₹1,200, not ₹-1,200. */
    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        val sign = if (v < 0) "-" else ""
        val a = kotlin.math.abs(v)
        return if (a % 1.0 == 0.0) "$sign$symbol%,.0f".format(a) else "$sign$symbol%,.2f".format(a)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val isCredit = r.noteType.equals("C", ignoreCase = true)

        // A credit note reduces the supply; show it as a negative.
        val signed = if (isCredit) -kotlin.math.abs(r.taxableValue) else kotlin.math.abs(r.taxableValue)

        val accent = if (isCredit) "#A32D2D" else "#1D9E75"
        val ink    = if (isCredit) "#A32D2D" else "#0F5943"
        val tint   = if (isCredit) "#FCEBEB" else "#E1F5EE"

        holder.tvBadge.visibility = View.VISIBLE
        holder.tvBadge.text = if (isCredit) "CREDIT" else "DEBIT"
        holder.tvBadge.setTextColor(Color.parseColor(ink))
        holder.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))

        val party = r.receiverName.ifBlank { r.gstin.ifBlank { "Unknown party" } }
        holder.tvTitle.text = if (r.noteNumber.isBlank()) party
                              else "${r.noteNumber}  ·  $party"

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            r.gstin.ifBlank { null },
            r.noteDate.ifBlank { null },
            rate,
            r.noteSupplyType.takeIf { it.isNotBlank() && it != "Regular" },
            "RCM".takeIf { r.reverseCharge.equals("Y", ignoreCase = true) }
        ).joinToString("  ·  ")

        holder.tvAmount.text = money(holder.itemView.context, signed)
        holder.tvAmount.setTextColor(Color.parseColor(if (isCredit) "#A32D2D" else "#1A1A18"))

        val tax = signed * r.rate / 100.0
        holder.tvTax.text = if (r.cessAmount != 0.0)
            "${money(holder.itemView.context, tax)} · cess ${money(holder.itemView.context, if (isCredit) -kotlin.math.abs(r.cessAmount) else r.cessAmount)}"
        else
            money(holder.itemView.context, tax)
        holder.tvTax.setTextColor(Color.parseColor(ink))

        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}
