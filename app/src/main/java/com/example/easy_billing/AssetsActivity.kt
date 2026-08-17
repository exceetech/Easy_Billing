package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.ui.ThemedDropdown
import com.example.easy_billing.util.CurrencyHelper
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** Sort options offered from the Sort button — same shape as InventoryActivity's InvSort. */
private enum class AssetSort { NAME_A_Z, NAME_Z_A, VALUE_HIGH_LOW, VALUE_LOW_HIGH }

/**
 * Read-only list of products created purely for asset record-keeping —
 * purchase lines whose ITC eligibility is "Capital goods" / "Input
 * services", or that were tagged "Raw material" (Product.isSellable ==
 * false). These are intentionally excluded from Manage Products / the
 * POS billing catalog (see ProductDao.getAll / getAllForShop), so this
 * screen is the only place a shop can see them. No add/edit/delete —
 * this is a view-only ledger.
 *
 * Visually mirrors InventoryActivity: a hero "total value" card with
 * three category KPI tiles, search + filter chips, and a striped row
 * list — rather than ManageProductsActivity's card-grid layout.
 */
class AssetsActivity : BaseActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: AssetsAdapter
    private lateinit var emptyState: View
    private lateinit var tvCount: TextView
    private lateinit var tvTotalValue: TextView
    private lateinit var tvTotalValueCaption: TextView
    private lateinit var tvCapitalGoodsCount: TextView
    private lateinit var tvInputServicesCount: TextView
    private lateinit var tvRawMaterialCount: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipFilter: ChipGroup
    private lateinit var btnFilter: View
    private lateinit var btnSort: View
    private lateinit var tvFilterBadge: TextView

    private var allRows: List<AssetRow> = emptyList()
    private var activeKind: AssetKind? = null
    private var searchQuery: String = ""
    private var currentSort: AssetSort = AssetSort.NAME_A_Z

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assets)
        com.example.easy_billing.util.UserEventLogger.logAction("Assets", "opened")

        setupToolbar(R.id.toolbar)

        rv = findViewById(R.id.rvAssets)
        emptyState = findViewById(R.id.emptyState)
        tvCount = findViewById(R.id.tvCount)
        tvTotalValue = findViewById(R.id.tvTotalValue)
        tvTotalValueCaption = findViewById(R.id.tvTotalValueCaption)
        tvCapitalGoodsCount = findViewById(R.id.tvCapitalGoodsCount)
        tvInputServicesCount = findViewById(R.id.tvInputServicesCount)
        tvRawMaterialCount = findViewById(R.id.tvRawMaterialCount)
        etSearch = findViewById(R.id.etSearch)
        chipFilter = findViewById(R.id.chipFilter)
        btnFilter = findViewById(R.id.btnFilter)
        btnSort = findViewById(R.id.btnSort)
        tvFilterBadge = findViewById(R.id.tvFilterBadge)

        adapter = AssetsAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etSearch.addTextChangedListener {
            searchQuery = it?.toString().orEmpty()
            applyFilters()
        }

        chipFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            activeKind = when (checkedIds.firstOrNull()) {
                R.id.chipCapitalGoods -> AssetKind.CAPITAL_GOODS
                R.id.chipInputServices -> AssetKind.INPUT_SERVICES
                R.id.chipRawMaterial -> AssetKind.RAW_MATERIAL
                else -> null
            }
            applyFilters()
        }

        btnFilter.setOnClickListener { showFilterPopup() }
        btnSort.setOnClickListener { showSortPopup() }

        findViewById<View>(R.id.tileCapitalGoods).setOnClickListener {
            findViewById<com.google.android.material.chip.Chip>(R.id.chipCapitalGoods).isChecked = true
        }
        findViewById<View>(R.id.tileInputServices).setOnClickListener {
            findViewById<com.google.android.material.chip.Chip>(R.id.chipInputServices).isChecked = true
        }
        findViewById<View>(R.id.tileRawMaterial).setOnClickListener {
            findViewById<com.google.android.material.chip.Chip>(R.id.chipRawMaterial).isChecked = true
        }

        loadAssets()
    }

    override fun onResume() {
        super.onResume()
        // A purchase saved (or edited) elsewhere may have changed the
        // asset list — refresh every time the screen becomes visible.
        loadAssets()
    }

    private fun loadAssets() {
        lifecycleScope.launch {
            val products = ProductRepository.get(application).getAssetsForCurrentShop()
            val db = AppDatabase.getDatabase(application)
            val purchaseItemDao = db.purchaseItemDao()
            val purchaseDao = db.purchaseDao()

            val rows = products.map { product ->
                val latestItem = purchaseItemDao.getByProduct(product.id).firstOrNull()
                val kind = when {
                    product.isRawMaterial -> AssetKind.RAW_MATERIAL
                    latestItem?.eligibilityForItc == "Input services" -> AssetKind.INPUT_SERVICES
                    else -> AssetKind.CAPITAL_GOODS
                }
                val purchaseDate = latestItem?.purchaseId?.let { purchaseDao.getById(it)?.invoiceDate }
                // The line's total invoice value (quantity × cost) — what
                // was actually paid — not Product.price, which stores the
                // per-unit cost price. Falls back to Product.price only if
                // no purchase line was found (shouldn't normally happen,
                // since doSave() always writes a matching PurchaseItem).
                val invoiceValue = latestItem?.invoiceValue ?: product.price
                AssetRow(product, kind, purchaseDate, invoiceValue)
            }.sortedBy { it.product.name.lowercase() }

            allRows = rows

            val capitalGoodsCount = rows.count { it.kind == AssetKind.CAPITAL_GOODS }
            val inputServicesCount = rows.count { it.kind == AssetKind.INPUT_SERVICES }
            val rawMaterialCount = rows.count { it.kind == AssetKind.RAW_MATERIAL }

            tvCapitalGoodsCount.text = capitalGoodsCount.toString()
            tvInputServicesCount.text = inputServicesCount.toString()
            tvRawMaterialCount.text = rawMaterialCount.toString()

            applyFilters()
        }
    }

    private fun applyFilters() {
        var filtered = allRows
        activeKind?.let { kind -> filtered = filtered.filter { it.kind == kind } }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            filtered = filtered.filter {
                it.product.name.lowercase().contains(q) ||
                    it.product.variant?.lowercase()?.contains(q) == true
            }
        }
        filtered = when (currentSort) {
            AssetSort.NAME_A_Z -> filtered.sortedBy { it.product.name.lowercase() }
            AssetSort.NAME_Z_A -> filtered.sortedByDescending { it.product.name.lowercase() }
            AssetSort.VALUE_HIGH_LOW -> filtered.sortedByDescending { it.invoiceValue }
            AssetSort.VALUE_LOW_HIGH -> filtered.sortedBy { it.invoiceValue }
        }
        adapter.submitList(filtered)
        renderEmptyState(filtered.isEmpty())
        tvFilterBadge.visibility = if (activeKind != null) View.VISIBLE else View.GONE
        tvCount.text = if (allRows.size == 1) {
            "1 asset · not counted as sellable stock"
        } else {
            "${allRows.size} assets · not counted as sellable stock"
        }

        // The hero total tracks whichever category is active — so a shop
        // can see "just Capital goods" (or Input services, or Raw
        // material) value without the other buckets mixed in. Search
        // narrows this further, matching what's actually listed below.
        val rowsForTotal = activeKind?.let { kind -> allRows.filter { it.kind == kind } } ?: allRows
        val searchedForTotal = if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            rowsForTotal.filter {
                it.product.name.lowercase().contains(q) ||
                    it.product.variant?.lowercase()?.contains(q) == true
            }
        } else rowsForTotal

        tvTotalValue.text = money(searchedForTotal.sumOf { it.invoiceValue })
        tvTotalValueCaption.text = when (activeKind) {
            AssetKind.CAPITAL_GOODS -> getString(R.string.assets_total_value_caption_capital_goods)
            AssetKind.INPUT_SERVICES -> getString(R.string.assets_total_value_caption_input_services)
            AssetKind.RAW_MATERIAL -> getString(R.string.assets_total_value_caption_raw_material)
            null -> getString(R.string.assets_total_value_caption)
        }
    }

    /** Same ThemedDropdown look/behaviour as InventoryActivity's Filter button. */
    private fun showFilterPopup() {
        val options = listOf(
            null to getString(R.string.manage_filter_all),
            AssetKind.CAPITAL_GOODS to getString(R.string.assets_kpi_capital_goods),
            AssetKind.INPUT_SERVICES to getString(R.string.assets_kpi_input_services),
            AssetKind.RAW_MATERIAL to getString(R.string.assets_kpi_raw_material)
        )
        val selectedIndex = options.indexOfFirst { it.first == activeKind }
        ThemedDropdown.show(btnFilter, options.map { it.second }, selectedIndex, rightAlign = false, minWidthDp = 190) { idx ->
            // Drive the selection through the existing chip group so the
            // chip row and the dropdown never fall out of sync.
            val chipId = when (options[idx].first) {
                AssetKind.CAPITAL_GOODS -> R.id.chipCapitalGoods
                AssetKind.INPUT_SERVICES -> R.id.chipInputServices
                AssetKind.RAW_MATERIAL -> R.id.chipRawMaterial
                null -> R.id.chipAll
            }
            findViewById<com.google.android.material.chip.Chip>(chipId).isChecked = true
        }
    }

    /** Same ThemedDropdown look/behaviour as InventoryActivity's Sort button. */
    private fun showSortPopup() {
        val options = listOf(
            AssetSort.NAME_A_Z to getString(R.string.dashboard_sort_name_asc),
            AssetSort.NAME_Z_A to getString(R.string.dashboard_sort_name_desc),
            AssetSort.VALUE_HIGH_LOW to getString(R.string.assets_sort_value_desc),
            AssetSort.VALUE_LOW_HIGH to getString(R.string.assets_sort_value_asc)
        )
        val selectedIndex = options.indexOfFirst { it.first == currentSort }
        ThemedDropdown.show(btnSort, options.map { it.second }, selectedIndex, rightAlign = true, minWidthDp = 210) { idx ->
            currentSort = options[idx].first
            applyFilters()
        }
    }

    private fun renderEmptyState(isEmpty: Boolean) {
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun money(p: Double): String {
        val symbol = CurrencyHelper.getCurrencySymbol(this)
        return if (p % 1.0 == 0.0) "$symbol${p.toLong()}" else "$symbol${"%.2f".format(p)}"
    }
}
