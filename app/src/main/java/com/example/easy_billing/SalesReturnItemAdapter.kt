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
import com.example.easy_billing.viewmodel.SalesReturnLineItem
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
class SalesReturnItemAdapter(
    private val items: List<SalesReturnLineItem>,
    private val maxReturnableQty: (productId: Int, soldQty: Double) -> Double,
    private val onTotalChanged: (totalAmount: Double, taxableAmount: Double, totalTax: Double, totalCess: Double) -> Unit
) : RecyclerView.Adapter<SalesReturnItemAdapter.ViewHolder>() {

    /** User-chosen return quantities, keyed by [BillItem.id]. */
    private val returnQtyMap = mutableMapOf<Int, Double>()

    // Random per-row colours (stripe + avatar tile) so every line item
    // doesn't read as the same flat teal — same palette pattern used for
    // Bill History's rows, picked deterministically per item so it doesn't
    // shuffle on rebind/scroll.
    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#1D6E6E"), Color.parseColor("#DDEEEE"), Color.parseColor("#1D6E6E")), // teal
        RowColor(Color.parseColor("#B23A3A"), Color.parseColor("#FBEDED"), Color.parseColor("#B23A3A")), // red
        RowColor(Color.parseColor("#8A6526"), Color.parseColor("#FAEEDA"), Color.parseColor("#8A6526")), // gold
        RowColor(Color.parseColor("#3A5FB2"), Color.parseColor("#E5EBFA"), Color.parseColor("#3A5FB2")), // blue
        RowColor(Color.parseColor("#7A4FA3"), Color.parseColor("#EFE5F7"), Color.parseColor("#7A4FA3")), // purple
        RowColor(Color.parseColor("#B2673A"), Color.parseColor("#FAEBE1"), Color.parseColor("#B2673A")), // rust
        RowColor(Color.parseColor("#3A8F6E"), Color.parseColor("#E1F2EA"), Color.parseColor("#3A8F6E")), // green
        RowColor(Color.parseColor("#B23A85"), Color.parseColor("#FAE1F0"), Color.parseColor("#B23A85"))  // pink
    )

    private fun colorFor(item: SalesReturnLineItem): RowColor {
        val bi = item.billItem
        val key = "${bi.id}${bi.productId}${bi.productName}"
        val index = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[index]
    }

    // ─────────────────────────────────────────────────────────────────────────

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewItemStripe:    View               = view.findViewById(R.id.viewItemStripe)
        val tvAvatar:          TextView           = view.findViewById(R.id.tvAvatar)
        val tvProductName:     TextView           = view.findViewById(R.id.tvProductName)
        val tvAlreadyReturned: TextView           = view.findViewById(R.id.tvAlreadyReturned)
        val tvHsnVariant:      TextView           = view.findViewById(R.id.tvHsnVariant)
        val tvQtySold:         TextView           = view.findViewById(R.id.tvQtySold)
        val tvUnitPrice:       TextView           = view.findViewById(R.id.tvUnitPrice)
        val tvGstRate:         TextView           = view.findViewById(R.id.tvGstRate)
        val tvGstLabel:        TextView           = view.findViewById(R.id.tvGstLabel)
        val tvMaxReturn:       TextView           = view.findViewById(R.id.tvMaxReturn)
        val btnDecrement:      MaterialButton     = view.findViewById(R.id.btnDecrement)
        val btnIncrement:      MaterialButton     = view.findViewById(R.id.btnIncrement)
        val etReturnQty:       TextInputEditText  = view.findViewById(R.id.etReturnQty)
        val tvReturnAmount:    TextView           = view.findViewById(R.id.tvReturnAmount)

        // TextWatcher reference kept so we can remove it before rebinding
        var watcher: TextWatcher? = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sales_return_row, parent, false)
        // Each row is its own rounded card — clip so the left stripe follows
        // the corner radius instead of poking out past it, matching
        // DebitNoteItemAdapter's item_debit_note_line.xml treatment.
        v.clipToOutline = true
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx      = holder.itemView.context
        val lineItem = items[position]
        val bi       = lineItem.billItem
        val max      = maxReturnableQty(bi.productId, bi.quantity)

        // ── Remove stale watcher before touching the EditText ────────────────
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }

        // ── Random row colour ────────────────────────────────────────────────
        val rowColor = colorFor(lineItem)
        holder.viewItemStripe.setBackgroundColor(rowColor.stripe)
        holder.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.avatarBg)
        holder.tvAvatar.setTextColor(rowColor.avatarText)
        holder.btnIncrement.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.stripe)

        // ── Static labels ────────────────────────────────────────────────────
        holder.tvAvatar.text = bi.productName.take(1).uppercase()
        holder.tvProductName.text = bi.productName

        val alreadyReturned = bi.quantity - max
        if (alreadyReturned > 0.0) {
            holder.tvAlreadyReturned.visibility = View.VISIBLE
            holder.tvAlreadyReturned.text =
                "Returned: ${formatQty(alreadyReturned)} ${bi.unit}"
        } else {
            holder.tvAlreadyReturned.visibility = View.GONE
        }

        // Join only the parts that actually exist, so a missing HSN doesn't
        // leave a dangling "· variant · unit" with no leading label, and
        // every separator gets consistent single-space padding.
        holder.tvHsnVariant.text = listOfNotNull(
            bi.hsnCode.takeIf { it.isNotBlank() }?.let { "HSN: $it" },
            bi.variant?.takeIf { it.isNotBlank() },
            bi.unit.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        holder.tvQtySold.text   = formatQty(bi.quantity)
        holder.tvUnitPrice.text = CurrencyHelper.format(ctx, bi.price)
        
        if (lineItem.cessRate > 0.0) {
            holder.tvGstRate.text = "${bi.gstRate.toInt()}% GST + ${lineItem.cessRate.toInt()}% CESS"
            holder.tvGstLabel.visibility = View.GONE
        } else {
            holder.tvGstRate.text = "${bi.gstRate.toInt()}%"
            holder.tvGstLabel.visibility = View.VISIBLE
            holder.tvGstLabel.text = "GST"
        }
        
        holder.tvMaxReturn.text = "Max returnable: ${formatQty(max)} ${bi.unit}"

        // ── Current quantity for this item ───────────────────────────────────
        val currentQty = returnQtyMap[bi.id] ?: 0.0
        holder.etReturnQty.setText(if (currentQty > 0.0) formatQty(currentQty) else "")

        updateReturnAmountView(holder, lineItem, currentQty, ctx)

        // ── Disable row entirely when nothing is returnable ──────────────────
        val rowEnabled = max > 0.0
        holder.btnDecrement.isEnabled = rowEnabled
        holder.btnIncrement.isEnabled = rowEnabled
        holder.etReturnQty.isEnabled  = rowEnabled

        // ── Increment ────────────────────────────────────────────────────────
        holder.btnIncrement.setOnClickListener {
            val cur = returnQtyMap[bi.id] ?: 0.0
            if (cur < max) {
                val step = if (bi.unit.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
                val next = (cur + step).coerceAtMost(max)
                setQty(holder, lineItem, next, ctx)
            }
        }

        // ── Decrement ────────────────────────────────────────────────────────
        holder.btnDecrement.setOnClickListener {
            val cur = returnQtyMap[bi.id] ?: 0.0
            if (cur > 0.0) {
                val step = if (bi.unit.lowercase() in setOf("kg", "g", "l", "ml", "kilogram", "gram", "litre", "liter", "millilitre", "milliliter")) 0.5 else 1.0
                val next = (cur - step).coerceAtLeast(0.0)
                setQty(holder, lineItem, next, ctx)
            }
        }

        // ── Text watcher for direct edit ─────────────────────────────────────
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.toDoubleOrNull() ?: 0.0
                val clamped = typed.coerceIn(0.0, max)
                returnQtyMap[bi.id] = clamped
                updateReturnAmountView(holder, lineItem, clamped, ctx)
                notifyGrandTotal()

                if (typed > max) {
                    holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
                    holder.etReturnQty.setText(if (clamped > 0.0) formatQty(clamped) else "")
                    holder.etReturnQty.setSelection(holder.etReturnQty.text?.length ?: 0)
                    holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
                    android.widget.Toast.makeText(
                        ctx,
                        "Maximum returnable is ${formatQty(max)}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        holder.etReturnQty.addTextChangedListener(watcher)
        holder.watcher = watcher

        holder.etReturnQty.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val typed = holder.etReturnQty.text?.toString()?.toDoubleOrNull() ?: 0.0
                if (typed > max) {
                    val clamped = max.coerceAtLeast(0.0)
                    holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
                    holder.etReturnQty.setText(if (clamped > 0.0) formatQty(clamped) else "")
                    holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setQty(
        holder: ViewHolder,
        item: SalesReturnLineItem,
        qty: Double,
        ctx: android.content.Context
    ) {
        val bi = item.billItem
        returnQtyMap[bi.id] = qty
        holder.watcher?.let { holder.etReturnQty.removeTextChangedListener(it) }
        holder.etReturnQty.setText(if (qty > 0.0) formatQty(qty) else "")
        holder.watcher?.let { holder.etReturnQty.addTextChangedListener(it) }
        updateReturnAmountView(holder, item, qty, ctx)
        notifyGrandTotal()
    }

    private fun updateReturnAmountView(
        holder: ViewHolder,
        item: SalesReturnLineItem,
        qty: Double,
        ctx: android.content.Context
    ) {
        if (qty > 0.0) {
            val bi          = item.billItem
            val ratio       = qty / bi.quantity
            val taxable     = bi.taxableValue * ratio
            val gst         = (bi.cgstAmount + bi.sgstAmount + bi.igstAmount) * ratio
            val cess        = if (bi.quantity > 0.0 && item.cessAmount > 0.0) {
                ratio * item.cessAmount
            } else if (item.cessRate > 0.0) {
                taxable * (item.cessRate / 100.0)
            } else {
                0.0
            }
            val lineTotal   = taxable + gst + cess
            holder.tvReturnAmount.visibility = View.VISIBLE
            holder.tvReturnAmount.text = if (cess > 0.0) {
                "Return value: ${CurrencyHelper.format(ctx, lineTotal)} (incl. CESS ${CurrencyHelper.format(ctx, cess)})"
            } else {
                "Return value: ${CurrencyHelper.format(ctx, lineTotal)}"
            }
            holder.tvReturnAmount.setTextColor(colorFor(item).stripe)
        } else {
            holder.tvReturnAmount.visibility = View.GONE
        }
    }

    private fun notifyGrandTotal() {
        var total = 0.0
        var taxableTotal = 0.0
        var gstTotal = 0.0
        var cessTotal = 0.0
        for (item in items) {
            val bi = item.billItem
            val qty = returnQtyMap[bi.id] ?: 0.0
            if (qty > 0.0) {
                val ratio   = qty / bi.quantity
                val taxable = bi.taxableValue * ratio
                val g       = (bi.cgstAmount + bi.sgstAmount + bi.igstAmount) * ratio
                val c       = if (bi.quantity > 0.0 && item.cessAmount > 0.0) {
                    ratio * item.cessAmount
                } else if (item.cessRate > 0.0) {
                    taxable * (item.cessRate / 100.0)
                } else {
                    0.0
                }
                taxableTotal += taxable
                gstTotal     += g
                cessTotal    += c
                total        += (taxable + g + c)
            }
        }
        onTotalChanged(total, taxableTotal, gstTotal, cessTotal)
    }

    private fun formatQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString()
        else "%.2f".format(q)

    // ─────────────────────────────────────────────────────────────────────────
    //  Data extraction for Activity
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns only the lines where the user entered a quantity > 0.
     * Checked against the [items] list to pair each entry with its [SalesReturnLineItem].
     */
    fun getReturnLines(): List<Pair<SalesReturnLineItem, Double>> =
        items.mapNotNull { item ->
            val qty = returnQtyMap[item.billItem.id] ?: 0.0
            if (qty > 0.0) item to qty else null
        }
}
