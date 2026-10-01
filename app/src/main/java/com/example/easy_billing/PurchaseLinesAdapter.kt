package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft
import com.example.easy_billing.util.CurrencyHelper

/**
 * One purchase-draft line paired with its slot index. PurchaseItemDraft has
 * no server id and is fully immutable (every field is `val`, so an edit
 * always creates a fresh object via `.copy()`), but two lines CAN have
 * identical field values (e.g. the same product added twice with the same
 * quantity) — the index keeps DiffUtil's identity unambiguous in that case.
 * Line order is append/remove-at-index only (no reordering), so the index
 * stays a stable identity across submits.
 */
internal data class PurchaseLineRow(val index: Int, val draft: PurchaseItemDraft)

private val PURCHASE_LINE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<PurchaseLineRow>() {
    override fun areItemsTheSame(oldItem: PurchaseLineRow, newItem: PurchaseLineRow): Boolean =
        oldItem.index == newItem.index

    override fun areContentsTheSame(oldItem: PurchaseLineRow, newItem: PurchaseLineRow): Boolean =
        oldItem.draft == newItem.draft
}

/**
 * Adapter for the line-item list inside [PurchaseActivity]. Each row has
 * an avatar tile, the product name on its own line, a brand/type tag row
 * (each tag hidden when blank), a "qty × rate · GST%" meta line, and a
 * right column with the line total plus its tax. A hairline separates
 * rows.
 */
internal class PurchaseLinesAdapter(
    initialItems: List<PurchaseItemDraft>,
    private val onRemove: (Int) -> Unit,
    private val onEdit: (Int) -> Unit = {}
) : ListAdapter<PurchaseLineRow, PurchaseLinesAdapter.VH>(PURCHASE_LINE_DIFF_CALLBACK) {

    init {
        submitList(initialItems.mapIndexed { index, draft -> PurchaseLineRow(index, draft) })
    }

    fun submit(newItems: List<PurchaseItemDraft>) {
        submitList(newItems.mapIndexed { index, draft -> PurchaseLineRow(index, draft) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_line, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position).draft

        // Avatar — up to two initials from the product name.
        holder.tvAvatar.text = initials(item.productName)

        // Name gets its own full-width line — never truncated by brand,
        // type, or the review tag competing for space.
        holder.tvName.text = item.productName

        // Brand + type each get their own small tag below the name, so
        // both stay visible regardless of how long the product name is.
        // A blank brand and/or type simply omits that tag; the whole row
        // is hidden when neither is present.
        val brand = item.brand?.takeIf { it.isNotBlank() }
        val type = item.variant?.takeIf { it.isNotBlank() }
        if (brand != null) {
            holder.tvBrandTag.text = brand
            holder.tvBrandTag.visibility = View.VISIBLE
        } else {
            holder.tvBrandTag.visibility = View.GONE
        }
        if (type != null) {
            holder.tvTypeTag.text = type
            holder.tvTypeTag.visibility = View.VISIBLE
        } else {
            holder.tvTypeTag.visibility = View.GONE
        }
        holder.llTags.visibility = if (brand != null || type != null) View.VISIBLE else View.GONE

        // Amber "Review" tag — only on lines added with placeholder
        // values (Inventory's "Add stock") that haven't been opened +
        // saved yet.
        holder.tvReviewTag.visibility = if (item.reviewed) View.GONE else View.VISIBLE

        // Derived figures.
        val tax = (item.invoiceValue - item.taxableAmount).coerceAtLeast(0.0)
        val rate = if (item.quantity > 0) item.taxableAmount / item.quantity else 0.0
        val gstPct = if (item.taxableAmount > 0) tax / item.taxableAmount * 100.0 else 0.0

        // Meta: "20 × ₹460 · GST 5%" (+ discount when present).
        holder.tvMeta.text = buildString {
            append("${trimNum(item.quantity)} × ${money(holder.itemView.context, rate)}")
            append(if (gstPct > 0) "  ·  GST ${trimNum(round1(gstPct))}%" else "  ·  No GST")
            if (item.discountAmount > 0) append("  ·  Disc ${money(holder.itemView.context, item.discountAmount)}")
        }

        // Line total + tax.
        holder.tvPrice.text = money(holder.itemView.context, item.invoiceValue)
        if (tax > 0) {
            holder.tvTax.text = "+${money(holder.itemView.context, tax)} tax"
            holder.tvTax.setTextColor(Color.parseColor("#0F6E56"))
        } else {
            holder.tvTax.text = holder.itemView.context.getString(R.string.invoice_no_tax)
            holder.tvTax.setTextColor(Color.parseColor("#A99E88"))
        }

        holder.vDivider.visibility =
            if (position == itemCount - 1) View.GONE else View.VISIBLE

        holder.btnRemove.setOnClickListener { onRemove(holder.adapterPosition) }
        holder.btnEdit.setOnClickListener { onEdit(holder.adapterPosition) }
        holder.itemView.setOnClickListener { onEdit(holder.adapterPosition) }
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    private fun money(context: android.content.Context, v: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        return if (v % 1.0 == 0.0) "$symbol${v.toLong()}" else "$symbol${"%.2f".format(v)}"
    }

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun round1(d: Double): Double = Math.round(d * 10.0) / 10.0

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvReviewTag: TextView = view.findViewById(R.id.tvReviewTag)
        val llTags: View = view.findViewById(R.id.llTags)
        val tvBrandTag: TextView = view.findViewById(R.id.tvBrandTag)
        val tvTypeTag: TextView = view.findViewById(R.id.tvTypeTag)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvTax: TextView = view.findViewById(R.id.tvTax)
        val vDivider: View = view.findViewById(R.id.vDivider)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
