package com.example.easy_billing

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.viewmodel.ManageProductsViewModel
import com.example.easy_billing.viewmodel.ManageProductsViewModel.Filter
import com.example.easy_billing.viewmodel.ManageProductsViewModel.SortBy
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Browse, search, filter, and sort the local product catalogue.
 * Tap a row (or swipe right) to edit; swipe left to hide (deactivate).
 * The KPI tiles double as filter shortcuts.
 */
class ManageProductsActivity : BaseActivity() {

    private val viewModel: ManageProductsViewModel by viewModels()

    private lateinit var rv: RecyclerView
    private lateinit var adapter: ManageProductsAdapter
    private lateinit var etSearch: EditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvCount: TextView
    private lateinit var tvAllCount: TextView
    private lateinit var tvPurchasedCount: TextView
    private lateinit var tvManualCount: TextView

    private lateinit var tileAll: View
    private lateinit var tilePurchased: View
    private lateinit var tileManual: View
    private lateinit var btnSort: View

    private lateinit var emptyState: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView
    private lateinit var btnEmptyAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_products)

        setupToolbar(R.id.toolbar)

        bindViews()
        setupRecycler()
        wireControls()
        observe()
    }

    override fun onResume() {
        super.onResume()
        // Edit screen may have changed something — refresh the list.
        viewModel.reload()
    }

    private fun bindViews() {
        rv        = findViewById(R.id.rvProducts)
        etSearch  = findViewById(R.id.etSearch)
        chipGroup = findViewById(R.id.chipFilter)
        tvCount           = findViewById(R.id.tvCount)
        tvAllCount        = findViewById(R.id.tvAllCount)
        tvPurchasedCount  = findViewById(R.id.tvPurchasedCount)
        tvManualCount     = findViewById(R.id.tvManualCount)
        tileAll       = findViewById(R.id.tileAll)
        tilePurchased = findViewById(R.id.tilePurchased)
        tileManual    = findViewById(R.id.tileManual)
        btnSort       = findViewById(R.id.btnSort)
        emptyState     = findViewById(R.id.emptyState)
        tvEmptyTitle   = findViewById(R.id.tvEmptyTitle)
        tvEmptySub     = findViewById(R.id.tvEmptySub)
        btnEmptyAction = findViewById(R.id.btnEmptyAction)
    }

    private fun setupRecycler() {
        adapter = ManageProductsAdapter { _ ->
            // Do nothing on tap, rely on swipe to edit
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        attachSwipe()
    }

    private fun openEdit(productId: Int) {
        startActivity(
            Intent(this, EditProductActivity::class.java)
                .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, productId)
        )
    }

    private fun wireControls() {
        etSearch.addTextChangedListener { editable ->
            viewModel.setQuery(editable?.toString().orEmpty())
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            if (checkedId == null || checkedId == R.id.chipCatAll) {
                viewModel.setCategory("")
            } else {
                val chip = group.findViewById<com.google.android.material.chip.Chip>(checkedId)
                viewModel.setCategory(chip?.tag as? String ?: "")
            }
        }

        // KPI tiles act as status filters.
        tileAll.setOnClickListener { viewModel.setFilter(Filter.ALL) }
        tilePurchased.setOnClickListener { viewModel.setFilter(Filter.PURCHASED) }
        tileManual.setOnClickListener { viewModel.setFilter(Filter.MANUAL) }

        btnSort.setOnClickListener { showSortPopup() }
    }

    // Sort dropdown styled like the invoice "place of supply" sheet.
    private val sortLabels = listOf(
        "Name: A → Z", "Name: Z → A",
        "Price: Low → High", "Price: High → Low",
        "Stock: Low → High", "Stock: High → Low",
        "Stock Value: High → Low", "Category"
    )
    private val sortTypes = listOf(
        SortBy.NAME_ASC, SortBy.NAME_DESC,
        SortBy.PRICE_LOW, SortBy.PRICE_HIGH,
        SortBy.STOCK_LOW, SortBy.STOCK_HIGH,
        SortBy.STOCK_VALUE, SortBy.CATEGORY
    )

    private fun showSortPopup() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val green = Color.parseColor("#0F6E56")
        val ink = Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val current = sortTypes.indexOf(viewModel.sort.value).coerceAtLeast(0)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        val popup = android.widget.PopupWindow(
            scroll, dp(224),
            minOf(sortLabels.size * dp(44) + dp(10), dp(360)),
            true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        sortLabels.forEachIndexed { i, label ->
            val isSel = i == current
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) green else ink)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            if (isSel) {
                row.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(green)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                viewModel.setSort(sortTypes[i])
                popup.dismiss()
            }
            container.addView(row)
        }

        // Right-align the sheet to the sort button's right edge.
        popup.showAsDropDown(btnSort, btnSort.width - dp(224), dp(6))
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filtered.collect { list ->
                        adapter.submit(list)
                        renderEmptyState(list.isEmpty())
                    }
                }
                launch {
                    viewModel.all.collect { all ->
                        val purchased = all.count { it.isPurchased }
                        val categories = all
                            .mapNotNull { it.category.takeIf { c -> c.isNotBlank() } }
                            .distinct()
                            .sortedBy { it.lowercase() }

                        tvAllCount.text = all.size.toString()
                        tvPurchasedCount.text = purchased.toString()
                        tvManualCount.text = (all.size - purchased).toString()
                        tvCount.text =
                            if (categories.size == 1) "1 category" else "${categories.size} categories"

                        // Rebuild category chips
                        val currentCheckedTag = chipGroup.findViewById<com.google.android.material.chip.Chip>(chipGroup.checkedChipId)?.tag as? String
                        
                        // Keep only "All" chip
                        for (i in chipGroup.childCount - 1 downTo 0) {
                            if (chipGroup.getChildAt(i).id != R.id.chipCatAll) chipGroup.removeViewAt(i)
                        }

                        for (cat in categories) {
                            val chip = layoutInflater.inflate(R.layout.item_inv_category_chip, chipGroup, false)
                                    as com.google.android.material.chip.Chip
                            chip.id = android.view.View.generateViewId()
                            chip.text = cat
                            chip.tag = cat
                            chipGroup.addView(chip)
                            if (cat == currentCheckedTag) {
                                chipGroup.check(chip.id)
                            }
                        }
                    }
                }
                launch {
                    viewModel.filter.collect { highlightActiveTile(it) }
                }
                launch {
                    viewModel.stock.collect { adapter.setStock(it) }
                }
            }
        }
    }

    private fun highlightActiveTile(f: Filter) {
        tileAll.setBackgroundResource(
            if (f == Filter.ALL) R.drawable.bg_mp_stat_active else R.drawable.bg_mp_stat)
        tilePurchased.setBackgroundResource(
            if (f == Filter.PURCHASED) R.drawable.bg_mp_stat_active else R.drawable.bg_mp_stat)
        tileManual.setBackgroundResource(
            if (f == Filter.MANUAL) R.drawable.bg_mp_stat_active else R.drawable.bg_mp_stat)
    }

    private fun renderEmptyState(isEmpty: Boolean) {
        if (!isEmpty) {
            emptyState.visibility = View.GONE
            return
        }
        emptyState.visibility = View.VISIBLE
        if (viewModel.all.value.isEmpty()) {
            tvEmptyTitle.text = "No products yet"
            tvEmptySub.text = "Add your first product to get started."
            btnEmptyAction.text = "Add product"
            btnEmptyAction.setOnClickListener {
                startActivity(Intent(this, AddProductActivity::class.java))
            }
        } else {
            tvEmptyTitle.text = "No products match"
            tvEmptySub.text = "Try a different search or filter."
            btnEmptyAction.text = "Clear filters"
            btnEmptyAction.setOnClickListener {
                etSearch.setText("")
                chipGroup.check(R.id.chipCatAll)
                viewModel.setFilter(Filter.ALL)
            }
        }
    }

    // ── Swipe: left = edit ──
    private fun attachSwipe() {
        val editBg = ColorDrawable(Color.parseColor("#EFE8F6"))
        val editIcon = ContextCompat.getDrawable(this, R.drawable.ic_edit)?.mutate()
        editIcon?.setTint(Color.parseColor("#6C4EA0"))

        val cb = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val product = adapter.itemAt(pos)
                if (direction == ItemTouchHelper.LEFT) {
                    adapter.notifyItemChanged(pos)   // snap the row back
                    openEdit(product.id)
                }
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean
            ) {
                val item = vh.itemView
                val pad = ((item.height - (editIcon?.intrinsicHeight ?: 0)) / 2)
                
                if (dX < 0) {                 // swiping left → edit
                    editBg.setBounds(item.right + dX.roundToInt() - 24, item.top, item.right, item.bottom)
                    editBg.draw(c)
                    editIcon?.let {
                        val r = item.right - 40
                        it.setBounds(r - it.intrinsicWidth, item.top + pad, r, item.bottom - pad)
                        it.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
    }
}
