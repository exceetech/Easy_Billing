package com.example.easy_billing.gstr2

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * GSTR-2 B2B row adapter — inward supplies from registered suppliers.
 *
 * Reuses the champagne ledger row built for GSTR-1, but what matters here is
 * the opposite of a sales table: this is money you are *claiming back*, so the
 * row leads with ITC eligibility rather than with the tax charged.
 *
 * The eligibility badge is the point of this design. A purchase marked
 * Ineligible or None carries blocked credit under s.17(5) — its tax was paid
 * but cannot be claimed — so it is shown in red with the availed credit
 * reading zero, while claimable rows show what is actually being availed.
 * Capital goods and input services get their own tint so they are
 * distinguishable at a glance from ordinary inputs.
 */
class Gstr2B2bAdapter(
    private val rows: List<Gstr2B2bRow>
) : RecyclerView.Adapter<Gstr2B2bAdapter.VH>() {

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

    private fun money(v: Double): String {
        val sign = if (v < 0) "-" else ""
        val a = kotlin.math.abs(v)
        return if (a % 1.0 == 0.0) "$sign₹%,.0f".format(a) else "$sign₹%,.2f".format(a)
    }

    /** Short label + colours for each ITC category. */
    private fun eligibilityStyle(raw: String): Triple<String, String, String> {
        return when (raw.trim().lowercase()) {
            "capital goods"  -> Triple("CAPITAL", "#0C447C", "#E6F1FB")
            "input services" -> Triple("SERVICES", "#3C3489", "#EEEDFE")
            "ineligible"     -> Triple("INELIGIBLE", "#A32D2D", "#FCEBEB")
            "none"           -> Triple("NO ITC", "#A32D2D", "#FCEBEB")
            else             -> Triple("INPUTS", "#0F5943", "#E1F5EE")
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val (label, ink, tint) = eligibilityStyle(r.eligibilityForItc)
        val blocked = r.eligibilityForItc.trim().lowercase() in listOf("ineligible", "none")

        val taxPaid = r.igstPaid + r.cgstPaid + r.sgstPaid
        val availed = r.availedItcIgst + r.availedItcCgst + r.availedItcSgst

        holder.tvBadge.visibility = View.VISIBLE
        holder.tvBadge.text = label
        holder.tvBadge.setTextColor(Color.parseColor(ink))
        holder.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))

        val supplier = r.supplierGstin.ifBlank { "Supplier GSTIN missing" }
        holder.tvTitle.text = if (r.invoiceNumber.isBlank()) supplier
                              else "${r.invoiceNumber}  ·  $supplier"

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            r.invoiceDate.ifBlank { null },
            rate,
            r.placeOfSupply.ifBlank { null },
            "RCM".takeIf { r.reverseCharge.equals("Y", ignoreCase = true) },
            r.invoiceType.takeIf { it.isNotBlank() && it != "Regular" }
        ).joinToString("  ·  ")

        // Tax paid vs credit actually available — the whole point of the table.
        holder.tvExtra.visibility = View.VISIBLE
        holder.tvExtra.text = if (blocked)
            "Tax ${money(taxPaid)} paid · credit blocked"
        else
            "Tax ${money(taxPaid)} · ITC ${money(availed)}"
        holder.tvExtra.setTextColor(
            Color.parseColor(if (blocked) "#A32D2D" else "#A89E88")
        )

        holder.tvAmount.text = money(r.taxableValue)

        // The headline figure is the credit being claimed, not the tax charged.
        holder.tvTax.text = if (blocked) "no credit" else "+${money(availed)} ITC"
        holder.tvTax.setTextColor(Color.parseColor(if (blocked) "#A32D2D" else "#0F6E56"))

        holder.tvSubAmount.visibility = if (r.cessPaid != 0.0) View.VISIBLE else View.GONE
        if (r.cessPaid != 0.0) holder.tvSubAmount.text = "cess ${money(r.cessPaid)}"

        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(if (blocked) "#A32D2D" else ink))
    }
}
