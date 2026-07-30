package com.example.easy_billing.gstr1

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * B2CS (GSTR-1 Table 7) row adapter — same ledger row as the other sections.
 *
 * Table 7 is *consolidated*, not invoice-wise: one row per
 * (place of supply, rate, e-commerce operator). So there is no invoice number
 * and no date — the place of supply is the row's identity.
 *
 * Beyond the raw row this shows three derived facts, all computable from what
 * the row already carries:
 *
 *  • INTRA / INTER — the place-of-supply state code compared against the
 *    shop's own (taken from the first two digits of its GSTIN). This decides
 *    whether the supply sits in Table 7A or 7B.
 *  • the tax split — CGST + SGST on intra-state, IGST on inter-state, which
 *    is the check most likely to catch a wrongly-taxed bucket.
 *  • the total including tax.
 *
 * These totals are reported NET of credit/debit notes on small B2C sales, so a
 * bucket can legitimately be negative in a month where refunds exceeded sales.
 * That is shown in red rather than hidden — a negative here is a real,
 * reportable figure, not an error.
 */
class Gstr1B2csAdapter(
    private val rows: List<B2CSRow>,
    /** Shop's own state code, used to tell intra-state from inter-state. */
    private val shopStateCode: String = ""
) : RecyclerView.Adapter<Gstr1B2csAdapter.VH>() {

    private val stripePalette = listOf(
        "#7F77DD", // purple
        "#1D9E75", // teal
        "#D85A30", // coral
        "#D4537E", // pink
        "#BA7517", // amber
        "#378ADD"  // blue
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View         = view.findViewById(R.id.vStripe)
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

    /** Keeps the minus sign outside the rupee symbol: -₹1,200, not ₹-1,200. */
    private fun money(v: Double): String {
        val sign = if (v < 0) "-" else ""
        val a = kotlin.math.abs(v)
        return if (a % 1.0 == 0.0) "$sign₹%,.0f".format(a) else "$sign₹%,.2f".format(a)
    }

    /** Leading 2-digit state code out of a "33-Tamil Nadu" place of supply. */
    private fun stateCodeOf(pos: String): String =
        pos.takeWhile { it.isDigit() }.padStart(2, '0').takeIf { it.length == 2 && pos.firstOrNull()?.isDigit() == true } ?: ""

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val viaEcom = r.type.equals("E", ignoreCase = true) || r.ecomGstin.isNotBlank()
        val isNegative = r.taxableValue < 0.0

        // Intra vs inter — only claimed when both codes are actually known.
        val rowState = stateCodeOf(r.placeOfSupply)
        val known = rowState.isNotBlank() && shopStateCode.isNotBlank()
        val isIntra = known && rowState == shopStateCode

        holder.tvTitle.text = r.placeOfSupply.ifBlank { "Place of supply not set" }

        if (known) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (isIntra) "INTRA" else "INTER"
            val ink  = if (isIntra) "#3C3489" else "#0F5943"
            val tint = if (isIntra) "#EEEDFE" else "#E1F5EE"
            holder.tvBadge.setTextColor(Color.parseColor(ink))
            holder.tvBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            rate,
            if (viaEcom) "e-com" else "direct sales",
            r.ecomGstin.takeIf { it.isNotBlank() },
            if (isNegative) "net of returns" else null
        ).joinToString("  ·  ")

        val tax = r.taxableValue * r.rate / 100.0

        // Tax split. Intra-state is halved into CGST + SGST, inter-state is all
        // IGST — the quickest way to spot a bucket taxed the wrong way.
        holder.tvExtra.visibility = if (known) View.VISIBLE else View.GONE
        if (known) {
            holder.tvExtra.text = if (isIntra) {
                val half = tax / 2.0
                "CGST ${money(half)} + SGST ${money(half)}"
            } else {
                "IGST ${money(tax)}"
            }
        }

        holder.tvAmount.text = money(r.taxableValue)
        holder.tvAmount.setTextColor(
            Color.parseColor(if (isNegative) "#A32D2D" else "#1A1A18")
        )

        holder.tvTax.text = if (r.cessAmount != 0.0)
            "${money(tax)} · cess ${money(r.cessAmount)}"
        else
            "${money(tax)} tax"
        holder.tvTax.setTextColor(
            Color.parseColor(
                when {
                    isNegative -> "#A32D2D"
                    viaEcom    -> "#854F0B"
                    else       -> "#0F6E56"
                }
            )
        )

        holder.tvSubAmount.visibility = View.VISIBLE
        holder.tvSubAmount.text = "${money(r.taxableValue + tax + r.cessAmount)} total"

        val accent = if (isNegative) "#A32D2D" else stripePalette[position % stripePalette.size]
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}
