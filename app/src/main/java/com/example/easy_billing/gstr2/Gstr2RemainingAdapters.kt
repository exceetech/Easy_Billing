package com.example.easy_billing.gstr2

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * The remaining four GSTR-2 sections: CDNR, CDNUR, EXEMP and HSNSUM.
 *
 * All four share the champagne ledger row; what differs is what each table is
 * for. CDNR/CDNUR are purchase returns, which REVERSE credit — so a credit
 * note reads negative, the opposite direction from a sales credit note. EXEMP
 * carries no tax at all, and HSNSUM is product-level rather than party-level.
 */

private fun g2Money(v: Double): String {
    val sign = if (v < 0) "-" else ""
    val a = kotlin.math.abs(v)
    return if (a % 1.0 == 0.0) "$sign₹%,.0f".format(a) else "$sign₹%,.2f".format(a)
}

private fun g2Rate(r: Double): String =
    if (r % 1.0 == 0.0) "${r.toInt()}%" else "$r%"

private fun g2Qty(v: Double): String =
    if (v % 1.0 == 0.0) "%,.0f".format(v) else "%,.2f".format(v)

private fun g2Blocked(raw: String) =
    raw.trim().lowercase() in listOf("ineligible", "none")

private val G2_PALETTE = listOf("#7F77DD", "#1D9E75", "#D85A30", "#D4537E", "#378ADD", "#BA7517")

/** Shared holder + optional-slot reset for all four sections. */
abstract class Gstr2BaseAdapter<T>(
    protected val rows: List<T>
) : RecyclerView.Adapter<Gstr2BaseAdapter.VH>() {

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
        // Holders are recycled across sections — clear the optional slots so
        // nothing bleeds through from a previously bound row.
        holder.tvBadge.visibility = View.GONE
        holder.tvExtra.visibility = View.GONE
        holder.tvSubAmount.visibility = View.GONE
        holder.tvAmount.setTextColor(Color.parseColor("#1A1A18"))
        holder.tvTax.setTextColor(Color.parseColor("#0F6E56"))
        bindRow(holder, rows[position], position)
    }

    /**
     * Purchase-return rows, shared by CDNR and CDNUR.
     *
     * A purchase credit note reverses credit you already took, so it renders
     * negative — the mirror image of a sales credit note, which reduces what
     * you owe. A debit note adds credit, so it stays positive.
     */
    protected fun bindReturn(
        h: VH, position: Int,
        title: String, metaParts: List<String?>,
        documentType: String, eligibility: String,
        taxable: Double, availed: Double, cess: Double, noteValue: Double
    ) {
        val isCredit = documentType.trim().lowercase().startsWith("credit")
        val blocked = g2Blocked(eligibility)
        val sign = if (isCredit) -1.0 else 1.0

        val (label, ink, tint) =
            if (isCredit) Triple("CREDIT", "#A32D2D", "#FCEBEB")
            else Triple("DEBIT", "#0F5943", "#E1F5EE")

        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = label
        h.tvBadge.setTextColor(Color.parseColor(ink))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))

        h.tvTitle.text = title
        h.tvMeta.text = metaParts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")

        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = when {
            blocked -> "Credit was blocked — nothing to reverse"
            else -> listOfNotNull(
                "ITC ${g2Money(sign * kotlin.math.abs(availed))}",
                if (cess != 0.0) "cess ${g2Money(cess)}" else null,
                if (noteValue != 0.0) "note ${g2Money(noteValue)}" else null
            ).joinToString("  ·  ")
        }
        h.tvExtra.setTextColor(Color.parseColor(if (blocked) "#A32D2D" else "#A89E88"))

        h.tvAmount.text = g2Money(sign * kotlin.math.abs(taxable))
        h.tvAmount.setTextColor(Color.parseColor(if (isCredit) "#A32D2D" else "#1A1A18"))

        h.tvTax.text = when {
            blocked  -> "no reversal"
            isCredit -> "${g2Money(-kotlin.math.abs(availed))} ITC"
            else     -> "+${g2Money(kotlin.math.abs(availed))} ITC"
        }
        h.tvTax.setTextColor(Color.parseColor(if (isCredit || blocked) "#A32D2D" else "#0F6E56"))

        h.vStripe.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (isCredit) "#A32D2D" else "#1D9E75")
        )
    }

    protected fun paletteFor(position: Int) = G2_PALETTE[position % G2_PALETTE.size]
}

