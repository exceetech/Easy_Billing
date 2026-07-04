package com.example.easy_billing

import android.graphics.Color
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product

/**
 * Manage-products adapter — invoice line-item style (champagne theme).
 * Rows are dividers inside one continuous card: a color-coded avatar
 * tile, name + muted variant, a single compact meta line
 * (Category · HSN · GST% · Purchased/Manual), and a right column with
 * the price anchor plus a sub-note (stock pill, or GST note when stock
 * isn't tracked). A bottom hairline separates rows; it is hidden on the
 * last row.
 */
class ManageProductsAdapter(
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ManageProductsAdapter.VH>() {

    private var items: List<Product> = emptyList()
    private var stock: Map<Int, Double> = emptyMap()

    fun itemAt(position: Int): Product = items[position]

    fun setStock(map: Map<Int, Double>) {
        stock = map
        notifyDataSetChanged()
    }

    private val tileBg = intArrayOf(
        R.drawable.bg_addp_tile_green,
        R.drawable.bg_tile_gold,
        R.drawable.bg_tile_violet
    )
    private val tileText = intArrayOf(
        Color.parseColor("#0F6E56"),
        Color.parseColor("#B8895A"),
        Color.parseColor("#6C4EA0")
    )

    fun submit(newItems: List<Product>) {
        val oldLast = items.size - 1
        val diff = DiffUtil.calculateDiff(Diff(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
        // Rebind the old/new last rows so the trailing divider toggles
        // correctly when items are added or removed at the end.
        val newLast = items.size - 1
        if (oldLast in 0 until items.size) notifyItemChanged(oldLast)
        if (newLast >= 0 && newLast != oldLast) notifyItemChanged(newLast)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_product, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // Avatar tile (color-coded, cycles for visual variety).
        val slot = position % 3
        holder.avatarTile.setBackgroundResource(tileBg[slot])
        holder.tvAvatar.setTextColor(tileText[slot])
        holder.tvAvatar.text = item.name.trim().firstOrNull()?.uppercase() ?: "?"

        // Name + muted variant.
        val variant = item.variant?.takeIf { it.isNotBlank() }
        if (variant != null) {
            val full = "${item.name}   ·   $variant"
            val sp = SpannableString(full)
            val from = item.name.length
            sp.setSpan(ForegroundColorSpan(Color.parseColor("#A99E88")), from, full.length, 0)
            sp.setSpan(AbsoluteSizeSpan(12, true), from, full.length, 0)
            holder.tvName.text = sp
        } else {
            holder.tvName.text = item.name
        }

        // GST value.
        val gst = (item.cgstPercentage + item.sgstPercentage)
            .let { if (it > 0) it else item.igstPercentage }

        // Single meta line: Category · HSN · GST% · Origin.
        val meta = buildList {
            item.category.takeIf { it.isNotBlank() }?.let { add(it) }
            item.hsnCode?.takeIf { it.isNotBlank() }?.let { add("HSN $it") }
            add(if (gst > 0) "GST ${trimNum(gst)}%" else "No GST")
            add(if (item.isPurchased) "Purchased" else "Manual")
        }.joinToString("  ·  ")
        holder.tvMeta.text = meta

        // Price anchor.
        holder.tvPrice.text = money(item.price)

        // Sub-note: stock pill when tracked, else a plain GST note.
        val note = holder.tvSubNote
        val qty = if (item.trackInventory) stock[item.id] else null
        if (qty != null) {
            val pad = note.dp(9)
            val padV = note.dp(2)
            note.setPadding(pad, padV, pad, padV)
            when {
                qty <= 0.0 -> {
                    note.text = "Out of stock"
                    note.setBackgroundResource(R.drawable.bg_mp_pill_red)
                    note.setTextColor(Color.parseColor("#B23A3A"))
                }
                qty <= LOW_STOCK -> {
                    note.text = "${trimNum(qty)} low"
                    note.setBackgroundResource(R.drawable.bg_mp_pill_amber)
                    note.setTextColor(Color.parseColor("#B45309"))
                }
                else -> {
                    note.text = "${trimNum(qty)} in stock"
                    note.setBackgroundResource(R.drawable.bg_mp_badge_green)
                    note.setTextColor(Color.parseColor("#0F6E56"))
                }
            }
        } else {
            // No stock tracked → unlimited.
            note.setBackgroundResource(0)
            note.setPadding(0, 0, 0, 0)
            note.text = "Unlimited"
            note.setTextColor(Color.parseColor("#A99E88"))
        }

        // Hairline divider — hidden on the last row.
        holder.vDivider.visibility =
            if (position == items.size - 1) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private companion object { const val LOW_STOCK = 10.0 }

    private fun money(p: Double): String =
        if (p % 1.0 == 0.0) "₹${p.toLong()}" else "₹${"%.2f".format(p)}"

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun View.dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatarTile: View = view.findViewById(R.id.avatarTile)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvSubNote: TextView = view.findViewById(R.id.tvSubNote)
        val vDivider: View = view.findViewById(R.id.vDivider)
    }

    private class Diff(
        private val old: List<Product>,
        private val new: List<Product>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }
}
