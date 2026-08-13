package com.example.easy_billing.adapter

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.PurchaseBatch
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the supplier-return / scrap batch picker — same card
 * shape as [com.example.easy_billing.SalesReturnItemAdapter]'s credit-note
 * line items (item_sales_return_row.xml / item_batch_pick.xml): left
 * accent stripe, monogram avatar, price-led header, hairline divider,
 * footer stepper with the qty centered between outlined −/+ buttons,
 * and a value preview line that only appears once a quantity is
 * entered. The oldest batch with stock remaining gets an "Oldest" tag,
 * same spot as the sales-return row's "Returned: N" badge.
 *
 *   adapter.onSelectionChanged = { running ->
 *       tvBatchRunning.text = formatQty(running)
 *   }
 *
 * Submit:
 *
 *   val lines = adapter.selectedLines()  // List<BatchReturnLine>
 */
class BatchPickerAdapter(
    private val batches: List<PurchaseBatch>
) : RecyclerView.Adapter<BatchPickerAdapter.BatchVH>() {

    /** position → user-entered qty for that batch. */
    private val selected = HashMap<Int, Double>()

    /** Fired whenever the per-batch total changes. */
    var onSelectionChanged: ((Double) -> Unit)? = null

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // Same random-per-row palette technique as SalesReturnItemAdapter /
    // BillHistoryAdapter, keyed by batch id so it's stable across rebinds.
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

    private fun colorFor(batch: PurchaseBatch): RowColor {
        val key = "${batch.id}"
        val index = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[index]
    }

    /** Position of the oldest batch that still has stock — gets the
     *  "Oldest" tag, same idea as FIFO ordering used elsewhere. */
    private val oldestWithStockIndex: Int =
        batches.withIndex()
            .filter { it.value.quantityRemaining > 0.0 }
            .minByOrNull { it.value.createdAt }
            ?.index ?: -1

    inner class BatchVH(view: View) : RecyclerView.ViewHolder(view) {
        val viewItemStripe: View = view.findViewById(R.id.viewItemStripe)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvBatchLabel: TextView = view.findViewById(R.id.tvBatchLabel)
        val tvOldestTag: TextView = view.findViewById(R.id.tvOldestTag)
        val tvBatchMeta: TextView = view.findViewById(R.id.tvBatchMeta)
        val tvUnitCost: TextView = view.findViewById(R.id.tvUnitCost)
        val tvMaxReturn: TextView = view.findViewById(R.id.tvMaxReturn)
        val btnDecrement: MaterialButton = view.findViewById(R.id.btnDecrement)
        val btnIncrement: MaterialButton = view.findViewById(R.id.btnIncrement)
        val etQty: TextInputEditText = view.findViewById(R.id.etBatchQty)
        val tvBatchAmount: TextView = view.findViewById(R.id.tvBatchAmount)

        /** Held so we can cleanly detach before rebinding — otherwise
         *  typing in one row could re-fire the previous binding's callback. */
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_pick, parent, false)
        v.clipToOutline = true
        return BatchVH(v)
    }

    override fun onBindViewHolder(holder: BatchVH, position: Int) {
        val b = batches[position]

        holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }

        val rowColor = colorFor(b)
        holder.viewItemStripe.setBackgroundColor(rowColor.stripe)
        holder.tvAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.avatarBg)
        holder.tvAvatar.setTextColor(rowColor.avatarText)
        holder.btnIncrement.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.stripe)
        holder.btnDecrement.setTextColor(rowColor.stripe)

        val invoiceText = b.invoiceNumber?.takeIf { it.isNotBlank() }
            ?: b.batchCode?.takeIf { it.isNotBlank() }
            ?: "Stock entry #${b.id}"

        val label = when {
            invoiceText == "MIGRATION" -> "Initial Stock"
            b.batchCode?.startsWith("CN-") == true && b.invoiceNumber?.isNotBlank() == true -> "$invoiceText (Credited)"
            else -> invoiceText
        }
        holder.tvBatchLabel.text = label
        holder.tvAvatar.text = label.take(1).uppercase()

        holder.tvOldestTag.visibility = if (position == oldestWithStockIndex) View.VISIBLE else View.GONE

        // Display the GROSS per-unit price (what was actually paid, incl. GST) so
        // a batch is easy to recognize at a glance. DISPLAY-ONLY — valuation
        // and supplier-return crediting still use the net unitCostExcludingTax.
        val grossUnit = if (b.quantityPurchased > 0.0 && b.invoiceValue > 0.0)
            b.invoiceValue / b.quantityPurchased
        else
            b.unitCostExcludingTax

        val symbol = CurrencyHelper.getCurrencySymbol(holder.itemView.context)
        holder.tvBatchMeta.text = "${dateFmt.format(Date(b.createdAt))} · $symbol${formatNum(grossUnit)}/unit"
        holder.tvUnitCost.text = "$symbol${formatNum(grossUnit)}"
        holder.tvMaxReturn.text = "Max reducible: ${formatNum(b.quantityRemaining)}"

        val currentQty = selected[position] ?: 0.0
        holder.etQty.setText(if (currentQty > 0.0) formatNum(currentQty) else "")
        updateAmountView(holder, currentQty, grossUnit, rowColor.stripe)

        val rowEnabled = b.quantityRemaining > 0.0
        holder.btnDecrement.isEnabled = rowEnabled
        holder.btnIncrement.isEnabled = rowEnabled
        holder.etQty.isEnabled = rowEnabled
        holder.itemView.alpha = if (rowEnabled) 1f else 0.55f

        holder.btnIncrement.setOnClickListener {
            val cur = selected[position] ?: 0.0
            if (cur < b.quantityRemaining) {
                val next = (cur + 1.0).coerceAtMost(b.quantityRemaining)
                setQty(holder, position, next, b, grossUnit, rowColor.stripe)
            }
        }
        holder.btnDecrement.setOnClickListener {
            val cur = selected[position] ?: 0.0
            if (cur > 0.0) {
                val next = (cur - 1.0).coerceAtLeast(0.0)
                setQty(holder, position, next, b, grossUnit, rowColor.stripe)
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.toDoubleOrNull() ?: 0.0
                val clamped = typed.coerceIn(0.0, b.quantityRemaining)
                if (typed > b.quantityRemaining) {
                    holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }
                    holder.etQty.setText(if (clamped > 0.0) formatNum(clamped) else "")
                    holder.etQty.setSelection(holder.etQty.text?.length ?: 0)
                    holder.watcher?.let { holder.etQty.addTextChangedListener(it) }
                    android.widget.Toast.makeText(
                        holder.itemView.context,
                        "Only ${formatNum(b.quantityRemaining)} available in this batch.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                if (clamped > 0.0) selected[position] = clamped else selected.remove(position)
                updateAmountView(holder, clamped, grossUnit, rowColor.stripe)
                onSelectionChanged?.invoke(totalSelected())
            }
        }
        holder.etQty.addTextChangedListener(watcher)
        holder.watcher = watcher

        holder.etQty.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val typed = holder.etQty.text?.toString()?.toDoubleOrNull() ?: 0.0
                if (typed > b.quantityRemaining) {
                    val clamped = b.quantityRemaining.coerceAtLeast(0.0)
                    holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }
                    holder.etQty.setText(if (clamped > 0.0) formatNum(clamped) else "")
                    holder.watcher?.let { holder.etQty.addTextChangedListener(it) }
                }
            }
        }
    }

    private fun setQty(
        holder: BatchVH,
        position: Int,
        qty: Double,
        b: PurchaseBatch,
        unitCost: Double,
        stripeColor: Int
    ) {
        selected[position] = qty
        holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }
        holder.etQty.setText(if (qty > 0.0) formatNum(qty) else "")
        holder.watcher?.let { holder.etQty.addTextChangedListener(it) }
        updateAmountView(holder, qty, unitCost, stripeColor)
        onSelectionChanged?.invoke(totalSelected())
    }

    private fun updateAmountView(holder: BatchVH, qty: Double, unitCost: Double, stripeColor: Int) {
        if (qty > 0.0) {
            holder.tvBatchAmount.visibility = View.VISIBLE
            holder.tvBatchAmount.text = "Reduce value: ${CurrencyHelper.getCurrencySymbol(holder.itemView.context)}${formatNum(qty * unitCost)}"
            holder.tvBatchAmount.setTextColor(stripeColor)
        } else {
            holder.tvBatchAmount.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = batches.size

    /** Running total of every per-row qty the user has typed. */
    fun totalSelected(): Double = selected.values.sum()

    fun selectedBatchCount(): Int = selected.values.count { it > 0.0 }

    /**
     * Snapshot the selection as
     * [com.example.easy_billing.repository.InventoryReductionRepository.BatchReturnLine]s
     * ready to pass into returnToSupplierByBatches.
     */
    fun selectedLines(): List<com.example.easy_billing.repository.InventoryReductionRepository.BatchReturnLine> =
        selected.entries
            .filter { (_, qty) -> qty > 0.0 }
            .map { (pos, qty) ->
                com.example.easy_billing.repository.InventoryReductionRepository.BatchReturnLine(
                    batchId  = batches[pos].id,
                    quantity = qty,
                )
            }

    private fun formatNum(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
