package com.example.easy_billing.gstr1

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * B2CL (GSTR-1 Table 5) row adapter — same ledger row as B2B.
 *
 * Table 5 is invoice-wise reporting of **inter-state** supplies to unregistered
 * customers above the threshold (Rs 1 lakh from Aug 2024, Rs 2.5 lakh before).
 * Every row here is inter-state by definition, so unlike B2B there is no
 * reverse-charge / SEZ split to flag. What does vary is whether the sale went
 * through an e-commerce operator, so that's what the stripe marks:
 *
 *   teal  = direct inter-state sale
 *   amber = supplied through an e-commerce operator (ecomGstin present)
 *
 * There is no recipient GSTIN or name on a B2CL row — the buyer is
 * unregistered — so the place of supply is the identity shown instead.
 */
class Gstr1B2clAdapter(
    private val rows: List<B2CLRow>
) : RecyclerView.Adapter<Gstr1B2clAdapter.VH>() {

    // Every B2CL row is an ordinary inter-state supply, so there is no status
    // to encode in the stripe the way B2B does (reverse charge / SEZ). Cycle
    // the champagne palette instead so consecutive rows stay easy to tell
    // apart. The e-commerce case is still called out in the meta line.
    private val stripePalette = listOf(
        "#7F77DD", // purple
        "#1D9E75", // teal
        "#D85A30", // coral
        "#D4537E", // pink
        "#BA7517", // amber
        "#378ADD"  // blue
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View      = view.findViewById(R.id.vStripe)
        val tvTitle: TextView  = view.findViewById(R.id.tvTitle)
        val tvMeta: TextView   = view.findViewById(R.id.tvMeta)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvTax: TextView    = view.findViewById(R.id.tvTax)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr_ledger_row, parent, false))

    override fun getItemCount() = rows.size

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹%,.0f".format(v) else "₹%,.2f".format(v)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val viaEcom = r.ecomGstin.isNotBlank()

        val pos = r.placeOfSupply.ifBlank { "Place of supply not set" }
        holder.tvTitle.text = if (r.invoiceNumber.isBlank()) pos
                              else "${r.invoiceNumber}  ·  $pos"

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            r.invoiceDate.ifBlank { null },
            rate,
            "inter-state",
            if (viaEcom) "e-com ${r.ecomGstin}" else null
        ).joinToString("  ·  ")

        holder.tvAmount.text = money(r.taxableValue)

        // Inter-state, so the whole slab is IGST.
        val tax = r.taxableValue * r.rate / 100.0
        holder.tvTax.text = if (r.cessAmount > 0.0)
            "+${money(tax)} · cess ${money(r.cessAmount)}"
        else
            "+${money(tax)}"

        val accent = stripePalette[position % stripePalette.size]
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
        holder.tvTax.setTextColor(Color.parseColor(if (viaEcom) "#854F0B" else "#0F6E56"))
    }
}
