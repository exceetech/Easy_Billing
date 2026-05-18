package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.PurchaseBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the unified Clear-Stock batch selector.
 * Allows checking individual batches to clear their entire remaining stock,
 * or selecting all via a master checkbox. No quantity inputs needed.
 */
class BatchClearAdapter(
    private val batches: List<PurchaseBatch>
) : RecyclerView.Adapter<BatchClearAdapter.BatchClearVH>() {

    // Tracks selected batch indices. Checked by default for premium convenience.
    private val selectedIndices = HashSet<Int>().apply {
        batches.indices.forEach { add(it) }
    }

    var onSelectionChanged: ((Double) -> Unit)? = null

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    inner class BatchClearVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvInvoice: TextView = view.findViewById(R.id.tvBatchInvoice)
        val tvSupplier: TextView = view.findViewById(R.id.tvBatchSupplier)
        val tvMeta: TextView = view.findViewById(R.id.tvBatchMeta)
        val tvRemain: TextView = view.findViewById(R.id.tvBatchRemain)
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelectBatch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchClearVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_clear, parent, false)
        return BatchClearVH(view)
    }

    override fun onBindViewHolder(holder: BatchClearVH, position: Int) {
        val b = batches[position]

        holder.tvInvoice.text = b.invoiceNumber?.takeIf { it.isNotBlank() }
            ?: "Stock entry #${b.id}"
        holder.tvSupplier.text = b.supplierName?.takeIf { it.isNotBlank() }
            ?: "Direct add-stock"
        holder.tvMeta.text = buildString {
            append(dateFmt.format(Date(b.createdAt)))
            append("  ·  ₹")
            append(formatNum(b.unitCostExcludingTax))
            append("/unit")
        }
        holder.tvRemain.text = "Remaining: ${formatNum(b.quantityRemaining)}"

        // Bind checked state without triggering the listener recursively
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = selectedIndices.contains(position)

        val toggleAction = { checked: Boolean ->
            if (checked) {
                selectedIndices.add(position)
            } else {
                selectedIndices.remove(position)
            }
            onSelectionChanged?.invoke(totalSelected())
        }

        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            toggleAction(isChecked)
        }

        // Tap the whole row container to toggle selection for maximum premium UX
        holder.itemView.setOnClickListener {
            val nextState = !holder.cbSelect.isChecked
            holder.cbSelect.isChecked = nextState
        }
    }

    override fun getItemCount(): Int = batches.size

    fun selectAll(checked: Boolean) {
        selectedIndices.clear()
        if (checked) {
            batches.indices.forEach { selectedIndices.add(it) }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(totalSelected())
    }

    fun totalSelected(): Double =
        selectedIndices.sumOf { batches[it].quantityRemaining }

    fun selectedBatches(): List<PurchaseBatch> =
        selectedIndices.map { batches[it] }

    private fun formatNum(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