/** CDNR — purchase returns to REGISTERED suppliers. */
class Gstr2CdnrAdapter(rows: List<Gstr2CdnrRow>) : Gstr2BaseAdapter<Gstr2CdnrRow>(rows) {
    override fun bindRow(h: VH, row: Gstr2CdnrRow, position: Int) {
        val supplier = row.supplierGstin.ifBlank { "Supplier GSTIN missing" }
        bindReturn(
            h, position,
            title = if (row.noteNumber.isBlank()) supplier else "${row.noteNumber}  ·  $supplier",
            metaParts = listOf(
                row.noteDate.ifBlank { null },
                g2Rate(row.rate),
                row.invoiceNumber.takeIf { it.isNotBlank() }?.let { "vs $it" },
                row.reason.takeIf { it.isNotBlank() && it != "Purchase return" },
                "pre-GST".takeIf { row.preGst.equals("Y", ignoreCase = true) }
            ),
            documentType = row.documentType,
            eligibility = row.eligibilityForItc,
            taxable = row.taxableValue,
            availed = row.availedItcIgst + row.availedItcCgst + row.availedItcSgst,
            cess = row.cessPaid,
            noteValue = row.noteValue
        )
    }
}

/** CDNUR — purchase returns to UNREGISTERED suppliers. */
class Gstr2CdnurAdapter(rows: List<Gstr2CdnurRow>) : Gstr2BaseAdapter<Gstr2CdnurRow>(rows) {
    override fun bindRow(h: VH, row: Gstr2CdnurRow, position: Int) {
        bindReturn(
            h, position,
            title = row.noteNumber.ifBlank { "Note number missing" },
            metaParts = listOf(
                "unregistered",
                row.noteDate.ifBlank { null },
                g2Rate(row.rate),
                row.invoiceNumber.takeIf { it.isNotBlank() }?.let { "vs $it" },
                row.invoiceType.takeIf { it.isNotBlank() && it != "Regular" }
            ),
            documentType = row.documentType,
            eligibility = row.eligibilityForItc,
            taxable = row.taxableValue,
            availed = row.availedItcIgst + row.availedItcCgst + row.availedItcSgst,
            cess = row.cessPaid,
            noteValue = row.noteValue
        )
    }
}

/**
 * EXEMP — composition / nil-rated / exempt / non-GST inward supplies.
 *
 * The server emits a single "Total" row holding four figures, so rather than
 * render one near-empty line this splits it into four rows — one per category
 * — and shows only the categories that actually have a value.
 */
class Gstr2ExempAdapter(
    rows: List<Gstr2ExempRow>
) : Gstr2BaseAdapter<Pair<String, Double>>(
    rows.flatMap { r ->
        listOf(
            "Composition dealer" to r.composition,
            "Nil rated" to r.nilRated,
            "Exempted" to r.exempted,
            "Non-GST" to r.nonGst
        ).filter { it.second != 0.0 }
    }
) {
    override fun bindRow(h: VH, row: Pair<String, Double>, position: Int) {
        h.tvTitle.text = row.first
        h.tvMeta.text = "no input tax credit"
        h.tvAmount.text = g2Money(row.second)
        h.tvTax.text = "exempt"
        h.tvTax.setTextColor(Color.parseColor("#9A8F79"))
        h.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(paletteFor(position)))
    }
}

/** HSNSUM — product-level summary of all inward supplies. */
class Gstr2HsnsumAdapter(rows: List<Gstr2HsnsumRow>) : Gstr2BaseAdapter<Gstr2HsnsumRow>(rows) {
    override fun bindRow(h: VH, row: Gstr2HsnsumRow, position: Int) {
        val hsnMissing = row.hsn.isBlank() || row.hsn.equals("Unknown", ignoreCase = true)
        val tax = row.igstAmount + row.cgstAmount + row.sgstAmount

        if (row.rate > 0.0) {
            h.tvBadge.visibility = View.VISIBLE
            h.tvBadge.text = g2Rate(row.rate)
            h.tvBadge.setTextColor(Color.parseColor("#3C3489"))
            h.tvBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEDFE"))
        }

        h.tvTitle.text = if (hsnMissing) "HSN missing" else row.hsn
        h.tvTitle.setTextColor(Color.parseColor(if (hsnMissing) "#854F0B" else "#1A1A18"))
        h.tvMeta.text = row.description.ifBlank { "—" }

        val split = when {
            row.igstAmount > 0.0 -> "IGST ${g2Money(row.igstAmount)}"
            row.cgstAmount > 0.0 || row.sgstAmount > 0.0 ->
                "CGST ${g2Money(row.cgstAmount)} + SGST ${g2Money(row.sgstAmount)}"
            else -> "No tax"
        }
        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = listOfNotNull(
            split,
            if (row.cessAmount != 0.0) "cess ${g2Money(row.cessAmount)}" else null,
            "${g2Qty(row.totalQuantity)} ${row.uqc.ifBlank { "OTH" }.uppercase()}"
        ).joinToString("  ·  ")

        h.tvAmount.text = g2Money(row.taxableValue)
        h.tvTax.text = "+${g2Money(tax)} tax"
        h.tvTax.setTextColor(Color.parseColor(if (hsnMissing) "#854F0B" else "#0F6E56"))

        if (row.totalValue != 0.0) {
            h.tvSubAmount.visibility = View.VISIBLE
            h.tvSubAmount.text = "${g2Money(row.totalValue)} total"
        }

        h.vStripe.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (hsnMissing) "#BA7517" else paletteFor(position))
        )
    }
}
