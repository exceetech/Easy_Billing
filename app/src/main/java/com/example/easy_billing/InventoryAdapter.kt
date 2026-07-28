package com.example.easy_billing

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.InventoryItemUI

/**
 * Inventory row adapter — "Option I": stripe row + monogram avatar + a
 * bigger tinted-pill price, plus two detail lines (category/variant, and
 * stock status paired with the row's total stock value) — see
 * item_inventory.xml doc comment. Each row gets a stable, random-looking
 * accent colour from [rowPalette] (same hash-and-pick approach as
 * BillHistoryAdapter/SalesReturnItemAdapter) shared by the stripe,
 * avatar tile, and price pill. Add/Reduce/Clear are offered from a
 * dedicated champagne dialog (dialog_inventory_stock_action.xml, opened
 * via [showStockActionDialog]) instead of three always-visible buttons.
 */
class InventoryAdapter(
    private var items: List<InventoryItemUI>,
    private val onAddStock: (InventoryItemUI) -> Unit,
    private val onReduceStock: (InventoryItemUI) -> Unit,
    private val onClearStock: (InventoryItemUI) -> Unit
) : RecyclerView.Adapter<InventoryAdapter.ViewHolder>() {

    private data class RowColor(val stripe: Int, val avatarBg: Int, val avatarText: Int)

    private val rowPalette = listOf(
        RowColor(Color.parseColor("#0F6E56"), Color.parseColor("#E1F5EE"), Color.parseColor("#085041")), // teal
        RowColor(Color.parseColor("#B23A3A"), Color.parseColor("#FCEBEB"), Color.parseColor("#791F1F")), // red
        RowColor(Color.parseColor("#8A6526"), Color.parseColor("#FAEEDA"), Color.parseColor("#633806")), // gold
        RowColor(Color.parseColor("#185FA5"), Color.parseColor("#E6F1FB"), Color.parseColor("#0C447C")), // blue
        RowColor(Color.parseColor("#534AB7"), Color.parseColor("#EEEDFE"), Color.parseColor("#3C3489")), // purple
        RowColor(Color.parseColor("#D85A30"), Color.parseColor("#FAECE7"), Color.parseColor("#993C1D")), // rust
        RowColor(Color.parseColor("#3B6D11"), Color.parseColor("#EAF3DE"), Color.parseColor("#27500A")), // green
        RowColor(Color.parseColor("#993556"), Color.parseColor("#FBEAF0"), Color.parseColor("#72243E"))  // pink
    )

    private fun colorFor(item: InventoryItemUI): RowColor {
        val key = "${item.productId}${item.productName}"
        val idx = (key.hashCode() and 0x7FFFFFFF) % rowPalette.size
        return rowPalette[idx]
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stripe: View = view.findViewById(R.id.viewStripe)
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView = view.findViewById(R.id.tvName)
        val cost: TextView = view.findViewById(R.id.tvCost)
        val detailLine: TextView = view.findViewById(R.id.tvDetailLine)
        val stockStatus: TextView = view.findViewById(R.id.tvStockStatus)
        val stockValue: TextView = view.findViewById(R.id.tvStockValue)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val item = items[position]
        val rowColor = colorFor(item)

        holder.stripe.setBackgroundColor(rowColor.stripe)
        holder.avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.avatarBg)
        holder.avatar.setTextColor(rowColor.avatarText)
        holder.avatar.text = monogramFor(item.productName)

        val variantText = item.variant?.takeIf { it.isNotBlank() } ?: ""
        val categoryText = item.category.trim()
        val hsnText = item.hsnCode?.takeIf { it.isNotBlank() } ?: ""
        val unitText = item.unit?.takeIf { it.isNotBlank() } ?: ""

        holder.name.text = item.productName
        holder.cost.text = "₹${"%.2f".format(item.avgCost)}"

        // Stock count (no decimals if whole)
        val stockText = if (item.stock % 1 == 0.0)
            item.stock.toInt().toString()
        else
            item.stock.toString()

        // Detail line — "Category · Variant · HSN NNNN · unit", dropping
        // whichever parts are blank, hidden entirely when nothing exists.
        val detailText = listOfNotNull(
            categoryText.takeIf { it.isNotBlank() },
            variantText.takeIf { it.isNotBlank() },
            hsnText.takeIf { it.isNotBlank() }?.let { "HSN $it" },
            unitText.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (detailText.isEmpty()) {
            holder.detailLine.visibility = View.GONE
        } else {
            holder.detailLine.visibility = View.VISIBLE
            holder.detailLine.text = detailText
        }

        // Stock status — colour and label carry the state, matching the
        // earlier editorial layout's status caption.
        when {
            item.stock <= 0 -> {
                holder.stockStatus.text = "Out of stock"
                holder.stockStatus.setTextColor(Color.parseColor("#B23A3A"))
            }
            item.stock <= 5 -> {
                holder.stockStatus.text = "$stockText units left · low stock"
                holder.stockStatus.setTextColor(Color.parseColor("#854F0B"))
            }
            else -> {
                holder.stockStatus.text = "$stockText units in stock"
                holder.stockStatus.setTextColor(Color.parseColor("#3B6D11"))
            }
        }

        holder.root.setOnClickListener { showStockActionDialog(holder.root.context, item, rowColor) }
    }

    /**
     * Champagne dialog offering Add / Reduce / Clear for [item] —
     * "Option 14" select-then-confirm: tapping a row only selects it
     * (radio dot + highlighted card), the actual action only fires when
     * "Continue" is pressed. Add stock is selected by default since it's
     * the most common action. Reduce/Clear rows stay tappable-but-inert
     * when there's nothing left to reduce or clear (selecting them is
     * blocked rather than hiding the row), same reasoning as the old
     * btnReduce/btnClear.isEnabled toggles.
     */
    private fun showStockActionDialog(context: android.content.Context, item: InventoryItemUI, rowColor: RowColor) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_inventory_stock_action, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val stockText = if (item.stock % 1 == 0.0) item.stock.toInt().toString() else item.stock.toString()
        val (captionText, captionColor) = when {
            item.stock <= 0 -> "Out of stock" to Color.parseColor("#B23A3A")
            item.stock <= 5 -> "$stockText units left · low stock" to Color.parseColor("#854F0B")
            else -> "$stockText units in stock" to Color.parseColor("#3B6D11")
        }

        view.findViewById<TextView>(R.id.tvActionAvatar).apply {
            text = monogramFor(item.productName)
            backgroundTintList = android.content.res.ColorStateList.valueOf(rowColor.avatarBg)
            setTextColor(rowColor.avatarText)
        }
        view.findViewById<TextView>(R.id.tvActionProductName).text = item.productName
        view.findViewById<TextView>(R.id.tvActionStockCaption).apply {
            text = captionText
            setTextColor(captionColor)
        }

        val canReduceOrClear = item.stock > 0

        val rowAdd = view.findViewById<View>(R.id.rowAddStock)
        val rowReduce = view.findViewById<View>(R.id.rowReduceStock)
        val rowClear = view.findViewById<View>(R.id.rowClearStock)
        val radioAdd = view.findViewById<View>(R.id.radioAddStock)
        val radioReduce = view.findViewById<View>(R.id.radioReduceStock)
        val radioClear = view.findViewById<View>(R.id.radioClearStock)
        val btnContinue = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnContinueAction)

        rowReduce.alpha = if (canReduceOrClear) 1f else 0.4f
        rowClear.alpha = if (canReduceOrClear) 1f else 0.4f

        // "Add stock" selected by default.
        var selected = "ADD"

        fun refreshSelection() {
            rowAdd.setBackgroundResource(if (selected == "ADD") R.drawable.bg_inv_action_row_selected else R.drawable.bg_inv_action_row)
            radioAdd.setBackgroundResource(if (selected == "ADD") R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
            rowReduce.setBackgroundResource(if (selected == "REDUCE") R.drawable.bg_inv_action_row_selected else R.drawable.bg_inv_action_row)
            radioReduce.setBackgroundResource(if (selected == "REDUCE") R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
            rowClear.setBackgroundResource(if (selected == "CLEAR") R.drawable.bg_inv_action_row_selected else R.drawable.bg_inv_action_row)
            radioClear.setBackgroundResource(if (selected == "CLEAR") R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
        }

        rowAdd.setOnClickListener { selected = "ADD"; refreshSelection() }
        rowReduce.setOnClickListener {
            if (canReduceOrClear) { selected = "REDUCE"; refreshSelection() }
        }
        rowClear.setOnClickListener {
            if (canReduceOrClear) { selected = "CLEAR"; refreshSelection() }
        }

        btnContinue.setOnClickListener {
            dialog.dismiss()
            when (selected) {
                "ADD" -> onAddStock(item)
                "REDUCE" -> onReduceStock(item)
                "CLEAR" -> onClearStock(item)
            }
        }

        dialog.show()
    }

    /** Two-letter monogram from the product name, e.g. "Colgate toothpaste" → "CT". */
    private fun monogramFor(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    override fun getItemCount() = items.size

    // INV-5 fix: notifyDataSetChanged() rebound every row on every refresh,
    // even when nothing about a given product had actually changed — every
    // refresh visually "flickered" the whole list, making it hard to tell
    // a genuine stock-count change from a harmless re-render. DiffUtil only
    // rebinds the rows whose values actually differ.
    fun updateData(newItems: List<InventoryItemUI>) {
        val oldItems = this.items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size

            // Identity: same product row.
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].productId == newItems[newPos].productId

            // Content: InventoryItemUI is a data class, so structural
            // equality already compares every field (stock, avgCost,
            // category, etc.) — a row only gets rebound if something in it
            // genuinely changed.
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos] == newItems[newPos]
        })
        this.items = newItems
        diff.dispatchUpdatesTo(this)
    }
}
