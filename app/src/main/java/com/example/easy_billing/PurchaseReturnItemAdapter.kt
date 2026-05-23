package com.example.easy_billing

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Adapter for the purchase-return (debit note) screen.
 *
 * Each row shows a [PurchaseItem]'s key data and lets the user choose
 * how many units to return via +/− buttons or direct text entry.
 *
 * [maxReturnableQty] is supplied per-row so the adapter can clamp input
 * and show the already-returned badge.
 *
 * After every quantity change, [onTotalChanged] is invoked with the
 * total debit value and the GST reclaim amount so the Activity can
 * update its bottom summary panel.
 */
class PurchaseReturnItemAdapter(
    private val items: List<PurchaseItem>,
    private val shopStateCode: String,
    private val supplierGstin: String?,
    private val maxReturnableQty: (productId: Int?, purchasedQty: Double) -> Double,
    private val onTotalChanged: (totalDebitValue: Double, totalGstReclaim: Double) -> Unit
) : RecyclerView.Adapter<PurchaseReturnItemAdapter.ViewHolder>() {

    /** User-chosen return quantities, keyed by [PurchaseItem.id]. */
    private val returnQtyMap = mutableMapOf<Int, Double>()

    // ─────────────────────────────────────────────────────────────────────────

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvProductName:     TextView          = view.findViewById(R.id.tvProductName)
        val tvAlreadyReturned: TextView          = view.findViewById(R.id.tvAlreadyReturned)
        val tvHsnVariant:      TextView          = view.findViewById(R.id.tvHsnVariant)
        val tvQtyBought:       TextView          = view.findViewById(R.id.tvQtyBought)
        val tvCostPrice:       TextView          = view.findViewById(R.id.tvCostPrice)
        val tvGstRate:         TextView          = view.findViewById(R.id.tvGstRate)
        val tvMaxReturn:       TextView          = view.findViewById(R.id.tvMaxReturn)
        val btnDecrement:      MaterialButton    = view.findViewById(R.id.btnDecrement)
        val btnIncrement:      MaterialButton    = view.findViewById(R.id.btnIncrement)
        val etReturnQty:       TextInputEditText = view.findViewById(R.id.etReturnQty)
        val tvDebitAmount:     TextView          = view.findViewById(R.id.tvDebitAmount)

        var watcher: TextWatcher? = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_return_row, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx  = holder.itemView.context
        val item = items[position]
        val max  = maxReturnableQty(item.productId, item.quantity)

        // Remove stale watcher
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }

        // ── Static labels ────────────────────────────────────────────────────
        holder.tvProductName.text = buildString {
            append(item.productName)
            if (!item.variant.isNullOrBlank()) append("  ·  ${item.variant}")
        }

        val alreadyReturned = item.quantity - max
        if (alreadyReturned > 0.0) {
            holder.tvAlreadyReturned.visibility = View.VISIBLE
            holder.tvAlreadyReturned.text =
                "Returned: ${formatQty(alreadyReturned)} ${item.unit ?: ""}"
        } else {
            holder.tvAlreadyReturned.visibility = View.GONE
        }

        val hsnPart  = if (!item.hsnCode.isNullOrBlank()) "HSN: ${item.hsnCode}" else ""
        val unitPart = if (!item.unit.isNullOrBlank()) "  ·  ${item.unit}" else ""
        holder.tvHsnVariant.text = "$hsnPart$unitPart"

        holder.tvQtyBought.text  = formatQty(item.quantity)
        val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
        holder.tvCostPrice.text  = CurrencyHelper.format(ctx, unitTaxable)

        val supplierState = com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)
        val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank()) {
            shopStateCode == supplierState
        } else {
            item.purchaseIgstPercentage <= 0.0
        }

        val gstStr = if (sameState) {
            val totalSgstCgst = item.purchaseCgstPercentage + item.purchaseSgstPercentage
            if (totalSgstCgst > 0) "CGST ${item.purchaseCgstPercentage.toInt()}% + SGST ${item.purchaseSgstPercentage.toInt()}%" else "0%"
        } else {
            if (item.purchaseIgstPercentage > 0) "IGST ${item.purchaseIgstPercentage.toInt()}%" else "0%"
        }
        holder.tvGstRate.text    = gstStr
        holder.tvMaxReturn.text  = formatQty(max)

        // ── Current qty ──────────────────────────────────────────────────────
        val currentQty = returnQtyMap[item.id] ?: 0.0
        holder.etReturnQty.setText(if (currentQty > 0.0) formatQty(currentQty) else "")

        updateDebitAmountView(holder, item, currentQty, ctx)

        // ── Disable row when nothing is returnable ───────────────────────────
        val rowEnabled = max > 0.0
        holder.btnDecrement.isEnabled = rowEnabled
        holder.btnIncrement.isEnabled = rowEnabled
        holder.etReturnQty.isEnabled  = rowEnabled

        // ── Increment ────────────────────────────────────────────────────────
        holder.btnIncrement.setOnClickListener {
            val cur  = returnQtyMap[item.id] ?: 0.0
            if (cur < max) {
                val step = if (item.unit?.lowercase() in listOf("kg", "g", "l", "ml")) 0.5 else 1.0
                val next = (cur + step).coerceAtMost(max)
                setQty(holder, item, next, ctx)
            }
        }

        // ── Decrement ────────────────────────────────────────────────────────
        holder.btnDecrement.setOnClickListener {
            val cur = returnQtyMap[item.id] ?: 0.0
            if (cur > 0.0) {
                val step = if (item.unit?.lowercase() in listOf("kg", "g", "l", "ml")) 0.5 else 1.0
                val next = (cur - step).coerceAtLeast(0.0)
                setQty(holder, item, next, ctx)
            }
        }

        // ── Text watcher ─────────────────────────────────────────────────────
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed   = s?.toString()?.toDoubleOrNull() ?: 0.0
                val clamped = typed.coerceIn(0.0, max)
                returnQtyMap[item.id] = clamped
                updateDebitAmountView(holder, item, clamped, ctx)
                notifyGrandTotal()
            }
        }
        holder.etReturnQty.addTextChangedListener(watcher)
        holder.watcher = watcher
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setQty(
        holder: ViewHolder,
        item: PurchaseItem,
        qty: Double,
        ctx: android.content.Context
    ) {
        returnQtyMap[item.id] = qty
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
        holder.etReturnQty.setText(if (qty > 0.0) formatQty(qty) else "")
        holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
        updateDebitAmountView(holder, item, qty, ctx)
        notifyGrandTotal()
    }

    private fun updateDebitAmountView(
        holder: ViewHolder,
        item: PurchaseItem,
        qty: Double,
        ctx: android.content.Context
    ) {
        if (qty > 0.0) {
            val unitTaxable = if (item.quantity > 0) item.taxableAmount / item.quantity else 0.0
            val taxable  = qty * unitTaxable

            val supplierState = com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)
            val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank()) {
                shopStateCode == supplierState
            } else {
                item.purchaseIgstPercentage <= 0.0
            }

            val gst = if (sameState) {
                taxable * (item.purchaseCgstPercentage + item.purchaseSgstPercentage) / 100.0
            } else {
                taxable * item.purchaseIgstPercentage / 100.0
            }

            val total = taxable + gst
            holder.tvDebitAmount.visibility = View.VISIBLE
            holder.tvDebitAmount.text =
                "Debit value: ${CurrencyHelper.format(ctx, total)}"
        } else {
            holder.tvDebitAmount.visibility = View.GONE
        }
    }

    private fun notifyGrandTotal() {
        var total = 0.0
        var gst   = 0.0

        val supplierState = com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)

        for (item in items) {
            val qty = returnQtyMap[item.id] ?: 0.0
            if (qty > 0.0) {
                val unitTaxable = if (item.quantity > 0) item.taxableAmount / item.quantity else 0.0
                val taxable = qty * unitTaxable
                
                val sameState = if (shopStateCode.isNotBlank() && supplierState.isNotBlank()) {
                    shopStateCode == supplierState
                } else {
                    item.purchaseIgstPercentage <= 0.0
                }

                val g = if (sameState) {
                    taxable * (item.purchaseCgstPercentage + item.purchaseSgstPercentage) / 100.0
                } else {
                    taxable * item.purchaseIgstPercentage / 100.0
                }

                total += taxable + g
                gst   += g
            }
        }
        onTotalChanged(total, gst)
    }

    private fun formatQty(q: Double) =
        if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(q)

    // ─────────────────────────────────────────────────────────────────────────
    //  Data extraction for Activity
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns only the lines where the user entered a quantity > 0,
     * paired with the chosen return quantity.
     */
    fun getReturnLines(): Map<PurchaseItem, Double> =
        items.mapNotNull { item ->
            val qty = returnQtyMap[item.id] ?: 0.0
            if (qty > 0.0) item to qty else null
        }.toMap()
}
