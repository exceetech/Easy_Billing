package com.example.easy_billing

import android.graphics.Color
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft

/**
 * Adapter for the line-item list inside [PurchaseActivity]. Each row
 * uses the same design as the invoice line item (item_invoice_premium):
 * an avatar tile, name + "qty × rate · GST%" meta, and a right column
 * with the line total plus its tax. A hairline separates rows.
 */
class PurchaseLinesAdapter(
    private var items: List<PurchaseItemDraft>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PurchaseLinesAdapter.VH>() {

    fun submit(newItems: List<PurchaseItemDraft>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_line, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // Avatar — up to two initials from the product name.
        holder.tvAvatar.text = initials(item.productName)

        // Name + muted variant (matches invoice / manage rows).
        val variant = item.variant?.takeIf { it.isNotBlank() }
        if (variant != null) {
            val full = "${item.productName}   ·   $variant"
            val sp = SpannableString(full)
            val from = item.productName.length
            sp.setSpan(ForegroundColorSpan(Color.parseColor("#A99E88")), from, full.length, 0)
            sp.setSpan(AbsoluteSizeSpan(11, true), from, full.length, 0)
            holder.tvName.text = sp
        } else {
            holder.tvName.text = item.productName
        }

        // Derived figures.
        val tax = (item.invoiceValue - item.taxableAmount).coerceAtLeast(0.0)
        val rate = if (item.quantity > 0) item.taxableAmount / item.quantity else 0.0
        val gstPct = if (item.taxableAmount > 0) tax / item.taxableAmount * 100.0 else 0.0

        // Meta: "20 × ₹460 · GST 5%" (+ discount when present).
        holder.tvMeta.text = buildString {
            append("${trimNum(item.quantity)} × ${money(rate)}")
            append(if (gstPct > 0) "  ·  GST ${trimNum(round1(gstPct))}%" else "  ·  No GST")
            if (item.discountAmount > 0) append("  ·  Disc ${money(item.discountAmount)}")
        }

        // Line total + tax.
        holder.tvPrice.text = money(item.invoiceValue)
        if (tax > 0) {
            holder.tvTax.text = "+${money(tax)} tax"
            holder.tvTax.setTextColor(Color.parseColor("#0F6E56"))
        } else {
            holder.tvTax.text = "no tax"
            holder.tvTax.setTextColor(Color.parseColor("#A99E88"))
        }

        holder.vDivider.visibility =
            if (position == items.size - 1) View.GONE else View.VISIBLE

        holder.btnRemove.setOnClickListener { onRemove(holder.adapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "₹${v.toLong()}" else "₹${"%.2f".format(v)}"

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun round1(d: Double): Double = Math.round(d * 10.0) / 10.0

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvTax: TextView = view.findViewById(R.id.tvTax)
        val vDivider: View = view.findViewById(R.id.vDivider)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
