package com.example.easy_billing.gstr1

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * Adapters for the five e-commerce-operator sections (GSTR-1 Tables 14 & 15).
 *
 * Table 15 splits by who is registered on each side of the sale:
 *
 *                    | registered recipient | unregistered recipient
 *   registered  supp | B2B (invoice level)  | B2C  (POS + rate)
 *   unregistered supp| URP2B (doc level)    | URP2C (POS + rate)
 *
 * That distinction is what each row leads with: B2B and URP2B name the
 * counterparty and carry a document number and date, while B2C and URP2C are
 * aggregates keyed on place of supply and rate, so the POS is their identity.
 *
 * All five share the ledger row and this base class; only [bindRow] differs.
 */
private val ECO_PALETTE = listOf(
    "#7F77DD", // purple
    "#1D9E75", // teal
    "#D85A30", // coral
    "#D4537E", // pink
    "#378ADD"  // blue
)

private fun ecoMoney(v: Double): String {
    val sign = if (v < 0) "-" else ""
    val a = kotlin.math.abs(v)
    return if (a % 1.0 == 0.0) "$sign₹%,.0f".format(a) else "$sign₹%,.2f".format(a)
}

private fun ecoRate(r: Double): String =
    if (r % 1.0 == 0.0) "${r.toInt()}%" else "$r%"

/** Shared plumbing: inflates the ledger row and paints the stripe. */
abstract class EcoBaseAdapter<T>(
    protected val rows: List<T>
) : RecyclerView.Adapter<EcoBaseAdapter.VH>() {

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
        // Reset the optional slots — holders are recycled across sections.
        holder.tvBadge.visibility = View.GONE
        holder.tvExtra.visibility = View.GONE
        holder.tvSubAmount.visibility = View.GONE
        holder.tvAmount.setTextColor(Color.parseColor("#1A1A18"))
        holder.tvTax.setTextColor(Color.parseColor("#0F6E56"))
        holder.vStripe.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(ECO_PALETTE[position % ECO_PALETTE.size])
        )
        bindRow(holder, rows[position], position)
    }
}

/** Table 14 — summary of supplies made through each operator. */
class Gstr1EcoAdapter(rows: List<EcoRow>) : EcoBaseAdapter<EcoRow>(rows) {
    override fun bindRow(h: VH, row: EcoRow, position: Int) {
        h.tvTitle.text = row.ecoName.ifBlank { row.ecoGstin.ifBlank { "Operator not named" } }
        h.tvMeta.text = listOfNotNull(
            row.ecoGstin.ifBlank { null },
            row.natureOfSupply.ifBlank { null }
        ).joinToString("  ·  ")

        val tax = row.igst + row.cgst + row.sgst
        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = if (row.igst > 0.0)
            "IGST ${ecoMoney(row.igst)}" + if (row.cess != 0.0) "  ·  cess ${ecoMoney(row.cess)}" else ""
        else
            "CGST ${ecoMoney(row.cgst)} + SGST ${ecoMoney(row.sgst)}" +
                if (row.cess != 0.0) "  ·  cess ${ecoMoney(row.cess)}" else ""

        h.tvAmount.text = ecoMoney(row.netValue)
        h.tvTax.text = "+${ecoMoney(tax)} tax"
    }
}

/** Table 15A(I) — registered supplier to registered recipient, invoice level. */
class Gstr1EcoB2bAdapter(rows: List<EcoB2BRow>) : EcoBaseAdapter<EcoB2BRow>(rows) {
    override fun bindRow(h: VH, row: EcoB2BRow, position: Int) {
        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = ecoRate(row.rate)
        h.tvBadge.setTextColor(Color.parseColor("#3C3489"))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEDFE"))

        val party = row.recipientName.ifBlank { row.recipientGstin.ifBlank { "Recipient" } }
        h.tvTitle.text = if (row.docNumber.isBlank()) party else "${row.docNumber}  ·  $party"
        h.tvMeta.text = listOfNotNull(
            row.recipientGstin.ifBlank { null },
            row.docDate.ifBlank { null },
            row.placeOfSupply.ifBlank { null }
        ).joinToString("  ·  ")

        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = listOfNotNull(
            row.docType.ifBlank { null },
            row.supplierGstin.takeIf { it.isNotBlank() }?.let { "supplier $it" },
            if (row.cessAmount != 0.0) "cess ${ecoMoney(row.cessAmount)}" else null
        ).joinToString("  ·  ").ifBlank { "—" }

        h.tvAmount.text = ecoMoney(row.taxableValue)
        h.tvTax.text = "+${ecoMoney(row.taxableValue * row.rate / 100.0)} tax"
        h.tvSubAmount.visibility = View.VISIBLE
        h.tvSubAmount.text = "${ecoMoney(row.supplyValue)} supply"
    }
}

