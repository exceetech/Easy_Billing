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
 * CDNUR (GSTR-1 Table 9B, unregistered) row adapter.
 *
 * Same shape as CDNR, because the same thing matters most: credit and debit
 * notes move tax in opposite directions, so the type is stated twice over —
 * a CREDIT / DEBIT badge and a signed amount.
 *
 * Two differences from CDNR, both from what Table 9B-unregistered actually is:
 *  • there is no recipient GSTIN or name (the buyer is unregistered), so the
 *    place of supply carries the row's identity;
 *  • the ur_type (B2CL / EXPWP / EXPWOP …) is shown, because it is the field
 *    that says *why* this note is reported invoice-wise rather than being
 *    netted into Table 7.
 */
class Gstr1CdnurAdapter(
    private val rows: List<CdnurRow>
) : RecyclerView.Adapter<Gstr1CdnurAdapter.VH>() {

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

        val pos = r.placeOfSupply.ifBlank { "Place of supply not set" }
        holder.tvTitle.text = if (r.noteNumber.isBlank()) pos
                              else "${r.noteNumber}  ·  $pos"

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            r.noteDate.ifBlank { null },
            rate,
            r.urType.ifBlank { null }
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
