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
 * Renders one row per cart line in [com.example.easy_billing.InvoiceActivity].
 *
 * Each row carries the per-product CGST / SGST / IGST percentages
 * saved at product creation time. The columns that don't apply to
 * the active supply type are dimmed to "—" so the user sees at a
 * glance which side of the GST split is in effect:
 *
 *   • Composition Scheme → all three columns dimmed.
 *   • Intra-state Normal → CGST + SGST shown, IGST dimmed.
 *   • Inter-state Normal → IGST shown, CGST + SGST dimmed.
 *
 * The adapter accepts the supply type as a constructor argument so
 * the activity can rebuild a fresh list (or call [updateMode]) when
 * the user toggles between B2B and B2C / changes the customer state.
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

        // Column accents — kept in sync with item_invoice.xml.
        private const val ACCENT_INTRA = 0xFF0E7490.toInt()  // teal — CGST + SGST
        private const val ACCENT_INTER = 0xFF7C3AED.toInt()  // purple — IGST
        private const val MUTED        = 0xFFB0B5BD.toInt()  // inactive cell
    }

    class InvoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView     = view.findViewById(R.id.tvName)
        val qty: TextView      = view.findViewById(R.id.tvQty)
        val rate: TextView     = view.findViewById(R.id.tvRate)
        val cgst: TextView     = view.findViewById(R.id.tvCgst)
        val sgst: TextView     = view.findViewById(R.id.tvSgst)
        val igst: TextView     = view.findViewById(R.id.tvIgst)
        /** Pre-tax amount: price × quantity. */
        val subtotal: TextView = view.findViewById(R.id.tvSubtotal)
        /** Final line amount: subtotal + CGST + SGST + IGST. */
        val price: TextView    = view.findViewById(R.id.tvPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
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

        // ---- Name ----
        holder.name.text = buildString {
            append(product.name)
            if (!product.variant.isNullOrBlank()) append(" (${product.variant})")
        }

        // ---- Quantity ----
        val unitText = when (unit) {
            "kilogram"   -> "kg"
            "litre"      -> "L"
            "gram"       -> "g"
            "millilitre" -> "ml"
            "piece"      -> "pc"
            else         -> unit
        }
        holder.qty.text = "$formattedQty $unitText"

        // ---- Rate (price per unit) ----
        // Rupee symbol lives in the column header — cells render
        // just the number so the column stays narrow.
        val formattedUnitPrice = CurrencyHelper.formatNoSymbol(context, product.price)
        holder.rate.text = "$formattedUnitPrice/$unitText"

        // ---- GST columns ----
        val isComposition = gstScheme.equals(SCHEME_COMPOSITION, ignoreCase = true) ||
                            supplyType.equals(SUPPLY_COMPOSITION, ignoreCase = true)
        val isIntra = supplyType.equals(SUPPLY_INTRASTATE, ignoreCase = true)
        val isInter = supplyType.equals(SUPPLY_INTERSTATE, ignoreCase = true)

        // Pull rates straight from the product row — these are what
        // the user keyed in on the AddProduct screen.
        val cgstPct = product.cgstPercentage
        val sgstPct = product.sgstPercentage
        val igstPct = product.igstPercentage

        // ---- Subtotal (price × qty, BEFORE GST) ----
        // Symbol-less render — column header already says "(₹)".
        val subtotal = item.subTotal()
        holder.subtotal.text = CurrencyHelper.formatNoSymbol(context, subtotal)

        // Per-line GST amounts derived from the line subtotal — kept
        // here so the cells can show "<amount> (<pct>%)" together.
        val cgstAmt = subtotal * cgstPct / 100.0
        val sgstAmt = subtotal * sgstPct / 100.0
        val igstAmt = subtotal * igstPct / 100.0

        bindGstCell(
            view        = holder.cgst,
            amount      = cgstAmt,
            pct         = cgstPct,
            highlight   = !isComposition && isIntra,
            activeColor = ACCENT_INTRA
        )
        bindGstCell(
            view        = holder.sgst,
            amount      = sgstAmt,
            pct         = sgstPct,
            highlight   = !isComposition && isIntra,
            activeColor = ACCENT_INTRA
        )
        bindGstCell(
            view        = holder.igst,
            amount      = igstAmt,
            pct         = igstPct,
            highlight   = !isComposition && isInter,
            activeColor = ACCENT_INTER
        )

        // ---- Total (subtotal + applicable GST) ----
        // Composition rows charge no GST separately, so the total
        // collapses to the subtotal. Otherwise we apply only the
        // percentages that match the supply type.
        val lineTotal = when {
            isComposition -> subtotal
            isIntra       -> subtotal + cgstAmt + sgstAmt
            isInter       -> subtotal + igstAmt
            else          -> subtotal
        }
        // Symbol-less render — column header already says "(₹)".
        holder.price.text = CurrencyHelper.formatNoSymbol(context, lineTotal)
    }

    /**
     * Renders one GST cell.
     *
     *   • Active (the cell applies to the chosen supply type, and
     *     the product carries a non-zero rate) — show
     *     "<amount> (<pct>%)" in [activeColor]. The rupee symbol
     *     is *not* prefixed here — the column header carries "(₹)"
     *     instead, which keeps the cell short and consistent across
     *     varying amounts.
     *   • Inactive (composition scheme, wrong supply type, or rate
     *     is zero) — show "—" in muted grey so it visually recedes.
     */
    private fun bindGstCell(
        view: TextView,
        amount: Double,
        pct: Double,
        highlight: Boolean,
        activeColor: Int
    ) {
        if (!highlight || pct <= 0.0) {
            view.text = "—"
            view.setTextColor(MUTED)
        } else {
            view.text = "${formatAmount(amount)} (${formatPct(pct)})"
            view.setTextColor(activeColor)
        }
    }

    /**
     * Formats a rupee amount without the symbol — the column header
     * already carries "(₹)" so cells stay short.
     *
     *   6.00  → "6"        (whole number)
     *   6.50  → "6.50"     (one decimal kept, trailing zero stripped to "6.5")
     *   6.36  → "6.36"
     */
    private fun formatAmount(value: Double): String {
        if (value % 1.0 == 0.0) return value.toInt().toString()
        return String.format("%.2f", value).trimEnd('0').trimEnd('.')
    }

    /** "6%" / "12.5%" — strips trailing zeros for whole-number rates. */
    private fun formatPct(value: Double): String {
        val pretty = if (value % 1.0 == 0.0) value.toInt().toString()
                     else String.format("%.2f", value).trimEnd('0').trimEnd('.')
        return "$pretty%"
    }

    /**
     * Lets the host activity flip the supply type / scheme without
     * rebuilding the adapter (e.g. when the user toggles the
     * customer state field or the B2B chip).
     */
    fun updateMode(supplyType: String, gstScheme: String) {
        this.supplyType = supplyType
        this.gstScheme = gstScheme
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}
