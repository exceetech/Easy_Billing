package com.example.easy_billing

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.Product
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GoogleTranslator
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*

class ProductAdapter(
    private val onItemClick: (Product) -> Unit,
    private val onItemLongClick: (Product) -> Unit,
    private val language: String,
    private val translationEnabled: Boolean
) : ListAdapter<ProductAdapter.Row, RecyclerView.ViewHolder>(RowDiff()) {

    /** A row is either a category header or a product tile. */
    sealed class Row {
        data class Header(val title: String, val count: Int) : Row()
        data class Item(val product: Product) : Row()
    }

    private val TYPE_HEADER = 0
    private val TYPE_TILE = 1
    private val TYPE_LIST = 2

    // Source products + current display options, so search/filter can be
    // re-applied without losing the grouped/flat/list mode.
    private var sourceProducts: List<Product> = emptyList()
    private var grouped: Boolean = false
    private var asList: Boolean = false
    private var currentQuery: String = ""

    private var inventoryMap: Map<Int, Double> = emptyMap()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Premium card palette (High-contrast pastels) - Optimized for light theme
    private val cardPastels = listOf(
        "#FFE4E6", "#DCFCE7", "#DBEAFE", "#FEF9C3", "#F3E8FF",
        "#E0E7FF", "#D1FAE5", "#FFEDD5", "#CCFBF1", "#FCE7F3"
    )

    // Bold accent colors for monograms (High contrast)
    private val monogramAccents = listOf(
        "#E11D48", "#059669", "#2563EB", "#CA8A04", "#7C3AED",
        "#4F46E5", "#047857", "#D97706", "#0D9488", "#DB2777"
    )

    private fun getStableIndex(name: String): Int = 
        kotlin.math.abs(name.hashCode()) % cardPastels.size

    fun setInventoryMap(map: Map<Int, Double>) {
        inventoryMap = map
        notifyDataSetChanged()
    }

    /** True if the row at [position] is a category header (full-span). */
    fun isHeader(position: Int): Boolean =
        position in 0 until itemCount && getItem(position) is Row.Header

    /**
     * Set the products to display.
     *  • [grouped] = true renders category section headers.
     *  • [asList]  = true renders each product as a compact list row
     *    instead of a tile.
     * Re-applies the current search query.
     */
    fun setProducts(products: List<Product>, grouped: Boolean, asList: Boolean = false) {
        // A tile↔list / grouped change alters each row's *view type* but
        // not its data, so DiffUtil wouldn't rebind. Force a clean refresh
        // only when the mode actually changes; ordinary updates still diff.
        val modeChanged = grouped != this.grouped || asList != this.asList
        this.sourceProducts = products
        this.grouped = grouped
        this.asList = asList
        submitRows(forceRefresh = modeChanged)
    }

    fun filter(query: String) {
        currentQuery = query.trim()
        submitRows()
    }

    private fun submitRows(forceRefresh: Boolean = false) {
        val q = currentQuery
        val filtered = if (q.isBlank()) sourceProducts else sourceProducts.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.variant?.contains(q, ignoreCase = true) == true
        }
        val rows = if (grouped) buildGroupedRows(filtered) else filtered.map { Row.Item(it) }
        if (forceRefresh) {
            // Clear then submit so every row rebinds with its new view type.
            submitList(null)
            submitList(rows)
        } else {
            submitList(rows)
        }
    }

    /**
     * Groups products into category sections. Sections are ordered
     * alphabetically with "Uncategorized" pinned last; within a section
     * the incoming order (i.e. the active sort) is preserved.
     */
    private fun buildGroupedRows(products: List<Product>): List<Row> {
        if (products.isEmpty()) return emptyList()
        val uncategorized = com.example.easy_billing.util.ProductCategories.UNCATEGORIZED
        val byCat = LinkedHashMap<String, MutableList<Product>>()
        for (p in products) {
            val cat = p.category.ifBlank { uncategorized }
            byCat.getOrPut(cat) { mutableListOf() }.add(p)
        }
        val orderedCats = byCat.keys.sortedWith(
            compareBy({ it == uncategorized }, { it.lowercase() })
        )
        val rows = ArrayList<Row>(products.size + byCat.size)
        for (cat in orderedCats) {
            val items = byCat[cat] ?: continue
            rows.add(Row.Header(cat, items.size))
            items.forEach { rows.add(Row.Item(it)) }
        }
        return rows
    }

    fun cancelScope() {
        scope.cancel()
    }

    override fun getItemViewType(position: Int): Int = when {
        getItem(position) is Row.Header -> TYPE_HEADER
        asList -> TYPE_LIST
        else -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_category_header, parent, false))
            // Flat, column-aligned row for the List view.
            TYPE_LIST   -> ListRowViewHolder(inflater.inflate(R.layout.item_product_list, parent, false))
            else        -> ProductViewHolder(inflater.inflate(R.layout.item_product, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row)
            is Row.Item   -> when (holder) {
                is ListRowViewHolder -> holder.bind(row.product)
                is ProductViewHolder -> holder.bind(row.product)
                else -> {}
            }
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tvCategoryHeader)
        private val count: TextView = view.findViewById(R.id.tvCategoryCount)
        fun bind(header: Row.Header) {
            title.text = header.title
            count.text = "${header.count}"
        }
    }

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.cardView)
        private val name: TextView         = view.findViewById(R.id.tvProductName)
        private val translated: TextView   = view.findViewById(R.id.tvTranslatedName)
        private val variant: TextView      = view.findViewById(R.id.tvVariantName)
        private val price: TextView        = view.findViewById(R.id.tvProductPrice)
        private val unit: TextView         = view.findViewById(R.id.tvProductUnit)
        private val stockDot: View         = view.findViewById(R.id.viewStockDot)
        private val stock: TextView        = view.findViewById(R.id.tvStock)
        private val lowBadge: TextView     = view.findViewById(R.id.tvLowStockBadge)
        private val overlay: FrameLayout   = view.findViewById(R.id.flOutOfStockOverlay)
        private val monogram: TextView     = view.findViewById(R.id.tvProductMonogram)
        private val monogramBg: View       = view.findViewById(R.id.viewProductMonogramBg)
        // Present only in the list-row layout; null in the grid tile.
        private val category: TextView?    = view.findViewById(R.id.tvListCategory)

        private var boundName: String = ""

        fun bind(product: Product) {
            val context = itemView.context
            val colorIdx = getStableIndex(product.name)

            card.alpha          = 1f
            card.isClickable    = true
            stock.visibility    = View.GONE
            stockDot.visibility = View.GONE
            overlay.visibility  = View.GONE
            lowBadge.visibility = View.GONE

            // ── Premium Pastel Concept ──────────────────────────────────
            card.setCardBackgroundColor(Color.parseColor(cardPastels[colorIdx]))

            // ── Monogram Styling ─────────────────────────────────────────
            val firstLetter = product.name.take(1).uppercase()
            monogram.text = firstLetter
            monogramBg.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(monogramAccents[colorIdx])
            )
            monogram.setTextColor(Color.WHITE)

            boundName = product.name
            name.text = product.name

            val variantText = product.variant?.takeIf { it.isNotBlank() }
            variant.visibility = if (variantText != null) View.VISIBLE else View.GONE
            variant.text       = variantText ?: ""

            // Category line (list layout only).
            category?.let {
                val c = product.category.takeIf { c -> c.isNotBlank() }
                it.text = c ?: ""
                it.visibility = if (c != null) View.VISIBLE else View.GONE
            }

            if (translationEnabled && language != "en") {
                translated.visibility = View.VISIBLE
                translated.text       = "…"
                val snapshot = product.name
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleTranslator.translate(snapshot, language)
                    }
                    if (boundName == snapshot) translated.text = result
                }
            } else {
                translated.visibility = View.GONE
            }

            val unitLabel = formatUnit(product.unit?.takeIf { it.isNotBlank() } ?: "unit")
            price.text = CurrencyHelper.format(context, product.price)
            unit.text  = "per $unitLabel"

            val stockEntry = if (product.trackInventory) inventoryMap[product.id] else null

            when {
                stockEntry == null -> {
                    setClickListeners(product)
                }
                stockEntry <= 0 -> {
                    overlay.visibility  = View.VISIBLE
                    card.alpha          = 0.55f
                    card.isClickable    = false
                    itemView.setOnClickListener {
                        Toast.makeText(context, "Out of stock", Toast.LENGTH_SHORT).show()
                    }
                    itemView.setOnLongClickListener {
                        onItemLongClick(product)
                        true
                    }
                }
                stockEntry <= 5 -> {
                    lowBadge.visibility = View.VISIBLE
                    stockDot.visibility = View.VISIBLE
                    stockDot.background.setTint(0xFFEF9F27.toInt())
                    stock.visibility = View.VISIBLE
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel left"
                    stock.setBackgroundResource(R.drawable.bg_stock_orange)
                    stock.setTextColor(0xFF92400E.toInt())
                    setClickListeners(product)
                }
                else -> {
                    stockDot.visibility = View.VISIBLE
                    stockDot.background.setTint(0xFF10B981.toInt())
                    stock.visibility = View.VISIBLE
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel"
                    stock.setBackgroundResource(R.drawable.bg_stock_green)
                    stock.setTextColor(0xFF065F46.toInt())
                    setClickListeners(product)
                }
            }
        }

        private fun setClickListeners(product: Product) {
            itemView.setOnClickListener { onItemClick(product) }
            itemView.setOnLongClickListener { onItemLongClick(product); true }
        }
    }

    /** Flat, column-aligned row for List view: Item · Category · Price · Stock. */
    inner class ListRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row: View          = view.findViewById(R.id.listRow)
        private val name: TextView     = view.findViewById(R.id.tvListName)
        private val variant: TextView  = view.findViewById(R.id.tvListVariant)
        private val category: TextView = view.findViewById(R.id.tvListCategory)
        private val price: TextView    = view.findViewById(R.id.tvListPrice)
        private val stock: TextView    = view.findViewById(R.id.tvListStock)

        fun bind(product: Product) {
            val context = itemView.context

            name.text = product.name

            val variantText = product.variant?.takeIf { it.isNotBlank() }
            variant.text = variantText ?: ""
            variant.visibility = if (variantText != null) View.VISIBLE else View.GONE

            category.text = product.category.ifBlank { "—" }
            price.text = CurrencyHelper.format(context, product.price)

            val unitLabel = formatUnit(product.unit?.takeIf { it.isNotBlank() } ?: "unit")
            val stockEntry = if (product.trackInventory) inventoryMap[product.id] else null

            row.alpha = 1f
            when {
                stockEntry == null -> {
                    stock.text = "—"
                    stock.setTextColor(0xFF9CA3AF.toInt())
                    setClickListeners(product)
                }
                stockEntry <= 0 -> {
                    stock.text = "Out of stock"
                    stock.setTextColor(0xFFB91C1C.toInt())
                    row.alpha = 0.6f
                    itemView.setOnClickListener {
                        Toast.makeText(context, "Out of stock", Toast.LENGTH_SHORT).show()
                    }
                    itemView.setOnLongClickListener { onItemLongClick(product); true }
                }
                stockEntry <= 5 -> {
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel"
                    stock.setTextColor(0xFFB45309.toInt())
                    setClickListeners(product)
                }
                else -> {
                    stock.text = "${String.format("%.2f", stockEntry)} $unitLabel"
                    stock.setTextColor(0xFF047857.toInt())
                    setClickListeners(product)
                }
            }
        }

        private fun setClickListeners(product: Product) {
            itemView.setOnClickListener { onItemClick(product) }
            itemView.setOnLongClickListener { onItemLongClick(product); true }
        }
    }

    private fun formatUnit(unit: String): String = when (unit.lowercase()) {
        "piece"  -> "Pc"
        "kg"     -> "Kg"
        "litre"  -> "L"
        "gram"   -> "g"
        "ml"     -> "ml"
        else     -> unit
    }

    class RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Header && newItem is Row.Header -> oldItem.title == newItem.title
            oldItem is Row.Item && newItem is Row.Item -> oldItem.product.id == newItem.product.id
            else -> false
        }
        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }
}