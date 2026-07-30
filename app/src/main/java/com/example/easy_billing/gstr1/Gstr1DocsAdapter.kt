package com.example.easy_billing.gstr1

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/**
 * DOCS (GSTR-1 Table 13) row adapter — document-series summary.
 *
 * The odd one out: this table has no money in it at all. It declares document
 * *numbering continuity* — for each series, the first and last number issued,
 * how many were issued, and how many of those were cancelled. So the count
 * takes the position the amount holds elsewhere, and the serial range is the
 * row's real content.
 *
 * A series with cancellations is marked amber, since that is the figure an
 * officer is most likely to ask about.
 */
class Gstr1DocsAdapter(
    private val rows: List<DocsRow>
) : RecyclerView.Adapter<Gstr1DocsAdapter.VH>() {

    private val stripePalette = listOf(
        "#7F77DD", // purple
        "#1D9E75", // teal
        "#378ADD", // blue
        "#D4537E"  // pink
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View         = view.findViewById(R.id.vStripe)
        val tvTitle: TextView     = view.findViewById(R.id.tvTitle)
        val tvMeta: TextView      = view.findViewById(R.id.tvMeta)
        val tvAmount: TextView    = view.findViewById(R.id.tvAmount)
        val tvTax: TextView       = view.findViewById(R.id.tvTax)
        val tvSubAmount: TextView = view.findViewById(R.id.tvSubAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr_ledger_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val hasCancelled = r.cancelled > 0

        holder.tvTitle.text = r.natureOfDoc.ifBlank { "Document series" }

        // The serial range is the substance of this row.
        holder.tvMeta.text = when {
            r.srFrom.isBlank() && r.srTo.isBlank() -> "No documents issued"
            r.srFrom == r.srTo                     -> r.srFrom
            else                                   -> "${r.srFrom}  →  ${r.srTo}"
        }

        // Count sits where the amount sits in the other sections.
        holder.tvAmount.text = r.totalNumber.toString()
        holder.tvAmount.setTextColor(Color.parseColor("#1A1A18"))
        holder.tvTax.text = if (r.totalNumber == 1) "document" else "documents"
        holder.tvTax.setTextColor(Color.parseColor("#9A8F79"))

        holder.tvSubAmount.visibility = if (hasCancelled) View.VISIBLE else View.GONE
        if (hasCancelled) {
            holder.tvSubAmount.text = "${r.cancelled} cancelled"
            holder.tvSubAmount.setTextColor(Color.parseColor("#A32D2D"))
        }

        val accent = if (hasCancelled) "#BA7517" else stripePalette[position % stripePalette.size]
        holder.vStripe.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
    }
}
