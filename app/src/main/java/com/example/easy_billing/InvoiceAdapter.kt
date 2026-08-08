package com.example.easy_billing.adapter

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.Product
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GstBillingCalculator

/**
 * One row's full display state — a value snapshot, not a live reference.
 *
 * [liveItem] is the actual mutable [CartItem] from InvoiceActivity's cart
 * (needed so the discount-edit click callback mutates the real object), but
 * everything DiffUtil compares comes from [quantity]/[discountAmount]/
 * [product] — copied out of the CartItem AT THE MOMENT this row was built.
 *
 * This separation matters because CartItem.quantity/discountAmount are
 * `var`s mutated in place (e.g. the discount dialog does
 * `item.discountAmount = x` on the same object InvoiceActivity already
 * holds). If DiffUtil compared the live CartItem directly, both the "old"
 * and "new" submitted lists would end up pointing at the same
 * already-mutated object by the time the diff actually runs, so a real
 * change could look like no change at all. Snapshotting the values here
 * avoids that trap.
 */
private data class InvoiceRow(
    val index: Int,
    val liveItem: CartItem,
    val product: Product,
    val quantity: Double,
    val discountAmount: Double,
    val line: GstBillingCalculator.LineBreakdown?,
    val supplyType: String,
    val gstScheme: String
)

private val INVOICE_ROW_DIFF_CALLBACK = object : DiffUtil.ItemCallback<InvoiceRow>() {
    // The cart's line count/order never changes on this screen (no
    // add/remove/reorder here — only per-line discount edits), so the
    // slot index is a safe, stable identity.
    override fun areItemsTheSame(oldItem: InvoiceRow, newItem: InvoiceRow): Boolean =
        oldItem.index == newItem.index

    // Deliberately compares only the snapshotted value fields, not
    // [InvoiceRow.liveItem] itself (see class doc).
    override fun areContentsTheSame(oldItem: InvoiceRow, newItem: InvoiceRow): Boolean =
        oldItem.product == newItem.product &&
            oldItem.quantity == newItem.quantity &&
            oldItem.discountAmount == newItem.discountAmount &&
            oldItem.line == newItem.line &&
            oldItem.supplyType == newItem.supplyType &&
            oldItem.gstScheme == newItem.gstScheme
}

/**
 * Renders one premium row per cart line in
 * [com.example.easy_billing.InvoiceActivity].
 *
 * Row anatomy:
 *   [avatar]  Name                       ₹LineTotal
 *             qty × rate · GST x%         +₹tax
 *
 * The supply type drives which GST applies:
 *   • Composition Scheme → no GST charged (meta shows "no GST").
 *   • Intra-state Normal → CGST + SGST.
 *   • Inter-state Normal → IGST.
 */
