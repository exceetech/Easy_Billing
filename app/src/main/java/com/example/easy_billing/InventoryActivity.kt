package com.example.easy_billing

import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.BatchPickerAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.db.InventoryItemUI
import com.example.easy_billing.db.Product
import com.example.easy_billing.db.PurchaseBatch
import com.example.easy_billing.repository.InventoryReductionRepository
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CreditAdjustmentPrompt
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.util.InvoiceDatePicker
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InventoryActivity : BaseActivity() {

    private lateinit var rvInventory: RecyclerView
    private lateinit var cardInventory: View
    private lateinit var layoutInventoryEmpty: View
    private lateinit var tvInventoryEmptyTitle: TextView
    private lateinit var tvInventoryEmptyTitleAccent: TextView
    private lateinit var tvInventoryEmptyBody: TextView
    private lateinit var adapter: InventoryAdapter
    private lateinit var db: AppDatabase

    private var fullList: List<InventoryItemUI> = emptyList()
    private var productMap: Map<Int, Product> = emptyMap()
    private var currentQuery = ""
    private var currentCategory = ""   // "" = All
    private var currentSort = InvSort.STOCK_HIGH_LOW

    // Stock-status quick filter — chosen from the Filter icon button's
    // popup (same pattern as BillHistoryActivity's Filter+Sort row); the
    // badge on the Filter icon shows whenever it's off "All".
    private enum class StockFilter { ALL, LOW, OUT }
    private var currentStockFilter = StockFilter.ALL

    private lateinit var btnFilter: View
    private lateinit var btnSortIcon: View
    private lateinit var tvFilterBadge: TextView
    private lateinit var tvResultSummary: TextView
    private lateinit var btnResetFilters: View

    // Random-looking stock count fix (same root cause as Dashboard's
    // loadProducts()): loadInventory() is triggered from onResume plus
    // several post-edit call sites, each starting a fresh, uncancelled
    // load. Two overlapping loads racing meant whichever finished LAST won
    // and overwrote the list — regardless of which actually had the newer
    // data — which is what made the stock count look like it was flipping
    // at random. This counter lets a load recognise it's been superseded
    // and discard its own (stale) results instead of applying them.
    private val loadInventoryGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private enum class InvSort {
        A_TO_Z, Z_TO_A, PRICE_LOW_HIGH, PRICE_HIGH_LOW,
        STOCK_LOW_HIGH, STOCK_HIGH_LOW, STOCK_VALUE_HIGH_LOW
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        db = AppDatabase.getDatabase(this)

        rvInventory = findViewById(R.id.rvInventory)
        cardInventory = findViewById(R.id.cardInventory)
        layoutInventoryEmpty = findViewById(R.id.layoutInventoryEmpty)
        tvInventoryEmptyTitle = findViewById(R.id.tvInventoryEmptyTitle)
        tvInventoryEmptyTitleAccent = findViewById(R.id.tvInventoryEmptyTitleAccent)
        tvInventoryEmptyBody = findViewById(R.id.tvInventoryEmptyBody)
        rvInventory.layoutManager = LinearLayoutManager(this)
        // Let the first and last row follow the card's rounded corners.
        // Applied to the shared card container rather than rvInventory
        // directly, since the rounded background lives on cardInventory.
        cardInventory.clipToOutline = true

        adapter = InventoryAdapter(
            emptyList(),
            onAddStock = { item ->
                productMap[item.productId]?.let { showAddStockDialog(it) }
            },
            onReduceStock = { item ->
                productMap[item.productId]?.let { showReduceStockDialog(it, item.stock) }
            },
            onClearStock = { item -> showClearStockDialog(item.productId) }
        )

        rvInventory.adapter = adapter

        btnFilter = findViewById(R.id.btnFilter)
        btnSortIcon = findViewById(R.id.btnSort)
        tvFilterBadge = findViewById(R.id.tvFilterBadge)
        tvResultSummary = findViewById(R.id.tvResultSummary)
        btnResetFilters = findViewById(R.id.btnResetFilters)

        setupSearch()
        setupHeaderActions()
        setupFilterAndSort()
    }

    // ================= FILTER + SORT =================

    private fun setupFilterAndSort() {
        btnFilter.setOnClickListener { showStockFilterPopup() }
        btnResetFilters.setOnClickListener {
            currentStockFilter = StockFilter.ALL
            currentCategory = ""
            currentSort = InvSort.STOCK_HIGH_LOW
            findViewById<com.google.android.material.chip.Chip?>(R.id.chipCatAll)?.isChecked = true
            applyFilter()
        }
    }

    private fun showStockFilterPopup() {
        val options = listOf(
            StockFilter.ALL to getString(R.string.inventory_filter_all_products),
            StockFilter.LOW to getString(R.string.inventory_filter_low_stock),
            StockFilter.OUT to getString(R.string.inventory_out_of_stock)
        )
        val selectedIndex = options.indexOfFirst { it.first == currentStockFilter }.coerceAtLeast(0)
        com.example.easy_billing.ui.ThemedDropdown.show(
            btnFilter, options.map { it.second }, selectedIndex,
            rightAlign = false, minWidthDp = 190
        ) { idx -> setStockFilter(options[idx].first) }
    }

    private fun setStockFilter(filter: StockFilter) {
        currentStockFilter = filter
        applyFilter()
    }

    // ================= HEADER ACTIONS =================

    private fun setupHeaderActions() {

        // Edit/Add product icons removed from the header's top-right
        // corner — Add product and Manage products are still reachable
        // elsewhere (add-product flow / product management), this screen
        // just no longer shortcuts to them from up here.

        findViewById<View?>(R.id.btnSort)?.apply {
            contentDescription = getString(R.string.inventory_sort_content_desc)
            setOnClickListener { showSortMenu(it) }
        }
    }

    // ================= THEMED SORT DROPDOWN =================

    private fun showSortMenu(anchor: View) {
        val options = listOf(
            InvSort.A_TO_Z              to getString(R.string.dashboard_sort_name_asc),
            InvSort.Z_TO_A              to getString(R.string.dashboard_sort_name_desc),
            InvSort.PRICE_LOW_HIGH      to getString(R.string.dashboard_sort_price_asc),
            InvSort.PRICE_HIGH_LOW      to getString(R.string.dashboard_sort_price_desc),
            InvSort.STOCK_LOW_HIGH      to getString(R.string.dashboard_sort_stock_asc),
            InvSort.STOCK_HIGH_LOW      to getString(R.string.dashboard_sort_stock_desc),
            InvSort.STOCK_VALUE_HIGH_LOW to getString(R.string.inventory_sort_value_desc)
        )
        val selectedIndex = options.indexOfFirst { it.first == currentSort }.coerceAtLeast(0)
        com.example.easy_billing.ui.ThemedDropdown.show(
            anchor, options.map { it.second }, selectedIndex,
            rightAlign = true, minWidthDp = 230
        ) { idx ->
            currentSort = options[idx].first
            updateSortLabel()
            applyFilter()
        }
    }

    private fun showAddEditProductChooser() {
        val view = layoutInflater.inflate(R.layout.dialog_add_product_chooser, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnNonPurchasedProduct).setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, AddProductActivity::class.java))
        }
        view.findViewById<View>(R.id.btnManageProducts).setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, ManageProductsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnChooserCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun sortLabelText(): String = when (currentSort) {
        InvSort.A_TO_Z              -> getString(R.string.inventory_sort_short_name_asc)
        InvSort.Z_TO_A              -> getString(R.string.inventory_sort_short_name_desc)
        InvSort.PRICE_LOW_HIGH      -> getString(R.string.inventory_sort_short_price_asc)
        InvSort.PRICE_HIGH_LOW      -> getString(R.string.inventory_sort_short_price_desc)
        InvSort.STOCK_LOW_HIGH      -> getString(R.string.inventory_sort_short_stock_asc)
        InvSort.STOCK_HIGH_LOW      -> getString(R.string.inventory_sort_short_stock_desc)
        InvSort.STOCK_VALUE_HIGH_LOW -> getString(R.string.inventory_sort_short_value_desc)
    }

    private fun updateSortLabel() {
        // The old standalone "Sort: X" label is gone — the sort choice now
        // rides inside the result-summary pill via updateResultSummary(),
        // called from applyFilter(). This just keeps the reset-button
        // logic in sync when sort changes independently of a filter.
        updateResultSummary(adapter.itemCount)
    }

    /** "$N products · $sortLabel", with Reset showing whenever category,
     * stock filter, or sort is non-default — mirrors BillHistoryActivity's
     * updateFilterSortIndicators. */
    private fun updateResultSummary(resultCount: Int) {
        val sortLabel = sortLabelText()
        tvResultSummary.text = "$resultCount product${if (resultCount == 1) "" else "s"} · $sortLabel"
        val hasNonDefault = currentStockFilter != StockFilter.ALL ||
            currentCategory.isNotEmpty() ||
            currentSort != InvSort.STOCK_HIGH_LOW
        btnResetFilters.visibility = if (hasNonDefault) View.VISIBLE else View.GONE
        tvFilterBadge.visibility = if (currentStockFilter != StockFilter.ALL) View.VISIBLE else View.GONE
    }

    private fun sortList(list: List<InventoryItemUI>): List<InventoryItemUI> = when (currentSort) {
        InvSort.A_TO_Z              -> list.sortedBy { it.productName.lowercase() }
        InvSort.Z_TO_A              -> list.sortedByDescending { it.productName.lowercase() }
        InvSort.PRICE_LOW_HIGH      -> list.sortedBy { it.avgCost }
        InvSort.PRICE_HIGH_LOW      -> list.sortedByDescending { it.avgCost }
        InvSort.STOCK_LOW_HIGH      -> list.sortedBy { it.stock }
        InvSort.STOCK_HIGH_LOW      -> list.sortedByDescending { it.stock }
        InvSort.STOCK_VALUE_HIGH_LOW -> list.sortedByDescending { it.stock * it.avgCost }
    }

    // ================= KPI HEADER =================

    private fun updateKpis() {
        val totalValue = fullList.sumOf { it.stock * it.avgCost }
        val lowCount = fullList.count { it.stock in 0.0001..5.0 }
        val outCount = fullList.count { it.stock <= 0.0 }

        findViewById<TextView?>(R.id.tvKpiValue)?.text = "${CurrencyHelper.getCurrencySymbol(this)}${formatIndianShort(totalValue)}"
        findViewById<TextView?>(R.id.tvKpiLow)?.text = lowCount.toString()
        findViewById<TextView?>(R.id.tvKpiOut)?.text = outCount.toString()
        findViewById<TextView?>(R.id.tvHeroSub)?.text = "${fullList.size} active SKUs"

        // Caption mirrors CreditAccountsActivity's tvNetCaption pattern —
        // its text/colour switches with what's actually going on instead
        // of always reading the same generic subtitle.
        val tvCaption = findViewById<TextView?>(R.id.tvKpiCaption)
        when {
            outCount > 0 -> {
                tvCaption?.text = "$outCount out of stock"
                tvCaption?.setTextColor(android.graphics.Color.parseColor("#B23A3A"))
            }
            lowCount > 0 -> {
                tvCaption?.text = "$lowCount running low"
                tvCaption?.setTextColor(android.graphics.Color.parseColor("#8A6526"))
            }
            else -> {
                tvCaption?.text = getString(R.string.inventory_kpi_all_in_stock)
                tvCaption?.setTextColor(android.graphics.Color.parseColor("#8A8272"))
            }
        }
    }

    private fun formatIndianShort(value: Double): String = when {
        value >= 1_00_00_000 -> "%.2fCr".format(value / 1_00_00_000)
        value >= 1_00_000    -> "%.2fL".format(value / 1_00_000)
        value >= 1_000       -> "%,.0f".format(value)
        else                 -> "%.0f".format(value)
    }

    override fun onResume() {
        super.onResume()
        loadInventory()
        com.example.easy_billing.sync.SyncCoordinator.get(this).requestSync()
    }

    // ================= LOAD INVENTORY =================

    private fun loadInventory() {

        // Claim this load's generation number before anything else so a
        // slower, superseded call can recognise it's stale once it finally
        // finishes (see the field doc comment for why this matters).
        val myGeneration = loadInventoryGeneration.incrementAndGet()

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val inventoryList = db.inventoryDao().getAll()
                val products = com.example.easy_billing.repository.ProductRepository.get(this@InventoryActivity).getAllForCurrentShop()

                val inventoryMap = inventoryList.associateBy { it.productId }
                val newProductMap = products.associateBy { it.id }

                // INV-2 fix (original): stock value used to be computed as an
                // entirely separate weighted average pulled straight from the
                // purchase_batches ledger (PurchaseBatchDao.getGrossValuationByProduct),
                // independently of inventory.averageCost — so this screen and
                // Dashboard (which reads inventory.averageCost directly)
                // could show two different numbers for the same product even
                // when nothing was wrong. That was first fixed by deriving
                // this screen's figure from inventory.averageCost too, but
                // still displaying it GROSSED UP by the product's GST rate —
                // which is technically correct (net vs. gross are genuinely
                // different, valid numbers) but meant this screen and
                // Dashboard still showed two different-looking figures for
                // the same product side by side, with nothing on either
                // screen explaining why. Avg-cost audit, Fix 4: this screen
                // now shows the exact same NET figure as Dashboard, COGS,
                // profit, and returns — inventory.averageCost, unmodified.
                // There is now exactly one average-cost number in the whole
                // app, and every screen shows the same one. If a GST-inclusive
                // figure is ever needed again (e.g. "what would it cost to
                // fully restock at MRP-equivalent"), it should be added as a
                // clearly-separate, explicitly-labelled figure (e.g. a second
                // line reading "incl. GST: ₹X") rather than silently
                // replacing the primary cost figure the way it did before.
                val displayList = products
                    .filter { inventoryMap[it.id]?.isActive == true }
                    .map { product ->

                        val inv = inventoryMap[product.id]

                        InventoryItemUI(
                            productName = product.name,
                            variant = product.variant ?: "",
                            stock = inv?.currentStock ?: 0.0,
                            avgCost = inv?.averageCost ?: 0.0,
                            productId = product.id,
                            category = product.category,
                            hsnCode = product.hsnCode,
                            unit = product.unit
                        )
                    }

                withContext(Dispatchers.Main) {
                    // A newer loadInventory() call has started since this one
                    // began — discard these now-stale results instead of
                    // overwriting the screen with older numbers.
                    if (myGeneration == loadInventoryGeneration.get()) {
                        productMap = newProductMap
                        fullList = displayList
                        buildCategoryChips()
                        applyFilter()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Stale data staying on screen with no signal it's stale is
                    // risky for stock decisions — say plainly it didn't refresh
                    // and offer a retry rather than a flat "failed" toast.
                    Toast.makeText(
                        this@InventoryActivity,
                        "Couldn't refresh inventory (${e.message ?: "unknown error"}) — showing last-known data. Pull down or reopen to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= SEARCH =================

    private fun setupSearch() {

        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)

        etSearch.addTextChangedListener { text ->
            currentQuery = text.toString().trim().lowercase()
            applyFilter()
        }
    }

    private fun applyFilter() {

        val filtered = fullList.filter { item ->

            val matchesCategory =
                currentCategory.isEmpty() || item.category.equals(currentCategory, ignoreCase = true)

            val matchesQuery = currentQuery.isEmpty() || run {
                val name = item.productName.lowercase()
                val variant = item.variant?.lowercase() ?: ""
                name.contains(currentQuery) || variant.contains(currentQuery)
            }

            val matchesStock = when (currentStockFilter) {
                StockFilter.ALL -> true
                StockFilter.LOW -> item.stock in 0.0001..5.0
                StockFilter.OUT -> item.stock <= 0.0
            }

            matchesCategory && matchesQuery && matchesStock
        }

        val sorted = sortList(filtered)
        adapter.updateData(sorted)
        updateKpis()
        updateResultSummary(sorted.size)

        layoutInventoryEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvInventory.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        // A search, category chip, or stock-status filter is what emptied
        // the list, not that nothing was ever stocked — same card, different
        // copy for the two situations.
        val isSearchOrFilter = currentQuery.isNotEmpty() ||
            currentCategory.isNotEmpty() || currentStockFilter != StockFilter.ALL
        if (isSearchOrFilter) {
            tvInventoryEmptyTitle.text = getString(R.string.credit_no_matches_title)
            tvInventoryEmptyTitleAccent.text = getString(R.string.credit_no_matches_accent)
            tvInventoryEmptyBody.text = getString(R.string.inventory_no_matches_body)
        } else {
            tvInventoryEmptyTitle.text = getString(R.string.inventory_no_products_title)
            tvInventoryEmptyTitleAccent.text = getString(R.string.credit_no_customers_accent)
            tvInventoryEmptyBody.text = getString(R.string.inventory_no_products_body)
        }
    }

    // ================= CATEGORY CHIPS =================

    // Deterministic per-category accent — same hash-and-pick approach as
    // BillHistoryAdapter/SalesReturnItemAdapter's row palette, so each
    // category gets its own stable colour (not everyone sharing the same
    // gold) but a given category always lands on the same colour across
    // rebinds instead of re-randomising every time the chip row rebuilds.
    private val categoryChipPalette = listOf(
        "#0F6E56", "#B23A3A", "#8A6526", "#185FA5",
        "#534AB7", "#D85A30", "#3B6D11", "#993556"
    )

    private fun applyCategoryChipColor(chip: com.google.android.material.chip.Chip, category: String) {
        val hex = categoryChipPalette[(category.hashCode() and 0x7FFFFFFF) % categoryChipPalette.size]
        val accent = Color.parseColor(hex)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        chip.chipStrokeColor = android.content.res.ColorStateList(
            states, intArrayOf(accent, Color.parseColor("#E4DCC8"))
        )
        chip.setTextColor(
            android.content.res.ColorStateList(
                states, intArrayOf(accent, Color.parseColor("#6E6A60"))
            )
        )
    }

    private fun buildCategoryChips() {

        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.layoutChips) ?: return
        val allChip = findViewById<com.google.android.material.chip.Chip>(R.id.chipCatAll) ?: return

        // Distinct, non-blank categories from the current inventory, sorted.
        val categories = fullList
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }

        // Remove previously added category chips (keep the static "All" chip).
        for (i in group.childCount - 1 downTo 0) {
            if (group.getChildAt(i).id != R.id.chipCatAll) group.removeViewAt(i)
        }

        for (cat in categories) {
            val chip = layoutInflater.inflate(R.layout.item_inv_category_chip, group, false)
                    as com.google.android.material.chip.Chip
            chip.id = View.generateViewId()
            chip.text = cat
            chip.tag = cat
            applyCategoryChipColor(chip, cat)
            group.addView(chip)
        }

        // If the previously selected category no longer exists, fall back to All.
        if (currentCategory.isNotEmpty() && categories.none { it.equals(currentCategory, true) }) {
            currentCategory = ""
            allChip.isChecked = true
        }

        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            currentCategory = when (checkedId) {
                null, R.id.chipCatAll -> ""
                else -> (group.findViewById<com.google.android.material.chip.Chip>(checkedId)?.tag as? String) ?: ""
            }
            applyFilter()
        }
    }

    // ================= ADD STOCK =================

    private fun showAddStockDialog(product: Product) {

        // Context-aware routing:
        //   • Purchased products → straight to PurchaseActivity (single-
        //     product mode); invoice number/supplier/GSTIN/state/date are
        //     entered right there on the record-a-purchase page, so the
        //     separate header dialog that used to run first is gone —
        //     it was a redundant extra popup before the same fields.
        //   • Manual products    → EditProductActivity (full product control).
        if (product.isPurchased) {
            val intent = android.content.Intent(this, PurchaseActivity::class.java).apply {
                putExtra("EXTRA_PRODUCT_ID", product.id)
                putExtra("EXTRA_PRODUCT_NAME", product.name)
                putExtra("EXTRA_PRODUCT_VARIANT", product.variant)
                putExtra("EXTRA_PRODUCT_UNIT", product.unit)
                putExtra("EXTRA_SINGLE_MODE", true)
            }
            startActivity(intent)
            return
        } else {
            startActivity(
                android.content.Intent(this, EditProductActivity::class.java)
                    .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
            )
            return
        }
    }

    // ================= REDUCE STOCK =================

    /**
     * Single-screen reduce-stock flow. Reason toggle swaps the input
     * region inline:
     *   • Return → batch list with per-batch qty
     *   • Scrap  → plain qty input
     *
     * No second dialog. Total quantity for Return is summed from the
     * adapter; for Scrap it comes from the qty field. Credit-adjust
     * checkbox is wired live and only meaningful for Return.
     */
    private fun showReduceStockDialog(product: Product, currentStock: Double) {

        // Full-screen champagne page (same shell pattern as
        // PurchaseLineDialog) — replaces the old dark-gradient
        // MaterialCardView dialog. A plain themed Dialog rather than an
        // AlertDialog so the window is full-screen from creation.
        val view = layoutInflater.inflate(R.layout.dialog_reduce_stock_fullscreen, null)
        val dialog = android.app.Dialog(this, R.style.PurchaseLineFullScreen)
        dialog.setContentView(view)
        // Let the keyboard push the content up so the per-batch field
        // never sits behind the IME.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { dialog.dismiss() }
        }

        view.findViewById<TextView>(R.id.tvProductAvatar).text = monogramFor(product.name)
        view.findViewById<TextView>(R.id.tvProductName).text = product.name

        // Variant / category / HSN — one dot-separated muted line, with
        // the category picked out in teal since it's the one people scan
        // for first. Hidden entirely if the product has none of these.
        view.findViewById<TextView>(R.id.tvProductTags).apply {
            val variant = product.variant?.trim().orEmpty()
            val category = product.category.trim()
            val hsn = product.hsnCode?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { "HSN $it" }.orEmpty()

            val parts = listOf(variant, category, hsn).filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                visibility = View.GONE
            } else {
                val separator = "   ·   "
                val full = parts.joinToString(separator)
                val spannable = android.text.SpannableString(full)
                if (category.isNotEmpty()) {
                    val start = full.indexOf(category)
                    if (start >= 0) {
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(Color.parseColor("#0F6E56")),
                            start, start + category.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            start, start + category.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                text = spannable
                visibility = View.VISIBLE
            }
        }

        val tvStockValue = view.findViewById<TextView>(R.id.tvStockValue)
        // Stock value = current qty × average cost — averageCost isn't
        // part of the Product row itself, so it's fetched from the
        // inventory table the same way PurchaseActivity does for its
        // single-mode line.
        lifecycleScope.launch(Dispatchers.IO) {
            val avgCost = AppDatabase.getDatabase(this@InventoryActivity)
                .inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
            withContext(Dispatchers.Main) {
                val value = avgCost * currentStock
                tvStockValue.text = "${CurrencyHelper.getCurrencySymbol(this@InventoryActivity)}${"%.0f".format(value)}"
            }
        }

        val tvCurrent     = view.findViewById<TextView>(R.id.tvCurrentStockLabel)
        val rgReason      = view.findViewById<RadioGroup>(R.id.rgReason)
        val rbReturn      = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap       = view.findViewById<RadioButton>(R.id.rbScrap)

        // Return section
        val returnSection = view.findViewById<LinearLayout>(R.id.returnSection)
        val rvBatches     = view.findViewById<RecyclerView>(R.id.rvBatches)
        val tvBatchesEmpty = view.findViewById<TextView>(R.id.tvBatchesEmpty)
        val tvBatchRunning = view.findViewById<TextView>(R.id.tvBatchRunning)
        val tvBatchCountSub = view.findViewById<TextView>(R.id.tvBatchCountSub)

        // Credit — same card + icon row + MaterialSwitch structure as
        // "Imported goods" in activity_purchase.xml.
        val layoutCredit = view.findViewById<LinearLayout>(R.id.layoutCreditReturn)
        val cbAdjust = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.cbAdjustCredit)
        val tvAccount    = view.findViewById<TextView>(R.id.tvReturnAccountName)
        val rowReturnAccount = view.findViewById<View>(R.id.rowReturnAccount)
        val dividerReturnAccount = view.findViewById<View>(R.id.dividerReturnAccount)

        val btnConfirm   = view.findViewById<Button>(R.id.btnReduceConfirm)
        val btnCancel     = view.findViewById<Button>(R.id.btnReduceCancel)
        btnCancel.setOnClickListener { dialog.dismiss() }

        tvCurrent.text = "${formatStock(currentStock)} ${product.unit ?: "piece"}"
        val allowDecimal = isDecimalAllowed(product.unit)

        var selectedAccountForReturn: com.example.easy_billing.db.CreditAccount? = null
        var batchAdapter: BatchPickerAdapter? = null

        // Reason swap: both reasons use the same batch-picker section
        // (scrap and return share the batch flow — see scrapByBatches /
        // returnToSupplierByBatches); only the label and credit panel differ.
        fun applyReason() {
            val isReturn = rbReturn.isChecked
            returnSection.visibility = View.VISIBLE
            layoutCredit.visibility = if (isReturn) View.VISIBLE else View.GONE

            val tvTotalLabel = view.findViewById<TextView>(R.id.tvTotalLabel)
            tvTotalLabel?.text = if (isReturn) getString(R.string.inventory_total_to_return) else getString(R.string.inventory_total_to_scrap)
        }
        rgReason.setOnCheckedChangeListener { _, _ -> applyReason() }
        applyReason()

        // "Reason" is now a single tappable pill (Option 19) instead of
        // a visible radio list — rgReason/rbReturn/rbScrap still hold
        // the actual selection state (hidden 0x0 in the layout) so
        // applyReason() and every isChecked check below keep working
        // unchanged; this popup is just the UI that drives them.
        val tvReasonChoice = view.findViewById<TextView>(R.id.tvReasonChoice)
        view.findViewById<View>(R.id.btnReasonPicker).setOnClickListener { anchor ->
            val options = listOf(getString(R.string.inventory_reason_return), getString(R.string.inventory_reason_scrap))
            val selectedIndex = if (rbReturn.isChecked) 0 else 1
            com.example.easy_billing.ui.ThemedDropdown.show(
                anchor, options, selectedIndex,
                rightAlign = true, minWidthDp = 200
            ) { idx ->
                tvReasonChoice.text = options[idx]
                rgReason.check(if (idx == 0) R.id.rbReturn else R.id.rbScrap)
            }
        }

        // Credit-adjust picker — account subtitle pill appears only
        // once the switch is turned on.
        fun renderAccountSubtitle() {
            val account = selectedAccountForReturn
            val show = cbAdjust.isChecked
            rowReturnAccount.visibility = if (show) View.VISIBLE else View.GONE
            dividerReturnAccount.visibility = if (show) View.VISIBLE else View.GONE
            if (account != null) {
                tvAccount.text = account.name
                tvAccount.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            } else {
                tvAccount.text = getString(R.string.inventory_no_account_selected)
                tvAccount.setTextColor(android.graphics.Color.parseColor("#9A8F79"))
            }
        }
        cbAdjust.setOnCheckedChangeListener { _, isChecked ->
            renderAccountSubtitle()
            if (isChecked && selectedAccountForReturn == null) {
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account ->
                        selectedAccountForReturn = account
                        renderAccountSubtitle()
                    },
                    onDismissedWithoutSelection = {
                        // No account chosen — don't leave the switch on.
                        if (selectedAccountForReturn == null) {
                            cbAdjust.isChecked = false
                            renderAccountSubtitle()
                            Toast.makeText(
                                this,
                                R.string.inventory_credit_needs_account,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
        rowReturnAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(
                activity = this,
                onAccountSelected = { account ->
                    selectedAccountForReturn = account
                    if (!cbAdjust.isChecked) cbAdjust.isChecked = true
                    renderAccountSubtitle()
                }
            )
        }
        renderAccountSubtitle()

        // Load batches in the background and bind the adapter once
        // they arrive. The dialog is already on screen so the user
        // sees the empty state ("No purchase batches available") for
        // a brief moment if the read is slow — fine for the rare DB
        // hit, and prevents holding up the UI thread.
        rvBatches.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = InventoryReductionRepository.get(this@InventoryActivity)
            val batches = repo.getRemainingBatchesForProduct(product.id)
            withContext(Dispatchers.Main) {
                if (batches.isEmpty()) {
                    tvBatchesEmpty.visibility = View.VISIBLE
                    rvBatches.visibility = View.GONE
                } else {
                    val adapter = BatchPickerAdapter(batches)
                    adapter.onSelectionChanged = { running ->
                        tvBatchRunning.text = formatStock(running)
                        val n = adapter.selectedBatchCount()
                        tvBatchCountSub.text = if (n == 0) "No batches selected"
                            else "$n ${if (n == 1) "batch" else "batches"} selected"
                    }
                    rvBatches.adapter = adapter
                    batchAdapter = adapter
                }
            }
        }

        btnConfirm.setOnClickListener {
            val isReturn = rbReturn.isChecked
            val isCredit = cbAdjust.isChecked
            val creditAccountId = selectedAccountForReturn?.id

            if (isReturn) {
                // Return — use the batch adapter's selection
                val adapter = batchAdapter
                if (adapter == null) {
                    Toast.makeText(this, R.string.inventory_batches_not_loaded, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val lines = adapter.selectedLines()
                if (lines.isEmpty()) {
                    Toast.makeText(this, R.string.inventory_enter_batch_quantity, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val total = adapter.totalSelected()
                if (total <= 0.0) {
                    Toast.makeText(this, R.string.inventory_return_qty_must_positive, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (total > currentStock + 0.0001) {
                    Toast.makeText(this, R.string.inventory_return_exceeds_stock, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                AlertDialog.Builder(this)
                    .setTitle(R.string.inventory_confirm_return_title)
                    .setMessage("Return ${formatStock(total)} ${product.unit ?: "unit(s)"} of ${product.name}? This removes it from stock and can't be undone from here.")
                    .setPositiveButton(getString(R.string.inventory_confirm_return_batches_button)) { d, _ ->
                        d.dismiss()
                        dialog.dismiss()
                        runReturnByBatches(
                            product = product,
                            lines = lines,
                            isCredit = isCredit,
                            creditAccountId = creditAccountId
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }

            if (!isReturn) {
                // Scrap path — use the batch adapter's selection
                val adapter = batchAdapter
                if (adapter == null) {
                    Toast.makeText(this, R.string.inventory_batches_not_loaded, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedLines = adapter.selectedLines()
                if (selectedLines.isEmpty()) {
                    Toast.makeText(this, R.string.inventory_enter_batch_quantity, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val total = adapter.totalSelected()
                if (total <= 0.0) {
                    Toast.makeText(this, R.string.inventory_scrap_qty_must_positive, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (total > currentStock + 0.0001) {
                    Toast.makeText(this, R.string.inventory_scrap_exceeds_stock, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Map to BatchScrapLine
                val scrapLines = selectedLines.map { line ->
                    InventoryReductionRepository.BatchScrapLine(
                        batchId = line.batchId,
                        quantity = line.quantity
                    )
                }

                AlertDialog.Builder(this)
                    .setTitle(R.string.inventory_confirm_scrap_title)
                    .setMessage("Scrap ${formatStock(total)} ${product.unit ?: "unit(s)"} of ${product.name}? This is irreversible and removes it from stock permanently.")
                    .setPositiveButton(getString(R.string.inventory_confirm_scrap_batches_button)) { d, _ ->
                        d.dismiss()
                        dialog.dismiss()
                        runScrapByBatches(
                            product = product,
                            lines = scrapLines
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }
        }

        dialog.show()
    }

    /** Async tail of the Return-to-Supplier flow. */
    private fun runReturnByBatches(
        product: Product,
        lines: List<InventoryReductionRepository.BatchReturnLine>,
        isCredit: Boolean,
        creditAccountId: Int?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = InventoryReductionRepository.get(this@InventoryActivity)
            try {
                val result = repo.returnToSupplierByBatches(
                    productId       = product.id,
                    productName     = product.name,
                    variantName     = product.variant,
                    hsnCode         = product.hsnCode,
                    lines           = lines,
                    supplierGstin   = null,
                    supplierName    = null,
                    isCredit        = isCredit,
                    creditAccountId = creditAccountId
                )
                withContext(Dispatchers.Main) {
                    loadInventory()
                    val msg = if (result != null) {
                        "Returned ${formatStock(result.totalQuantity)} units to supplier"
                    } else {
                        getString(R.string.inventory_return_failed_toast)
                    }
                    Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                }
                if (result != null) SyncManager(this@InventoryActivity).syncInventory()

                // Adjust the supplier's balance for a credit return — clamped,
                // asking cash-vs-advance only on an overshoot. Skips itself for
                // a cash return. Runs on the main thread (shows a dialog).
                result?.creditAdjustment?.let { adj ->
                    withContext(Dispatchers.Main) {
                        CreditAdjustmentPrompt.handleAccountReturn(
                            activity = this@InventoryActivity,
                            accountId = adj.accountId,
                            amount = adj.amount,
                            documentLocalId = adj.documentId,
                            onDone = { }
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InventoryActivity,
                        e.message ?: getString(R.string.inventory_return_batches_failed_toast),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun runScrapByBatches(
        product: Product,
        lines: List<InventoryReductionRepository.BatchScrapLine>
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = InventoryReductionRepository.get(this@InventoryActivity)
            try {
                val result = repo.scrapByBatches(
                    productId       = product.id,
                    productName     = product.name,
                    variantName     = product.variant,
                    hsnCode         = product.hsnCode,
                    lines           = lines
                )
                withContext(Dispatchers.Main) {
                    loadInventory()
                    val msg = if (result != null) {
                        "Scrapped ${formatStock(result.totalQuantity)} units"
                    } else {
                        getString(R.string.inventory_scrap_failed_toast)
                    }
                    Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                }
                if (result != null) SyncManager(this@InventoryActivity).syncInventory()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InventoryActivity,
                        e.message ?: getString(R.string.inventory_scrap_batches_failed_toast),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= BATCH PICKER (Supplier Return) =================

    /**
     * Opens the batch-selection dialog for a supplier return.
     *
     * The user picks how many units of each remaining purchase batch
     * they're sending back. Confirm only enables when the per-batch
     * total equals [targetQty]; per-batch entries are already clamped
     * to the batch's remaining qty by [BatchPickerAdapter].
     *
     * On confirm, [InventoryReductionRepository.returnToSupplierByBatches]
     * does the heavy lifting — it values each batch at its own cost
     * (not the weighted average), debits those specific batches,
     * inserts the purchase_return row with GST split, and reduces the
     * inventory row through [InventoryManager.reduceStock] with
     * `skipBatchConsume = true`.
     */


    private fun formatStock(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    /** Two-letter monogram from a product name — same rule as
     *  InventoryAdapter.monogramFor, used for the reduce-stock page's
     *  avatar tile. */
    private fun monogramFor(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    // ================= CLEAR STOCK =================

    private fun showClearStockDialog(productId: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_clear_stock_fullscreen, null)
        val dialog = android.app.Dialog(this, R.style.PurchaseLineFullScreen)
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back_arrow)
            setNavigationOnClickListener { dialog.dismiss() }
        }

        val tvAvatar  = view.findViewById<TextView>(R.id.tvProductAvatar)
        val tvName    = view.findViewById<TextView>(R.id.tvProductName)
        val tvTags    = view.findViewById<TextView>(R.id.tvProductTags)
        val tvStock   = view.findViewById<TextView>(R.id.tvCurrentStockLabel)

        val rgReason  = view.findViewById<RadioGroup>(R.id.rgReason)
        val rbReturn  = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap   = view.findViewById<RadioButton>(R.id.rbScrap)
        val tvReasonChoice = view.findViewById<TextView>(R.id.tvReasonChoice)

        // Batches section
        val batchesSection = view.findViewById<LinearLayout>(R.id.batchesSection)
        val rvBatches      = view.findViewById<RecyclerView>(R.id.rvBatches)
        val tvBatchesEmpty = view.findViewById<TextView>(R.id.tvBatchesEmpty)
        val tvBatchRunning = view.findViewById<TextView>(R.id.tvBatchRunning)
        val tvBatchCountSub = view.findViewById<TextView>(R.id.tvBatchCountSub)

        // "Select all" — same premium teal-square checkbox as each
        // batch row (checkBoxSelect/ivCheckMark in item_batch_clear.xml),
        // instead of the native CheckBox that renders the system green.
        val rowSelectAll = view.findViewById<View>(R.id.rowSelectAll)
        val checkBoxSelectAll = view.findViewById<View>(R.id.checkBoxSelectAll)
        val ivCheckMarkSelectAll = view.findViewById<android.widget.ImageView>(R.id.ivCheckMarkSelectAll)
        var onSelectAllChanged: ((Any?, Boolean) -> Unit)? = null
        val cbSelectAll = object {
            var isChecked: Boolean = true
                set(value) {
                    field = value
                    checkBoxSelectAll.setBackgroundResource(
                        if (value) R.drawable.bg_check_box_selected else R.drawable.bg_check_box_unselected
                    )
                    ivCheckMarkSelectAll.visibility = if (value) View.VISIBLE else View.GONE
                }
            var isEnabled: Boolean
                get() = rowSelectAll.isEnabled
                set(value) { rowSelectAll.isEnabled = value; rowSelectAll.alpha = if (value) 1f else 0.4f }
            fun setOnCheckedChangeListener(listener: ((Any?, Boolean) -> Unit)?) {
                onSelectAllChanged = listener
            }
        }
        rowSelectAll.setOnClickListener {
            cbSelectAll.isChecked = !cbSelectAll.isChecked
            onSelectAllChanged?.invoke(null, cbSelectAll.isChecked)
        }
        cbSelectAll.isChecked = true

        // Credit — same card + icon row + MaterialSwitch structure as
        // "Imported goods" in activity_purchase.xml, ported over from
        // showReduceStockDialog.
        val layoutCredit = view.findViewById<LinearLayout>(R.id.layoutCreditReturn)
        val cbAdjust     = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.cbAdjustCredit)
        val tvAccount    = view.findViewById<TextView>(R.id.tvReturnAccountName)
        val rowReturnAccount = view.findViewById<View>(R.id.rowReturnAccount)
        val dividerReturnAccount = view.findViewById<View>(R.id.dividerReturnAccount)
        var selectedAccountForClear: com.example.easy_billing.db.CreditAccount? = null

        val btnClear  = view.findViewById<Button>(R.id.btnClear)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        btnCancel.setOnClickListener { dialog.dismiss() }

        var batchAdapter: com.example.easy_billing.adapter.BatchClearAdapter? = null
        var isPurchasedProduct = false
        var currentProduct: Product? = null

        fun applyReason() {
            layoutCredit.visibility = if (rbReturn.isChecked) View.VISIBLE else View.GONE
        }
        rgReason.setOnCheckedChangeListener { _, _ -> applyReason() }
        applyReason()

        view.findViewById<View>(R.id.btnReasonPicker).setOnClickListener { anchor ->
            val options = listOf(getString(R.string.purchase_return_label), getString(R.string.inventory_reason_scrap))
            val selectedIndex = if (rbReturn.isChecked) 0 else 1
            com.example.easy_billing.ui.ThemedDropdown.show(
                anchor, options, selectedIndex,
                rightAlign = true, minWidthDp = 200
            ) { idx ->
                tvReasonChoice.text = options[idx]
                rgReason.check(if (idx == 0) R.id.rbReturn else R.id.rbScrap)
            }
        }

        fun renderAccountSubtitle() {
            val account = selectedAccountForClear
            val show = cbAdjust.isChecked
            rowReturnAccount.visibility = if (show) View.VISIBLE else View.GONE
            dividerReturnAccount.visibility = if (show) View.VISIBLE else View.GONE
            if (account != null) {
                tvAccount.text = account.name
                tvAccount.setTextColor(android.graphics.Color.parseColor("#0F6E56"))
            } else {
                tvAccount.text = getString(R.string.inventory_no_account_selected)
                tvAccount.setTextColor(android.graphics.Color.parseColor("#9A8F79"))
            }
        }
        cbAdjust.setOnCheckedChangeListener { _, isChecked ->
            renderAccountSubtitle()
            if (isChecked && selectedAccountForClear == null) {
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account ->
                        selectedAccountForClear = account
                        renderAccountSubtitle()
                    },
                    onDismissedWithoutSelection = {
                        if (selectedAccountForClear == null) {
                            cbAdjust.isChecked = false
                            renderAccountSubtitle()
                            Toast.makeText(
                                this,
                                R.string.inventory_credit_needs_account,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
        rowReturnAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(
                activity = this,
                onAccountSelected = { account ->
                    selectedAccountForClear = account
                    if (!cbAdjust.isChecked) cbAdjust.isChecked = true
                    renderAccountSubtitle()
                }
            )
        }
        renderAccountSubtitle()

        lifecycleScope.launch(Dispatchers.IO) {
            val product = db.productDao().getById(productId)
            val current = db.inventoryDao().getInventory(productId)?.currentStock ?: 0.0

            withContext(Dispatchers.Main) {
                currentProduct = product
                tvStock.text = "${formatStock(current)} ${product?.unit ?: "piece"}"
                if (product != null) {
                    tvName.text = product.name
                    tvAvatar.text = monogramFor(product.name)
                    val tagParts = mutableListOf<String>()
                    product.variant?.takeIf { it.isNotBlank() }?.let { tagParts.add(it) }
                    product.category?.takeIf { it.isNotBlank() }?.let { tagParts.add(it) }
                    product.hsnCode?.takeIf { it.isNotBlank() }?.let { tagParts.add("HSN $it") }
                    if (tagParts.isNotEmpty()) {
                        tvTags.text = tagParts.joinToString("  ·  ")
                        tvTags.visibility = View.VISIBLE
                    }
                }

                if (product != null && product.isPurchased) {
                    isPurchasedProduct = true
                    batchesSection.visibility = View.VISIBLE

                    rvBatches.layoutManager = LinearLayoutManager(this@InventoryActivity)

                    lifecycleScope.launch(Dispatchers.IO) {
                        val repo = InventoryReductionRepository.get(this@InventoryActivity)
                        val batches = repo.getRemainingBatchesForProduct(productId)

                        withContext(Dispatchers.Main) {
                            if (batches.isEmpty()) {
                                tvBatchesEmpty.visibility = View.VISIBLE
                                rvBatches.visibility = View.GONE
                                cbSelectAll.isEnabled = false
                                tvBatchRunning.text = "0"
                                tvBatchCountSub.text = getString(R.string.inventory_no_batches_selected)
                            } else {
                                val adapter = com.example.easy_billing.adapter.BatchClearAdapter(batches)
                                fun renderRunning(running: Double) {
                                    tvBatchRunning.text = formatStock(running)
                                    val n = adapter.selectedCount()
                                    tvBatchCountSub.text = if (n == 0) "No batches selected"
                                        else "$n ${if (n == 1) "batch" else "batches"} selected"
                                }
                                adapter.onSelectionChanged = { running ->
                                    renderRunning(running)
                                    val selectedSize = adapter.selectedBatches().size
                                    cbSelectAll.setOnCheckedChangeListener(null)
                                    cbSelectAll.isChecked = selectedSize == batches.size
                                    cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                                        adapter.selectAll(isChecked)
                                    }
                                }
                                rvBatches.adapter = adapter
                                batchAdapter = adapter
                                renderRunning(adapter.totalSelected())

                                cbSelectAll.isChecked = true
                                cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                                    adapter.selectAll(isChecked)
                                }
                            }
                        }
                    }
                } else {
                    batchesSection.visibility = View.GONE
                }
            }
        }

        btnClear.setOnClickListener {
            val product = currentProduct
            if (product == null) {
                Toast.makeText(this, R.string.inventory_product_not_loaded, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isReturn = rbReturn.isChecked
            val isScrap  = rbScrap.isChecked
            if (!isReturn && !isScrap) {
                Toast.makeText(this, R.string.inventory_select_reason, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCredit = cbAdjust.isChecked
            val creditAccountId = selectedAccountForClear?.id

            if (isPurchasedProduct) {
                val adapter = batchAdapter
                if (adapter == null) {
                    Toast.makeText(this, R.string.inventory_batches_not_loaded, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val selectedBatches = adapter.selectedBatches()
                if (selectedBatches.isEmpty()) {
                    Toast.makeText(this, R.string.inventory_select_one_batch, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()

                if (isReturn) {
                    val lines = selectedBatches.map {
                        InventoryReductionRepository.BatchReturnLine(
                            batchId = it.id,
                            quantity = it.quantityRemaining
                        )
                    }
                    runReturnByBatches(
                        product = product,
                        lines = lines,
                        isCredit = isCredit,
                        creditAccountId = creditAccountId
                    )
                } else {
                    val lines = selectedBatches.map {
                        InventoryReductionRepository.BatchScrapLine(
                            batchId = it.id,
                            quantity = it.quantityRemaining
                        )
                    }
                    runScrapByBatches(
                        product = product,
                        lines = lines
                    )
                }
            } else {
                // Non-purchased (manual) product - clear all stock using the standard weighted average reduction
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val repo = InventoryReductionRepository.get(this@InventoryActivity)
                        val result = repo.clearRemainingStock(
                            productId   = productId,
                            productName = product.name,
                            variantName = product.variant,
                            hsnCode     = product.hsnCode,
                            reason      = if (isReturn) InventoryReductionRepository.ClearReason.PURCHASE_RETURN else InventoryReductionRepository.ClearReason.SCRAP,
                            purchaseTaxCgst = product.cgstPercentage,
                            purchaseTaxSgst = product.sgstPercentage,
                            purchaseTaxIgst = product.igstPercentage,
                            isCredit = isCredit,
                            creditAccountId = creditAccountId
                        )

                        withContext(Dispatchers.Main) {
                            loadInventory()
                            val msg = when (result) {
                                is InventoryReductionRepository.ClearStockResult.Cleared ->
                                    "Cleared ${formatStock(result.quantity)} units"
                                else -> getString(R.string.inventory_no_stock_to_clear)
                            }
                            Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                        SyncManager(this@InventoryActivity).syncInventory()

                        (result as? InventoryReductionRepository.ClearStockResult.Cleared)
                            ?.creditAdjustment?.let { adj ->
                                withContext(Dispatchers.Main) {
                                    CreditAdjustmentPrompt.handleAccountReturn(
                                        activity = this@InventoryActivity,
                                        accountId = adj.accountId,
                                        amount = adj.amount,
                                        documentLocalId = adj.documentId,
                                        onDone = { }
                                    )
                                }
                            }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@InventoryActivity,
                                "Couldn't clear stock: ${e.message ?: "unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun isDecimalAllowed(unit: String?): Boolean {
        return when (unit?.lowercase()) {
            "kilogram", "kg", "litre", "l" -> true
            else -> false
        }
    }
}