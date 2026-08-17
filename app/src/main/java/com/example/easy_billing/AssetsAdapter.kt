package com.example.easy_billing

import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product
import com.example.easy_billing.util.CurrencyHelper
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The bucket an asset row belongs to — derived at load time from either
 * [Product.isRawMaterial] (independent tag, always wins) or the most
 * recent [com.example.easy_billing.db.PurchaseItem.eligibilityForItc]
 * for that product ("Capital goods" / "Input services"). Drives the
 * KPI tiles, filter chips, and per-row stripe/tag color in
 * [AssetsAdapter] — matches the Inventory screen's category-coded rows.
 */
enum class AssetKind(val label: String) {
    CAPITAL_GOODS("Capital goods"),
    INPUT_SERVICES("Input services"),
    RAW_MATERIAL("Raw material")
}

/**
 * One row on the Assets screen: a Product plus its resolved [kind],
 * purchase date, and [invoiceValue] — the purchase line's total invoice
 * value (quantity × cost, i.e. what was actually paid), shown instead of
 * [Product.price] which stores the per-unit cost price.
 */
data class AssetRow(
    val product: Product,
    val kind: AssetKind,
    val purchaseDateMillis: Long?,
    val invoiceValue: Double
)

private val ASSET_DIFF_CALLBACK = object : DiffUtil.ItemCallback<AssetRow>() {
    override fun areItemsTheSame(oldItem: AssetRow, newItem: AssetRow): Boolean =
        oldItem.product.id == newItem.product.id

    override fun areContentsTheSame(oldItem: AssetRow, newItem: AssetRow): Boolean =
        oldItem == newItem
}

/**
 * Read-only adapter for [AssetsActivity] — products created purely for
 * asset record-keeping (isSellable = false). No click/edit/delete
 * affordance; this list is informational only. Visually mirrors
 * [com.example.easy_billing.InventoryActivity]'s `item_inventory` row —
 * accent stripe, circular monogram avatar, two muted detail lines, and
 * a plain serif price — with the stripe/avatar/tag color coded per
 * [AssetKind] so a row reads its category at a glance.
 */
class AssetsAdapter : ListAdapter<AssetRow, AssetsAdapter.VH>(ASSET_DIFF_CALLBACK) {

    fun itemAt(position: Int): AssetRow = getItem(position)

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val item = row.product

        holder.tvAvatar.text = item.name.trim().take(2).uppercase()

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

        // Meta line: Kind · purchase date.
        val dateStr = row.purchaseDateMillis?.let { dateFormat.format(it) }
        holder.tvMeta.text = listOfNotNull(row.kind.label, dateStr).joinToString(" · ")

        holder.tvPrice.text = money(holder.itemView.context, row.invoiceValue)

        val ctx = holder.itemView.context
        val (stripeColor, avatarBg, avatarText, subNote) = when (row.kind) {
            AssetKind.RAW_MATERIAL -> RowColors(
                Color.parseColor("#8A6526"),
                R.drawable.bg_tile_gold,
                Color.parseColor("#8A6526"),
                ctx.getString(R.string.assets_meta_raw_material_tag)
            )
            else -> RowColors(
                Color.parseColor("#0F6E56"),
                R.drawable.bg_credit_tile_teal,
                Color.parseColor("#085041"),
                ctx.getString(R.string.assets_meta_asset_tag)
            )
        }
        holder.vStripe.setBackgroundColor(stripeColor)
        holder.avatarTile.setBackgroundResource(avatarBg)
        holder.tvAvatar.setTextColor(avatarText)
        holder.tvSubNote.text = subNote
        holder.tvSubNote.setTextColor(stripeColor)

        // Hairline divider — hidden on the last row.
        holder.vDivider.visibility =
            if (position == itemCount - 1) View.GONE else View.VISIBLE
    }

    private data class RowColors(
        val stripe: Int,
        val avatarBgRes: Int,
        val avatarText: Int,
        val subNote: String
    )

    private fun money(context: android.content.Context, p: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(context)
        return if (p % 1.0 == 0.0) "$symbol${p.toLong()}" else "$symbol${"%.2f".format(p)}"
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val vStripe: View = view.findViewById(R.id.vStripe)
        val avatarTile: View = view.findViewById(R.id.avatarTile)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvSubNote: TextView = view.findViewById(R.id.tvSubNote)
        val vDivider: View = view.findViewById(R.id.vDivider)
    }
}
