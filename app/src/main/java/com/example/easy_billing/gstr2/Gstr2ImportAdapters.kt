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
 * Adapters for the two GSTR-2 import sections.
 *
 *  IMPS — import of SERVICES, identified by the supplier's invoice.
 *  IMPG — import of GOODS, identified by the bill of entry and port code.
 *
 * Both are IGST-only: an import is inter-state by definition, so there is no
 * CGST/SGST split to show, and both schemas correctly carry only IGST + cess.
 * Both are also reverse-charge supplies — you pay the tax yourself and then
 * claim it — so the headline figure is the credit available, not the tax
 * charged, exactly as in the B2B section.
 */
private val IMPORT_PALETTE = listOf("#1D9E75", "#0C447C", "#7F77DD", "#D85A30")

private fun impMoney(context: android.content.Context, v: Double): String {
    val symbol = CurrencyHelper.getCurrencySymbol(context)
    val sign = if (v < 0) "-" else ""
    val a = kotlin.math.abs(v)
    return if (a % 1.0 == 0.0) "$sign$symbol%,.0f".format(a) else "$sign$symbol%,.2f".format(a)
}

private fun impRate(r: Double): String =
    if (r % 1.0 == 0.0) "${r.toInt()}%" else "$r%"

/** Short label + colours for each ITC category, shared by both importers. */
private fun impEligibilityStyle(raw: String): Triple<String, String, String> =
    when (raw.trim().lowercase()) {
        "capital goods"  -> Triple("CAPITAL", "#0C447C", "#E6F1FB")
        "input services" -> Triple("SERVICES", "#0F5943", "#E1F5EE")
        "ineligible"     -> Triple("NO ITC", "#A32D2D", "#FCEBEB")
        "none"           -> Triple("NO ITC", "#A32D2D", "#FCEBEB")
        else             -> Triple("INPUTS", "#0F5943", "#E1F5EE")
    }

private fun isBlocked(raw: String) =
    raw.trim().lowercase() in listOf("ineligible", "none")

/** Shared holder + inflation for both import sections. */
abstract class ImportBaseAdapter<T>(
    protected val rows: List<T>
) : RecyclerView.Adapter<ImportBaseAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
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

    protected abstract fun bindRow(h: VH, row: T, position: Int)

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Holders are recycled across sections — reset the optional slots so
        // nothing bleeds through from the previously bound row.
        holder.tvBadge.visibility = View.GONE
        holder.tvExtra.visibility = View.GONE
        holder.tvSubAmount.visibility = View.GONE
        holder.tvAmount.setTextColor(Color.parseColor("#1A1A18"))
        bindRow(holder, rows[position], position)
    }

    /** Badge + stripe + the tax/ITC line, identical for both sections. */
    protected fun paintItc(
        h: VH, position: Int, eligibility: String,
        igst: Double, cess: Double, availed: Double
    ) {
        val (label, ink, tint) = impEligibilityStyle(eligibility)
        val blocked = isBlocked(eligibility)

        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = label
        h.tvBadge.setTextColor(Color.parseColor(ink))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))

        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = if (blocked)
            "IGST ${impMoney(h.itemView.context, igst)} paid · credit blocked"
        else
            listOfNotNull(
                "IGST ${impMoney(h.itemView.context, igst)}",
                "ITC ${impMoney(h.itemView.context, availed)}",
                if (cess != 0.0) "cess ${impMoney(h.itemView.context, cess)}" else null
            ).joinToString("  ·  ")
        h.tvExtra.setTextColor(Color.parseColor(if (blocked) "#A32D2D" else "#A89E88"))

        h.tvTax.text = if (blocked) "no credit" else "+${impMoney(h.itemView.context, availed)} ITC"
        h.tvTax.setTextColor(Color.parseColor(if (blocked) "#A32D2D" else "#0F6E56"))

        val accent = if (blocked) "#A32D2D" else IMPORT_PALETTE[position % IMPORT_PALETTE.size]
        h.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}

/** IMPS — import of services, identified by the supplier's invoice. */
class Gstr2ImpsAdapter(rows: List<Gstr2ImpsRow>) : ImportBaseAdapter<Gstr2ImpsRow>(rows) {
    override fun bindRow(h: VH, row: Gstr2ImpsRow, position: Int) {
        val pos = row.placeOfSupply.ifBlank { "Place of supply not set" }
        h.tvTitle.text = if (row.invoiceNumber.isBlank()) pos
                         else "${row.invoiceNumber}  ·  $pos"
        h.tvMeta.text = listOfNotNull(
            row.invoiceDate.ifBlank { null },
            impRate(row.rate),
            "import",
            "RCM"
        ).joinToString("  ·  ")

        h.tvAmount.text = impMoney(h.itemView.context, row.taxableValue)
        paintItc(h, position, row.eligibilityForItc, row.igstPaid, row.cessPaid, row.availedItcIgst)
    }
}

/** IMPG — import of goods, identified by the bill of entry and port. */
class Gstr2ImpgAdapter(rows: List<Gstr2ImpgRow>) : ImportBaseAdapter<Gstr2ImpgRow>(rows) {
    override fun bindRow(h: VH, row: Gstr2ImpgRow, position: Int) {
        // The bill of entry, not an invoice, is the document for imported goods.
        val boe = row.billOfEntryNumber.ifBlank { "BoE not recorded" }
        val port = row.portCode.ifBlank { null }
        h.tvTitle.text = listOfNotNull("BoE $boe", port).joinToString("  ·  ")

        h.tvMeta.text = listOfNotNull(
            row.billOfEntryDate.ifBlank { null },
            impRate(row.rate),
            row.documentType.ifBlank { "Bill of Entry" },
            row.sezSupplierGstin.takeIf { it.isNotBlank() }?.let { "SEZ $it" }
        ).joinToString("  ·  ")

        h.tvAmount.text = impMoney(h.itemView.context, row.taxableValue)
        paintItc(h, position, row.eligibilityForItc, row.igstPaid, row.cessPaid, row.availedItcIgst)

        if (row.billOfEntryValue != 0.0) {
            h.tvSubAmount.visibility = View.VISIBLE
            h.tvSubAmount.text = "${impMoney(h.itemView.context, row.billOfEntryValue)} BoE"
        }
    }
}
