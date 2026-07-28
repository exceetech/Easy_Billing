package com.example.easy_billing

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Adapter for the sales-return screen.
 *
 * Each row shows a [BillItem]'s key data and lets the user choose how many
 * units to return via +/− buttons or direct text entry.
 *
 * [maxReturnableQty] is supplied per-row so the adapter can clamp input and
 * update the already-returned badge without knowing about the ViewModel.
 *
 * After every quantity change, [onTotalChanged] is invoked with the current
 * grand total so the Activity can update its bottom summary panel.
 */
class DebitNoteItemAdapter(
    private val items: List<BillItem>,
    private val supplyType: String,
    private val onTotalChanged: (totalTaxable: Double, totalTax: Double, itemsAdjusted: Int) -> Unit
) : RecyclerView.Adapter<DebitNoteItemAdapter.ViewHolder>() {

    /** User-entered additional quantity, keyed by [BillItem.id]. */
    private val additionalQtyMap = mutableMapOf<Int, Double>()

    /**
     * Per-row accent, cycled by position so each item reads as visually
     * distinct in the list — stripe, monogram tile, and stepper all share
     * one accent from the champagne palette (gold / teal / red).
     */
    private data class RowAccent(val accent: Int, val tileBg: Int, val tileText: Int)

    private val rowAccents = listOf(
        RowAccent(Color.parseColor("#8A6526"), Color.parseColor("#F6F0E4"), Color.parseColor("#8A6526")),
        RowAccent(Color.parseColor("#0F6E56"), Color.parseColor("#E7F1EC"), Color.parseColor("#0F6E56")),
        RowAccent(Color.parseColor("#A32D2D"), Color.parseColor("#FCEBEB"), Color.parseColor("#A32D2D"))
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewItemStripe:    View               = view.findViewById(R.id.viewItemStripe)
        val tvAvatar:          TextView           = view.findViewById(R.id.tvAvatar)
        val tvProductName:     TextView           = view.findViewById(R.id.tvProductName)
        val tvAlreadyReturned: TextView           = view.findViewById(R.id.tvAlreadyReturned) // Hide
        val tvHsnVariant:      TextView           = view.findViewById(R.id.tvHsnVariant)
        val tvQtySold:         TextView           = view.findViewById(R.id.tvQtySold)
        val tvUnitPrice:       TextView           = view.findViewById(R.id.tvUnitPrice)
        val tvGstRate:         TextView           = view.findViewById(R.id.tvGstRate)
        val tvMaxReturn:       TextView           = view.findViewById(R.id.tvMaxReturn)       // Hide
        val etAdditionalQty:   TextInputEditText  = view.findViewById(R.id.etAdditionalTaxable)
        val tvAdditionalTax:   TextView           = view.findViewById(R.id.tvAdditionalTax)
        val btnDecrement:      MaterialButton     = view.findViewById(R.id.btnDecrement)
        val btnIncrement:      MaterialButton     = view.findViewById(R.id.btnIncrement)

        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debit_note_line, parent, false)
        // Each row is its own rounded card now — clip so the left stripe
        // follows the corner radius instead of poking out past it.
        v.clipToOutline = true
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx  = holder.itemView.context
        val item = items[position]

        holder.watcher?.let { holder.etAdditionalQty.removeTextChangedListener(it) }

        val accent = rowAccents[position % rowAccents.size]
        holder.viewItemStripe.setBackgroundColor(accent.accent)
        holder.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(accent.tileBg)
        holder.tvAvatar.setTextColor(accent.tileText)
        holder.btnDecrement.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E1DAC8"))
        holder.btnDecrement.setTextColor(accent.accent)
        holder.btnIncrement.backgroundTintList = android.content.res.ColorStateList.valueOf(accent.accent)

        val initial = item.productName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P"
        holder.tvAvatar.text = initial

        holder.tvProductName.text = item.productName
        holder.tvAlreadyReturned.visibility = View.GONE
        
        // Hide max return section as it's not applicable for debit notes
        val maxReturnParent = holder.tvMaxReturn.parent as? View
        maxReturnParent?.visibility = View.GONE

        // Join only the parts that actually exist, so a missing HSN doesn't
        // leave a dangling "· variant · unit" with no leading label, and
        // every separator gets consistent single-space padding.
        holder.tvHsnVariant.text = listOfNotNull(
            item.hsnCode.takeIf { it.isNotBlank() }?.let { "HSN: $it" },
            item.variant?.takeIf { it.isNotBlank() },
            item.unit.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        holder.tvQtySold.text   = formatQty(item.quantity)
        holder.tvUnitPrice.text = CurrencyHelper.format(ctx, item.price)
        holder.tvGstRate.text   = "${item.gstRate.toInt()}%"

        val currentVal = additionalQtyMap[item.id] ?: 0.0
        holder.etAdditionalQty.setText(if (currentVal > 0.0) formatQty(currentVal) else "")

        updateAdditionalAmountView(holder, item, currentVal, ctx)

        // ── Increment ────────────────────────────────────────────────────────
        holder.btnIncrement.setOnClickListener {
            val cur = additionalQtyMap[item.id] ?: 0.0
            val step = if (item.unit.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
            val next = cur + step
            setQty(holder, item, next, ctx)
        }

        // ── Decrement ────────────────────────────────────────────────────────
        holder.btnDecrement.setOnClickListener {
            val cur = additionalQtyMap[item.id] ?: 0.0
            if (cur > 0.0) {
                val step = if (item.unit.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
                val next = (cur - step).coerceAtLeast(0.0)
                setQty(holder, item, next, ctx)
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.toDoubleOrNull() ?: 0.0
                val clamped = typed.coerceAtLeast(0.0) // No max limit for additional qty
                additionalQtyMap[item.id] = clamped
                updateAdditionalAmountView(holder, item, clamped, ctx)
                notifyGrandTotal()
            }
        }
        holder.etAdditionalQty.addTextChangedListener(watcher)
        holder.watcher = watcher
    }

    private fun setQty(
        holder: ViewHolder,
        item: BillItem,
        qty: Double,
        ctx: android.content.Context
    ) {
        additionalQtyMap[item.id] = qty
        holder.watcher?.let { holder.etAdditionalQty.removeTextChangedListener(it) }
        holder.etAdditionalQty.setText(if (qty > 0.0) formatQty(qty) else "")
        holder.watcher?.let { holder.etAdditionalQty.addTextChangedListener(it) }
        updateAdditionalAmountView(holder, item, qty, ctx)
        notifyGrandTotal()
    }

    private fun updateAdditionalAmountView(
        holder: ViewHolder,
        item: BillItem,
        qtyVal: Double,
        ctx: android.content.Context
    ) {
        if (qtyVal > 0.0) {
            val unitTaxable = if (item.quantity > 0.0) item.taxableValue / item.quantity else 0.0
            val taxableVal = qtyVal * unitTaxable
            val taxRate = item.gstRate
            val tax = taxableVal * (taxRate / 100.0)
            holder.tvAdditionalTax.visibility = View.VISIBLE
            holder.tvAdditionalTax.text =
                "Taxable: ${CurrencyHelper.format(ctx, taxableVal)} | Tax: ${CurrencyHelper.format(ctx, tax)}"
            val accent = rowAccents[holder.adapterPosition.coerceAtLeast(0) % rowAccents.size]
            holder.tvAdditionalTax.setTextColor(accent.accent)
        } else {
            holder.tvAdditionalTax.visibility = View.GONE
        }
    }

    private fun notifyGrandTotal() {
        var totalTaxable = 0.0
        var totalTax   = 0.0
        var itemsAdjusted = 0
        for (item in items) {
            val qVal = additionalQtyMap[item.id] ?: 0.0
            if (qVal > 0.0) {
                val unitTaxable = if (item.quantity > 0.0) item.taxableValue / item.quantity else 0.0
                val tVal = qVal * unitTaxable
                val tax = tVal * (item.gstRate / 100.0)
                totalTaxable += tVal
                totalTax   += tax
                itemsAdjusted += 1
            }
        }
        onTotalChanged(totalTaxable, totalTax, itemsAdjusted)
    }

    private fun formatQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString()
        else "%.2f".format(q)

    fun getDebitLines(): List<Pair<BillItem, Double>> =
        items.mapNotNull { item ->
            val qVal = additionalQtyMap[item.id] ?: 0.0
            if (qVal > 0.0) item to qVal else null
        }
}
