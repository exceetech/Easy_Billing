package com.example.easy_billing.gstr2

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper

/**
 * GSTR-2 B2BUR row adapter — inward supplies from UNREGISTERED suppliers.
 *
 * These suppliers have no GSTIN, so they cannot legally charge GST. The buyer
 * self-assesses the tax under reverse charge and then claims it back, which
 * makes reverse charge the defining attribute of this table — hence the RCM
 * badge leading each row, and the supplier's *name* standing in for a GSTIN.
 *
 * The row also calls out the case the data model can't currently handle: an
 * unregistered purchase carrying no tax at all. That is what a correctly
 * entered cash purchase looks like today, but it usually means RCM was owed
 * and never self-assessed — so the row says so rather than quietly showing a
 * clean zero.
 */
class Gstr2B2burAdapter(
    private val rows: List<Gstr2B2burRow>
) : RecyclerView.Adapter<Gstr2B2burAdapter.VH>() {

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

    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        val sign = if (v < 0) "-" else ""
        val a = kotlin.math.abs(v)
        return if (a % 1.0 == 0.0) "$sign$symbol%,.0f".format(a) else "$sign$symbol%,.2f".format(a)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val isRcm = r.reverseCharge.equals("Y", ignoreCase = true)
        val blocked = r.eligibilityForItc.trim().lowercase() in listOf("ineligible", "none")

        val taxPaid = r.igstPaid + r.cgstPaid + r.sgstPaid
        val availed = r.availedItcIgst + r.availedItcCgst + r.availedItcSgst
        // No tax on an unregistered purchase usually means RCM was owed and
        // never self-assessed — worth surfacing, not hiding behind a zero.
        val rcmLikelyMissed = !isRcm && taxPaid == 0.0

        val ctx = holder.itemView.context
        val (label, ink, tint) = when {
            blocked  -> Triple(ctx.getString(R.string.gstr2_itc_none), "#A32D2D", "#FCEBEB")
            isRcm    -> Triple(ctx.getString(R.string.gstr2_flag_rcm), "#854F0B", "#FAEEDA")
            else     -> Triple(ctx.getString(R.string.gstr2_flag_no_rcm), "#5F5E5A", "#F1EFE8")
        }
        holder.tvBadge.visibility = View.VISIBLE
        holder.tvBadge.text = label
        holder.tvBadge.setTextColor(Color.parseColor(ink))
        holder.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))

        val supplier = r.supplierName.ifBlank { ctx.getString(R.string.gstr2_supplier_not_named) }
        holder.tvTitle.text = if (r.invoiceNumber.isBlank()) supplier
                              else "${r.invoiceNumber}  ·  $supplier"

        val rate = if (r.rate % 1.0 == 0.0) "${r.rate.toInt()}%" else "${r.rate}%"
        holder.tvMeta.text = listOfNotNull(
            ctx.getString(R.string.gstr2_unregistered),
            r.invoiceDate.ifBlank { null },
            rate,
            r.placeOfSupply.ifBlank { null }
        ).joinToString("  ·  ")

        holder.tvExtra.visibility = View.VISIBLE
        when {
            blocked -> {
                holder.tvExtra.text = "Tax ${money(holder.itemView.context, taxPaid)} paid · credit blocked"
                holder.tvExtra.setTextColor(Color.parseColor("#A32D2D"))
            }
            rcmLikelyMissed -> {
                holder.tvExtra.text = ctx.getString(R.string.gstr2_rcm_likely_missed)
                holder.tvExtra.setTextColor(Color.parseColor("#BA7517"))
            }
            else -> {
                holder.tvExtra.text = listOfNotNull(
                    "Tax ${money(holder.itemView.context, taxPaid)}",
                    "ITC ${money(holder.itemView.context, availed)}",
                    r.supplyType.ifBlank { null }
                ).joinToString("  ·  ")
                holder.tvExtra.setTextColor(Color.parseColor("#A89E88"))
            }
        }

        holder.tvAmount.text = money(holder.itemView.context, r.taxableValue)

        holder.tvTax.text = when {
            blocked          -> ctx.getString(R.string.gstr2_no_credit)
            availed == 0.0   -> ctx.getString(R.string.gstr2_no_itc)
            else             -> "+${money(holder.itemView.context, availed)} ITC"
        }
        holder.tvTax.setTextColor(
            Color.parseColor(
                when {
                    blocked        -> "#A32D2D"
                    availed == 0.0 -> "#A89E88"
                    else           -> "#0F6E56"
                }
            )
        )

        holder.tvSubAmount.visibility = if (r.cessPaid != 0.0) View.VISIBLE else View.GONE
        if (r.cessPaid != 0.0) holder.tvSubAmount.text = "cess ${money(holder.itemView.context, r.cessPaid)}"

        val accent = when {
            blocked         -> "#A32D2D"
            rcmLikelyMissed -> "#9A8F79"
            isRcm           -> "#854F0B"
            else            -> "#9A8F79"
        }
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}