class InvoiceAdapter(
    private val items: List<CartItem>,
    private var supplyType: String = SUPPLY_INTRASTATE,
    private var gstScheme: String = SCHEME_NORMAL,
    private val onItemClick: (CartItem) -> Unit = {}
) : ListAdapter<InvoiceRow, InvoiceAdapter.InvoiceViewHolder>(INVOICE_ROW_DIFF_CALLBACK) {

    companion object {
        const val SUPPLY_INTRASTATE = "intrastate"
        const val SUPPLY_INTERSTATE = "interstate"
        const val SUPPLY_COMPOSITION = "composition"

        const val SCHEME_NORMAL = "Normal GST Scheme"
        const val SCHEME_COMPOSITION = "Composition Scheme"

        // Avatar tints (alternate per row).
        private val AVATAR_BG    = intArrayOf(R.drawable.bg_inv_avatar_green, R.drawable.bg_inv_avatar_gold)
        private val AVATAR_INK   = intArrayOf(0xFF0B5544.toInt(), 0xFF8A6526.toInt())
    }

    /** Per-line discounted breakdown (parallel to [items]); null when no discount. */
    private var lineCalcs: List<GstBillingCalculator.LineBreakdown>? = null

    init {
        submitList(buildRows())
    }

    private fun buildRows(): List<InvoiceRow> = items.mapIndexed { index, cartItem ->
        InvoiceRow(
            index = index,
            liveItem = cartItem,
            product = cartItem.product,
            quantity = cartItem.quantity,
            discountAmount = cartItem.discountAmount,
            line = lineCalcs?.getOrNull(index),
            supplyType = supplyType,
            gstScheme = gstScheme
        )
    }

    class InvoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView   = view.findViewById(R.id.tvName)
        val meta: TextView   = view.findViewById(R.id.tvMeta)
        val priceOriginal: TextView = view.findViewById(R.id.tvPriceOriginal)
        val price: TextView  = view.findViewById(R.id.tvPrice)
        val tax: TextView    = view.findViewById(R.id.tvTax)
        val discountChip: TextView = view.findViewById(R.id.tvDiscountChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice_premium, parent, false)
        return InvoiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        val product = row.product
        val qty = row.quantity
        val unit = product.unit?.lowercase() ?: "unit"

        val formattedQty = if (qty % 1 == 0.0) qty.toInt().toString()
                           else String.format("%.2f", qty).trimEnd('0').trimEnd('.')

        val unitText = when (unit) {
            "kilogram"   -> "kg"
            "litre"      -> "L"
            "gram"       -> "g"
            "millilitre" -> "ml"
            "piece"      -> "pc"
            else         -> unit
        }

        // ---- Name ----
        val displayName = buildString {
            append(product.name)
            if (!product.variant.isNullOrBlank()) append(" (${product.variant})")
        }
        holder.name.text = displayName

        // ---- Avatar (initials + alternating tint) ----
        holder.avatar.text = initialsOf(product.name)
        val slot = position % 2
        holder.avatar.setBackgroundResource(AVATAR_BG[slot])
        holder.avatar.setTextColor(AVATAR_INK[slot])

        // ---- GST context ----
        val isComposition = row.gstScheme.equals(SCHEME_COMPOSITION, ignoreCase = true) ||
                            row.supplyType.equals(SUPPLY_COMPOSITION, ignoreCase = true)
        val isIntra = row.supplyType.equals(SUPPLY_INTRASTATE, ignoreCase = true)
        val isInter = row.supplyType.equals(SUPPLY_INTERSTATE, ignoreCase = true)

        val cgstPct = product.cgstPercentage
        val sgstPct = product.sgstPercentage
        val igstPct = product.igstPercentage

        // Per-line discounted breakdown from the calculator (spreads the bill discount).
        val line = row.line

        // Use the calculator's base selling price so we don't treat tax-exclusive adjustments as discounts
        val unitPrice = line?.sellingPrice ?: product.price
        val grossSubtotal = unitPrice * row.quantity

        val netTaxable = line?.taxableAmount ?: grossSubtotal
        val hasDiscount = line != null && netTaxable < grossSubtotal - 0.01

        // Tax amounts: prefer the calculator's per-line figures (on the net taxable).
        val cgstAmt = line?.cgstAmount ?: (grossSubtotal * cgstPct / 100.0)
        val sgstAmt = line?.sgstAmount ?: (grossSubtotal * sgstPct / 100.0)
        val igstAmt = line?.igstAmount ?: (grossSubtotal * igstPct / 100.0)

        val (taxAmt, taxPct) = when {
            isComposition -> 0.0 to 0.0
            isIntra       -> (cgstAmt + sgstAmt) to (cgstPct + sgstPct)
            isInter       -> igstAmt to igstPct
            else          -> 0.0 to 0.0
        }

        // ---- Meta line: "2 × ₹480 · GST 5%" ----
        val rateText = CurrencyHelper.format(context, unitPrice)
        holder.meta.text = buildString {
            append("$formattedQty $unitText × $rateText")
            if (taxPct > 0.0) append(" · GST ${formatPct(taxPct)}")
        }

        // ---- Line amount (ex-GST). Strike the original when discounted. ----
        if (hasDiscount) {
            holder.priceOriginal.visibility = View.VISIBLE
            holder.priceOriginal.text = CurrencyHelper.format(context, grossSubtotal)
            holder.priceOriginal.paintFlags =
                holder.priceOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.price.text = CurrencyHelper.format(context, netTaxable)
        } else {
            holder.priceOriginal.visibility = View.GONE
            holder.price.text = CurrencyHelper.format(context, netTaxable)
        }

        holder.tax.text = if (taxAmt > 0.0)
            "+${CurrencyHelper.format(context, taxAmt)} tax"
        else
            "no tax"

        // Discoverable discount chip — shows current state, opens the dialog.
        if (row.discountAmount > 0.0) {
            holder.discountChip.text =
                "✎  ${CurrencyHelper.format(context, row.discountAmount)} off · edit"
            holder.discountChip.setTextColor(0xFF0F6E56.toInt())
        } else {
            holder.discountChip.text = "＋ Add discount"
            holder.discountChip.setTextColor(0xFF8A6526.toInt())
        }
        // Passes the LIVE CartItem, not the snapshot — the discount dialog
        // needs to mutate the object InvoiceActivity's saveBill() will
        // actually read from.
        holder.discountChip.setOnClickListener { onItemClick(row.liveItem) }
    }

    /** Feed the calculator's per-line breakdown so rows can show discounted amounts. */
    fun updateBreakdown(lines: List<GstBillingCalculator.LineBreakdown>?) {
        lineCalcs = lines
        submitList(buildRows())
    }

    private fun initialsOf(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty()   -> "•"
            parts.size == 1   -> parts[0].take(2).uppercase()
            else              -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    private fun formatPct(value: Double): String {
        val pretty = if (value % 1.0 == 0.0) value.toInt().toString()
                     else String.format("%.2f", value).trimEnd('0').trimEnd('.')
        return "$pretty%"
    }

    fun updateMode(supplyType: String, gstScheme: String) {
        this.supplyType = supplyType
        this.gstScheme = gstScheme
        submitList(buildRows())
    }
}
