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
 * HSN summary (GSTR-1 Table 12) row adapter — used for both HSN(B2B) and
 * HSN(B2C), which share a row shape and differ only in their source invoices.
 *
 * This table is product-level rather than customer-level, so quantity and UQC
 * carry as much weight as the money: the quantity leads the row in serif, the
 * way a stock listing reads.
 *
 * A missing HSN code is called out explicitly (amber stripe, "HSN missing").
 * The portal rejects a blank HSN, so this is the row most likely to hold up a
 * return — it should be visible while scanning, not buried.
 */
class Gstr1HsnAdapter(
    private val rows: List<HsnRow>
) : RecyclerView.Adapter<Gstr1HsnAdapter.VH>() {

    private val stripePalette = listOf(
        "#7F77DD", // purple
        "#1D9E75", // teal
        "#D85A30", // coral
        "#D4537E", // pink
        "#378ADD"  // blue
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View         = view.findViewById(R.id.vStripe)
        val boxQty: View          = view.findViewById(R.id.boxQty)
        val tvBadge: TextView     = view.findViewById(R.id.tvBadge)
        val tvTitle: TextView     = view.findViewById(R.id.tvTitle)
        val tvMeta: TextView      = view.findViewById(R.id.tvMeta)
        val tvExtra: TextView     = view.findViewById(R.id.tvExtra)
        val tvAmount: TextView    = view.findViewById(R.id.tvAmount)
        val tvTax: TextView       = view.findViewById(R.id.tvTax)
        val tvSubAmount: TextView = view.findViewById(R.id.tvSubAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr_ledger_row, parent, false))

    override fun getItemCount() = rows.size

    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        val sign = if (v < 0) "-" else ""
        val a = kotlin.math.abs(v)
        return if (a % 1.0 == 0.0) "$sign$symbol%,.0f".format(a) else "$sign$symbol%,.2f".format(a)
    }

    private fun qty(v: Double): String =
        if (v % 1.0 == 0.0) "%,.0f".format(v) else "%,.2f".format(v)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val hsnMissing = r.hsn.isBlank() || r.hsn.equals("N/A", ignoreCase = true)
        val hasCess = r.cessAmount != 0.0
        val tax = r.igstAmount + r.cgstAmount + r.sgstAmount

        // Quantity is not a leading column — it reads after the tax split
        // below, where it sits with the other per-HSN detail.
        holder.boxQty.visibility = View.GONE

        // Rate badge.
        val rateLabel = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvBadge.visibility = if (r.rate > 0.0) View.VISIBLE else View.GONE
        holder.tvBadge.text = rateLabel
        holder.tvBadge.setTextColor(Color.parseColor("#3C3489"))
        holder.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEDFE"))

        holder.tvTitle.text = if (hsnMissing) "HSN missing" else r.hsn
        holder.tvTitle.setTextColor(
            Color.parseColor(if (hsnMissing) "#854F0B" else "#1A1A18")
        )

        holder.tvMeta.text = r.description.ifBlank { "—" }

        // Tax split — CGST+SGST means intra-state, IGST means inter-state.
        // Cess is appended so a cess-bearing HSN is obvious at a glance.
        val split = when {
            r.igstAmount > 0.0 -> "IGST ${money(holder.itemView.context, r.igstAmount)}"
            r.cgstAmount > 0.0 || r.sgstAmount > 0.0 ->
                "CGST ${money(holder.itemView.context, r.cgstAmount)} + SGST ${money(holder.itemView.context, r.sgstAmount)}"
            else -> "No tax"
        }
        val qtyLabel = "${qty(r.totalQuantity)} ${r.uqc.ifBlank { "NOS" }.uppercase()}"
        holder.tvExtra.visibility = View.VISIBLE
        holder.tvExtra.text = listOfNotNull(
            split,
            if (hasCess) "cess ${money(holder.itemView.context, r.cessAmount)}" else null,
            qtyLabel
        ).joinToString("  ·  ")

        holder.tvAmount.text = money(holder.itemView.context, r.taxableValue)
        holder.tvTax.text = "+${money(holder.itemView.context, tax)} tax"
        holder.tvTax.setTextColor(Color.parseColor(if (hsnMissing) "#854F0B" else "#0F6E56"))

        // Prefer the filed Total Value; fall back if an older server sent 0.
        val total = if (r.totalValue != 0.0) r.totalValue
                    else r.taxableValue + tax + r.cessAmount
        holder.tvSubAmount.visibility = View.VISIBLE
        holder.tvSubAmount.text = "${money(holder.itemView.context, total)} total"

        val accent = if (hsnMissing) "#BA7517" else stripePalette[position % stripePalette.size]
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}
