package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
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
    private val supplierStateName: String?,
    private val noteType: String = "D",
    private val maxReturnableQty: (productId: Int?, purchasedQty: Double) -> Double,
    private val onTotalChanged: (totalDebitValue: Double, totalGstReclaim: Double) -> Unit
) : RecyclerView.Adapter<PurchaseReturnItemAdapter.ViewHolder>() {

    /** User-chosen return quantities, keyed by [PurchaseItem.id]. */
    private val returnQtyMap = mutableMapOf<Int, Double>()

    // Champagne accent — cycles per row across a wider palette, each
    // paired with its own pale monogram tile background.
    private data class RowAccent(val accent: Int, val tileBg: Int)
    private val rowAccents = listOf(
        RowAccent(Color.parseColor("#8A6526"), Color.parseColor("#F3ECDD")),
        RowAccent(Color.parseColor("#0F6E56"), Color.parseColor("#E4F0EC")),
        RowAccent(Color.parseColor("#B23A3A"), Color.parseColor("#F7E2E0")),
        RowAccent(Color.parseColor("#1D6FA5"), Color.parseColor("#E1EEF5")),
        RowAccent(Color.parseColor("#6B4C9A"), Color.parseColor("#EDE6F5")),
        RowAccent(Color.parseColor("#B8631F"), Color.parseColor("#F6E7D8"))
    )

    // ─────────────────────────────────────────────────────────────────────────

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewItemStripe:    View              = view.findViewById(R.id.viewItemStripe)
        val tvAvatar:          TextView          = view.findViewById(R.id.tvAvatar)
        val tvProductName:     TextView          = view.findViewById(R.id.tvProductName)
        val tvAlreadyReturned: TextView          = view.findViewById(R.id.tvAlreadyReturned)
        val tvHsnVariant:      TextView          = view.findViewById(R.id.tvHsnVariant)
        val tvQtyBought:       TextView          = view.findViewById(R.id.tvQtyBought)
        val tvCostPrice:       TextView          = view.findViewById(R.id.tvCostPrice)
        val tvGstRate:         TextView          = view.findViewById(R.id.tvGstRate)
        val tvMaxReturn:       TextView          = view.findViewById(R.id.tvMaxReturn)
        val tvMaxReturnLabel:  TextView          = view.findViewById(R.id.tvMaxReturnLabel)
        val btnDecrement:      MaterialButton    = view.findViewById(R.id.btnDecrement)
        val btnIncrement:      MaterialButton    = view.findViewById(R.id.btnIncrement)
        val etReturnQty:       TextInputEditText = view.findViewById(R.id.etReturnQty)
        val tvDebitAmount:     TextView          = view.findViewById(R.id.tvDebitAmount)
        val llMaxReturnContainer: View           = view.findViewById(R.id.llMaxReturnContainer)
        val tvQtyInputLabel:   TextView          = view.findViewById(R.id.tvQtyInputLabel)

        var watcher: TextWatcher? = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase_return_row, parent, false)
        v.clipToOutline = true
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx  = holder.itemView.context
        val item = items[position]
        val max  = if (noteType == "C") Double.MAX_VALUE else maxReturnableQty(item.productId, item.quantity)

        // Remove stale watcher
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }

        // ── Accent stripe + monogram (cycles per row) ────────────────────────
        val rowAccent = rowAccents[position % rowAccents.size]
        val accentColor = rowAccent.accent
        holder.viewItemStripe.setBackgroundColor(accentColor)
        holder.tvAvatar.text = item.productName.trim().firstOrNull()?.uppercase() ?: "P"
        holder.tvAvatar.setTextColor(accentColor)
        holder.tvAvatar.backgroundTintList = ColorStateList.valueOf(rowAccent.tileBg)

        // ── Static labels ────────────────────────────────────────────────────
        holder.tvProductName.text = buildString {
            append(item.productName)
            if (!item.variant.isNullOrBlank()) append("  ·  ${item.variant}")
        }

        val alreadyReturned = item.quantity - (if (noteType == "C") maxReturnableQty(item.productId, item.quantity) else max)
        if (noteType != "C" && alreadyReturned > 0.0) {
            holder.tvAlreadyReturned.visibility = View.VISIBLE
            holder.tvAlreadyReturned.text =
                "Returned: ${formatQty(alreadyReturned)} ${item.unit ?: ""}"
        } else {
            holder.tvAlreadyReturned.visibility = View.GONE
        }

        holder.tvHsnVariant.text = if (!item.hsnCode.isNullOrBlank()) "HSN: ${item.hsnCode}" else "HSN: —"

        val unitPart = if (!item.unit.isNullOrBlank()) " ${item.unit}" else ""
        holder.tvQtyBought.text = "Bought ${formatQty(item.quantity)}$unitPart"
        val unitTaxable = if (item.quantity > 0.0) item.taxableAmount / item.quantity else 0.0
        holder.tvCostPrice.text  = CurrencyHelper.format(ctx, unitTaxable)

        val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(supplierStateName)
            ?: com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)
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
        holder.tvMaxReturn.text  = if (noteType == "C") "N/A" else formatQty(max)
        holder.tvMaxReturn.setTextColor(accentColor)
        holder.tvMaxReturnLabel.setTextColor(accentColor)

        if (noteType == "C") {
            holder.llMaxReturnContainer.visibility = View.GONE
            holder.tvQtyInputLabel.text = "Receive qty"
        } else {
            holder.llMaxReturnContainer.visibility = View.VISIBLE
            holder.tvQtyInputLabel.text = "Return qty"
        }
        holder.btnIncrement.backgroundTintList = ColorStateList.valueOf(accentColor)

        // ── Current qty ──────────────────────────────────────────────────────
        val currentQty = returnQtyMap[item.id] ?: 0.0
        holder.etReturnQty.setText(if (currentQty > 0.0) formatQty(currentQty) else "")

        updateDebitAmountView(holder, item, currentQty, ctx, accentColor)

        // ── Disable row when nothing is returnable ───────────────────────────
        val rowEnabled = noteType == "C" || max > 0.0
        holder.btnDecrement.isEnabled = rowEnabled
        holder.btnIncrement.isEnabled = rowEnabled
        holder.etReturnQty.isEnabled  = rowEnabled

        // ── Increment ────────────────────────────────────────────────────────
        holder.btnIncrement.setOnClickListener {
            val cur  = returnQtyMap[item.id] ?: 0.0
            if (noteType == "C" || cur < max) {
                val step = if (item.unit?.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
                val next = if (noteType == "C") cur + step else (cur + step).coerceAtMost(max)
                setQty(holder, item, next, ctx, accentColor)
            }
        }

        // ── Decrement ────────────────────────────────────────────────────────
        holder.btnDecrement.setOnClickListener {
            val cur = returnQtyMap[item.id] ?: 0.0
            if (cur > 0.0) {
                val step = if (item.unit?.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
                val next = (cur - step).coerceAtLeast(0.0)
                setQty(holder, item, next, ctx, accentColor)
            }
        }

        // ── Text watcher ─────────────────────────────────────────────────────
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed   = s?.toString()?.toDoubleOrNull() ?: 0.0
                val clamped = if (noteType == "C") typed else typed.coerceIn(0.0, max)
                returnQtyMap[item.id] = clamped
                updateDebitAmountView(holder, item, clamped, ctx, accentColor)
                notifyGrandTotal()

                // Bug fix: the submitted quantity was already correctly
                // clamped to `max` above, but the EditText itself was left
                // showing whatever the user typed — so typing "50" against a
                // batch with 10 remaining silently submitted 10 while the box
                // kept showing "50". That looked, from the outside, exactly
                // like "I can return more than remaining." Rewrite the field
                // to the clamped value so what's on screen always matches
                // what gets submitted. Detach/reattach to avoid a recursive
                // afterTextChanged call, matching setQty()'s own pattern.
                if (noteType != "C" && typed > max) {
                    holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
                    holder.etReturnQty.setText(if (clamped > 0.0) formatQty(clamped) else "")
                    holder.etReturnQty.setSelection(holder.etReturnQty.text?.length ?: 0)
                    holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
                    android.widget.Toast.makeText(
                        ctx,
                        "Only ${formatQty(max)} remaining on this batch — capped to that",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
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
        ctx: android.content.Context,
        accentColor: Int
    ) {
        returnQtyMap[item.id] = qty
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
        holder.etReturnQty.setText(if (qty > 0.0) formatQty(qty) else "")
        holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
        updateDebitAmountView(holder, item, qty, ctx, accentColor)
        notifyGrandTotal()
    }

    private fun updateDebitAmountView(
        holder: ViewHolder,
        item: PurchaseItem,
        qty: Double,
        ctx: android.content.Context,
        accentColor: Int
    ) {
        if (qty > 0.0) {
            val unitTaxable = if (item.quantity > 0) item.taxableAmount / item.quantity else 0.0
            val taxable  = qty * unitTaxable

            val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(supplierStateName)
                ?: com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)
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
            holder.tvDebitAmount.setTextColor(accentColor)
            holder.tvDebitAmount.text = if (noteType == "C")
                "Credit value: ${CurrencyHelper.format(ctx, total)}"
            else
                "Debit value: ${CurrencyHelper.format(ctx, total)}"
        } else {
            holder.tvDebitAmount.visibility = View.GONE
        }
    }

    private fun notifyGrandTotal() {
        var total = 0.0
        var gst   = 0.0

        val supplierState = com.example.easy_billing.util.GstEngine.getStateCodeFromName(supplierStateName)
            ?: com.example.easy_billing.util.GstEngine.getStateCode(supplierGstin)

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
