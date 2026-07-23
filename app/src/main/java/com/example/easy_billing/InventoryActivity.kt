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
    private lateinit var adapter: InventoryAdapter
    private lateinit var db: AppDatabase

    private var fullList: List<InventoryItemUI> = emptyList()
    private var productMap: Map<Int, Product> = emptyMap()
    private var currentQuery = ""
    private var currentCategory = ""   // "" = All
    private var currentSort = InvSort.STOCK_HIGH_LOW

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
        rvInventory.layoutManager = LinearLayoutManager(this)

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

        setupSearch()
        setupHeaderActions()
    }

    // ================= HEADER ACTIONS =================

    private fun setupHeaderActions() {

        findViewById<View?>(R.id.btnAddProduct)?.apply {
            contentDescription = "Add product"
            setOnClickListener { showAddEditProductChooser() }
        }

        findViewById<View?>(R.id.btnEdit)?.apply {
            contentDescription = "Manage products"
            setOnClickListener {
                startActivity(android.content.Intent(this@InventoryActivity, ManageProductsActivity::class.java))
            }
        }

        findViewById<View?>(R.id.btnSort)?.apply {
            contentDescription = "Sort inventory"
            setOnClickListener { showSortMenu(it) }
        }

        updateSortLabel()
    }

    // ================= THEMED SORT DROPDOWN =================

    private fun showSortMenu(anchor: View) {
        val options = listOf(
            InvSort.A_TO_Z              to "Name: A → Z",
            InvSort.Z_TO_A              to "Name: Z → A",
            InvSort.PRICE_LOW_HIGH      to "Price: Low → High",
            InvSort.PRICE_HIGH_LOW      to "Price: High → Low",
            InvSort.STOCK_LOW_HIGH      to "Stock: Low → High",
            InvSort.STOCK_HIGH_LOW      to "Stock: High → Low",
            InvSort.STOCK_VALUE_HIGH_LOW to "Stock value: High → Low"
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

    private fun updateSortLabel() {
        val label = when (currentSort) {
            InvSort.A_TO_Z              -> "Name ↑"
            InvSort.Z_TO_A              -> "Name ↓"
            InvSort.PRICE_LOW_HIGH      -> "Price ↑"
            InvSort.PRICE_HIGH_LOW      -> "Price ↓"
            InvSort.STOCK_LOW_HIGH      -> "Stock ↑"
            InvSort.STOCK_HIGH_LOW      -> "Stock ↓"
            InvSort.STOCK_VALUE_HIGH_LOW -> "Value ↓"
        }
        findViewById<TextView?>(R.id.tvSortLabel)?.text = "Sort: $label"
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
        val totalUnits = fullList.sumOf { it.stock }
        val lowCount = fullList.count { it.stock in 0.0001..5.0 }
        val outCount = fullList.count { it.stock <= 0.0 }

        findViewById<TextView?>(R.id.tvKpiValue)?.text = "₹${formatIndianShort(totalValue)}"
        findViewById<TextView?>(R.id.tvKpiUnits)?.text =
            if (totalUnits % 1 == 0.0) "%,d".format(totalUnits.toLong())
            else "%,.1f".format(totalUnits)
        findViewById<TextView?>(R.id.tvKpiLow)?.text = lowCount.toString()
        findViewById<TextView?>(R.id.tvKpiOut)?.text = outCount.toString()
        findViewById<TextView?>(R.id.tvHeroSub)?.text = "${fullList.size} active SKUs"
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

                // INV-2 fix: stock value is shown at the GROSS purchase cost
                // (invoice value incl. GST), not the net taxable cost — but
                // this used to be computed as an entirely separate weighted
                // average pulled straight from the purchase_batches ledger
                // (getGrossValuationByProduct), independently of
                // inventory.averageCost. That meant this screen and
                // Dashboard (which reads inventory.averageCost directly)
                // could show two different numbers for the same product
                // even when nothing was wrong, and diverged further if the
                // batch ledger ever drifted from currentStock. There is now
                // exactly ONE canonical average cost — inventory.averageCost
                // (net) — and the gross figure shown here is simply that
                // number grossed up by the product's own GST rate, so both
                // screens always agree on the underlying value.
                // COGS / profit / returns still use inventory.averageCost (net).
                val displayList = products
                    .filter { inventoryMap[it.id]?.isActive == true }
                    .map { product ->

                        val inv = inventoryMap[product.id]
                        val netAvg = inv?.averageCost ?: 0.0
                        val gstRate = product.defaultGstRate.takeIf { it > 0.0 } ?: 0.0
                        val grossAvg = netAvg * (1.0 + gstRate / 100.0)

                        InventoryItemUI(
                            productName = product.name,
                            variant = product.variant ?: "",
                            stock = inv?.currentStock ?: 0.0,
                            avgCost = grossAvg,
                            productId = product.id,
                            category = product.category
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

            matchesCategory && matchesQuery
        }

        adapter.updateData(sortList(filtered))
        updateKpis()

        val tvEmpty = findViewById<View?>(R.id.tvInventoryEmpty)
        tvEmpty?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvInventory.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    // ================= CATEGORY CHIPS =================

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
        //   • Purchased products → Ask for header info, then go to PurchaseActivity.
        //   • Manual products    → EditProductActivity (full product control).
        if (product.isPurchased) {
            showPurchasedHeaderDialog(product)
            return
        } else {
            startActivity(
                android.content.Intent(this, EditProductActivity::class.java)
                    .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
            )
            return
        }
    }

    private fun showPurchasedHeaderDialog(product: Product) {
        val view = layoutInflater.inflate(R.layout.dialog_purchased_header, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etInvoice = view.findViewById<EditText>(R.id.etInvoiceNumber)
        val etSupplier = view.findViewById<EditText>(R.id.etSupplierName)
        val etGstin = view.findViewById<EditText>(R.id.etSupplierGstin)
        val etState = view.findViewById<AutoCompleteTextView>(R.id.etState)
        val etInvoiceDate = view.findViewById<EditText>(R.id.etInvoiceDate)
        // Reusable picker — opens calendar on tap, blocks keyboard,
        // formats dd/MM/yyyy, caps at today.
        val getInvoiceDate = InvoiceDatePicker.bind(etInvoiceDate)
        val btnContinue = view.findViewById<Button>(R.id.btnContinue)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        // Setup state suggestions
        val states = com.example.easy_billing.util.GstEngine.INDIA_STATES.values.toList()
        etState.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, states))
        etState.setOnClickListener { etState.showDropDown() }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnContinue.setOnClickListener {
            val inv = etInvoice.text.toString().trim()
            val sup = etSupplier.text.toString().trim()
            val gst = etGstin.text.toString().trim()
            val st = etState.text.toString().trim()

            if (inv.isEmpty() || sup.isEmpty() || st.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (gst.isNotEmpty() && !GstEngine.isValidGstin(gst)) {
                etGstin.error = "Enter a valid 15-character GSTIN"
                Toast.makeText(this, "Enter a valid 15-character GSTIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pickedInvoiceDate = getInvoiceDate()
            if (pickedInvoiceDate == null) {
                etInvoiceDate.error = "Pick the invoice date"
                Toast.makeText(this, "Invoice date is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }


            dialog.dismiss()

            val intent = android.content.Intent(this, PurchaseActivity::class.java).apply {
                putExtra("EXTRA_INVOICE_NUMBER", inv)
                putExtra("EXTRA_SUPPLIER_NAME", sup)
                putExtra("EXTRA_SUPPLIER_GSTIN", gst)
                putExtra("EXTRA_STATE", st)
                putExtra("EXTRA_INVOICE_DATE", pickedInvoiceDate)
                putExtra("EXTRA_PRODUCT_ID", product.id)
                putExtra("EXTRA_PRODUCT_NAME", product.name)
                putExtra("EXTRA_PRODUCT_VARIANT", product.variant)
                putExtra("EXTRA_PRODUCT_UNIT", product.unit)
                putExtra("EXTRA_SINGLE_MODE", true)
            }
            startActivity(intent)
        }

        dialog.show()
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

        val view = layoutInflater.inflate(R.layout.dialog_reduce_stock, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Let the keyboard push the dialog up so the per-batch field
        // never sits behind the IME.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val tvCurrent     = view.findViewById<TextView>(R.id.tvCurrentStockLabel)
        val rgReason      = view.findViewById<RadioGroup>(R.id.rgReason)
        val rbReturn      = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap       = view.findViewById<RadioButton>(R.id.rbScrap)

        // Return section
        val returnSection = view.findViewById<LinearLayout>(R.id.returnSection)
        val rvBatches     = view.findViewById<RecyclerView>(R.id.rvBatches)
        val tvBatchesEmpty = view.findViewById<TextView>(R.id.tvBatchesEmpty)
        val tvBatchRunning = view.findViewById<TextView>(R.id.tvBatchRunning)

        // Credit
        val layoutCredit = view.findViewById<LinearLayout>(R.id.layoutCreditReturn)
        val cbAdjust     = view.findViewById<CheckBox>(R.id.cbAdjustCredit)
        val tvAccount    = view.findViewById<TextView>(R.id.tvReturnAccountName)

        val btnCancel    = view.findViewById<Button>(R.id.btnReduceCancel)
        val btnConfirm   = view.findViewById<Button>(R.id.btnReduceConfirm)

        tvCurrent.text = "Available: ${formatStock(currentStock)} ${product.unit ?: "piece"}"
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
            tvTotalLabel?.text = if (isReturn) "Total to return" else "Total to scrap"
        }
        rgReason.setOnCheckedChangeListener { _, _ -> applyReason() }
        applyReason()

        // Credit-adjust picker is identical to the prior dialog.
        cbAdjust.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && selectedAccountForReturn == null) {
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account ->
                        selectedAccountForReturn = account
                        tvAccount.text = "Account: ${account.name}"
                        tvAccount.visibility = View.VISIBLE
                    },
                    onDismissedWithoutSelection = {
                        // No account chosen — don't leave the box ticked.
                        if (selectedAccountForReturn == null) {
                            cbAdjust.isChecked = false
                            Toast.makeText(
                                this,
                                "Credit needs an account — adjustment turned off",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            } else if (!isChecked) {
                tvAccount.visibility = View.GONE
            } else if (selectedAccountForReturn != null) {
                tvAccount.visibility = View.VISIBLE
            }
        }
        tvAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(
                activity = this,
                onAccountSelected = { account ->
                    selectedAccountForReturn = account
                    tvAccount.text = "Account: ${account.name}"
                }
            )
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

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
                    Toast.makeText(this, "Batches not loaded yet", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val lines = adapter.selectedLines()
                if (lines.isEmpty()) {
                    Toast.makeText(this, "Enter quantity for at least one batch", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val total = adapter.totalSelected()
                if (total <= 0.0) {
                    Toast.makeText(this, "Total return quantity must be > 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (total > currentStock + 0.0001) {
                    Toast.makeText(this, "Return total exceeds available stock", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                AlertDialog.Builder(this)
                    .setTitle("Confirm return to supplier")
                    .setMessage("Return ${formatStock(total)} ${product.unit ?: "unit(s)"} of ${product.name}? This removes it from stock and can't be undone from here.")
                    .setPositiveButton("Return") { d, _ ->
                        d.dismiss()
                        dialog.dismiss()
                        runReturnByBatches(
                            product = product,
                            lines = lines,
                            isCredit = isCredit,
                            creditAccountId = creditAccountId
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }

            if (!isReturn) {
                // Scrap path — use the batch adapter's selection
                val adapter = batchAdapter
                if (adapter == null) {
                    Toast.makeText(this, "Batches not loaded yet", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedLines = adapter.selectedLines()
                if (selectedLines.isEmpty()) {
                    Toast.makeText(this, "Enter quantity for at least one batch", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val total = adapter.totalSelected()
                if (total <= 0.0) {
                    Toast.makeText(this, "Total scrap quantity must be > 0", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (total > currentStock + 0.0001) {
                    Toast.makeText(this, "Scrap total exceeds available stock", Toast.LENGTH_SHORT).show()
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
                    .setTitle("Confirm scrap")
                    .setMessage("Scrap ${formatStock(total)} ${product.unit ?: "unit(s)"} of ${product.name}? This is irreversible and removes it from stock permanently.")
                    .setPositiveButton("Scrap") { d, _ ->
                        d.dismiss()
                        dialog.dismiss()
                        runScrapByBatches(
                            product = product,
                            lines = scrapLines
                        )
                    }
                    .setNegativeButton("Cancel", null)
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
                        "Could not return — check selected batches"
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
                        e.message ?: "Failed to return batches",
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
                        "Could not scrap — check selected batches"
                    }
                    Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                }
                if (result != null) SyncManager(this@InventoryActivity).syncInventory()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InventoryActivity,
                        e.message ?: "Failed to scrap batches",
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

    // ================= CLEAR STOCK =================

    private fun showClearStockDialog(productId: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_clear_stock, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnClear  = view.findViewById<Button>(R.id.btnClear)
        val rgReason  = view.findViewById<RadioGroup>(R.id.rgReason)
        val rbReturn  = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap   = view.findViewById<RadioButton>(R.id.rbScrap)
        val tvStock   = view.findViewById<TextView>(R.id.tvCurrentStock)

        // Batches section
        val batchesSection = view.findViewById<LinearLayout>(R.id.batchesSection)
        val cbSelectAll    = view.findViewById<CheckBox>(R.id.cbSelectAll)
        val rvBatches      = view.findViewById<RecyclerView>(R.id.rvBatches)
        val tvBatchesEmpty = view.findViewById<TextView>(R.id.tvBatchesEmpty)
        val tvBatchRunning = view.findViewById<TextView>(R.id.tvBatchRunning)

        // Credit Integration
        val layoutCredit = view.findViewById<LinearLayout>(R.id.layoutCreditReturn)
        val cbAdjust     = view.findViewById<CheckBox>(R.id.cbAdjustCredit)
        val tvAccount    = view.findViewById<TextView>(R.id.tvReturnAccountName)
        var selectedAccountForClear: com.example.easy_billing.db.CreditAccount? = null

        var batchAdapter: com.example.easy_billing.adapter.BatchClearAdapter? = null
        var isPurchasedProduct = false
        var currentProduct: Product? = null

        lifecycleScope.launch(Dispatchers.IO) {
            val product = db.productDao().getById(productId)
            val current = db.inventoryDao().getInventory(productId)?.currentStock ?: 0.0
            
            withContext(Dispatchers.Main) {
                currentProduct = product
                tvStock.text = "Remaining: ${formatStock(current)}"
                
                if (product != null && product.isPurchased) {
                    isPurchasedProduct = true
                    batchesSection.visibility = View.VISIBLE
                    
                    // Setup recycler view
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
                            } else {
                                val adapter = com.example.easy_billing.adapter.BatchClearAdapter(batches)
                                adapter.onSelectionChanged = { running ->
                                    tvBatchRunning.text = formatStock(running)
                                    // Sync Select All checkbox if all/none are checked
                                    val selectedSize = adapter.selectedBatches().size
                                    cbSelectAll.setOnCheckedChangeListener(null)
                                    cbSelectAll.isChecked = selectedSize == batches.size
                                    cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                                        adapter.selectAll(isChecked)
                                    }
                                }
                                rvBatches.adapter = adapter
                                batchAdapter = adapter
                                tvBatchRunning.text = formatStock(adapter.totalSelected())
                                
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

        rgReason.setOnCheckedChangeListener { _, checkedId ->
            layoutCredit.visibility = if (checkedId == R.id.rbReturn) View.VISIBLE else View.GONE
        }
        layoutCredit.visibility = if (rbReturn.isChecked) View.VISIBLE else View.GONE

        cbAdjust.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && selectedAccountForClear == null) {
                com.example.easy_billing.util.CreditAccountPicker.show(
                    activity = this,
                    onAccountSelected = { account ->
                        selectedAccountForClear = account
                        tvAccount.text = "Account: ${account.name}"
                        tvAccount.visibility = View.VISIBLE
                    },
                    onDismissedWithoutSelection = {
                        // No account chosen — don't leave the box ticked.
                        if (selectedAccountForClear == null) {
                            cbAdjust.isChecked = false
                            Toast.makeText(
                                this,
                                "Credit needs an account — adjustment turned off",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            } else if (!isChecked) {
                tvAccount.visibility = View.GONE
            } else if (selectedAccountForClear != null) {
                tvAccount.visibility = View.VISIBLE
            }
        }
        
        tvAccount.setOnClickListener {
            com.example.easy_billing.util.CreditAccountPicker.show(
                activity = this,
                onAccountSelected = { account ->
                    selectedAccountForClear = account
                    tvAccount.text = "Account: ${account.name}"
                }
            )
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {
            val product = currentProduct
            if (product == null) {
                Toast.makeText(this, "Product details not loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isReturn = rbReturn.isChecked
            val isScrap  = rbScrap.isChecked
            if (!isReturn && !isScrap) {
                Toast.makeText(this, "Select reason", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCredit = cbAdjust.isChecked
            val creditAccountId = selectedAccountForClear?.id

            if (isPurchasedProduct) {
                val adapter = batchAdapter
                if (adapter == null) {
                    Toast.makeText(this, "Batches not loaded yet", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val selectedBatches = adapter.selectedBatches()
                if (selectedBatches.isEmpty()) {
                    Toast.makeText(this, "Select at least one batch to clear", Toast.LENGTH_SHORT).show()
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
                                else -> "No stock to clear"
                            }
                            Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                        SyncManager(this@InventoryActivity).syncInventory()

                        // Supplier-balance adjustment for a credit return —
                        // clamped, asks cash-vs-advance only on an overshoot.
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