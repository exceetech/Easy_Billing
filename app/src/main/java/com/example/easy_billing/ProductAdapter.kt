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
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*

class ProductAdapter(
    // Normal (piece/packet) items: single tap adds 1 to the bill.
    private val onQuickAdd: (Product) -> Unit,
    // Any item: opens the number pad. Used as-is (additive, blank start)
    // for weighed items on every tap, and for normal items on a fast
    // second tap — see [TapArbiter].
    private val onOpenQuantityPad: (Product) -> Unit,
    // Normal items only, fired on a fast second tap: opens the number
    // pad pre-filled with whatever is already in the bill, so the typed
    // number REPLACES it instead of adding on top — lets someone type an
    // exact large quantity (e.g. 1000) instead of tapping 1000 times.
    private val onSetExactQuantity: (Product) -> Unit,
    private val onItemLongClick: (Product, View) -> Unit
) : ListAdapter<ProductAdapter.Row, RecyclerView.ViewHolder>(RowDiff()) {

    /**
     * Arbitrates single-tap vs double-tap on a product card.
     *
     * Weighed items (kg/litre) never use this — they keep their original
     * behaviour of opening the number pad on every tap, since a decimal
     * weight can't be entered by tapping anyway.
     *
     * Normal items: a single tap waits out the *system's own* double-tap
     * window (the same timeout Android uses everywhere, e.g. double-tapping
     * a photo to like it) before adding 1 — so a fast second tap can
     * instead open the pad to type an exact number. One instance is reused
     * across row recycling; [reset] MUST run at the very top of every
     * bind() so a pending "add 1" left over from whatever product this row
     * showed *before* being recycled can never fire against the new one.
     */
    private class TapArbiter(private val view: View) {
        private var pendingSingleTap: Runnable? = null
        private var lastTapAtMs = 0L
        private var lastDebouncedOpenAtMs = 0L
        private val doubleTapWindowMs =
            android.view.ViewConfiguration.getDoubleTapTimeout().toLong()

        fun reset() {
            pendingSingleTap?.let { view.removeCallbacks(it) }
            pendingSingleTap = null
            lastTapAtMs = 0L
            lastDebouncedOpenAtMs = 0L
        }

        fun onTap(onSingleTap: () -> Unit, onDoubleTap: () -> Unit) {
            val now = System.currentTimeMillis()
            val pending = pendingSingleTap
            if (pending != null && now - lastTapAtMs <= doubleTapWindowMs) {
                view.removeCallbacks(pending)
                pendingSingleTap = null
                lastTapAtMs = 0L
                onDoubleTap()
            } else {
                lastTapAtMs = now
                val runnable = Runnable {
                    pendingSingleTap = null
                    onSingleTap()
                }
                pendingSingleTap = runnable
                view.postDelayed(runnable, doubleTapWindowMs)
            }
        }

        /**
         * For weighed items: every tap should open the pad — including a
         * fast double-tap, which should still count as "open the pad,"
         * just once, not twice. A plain OnClickListener fires once per tap,
         * so a real double-tap (two taps inside the system's double-tap
         * window) was opening the dialog twice — a second AlertDialog
         * stacking on top of the first. This swallows any tap that lands
         * within that same window as the one before it.
         */
        fun onTapDebounced(action: () -> Unit) {
            val now = System.currentTimeMillis()
            if (now - lastDebouncedOpenAtMs <= doubleTapWindowMs) return
            lastDebouncedOpenAtMs = now
            action()
        }
    }

    /** Same weighed-unit definition used app-wide (DashboardActivity's
     *  quantity pad, InventoryActivity) — kept identical so a product
     *  never behaves as "weighed" in one place and "counted" in another. */
    private fun isWeighed(product: Product): Boolean = when (product.unit?.lowercase()) {
        "kilogram", "kg", "litre", "l" -> true
        else -> false
    }

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

    // Product id -> quantity currently in the bill, so cards can show the
    // "✓ N in bill" badge and a green border without the adapter needing
    // to know anything about CartItem/DashboardActivity's cart internals.
    private var cartQtyMap: Map<Int, Double> = emptyMap()

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

    /** Called whenever the bill changes (add/remove/quantity edit) so every
     *  visible card's "in bill" badge and border stay in sync. */
    fun setCartQtyMap(map: Map<Int, Double>) {
        cartQtyMap = map
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
            count.text = "${header.count} item${if (header.count == 1) "" else "s"}"
        }
    }

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tapArbiter = TapArbiter(itemView)
        private val card: MaterialCardView = view.findViewById(R.id.cardView)
        private val name: TextView         = view.findViewById(R.id.tvProductName)
        private val variant: TextView      = view.findViewById(R.id.tvVariantName)
        private val price: TextView        = view.findViewById(R.id.tvProductPrice)
        private val stockDot: View         = view.findViewById(R.id.viewStockDot)
        private val pill: View             = view.findViewById(R.id.pillStock)
        private val pillText: TextView     = view.findViewById(R.id.tvStockPill)
        private val priceUnit: TextView    = view.findViewById(R.id.tvPriceUnit)
        private val addBtn: View           = view.findViewById(R.id.ivAddProduct)
        private val overlay: FrameLayout   = view.findViewById(R.id.flOutOfStockOverlay)
        private val monogram: TextView     = view.findViewById(R.id.tvProductMonogram)
        private val monogramBg: View       = view.findViewById(R.id.viewProductMonogramBg)
        private val accentStripe: View     = view.findViewById(R.id.viewAccentStripe)
        // Category chip (now shown in the grid tile too).
        private val category: TextView?    = view.findViewById(R.id.tvListCategory)
        private val chipInBill: View       = view.findViewById(R.id.chipInBill)
        private val tvInBillCount: TextView = view.findViewById(R.id.tvInBillCount)

        // Tracks what THIS row last showed, so a crossfade only plays when
        // the same product's in-bill state actually flips (i.e. the tap
        // that just added/cleared it) — not on every unrelated rebind
        // (scrolling to a different product, an inventory refresh, etc.).
        private var lastBoundProductId: Int? = null
        private var lastHadQtyInBill: Boolean? = null

        fun bind(product: Product) {
            // MUST be first: cancels any pending "add 1" left over from
            // whatever product this recycled row showed before, so it can
            // never fire against the product now being bound.
            tapArbiter.reset()

            val context = itemView.context
            val colorIdx = getStableIndex(product.name)

            card.alpha          = 1f
            card.isClickable    = true
            stockDot.visibility = View.GONE
            overlay.visibility  = View.GONE
            addBtn.backgroundTintList = null

            // ── Random pastel card (stable per product, just a try) ────────
            card.setCardBackgroundColor(Color.parseColor(cardPastels[colorIdx]))

            // ── Monogram Styling ─────────────────────────────────────────
            // Monogram circle stays white so it still pops against the
            // colored card instead of blending into it.
            val firstLetter = product.name.take(1).uppercase()
            monogram.text = firstLetter
            monogramBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            monogram.setTextColor(Color.parseColor(monogramAccents[colorIdx]))
            accentStripe.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(monogramAccents[colorIdx]))

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

            val unitLabel = formatUnit(context, product.unit?.takeIf { it.isNotBlank() } ?: "unit")
            price.text = CurrencyHelper.format(context, product.price)
            priceUnit.text = "/ $unitLabel"

            val stockEntry = if (product.trackInventory) inventoryMap[product.id] else null

            when {
                stockEntry == null -> {
                    // Untracked / service item — neutral pill, no dot.
                    stockDot.visibility     = View.GONE
                    pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F1EFE8"))
                    pillText.text           = context.getString(R.string.product_adapter_service_pill)
                    pillText.setTextColor(Color.parseColor("#6B6B63"))
                    setClickListeners(product)
                }
                stockEntry <= 0 -> {
                    stockDot.visibility     = View.VISIBLE
                    stockDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
                    pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FCEBEB"))
                    pillText.text           = context.getString(R.string.product_adapter_out_of_stock_pill)
                    pillText.setTextColor(Color.parseColor("#B91C1C"))
                    addBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C9C3B4"))
                    overlay.visibility  = View.VISIBLE
                    card.alpha          = 0.55f
                    card.isClickable    = false
                    itemView.setOnClickListener {
                        Toast.makeText(context, context.getString(R.string.product_adapter_out_of_stock_toast), Toast.LENGTH_SHORT).show()
                    }
                    itemView.setOnLongClickListener {
                        onItemLongClick(product, card)
                        true
                    }
                }
                stockEntry <= 5 -> {
                    stockDot.visibility     = View.VISIBLE
                    stockDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF9F27"))
                    pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FBF0DC"))
                    pillText.text           = "Low · ${fmtQty(stockEntry)} left"
                    pillText.setTextColor(Color.parseColor("#854F0B"))
                    setClickListeners(product)
                }
                else -> {
                    stockDot.visibility     = View.VISIBLE
                    stockDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E7F6EF"))
                    pillText.text           = "${fmtQty(stockEntry)} in stock"
                    pillText.setTextColor(Color.parseColor("#0F6E56"))
                    setClickListeners(product)
                }
            }

            // ── "In bill" chip (replaces "+ Add" in place) + green border ──
            // Computed after the stock branch above so it always reflects
            // the bill regardless of which stock state the card is in.
            val inBillQty = cartQtyMap[product.id]
            val hasQtyInBill = inBillQty != null && inBillQty > 0

            if (hasQtyInBill) {
                tvInBillCount.text = "${fmtQty(inBillQty!!)} in bill"
                card.strokeColor = Color.parseColor("#0F6E56")
                card.strokeWidth = dpToPx(context, 2)
            } else {
                card.strokeColor = Color.parseColor("#ECE7DA")
                card.strokeWidth = dpToPx(context, 1)
            }

            // Same product, state actually flipped just now (this tap) →
            // crossfade. Anything else (fresh bind, a different product
            // after recycling, an unrelated rebind that left the state
            // unchanged) → snap straight to the right state, no animation.
            val sameProductStillBound = lastBoundProductId == product.id
            if (sameProductStillBound && lastHadQtyInBill != null && lastHadQtyInBill != hasQtyInBill) {
                crossfadeAddButtonAndBillChip(hasQtyInBill)
            } else {
                snapAddButtonAndBillChip(hasQtyInBill)
            }
            lastBoundProductId = product.id
            lastHadQtyInBill = hasQtyInBill
        }

        /** Instant, no-animation state — used on first bind and whenever a
         *  row is recycled to a *different* product, so a stale animation
         *  can never bleed onto the wrong card. */
        private fun snapAddButtonAndBillChip(showChip: Boolean) {
            addBtn.animate().cancel()
            chipInBill.animate().cancel()
            addBtn.alpha = if (showChip) 0f else 1f
            addBtn.visibility = if (showChip) View.GONE else View.VISIBLE
            chipInBill.alpha = if (showChip) 1f else 0f
            chipInBill.visibility = if (showChip) View.VISIBLE else View.GONE
        }

        /** Smooth crossfade between "+ Add" and "✓ N in bill" in the exact
         *  same spot — played only when THIS row's own state just changed
         *  (see the caller), so tapping a card feels like the button
         *  morphing into the badge rather than a jump-cut. */
        private fun crossfadeAddButtonAndBillChip(showChip: Boolean) {
            val fadeInView = if (showChip) chipInBill else addBtn
            val fadeOutView = if (showChip) addBtn else chipInBill

            fadeOutView.animate().cancel()
            fadeInView.animate().cancel()

            fadeInView.alpha = 0f
            fadeInView.visibility = View.VISIBLE
            fadeInView.animate()
                .alpha(1f)
                .setDuration(180)
                .setStartDelay(40) // lets the fade-out lead very slightly
                .start()

            fadeOutView.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { fadeOutView.visibility = View.GONE }
                .start()
        }

        private fun setClickListeners(product: Product) {
            if (isWeighed(product)) {
                // Every tap opens the pad, additive, blank start — but a
                // fast double-tap must only open it once, not stack two
                // dialogs on top of each other.
                itemView.setOnClickListener {
                    tapArbiter.onTapDebounced { onOpenQuantityPad(product) }
                }
            } else {
                itemView.setOnClickListener {
                    tapArbiter.onTap(
                        onSingleTap = { onQuickAdd(product) },
                        onDoubleTap = { onSetExactQuantity(product) }
                    )
                }
            }
            itemView.setOnLongClickListener {
                tapArbiter.reset()
                onItemLongClick(product, card)
                true
            }
        }
    }

    /** Flat, column-aligned row for List view: Item · Category · Price · Stock. */
    inner class ListRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tapArbiter = TapArbiter(itemView)
        private val row: View          = view.findViewById(R.id.listRow)
        private val name: TextView     = view.findViewById(R.id.tvListName)
        private val variant: TextView  = view.findViewById(R.id.tvListVariant)
        private val price: TextView    = view.findViewById(R.id.tvListPrice)
        private val stock: TextView    = view.findViewById(R.id.tvListStock)
        private val monogram: TextView = view.findViewById(R.id.tvListMonogram)
        private val monogramBg: View   = view.findViewById(R.id.viewListMonogramBg)
        private val inBillStripe: View? = view.findViewById(R.id.viewListInBillStripe)
        private val inBillBadge: TextView = view.findViewById(R.id.tvListInBillBadge)

        fun bind(product: Product) {
            // MUST be first — see TapArbiter's kdoc.
            tapArbiter.reset()

            val context = itemView.context

            name.text = product.name

            // Same avatar treatment as the grid tile: single-letter
            // monogram on a pastel tile, coloured to match.
            val colorIdx = getStableIndex(product.name)
            val accent = monogramAccents[colorIdx]
            monogram.text = product.name.take(1).uppercase()
            monogramBg.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(cardPastels[colorIdx]))
            monogram.setTextColor(Color.parseColor(accent))

            val variantText = product.variant?.takeIf { it.isNotBlank() }
            variant.visibility = if (variantText != null) View.VISIBLE else View.GONE
            variant.text = variantText ?: ""

            price.text = CurrencyHelper.format(context, product.price)

            val stockEntry = if (product.trackInventory) inventoryMap[product.id] else null

            row.alpha = 1f
            when {
                stockEntry == null -> {
                    stock.text = context.getString(R.string.product_adapter_service_item)
                    stock.setTextColor(0xFF9A8F79.toInt())
                    setClickListeners(product)
                }
                stockEntry <= 0 -> {
                    stock.text = context.getString(R.string.product_adapter_out_of_stock_pill_2)
                    stock.setTextColor(0xFFA32D2D.toInt())
                    row.alpha = 0.6f
                    itemView.setOnClickListener {
                        Toast.makeText(context, context.getString(R.string.product_adapter_out_of_stock_toast_2), Toast.LENGTH_SHORT).show()
                    }
                    itemView.setOnLongClickListener { onItemLongClick(product, row); true }
                }
                stockEntry <= 5 -> {
                    stock.text = "Low · ${fmtQty(stockEntry)} left"
                    stock.setTextColor(0xFF854F0B.toInt())
                    setClickListeners(product)
                }
                else -> {
                    stock.text = "${fmtQty(stockEntry)} units in stock"
                    stock.setTextColor(0xFF3B6D11.toInt())
                    setClickListeners(product)
                }
            }

            // Compact "N in bill" badge on the right side
            val inBillQty = cartQtyMap[product.id]
            if (inBillQty != null && inBillQty > 0) {
                inBillBadge.visibility = View.VISIBLE
                inBillBadge.text = "${fmtQty(inBillQty)} in bill"
            } else {
                inBillBadge.visibility = View.GONE
            }
        }

        private fun setClickListeners(product: Product) {
            if (isWeighed(product)) {
                itemView.setOnClickListener {
                    tapArbiter.onTapDebounced { onOpenQuantityPad(product) }
                }
            } else {
                itemView.setOnClickListener {
                    tapArbiter.onTap(
                        onSingleTap = { onQuickAdd(product) },
                        onDoubleTap = { onSetExactQuantity(product) }
                    )
                }
            }
            itemView.setOnLongClickListener {
                tapArbiter.reset()
                onItemLongClick(product, row)
                true
            }
        }
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** Stock quantity without a trailing ".0" (24.0 -> "24", 2.5 -> "2.5"). */
    private fun fmtQty(q: Double): String =
        if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()

    private fun formatUnit(context: android.content.Context, unit: String): String = when (unit.lowercase()) {
        "piece"  -> context.getString(R.string.product_unit_pc)
        "kg"     -> context.getString(R.string.product_unit_kg)
        "litre"  -> context.getString(R.string.product_unit_litre)
        "gram"   -> context.getString(R.string.product_unit_gram)
        "ml"     -> context.getString(R.string.product_unit_ml)
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
