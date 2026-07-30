package com.example.easy_billing.gstr1

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R

/** Generic row adapter — takes list of (primary, secondary) string pairs.
 *  Champagne theme: a monogram avatar (initials from the primary line) with a
 *  per-row colour from a fixed palette, matching the credit-accounts / purchase
 *  detail row language. */
class Gstr1RowAdapter(
    private val rows: List<Pair<String, String>>
) : RecyclerView.Adapter<Gstr1RowAdapter.VH>() {

    // (avatar fill, avatar text) — same six pairs used on the purchase detail rows.
    private val palette = listOf(
        "#EEEDFE" to "#3C3489", // purple
        "#E1F5EE" to "#0F6E56", // teal
        "#FAECE7" to "#993C1D", // coral
        "#FBEAF0" to "#72243E", // pink
        "#FAEEDA" to "#854F0B", // amber
        "#E6F1FB" to "#0C447C"  // blue
    )

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView    = view.findViewById(R.id.tvAvatar)
        val tvPrimary: TextView   = view.findViewById(R.id.tvPrimary)
        val tvSecondary: TextView = view.findViewById(R.id.tvSecondary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gstr1_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (primary, secondary) = rows[position]
        holder.tvPrimary.text   = primary
        holder.tvSecondary.text = secondary

        // Monogram: first two alphanumerics of the primary line, uppercased.
        val initials = primary.filter { it.isLetterOrDigit() }.take(2).uppercase()
        holder.tvAvatar.text = if (initials.isNotEmpty()) initials else "•"

        val (fill, ink) = palette[position % palette.size]
        holder.tvAvatar.backgroundTintList = ColorStateList.valueOf(Color.parseColor(fill))
        holder.tvAvatar.setTextColor(Color.parseColor(ink))
    }
}
