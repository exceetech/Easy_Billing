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
 * B2B (GSTR-1 Table 4) row adapter.
 *
 * Unlike the generic [Gstr1RowAdapter] — which renders every section as an
 * anonymous two-line text pair — this one knows what a B2B row actually is, so
 * it can surface the fields that decide which GST table the supply belongs to:
 *
 *  • reverse charge ("Y") → the supply belongs in Table 4B, not 4A
 *  • invoice type other than "Regular" → SEZ / deemed export treatment
 *
 * Those rows get an amber warning glyph and a red stripe so a misclassified
 * invoice is visible while scanning, rather than buried in a text blob. Clean
 * regular supplies get a green tick and a teal stripe.
 */
class Gstr1B2bAdapter(
    private val rows: List<B2BRow>
) : RecyclerView.Adapter<Gstr1B2bAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View       = view.findViewById(R.id.vStripe)
        val tvTitle: TextView   = view.findViewById(R.id.tvTitle)
        val tvMeta: TextView    = view.findViewById(R.id.tvMeta)
        val tvAmount: TextView  = view.findViewById(R.id.tvAmount)
        val tvTax: TextView     = view.findViewById(R.id.tvTax)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr_ledger_row, parent, false))

    override fun getItemCount() = rows.size

    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        return if (v % 1.0 == 0.0) "$symbol%,.0f".format(v) else "$symbol%,.2f".format(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]

        val isReverseCharge = r.reverseCharge.equals("Y", ignoreCase = true)
        val isSpecialType   = r.invoiceType.isNotBlank() && r.invoiceType != "Regular"
        val needsAttention  = isReverseCharge || isSpecialType

        // Title — invoice number, then whoever the supply went to.
        val party = r.receiverName.ifBlank { r.gstin.ifBlank { "Unknown party" } }
        holder.tvTitle.text = if (r.invoiceNumber.isBlank()) party
                              else "${r.invoiceNumber}  ·  $party"

        // Meta — GSTIN · date · rate, plus the flag that changes GST treatment.
        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        val flag = when {
            isReverseCharge -> "RCM"
            isSpecialType   -> r.invoiceType
            else            -> null
        }
        holder.tvMeta.text = listOfNotNull(
            r.gstin.ifBlank { null },
            r.invoiceDate.ifBlank { null },
            rate,
            flag
        ).joinToString("  ·  ")

        holder.tvAmount.text = money(holder.itemView.context, r.taxableValue)

        // Tax for this rate slab, plus cess when the goods carry it.
        val tax = r.taxableValue * r.rate / 100.0
        holder.tvTax.text = if (r.cessAmount > 0.0)
            "+${money(holder.itemView.context, tax)} · cess ${money(holder.itemView.context, r.cessAmount)}"
        else
            "+${money(holder.itemView.context, tax)}"

        // Tint (not setBackgroundColor) so the stripe keeps its rounded shape.
        // The stripe alone now carries the status: red = reverse charge,
        // amber = SEZ / deemed export, teal = ordinary Table 4A supply.
        val accent = when {
            isReverseCharge -> "#A32D2D"
            isSpecialType   -> "#BA7517"
            else            -> "#0F6E56"
        }
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
        holder.tvTax.setTextColor(Color.parseColor(if (needsAttention) "#854F0B" else "#0F6E56"))
    }
}
