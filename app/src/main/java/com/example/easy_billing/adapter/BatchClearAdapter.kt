package com.example.easy_billing.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.R
import com.example.easy_billing.db.PurchaseBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the unified Clear-Stock batch selector — same card shell
 * and per-row hash color palette as BatchPickerAdapter/item_batch_pick.xml
 * (left accent stripe, monogram avatar, hairline divider), but with a
 * custom premium checkbox (teal-filled square + white check, instead
 * of the native Android CheckBox which renders a stock green tick on
 * some devices) since Clear Stock removes each batch's entire
 * remaining quantity rather than a partial amount. Checked by default
 * for convenience.
 */
class BatchClearAdapter(
    private val batches: List<PurchaseBatch>
) : RecyclerView.Adapter<BatchClearAdapter.BatchClearVH>() {

    private val selectedIndices = HashSet<Int>().apply {
        batches.indices.forEach { add(it) }
    }

    var onSelectionChanged: ((Double) -> Unit)? = null

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // Same random-per-row palette technique as BatchPickerAdapter /
    // SalesReturnItemAdapter / BillHistoryAdapter, keyed by batch id so
    // it's stable across rebinds.
    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(android.graphics.Color.parseColor("#1D6E6E"), android.graphics.Color.parseColor("#DDEEEE"), android.graphics.Color.parseColor("#1D6E6E")),
        RowColor(android.graphics.Color.parseColor("#B23A3A"), android.graphics.Color.parseColor("#FBEDED"), android.graphics.Color.parseColor("#B23A3A")),
        RowColor(android.graphics.Color.parseColor("#8A6526"), android.graphics.Color.parseColor("#FAEEDA"), android.graphics.Color.parseColor("#8A6526")),
        RowColor(android.graphics.Color.parseColor("#3A5FB2"), android.graphics.Color.parseColor("#E5EBFA"), android.graphics.Color.parseColor("#3A5FB2")),
        RowColor(android.graphics.Color.parseColor("#7A4FA3"), android.graphics.Color.parseColor("#EFE5F7"), android.graphics.Color.parseColor("#7A4FA3")),
        RowColor(android.graphics.Color.parseColor("#B2673A"), android.graphics.Color.parseColor("#FAEBE1"), android.graphics.Color.parseColor("#B2673A")),
        RowColor(android.graphics.Color.parseColor("#3A8F6E"), android.graphics.Color.parseColor("#E1F2EA"), android.graphics.Color.parseColor("#3A8F6E")),
        RowColor(android.graphics.Color.parseColor("#A33A7A"), android.graphics.Color.parseColor("#F7E5EF"), android.graphics.Color.parseColor("#A33A7A"))
    )

    private fun colorFor(b: PurchaseBatch): RowColor {
        val idx = kotlin.math.abs(b.id.hashCode()) % rowPalette.size
        return rowPalette[idx]
    }

    inner class BatchClearVH(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.viewItemStripe)
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvInvoice: TextView = view.findViewById(R.id.tvBatchInvoice)
        val tvMeta: TextView = view.findViewById(R.id.tvBatchMeta)
        val tvRemain: TextView = view.findViewById(R.id.tvBatchRemain)
        val tvValue: TextView = view.findViewById(R.id.tvBatchValue)
        val checkBox: View = view.findViewById(R.id.checkBoxSelect)
        val checkMark: ImageView = view.findViewById(R.id.ivCheckMark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchClearVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_clear, parent, false)
        return BatchClearVH(view)
    }

    private fun renderCheckState(holder: BatchClearVH, checked: Boolean) {
        holder.checkBox.setBackgroundResource(
            if (checked) R.drawable.bg_check_box_selected else R.drawable.bg_check_box_unselected
        )
        holder.checkMark.visibility = if (checked) View.VISIBLE else View.GONE
    }

    override fun onBindViewHolder(holder: BatchClearVH, position: Int) {
        val b = batches[position]
        val rowColor = colorFor(b)

        holder.stripe.setBackgroundColor(rowColor.stripe)
        holder.avatar.setTextColor(rowColor.avatarText)
        holder.avatar.background.setTint(rowColor.avatarBg)

        val label = b.invoiceNumber?.takeIf { it.isNotBlank() }
            ?: b.batchCode?.takeIf { it.isNotBlank() }
            ?: "Stock entry #${b.id}"
        holder.tvInvoice.text = label
        holder.avatar.text = label.trim().take(1).uppercase()

        holder.tvMeta.text = buildString {
            append(dateFmt.format(Date(b.createdAt)))
            append(" · ₹")
            append(formatNum(b.unitCostExcludingTax))
            append("/unit")
        }
        holder.tvRemain.text = "Remaining: ${formatNum(b.quantityRemaining)}"
        holder.tvValue.text = "₹${formatNum(b.quantityRemaining * b.unitCostExcludingTax)}"
        holder.tvValue.setTextColor(rowColor.stripe)

        renderCheckState(holder, selectedIndices.contains(position))

        val toggle = {
            val nowChecked = !selectedIndices.contains(position)
            if (nowChecked) selectedIndices.add(position) else selectedIndices.remove(position)
            renderCheckState(holder, nowChecked)
            onSelectionChanged?.invoke(totalSelected())
        }

        holder.checkBox.setOnClickListener { toggle() }
        holder.itemView.setOnClickListener { toggle() }
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

    fun selectedCount(): Int = selectedIndices.size

    private fun formatNum(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