/** Table 15A(II) — registered supplier to UNregistered recipient, POS + rate. */
class Gstr1EcoB2cAdapter(rows: List<EcoB2CRow>) : EcoBaseAdapter<EcoB2CRow>(rows) {
    override fun bindRow(h: VH, row: EcoB2CRow, position: Int) {
        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = ecoRate(row.rate)
        h.tvBadge.setTextColor(Color.parseColor("#3C3489"))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEDFE"))

        // Aggregate row — place of supply is the identity.
        h.tvTitle.text = row.placeOfSupply.ifBlank { "Place of supply not set" }
        h.tvMeta.text = listOfNotNull(
            row.supplierName.ifBlank { null },
            row.supplierGstin.ifBlank { null },
            "unregistered buyer"
        ).joinToString("  ·  ")

        if (row.cessAmount != 0.0) {
            h.tvExtra.visibility = View.VISIBLE
            h.tvExtra.text = "cess ${ecoMoney(row.cessAmount)}"
        }

        h.tvAmount.text = ecoMoney(row.taxableValue)
        h.tvTax.text = "+${ecoMoney(row.taxableValue * row.rate / 100.0)} tax"
    }
}

/** Table 15B(I) — UNregistered supplier to registered recipient, doc level. */
class Gstr1EcoUrp2bAdapter(rows: List<EcoUrp2BRow>) : EcoBaseAdapter<EcoUrp2BRow>(rows) {
    override fun bindRow(h: VH, row: EcoUrp2BRow, position: Int) {
        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = ecoRate(row.rate)
        h.tvBadge.setTextColor(Color.parseColor("#854F0B"))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FAEEDA"))

        val party = row.recipientName.ifBlank { row.recipientGstin.ifBlank { "Recipient" } }
        h.tvTitle.text = if (row.docNumber.isBlank()) party else "${row.docNumber}  ·  $party"
        h.tvMeta.text = listOfNotNull(
            row.recipientGstin.ifBlank { null },
            row.docDate.ifBlank { null },
            row.placeOfSupply.ifBlank { null }
        ).joinToString("  ·  ")

        h.tvExtra.visibility = View.VISIBLE
        h.tvExtra.text = listOfNotNull(
            row.docType.ifBlank { null },
            "unregistered supplier",
            if (row.cessAmount != 0.0) "cess ${ecoMoney(row.cessAmount)}" else null
        ).joinToString("  ·  ")

        h.tvAmount.text = ecoMoney(row.taxableValue)
        h.tvTax.text = "+${ecoMoney(row.taxableValue * row.rate / 100.0)} tax"
        h.tvSubAmount.visibility = View.VISIBLE
        h.tvSubAmount.text = "${ecoMoney(row.supplyValue)} supply"
    }
}

/** Table 15B(II) — UNregistered supplier to UNregistered recipient, POS + rate. */
class Gstr1EcoUrp2cAdapter(rows: List<EcoUrp2CRow>) : EcoBaseAdapter<EcoUrp2CRow>(rows) {
    override fun bindRow(h: VH, row: EcoUrp2CRow, position: Int) {
        h.tvBadge.visibility = View.VISIBLE
        h.tvBadge.text = ecoRate(row.rate)
        h.tvBadge.setTextColor(Color.parseColor("#854F0B"))
        h.tvBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FAEEDA"))

        h.tvTitle.text = row.placeOfSupply.ifBlank { h.itemView.context.getString(R.string.gstr1_pos_not_set) }
        h.tvMeta.text = h.itemView.context.getString(R.string.gstr1_eco_unreg_supplier_buyer)

        if (row.cessAmount != 0.0) {
            h.tvExtra.visibility = View.VISIBLE
            h.tvExtra.text = "cess ${ecoMoney(row.cessAmount)}"
        }

        h.tvAmount.text = ecoMoney(row.taxableValue)
        h.tvTax.text = "+${ecoMoney(row.taxableValue * row.rate / 100.0)} tax"
    }
}
