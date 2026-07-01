package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.util.CurrencyHelper

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
    private var gstScheme: String = SCHEME_NORMAL
) : RecyclerView.Adapter<InvoiceAdapter.InvoiceViewHolder>() {

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

    class InvoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView   = view.findViewById(R.id.tvName)
        val meta: TextView   = view.findViewById(R.id.tvMeta)
        val price: TextView  = view.findViewById(R.id.tvPrice)
        val tax: TextView    = view.findViewById(R.id.tvTax)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice_premium, parent, false)
        return InvoiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val product = item.product
        val qty = item.quantity
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
        val isComposition = gstScheme.equals(SCHEME_COMPOSITION, ignoreCase = true) ||
                            supplyType.equals(SUPPLY_COMPOSITION, ignoreCase = true)
        val isIntra = supplyType.equals(SUPPLY_INTRASTATE, ignoreCase = true)
        val isInter = supplyType.equals(SUPPLY_INTERSTATE, ignoreCase = true)

        val cgstPct = product.cgstPercentage
        val sgstPct = product.sgstPercentage
        val igstPct = product.igstPercentage

        val subtotal = item.subTotal()
        val cgstAmt = subtotal * cgstPct / 100.0
        val sgstAmt = subtotal * sgstPct / 100.0
        val igstAmt = subtotal * igstPct / 100.0

        val (taxAmt, taxPct) = when {
            isComposition -> 0.0 to 0.0
            isIntra       -> (cgstAmt + sgstAmt) to (cgstPct + sgstPct)
            isInter       -> igstAmt to igstPct
            else          -> 0.0 to 0.0
        }

        // ---- Meta line: "2 × ₹480 · GST 5%" ----
        val rateText = CurrencyHelper.format(context, product.price)
        holder.meta.text = buildString {
            append("$formattedQty $unitText × $rateText")
            if (taxPct > 0.0) append(" · GST ${formatPct(taxPct)}")
        }

        // ---- Line total + tax ----
        val lineTotal = subtotal + taxAmt
        holder.price.text = CurrencyHelper.format(context, lineTotal)
        holder.tax.text = if (taxAmt > 0.0)
            "+${CurrencyHelper.format(context, taxAmt)} tax"
        else
            "no tax"
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
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}
