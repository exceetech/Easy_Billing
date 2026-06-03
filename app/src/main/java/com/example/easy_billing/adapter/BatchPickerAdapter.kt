package com.example.easy_billing.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.PurchaseBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the supplier-return batch picker.
 *
 * Each row binds one remaining [PurchaseBatch] alongside a numeric
 * [EditText] for the per-batch return quantity. The host dialog
 * observes the running total via [onSelectionChanged] and keeps the
 * Confirm button gated until it matches the target.
 *
 *   adapter.onSelectionChanged = { running ->
 *       tvBatchRunning.text = formatQty(running)
 *       btnConfirm.isEnabled = running == targetQty
 *   }
 *
 * Submit:
 *
 *   val lines = adapter.selectedLines()  // List<BatchReturnLine>
 *
 * Then call:
 *   InventoryReductionRepository.returnToSupplierByBatches(
 *       productId, lines, productName, hsnCode, variantName, …
 *   )
 *
 * IMPORTANT — the input uses a plain [EditText] (not
 * TextInputEditText). TextInputLayout's compound-drawable focus
 * handling interacted badly with the RecyclerView-inside-Dialog
 * setup and ate user input on some devices. Plain EditText + a
 * rounded selector drawable gives the same premium look without
 * losing the IME bridge.
 */
class BatchPickerAdapter(
    private val batches: List<PurchaseBatch>
) : RecyclerView.Adapter<BatchPickerAdapter.BatchVH>() {

    /** position → user-entered qty for that batch. */
    private val selected = HashMap<Int, Double>()

    /** Fired whenever the per-batch total changes. */
    var onSelectionChanged: ((Double) -> Unit)? = null

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    inner class BatchVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvInvoice: TextView = view.findViewById(R.id.tvBatchInvoice)
        val tvSupplier: TextView = view.findViewById(R.id.tvBatchSupplier)
        val tvMeta: TextView = view.findViewById(R.id.tvBatchMeta)
        val tvRemain: TextView = view.findViewById(R.id.tvBatchRemain)
        val etQty: EditText = view.findViewById(R.id.etBatchQty)

        /**
         * The currently-attached watcher. Held so we can cleanly
         * detach when the holder is recycled — otherwise typing in
         * one row would re-fire the previous binding's callback.
         */
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_pick, parent, false)
        return BatchVH(view)
    }

    override fun onBindViewHolder(holder: BatchVH, position: Int) {
        val b = batches[position]

        val invoiceText = b.invoiceNumber?.takeIf { it.isNotBlank() }
            ?: b.batchCode?.takeIf { it.isNotBlank() }
            ?: "Stock entry #${b.id}"
            
        holder.tvInvoice.text = when {
            invoiceText == "MIGRATION" -> "Initial Stock"
            b.batchCode?.startsWith("CN-") == true && b.invoiceNumber?.isNotBlank() == true -> "$invoiceText (Credited)"
            else -> invoiceText
        }

        val supplierText = b.supplierName?.takeIf { it.isNotBlank() }
            ?: if (b.batchCode?.startsWith("SALES_RETURN") == true) "Returned by Customer"
            else if (b.batchCode?.startsWith("CN-") == true || b.batchCode?.startsWith("DN-") == true) "Supplier Adjustment"
            else if (b.batchCode == "MIGRATION") "System Migration"
            else "Direct add-stock"
            
        holder.tvSupplier.text = supplierText
        holder.tvMeta.text = buildString {
            append(dateFmt.format(Date(b.createdAt)))
            append("  ·  ₹")
            append(formatNum(b.unitCostExcludingTax))
            append("/unit")
        }
        holder.tvRemain.text = "Remaining: ${formatNum(b.quantityRemaining)}"

        // Detach old watcher BEFORE we reset the text — otherwise the
        // stale row's callback fires on the new binding.
        holder.watcher?.let { holder.etQty.removeTextChangedListener(it) }

        val existing = selected[position]
        holder.etQty.setText(existing?.let { formatNum(it) } ?: "")

        // Re-enable in case it was disabled for a zero-remaining row.
        holder.etQty.isEnabled = b.quantityRemaining > 0.0

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val v = text.toDoubleOrNull() ?: 0.0

                // Clamp to remaining qty. Use a tiny tolerance so the
                // user can type "10" when the batch has exactly 10
                // remaining without us rewriting their input.
                val clamped = v.coerceIn(0.0, b.quantityRemaining)
                if (kotlin.math.abs(clamped - v) > 0.0001) {
                    // Detach + reattach so the recursive setText
                    // doesn't run this same watcher again. Without
                    // this guard the cursor occasionally jumped to
                    // the start of the field on clamp.
                    holder.etQty.removeTextChangedListener(this)
                    val formatted = formatNum(clamped)
                    holder.etQty.setText(formatted)
                    holder.etQty.setSelection(formatted.length)
                    holder.etQty.addTextChangedListener(this)

                    if (v > b.quantityRemaining) {
                        android.widget.Toast.makeText(
                            holder.itemView.context,
                            "Only ${formatNum(b.quantityRemaining)} available in this batch. Debit the rest from the next batch.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                if (clamped > 0.0) selected[position] = clamped
                else selected.remove(position)

                onSelectionChanged?.invoke(totalSelected())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        holder.etQty.addTextChangedListener(watcher)
        holder.watcher = watcher
    }

    override fun getItemCount(): Int = batches.size

    /** Running total of every per-row qty the user has typed. */
    fun totalSelected(): Double = selected.values.sum()

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
