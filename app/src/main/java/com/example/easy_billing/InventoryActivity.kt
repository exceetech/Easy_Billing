package com.example.easy_billing

import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.InventoryItemUI
import com.example.easy_billing.db.LossEntry
import com.example.easy_billing.db.Product
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.GstEngine
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
        loadInventory()
    }

    // ================= LOAD INVENTORY =================

    private fun loadInventory() {

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val inventoryList = db.inventoryDao().getAll()
                val products = db.productDao().getAll()

                val inventoryMap = inventoryList.associateBy { it.productId }
                val newProductMap = products.associateBy { it.id }

                val displayList = products
                    .filter { inventoryMap[it.id]?.isActive == true }
                    .map { product ->

                        val inv = inventoryMap[product.id]

                        InventoryItemUI(
                            productName = product.name,
                            variant = product.variant ?: "",
                            stock = inv?.currentStock ?: 0.0,
                            avgCost = inv?.averageCost ?: 0.0,
                            productId = product.id
                        )
                    }

                withContext(Dispatchers.Main) {
                    productMap = newProductMap
                    fullList = displayList
                    applyFilter()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InventoryActivity,
                        "Failed to load inventory",
                        Toast.LENGTH_SHORT
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

        val filtered = if (currentQuery.isEmpty()) {
            fullList
        } else {
            fullList.filter {

                val name = it.productName.lowercase()
                val variant = it.variant!!.lowercase()

                name.contains(currentQuery) || variant.contains(currentQuery)
            }
        }

        adapter.updateData(filtered)
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

            dialog.dismiss()

            val intent = android.content.Intent(this, PurchaseActivity::class.java).apply {
                putExtra("EXTRA_INVOICE_NUMBER", inv)
                putExtra("EXTRA_SUPPLIER_NAME", sup)
                putExtra("EXTRA_SUPPLIER_GSTIN", gst)
                putExtra("EXTRA_STATE", st)
                putExtra("EXTRA_PRODUCT_ID", product.id)
                putExtra("EXTRA_PRODUCT_NAME", product.name)
                putExtra("EXTRA_PRODUCT_VARIANT", product.variant)
                putExtra("EXTRA_SINGLE_MODE", true)
            }
            startActivity(intent)
        }

        dialog.show()
    }

    // ================= REDUCE STOCK =================

    private fun showReduceStockDialog(product: Product, currentStock: Double) {

        val view = layoutInflater.inflate(R.layout.dialog_reduce_stock, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvCurrent     = view.findViewById<TextView>(R.id.tvCurrentStockLabel)
        val rgReason      = view.findViewById<RadioGroup>(R.id.rgReason)
        val rbReturn      = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap       = view.findViewById<RadioButton>(R.id.rbScrap)
        val etQty         = view.findViewById<EditText>(R.id.etReduceQty)
        val btnCancel     = view.findViewById<Button>(R.id.btnReduceCancel)
        val btnConfirm    = view.findViewById<Button>(R.id.btnReduceConfirm)

        tvCurrent.text = "Available: ${formatStock(currentStock)} ${product.unit ?: "piece"}"
        val allowDecimal = isDecimalAllowed(product.unit)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val qtyText = etQty.text?.toString()?.trim().orEmpty()
            if (qtyText.isEmpty()) { etQty.error = "Enter quantity"; return@setOnClickListener }
            if (!allowDecimal && qtyText.contains(".")) {
                etQty.error = "Decimal not allowed"; return@setOnClickListener
            }
            val qty = qtyText.toDoubleOrNull() ?: 0.0
            if (qty <= 0) { etQty.error = "Invalid quantity"; return@setOnClickListener }
            if (qty > currentStock) {
                etQty.error = "Exceeds available stock"; return@setOnClickListener
            }

            val reason = if (rbReturn.isChecked)
                com.example.easy_billing.repository.InventoryReductionRepository.ClearReason.PURCHASE_RETURN
            else
                com.example.easy_billing.repository.InventoryReductionRepository.ClearReason.SCRAP

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {
                val repo = com.example.easy_billing.repository.InventoryReductionRepository.get(this@InventoryActivity)
                try {
                    val success = repo.reduceStockByReason(
                        productId = product.id,
                        productName = product.name,
                        hsnCode = product.hsnCode,
                        quantity = qty,
                        reason = reason,
                        purchaseTaxCgst = product.cgstPercentage,
                        purchaseTaxSgst = product.sgstPercentage,
                        purchaseTaxIgst = product.igstPercentage
                    )

                    if (success) {
                        withContext(Dispatchers.Main) {
                            loadInventory()
                            Toast.makeText(this@InventoryActivity, "Stock reduced", Toast.LENGTH_SHORT).show()
                        }
                        SyncManager(this@InventoryActivity).syncInventory()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun formatStock(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    // ================= CLEAR STOCK =================

    private fun showClearStockDialog(productId: Int) {

        val view = layoutInflater.inflate(R.layout.dialog_clear_stock, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnClear  = view.findViewById<Button>(R.id.btnClear)
        val rbReturn  = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap   = view.findViewById<RadioButton>(R.id.rbScrap)
        val tvStock   = view.findViewById<TextView>(R.id.tvCurrentStock)

        lifecycleScope.launch(Dispatchers.IO) {
            val current = db.inventoryDao().getInventory(productId)?.currentStock ?: 0.0
            withContext(Dispatchers.Main) { tvStock.text = "Remaining: ${formatStock(current)}" }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {
            val reason = when {
                rbReturn.isChecked ->
                    com.example.easy_billing.repository.InventoryReductionRepository.ClearReason.PURCHASE_RETURN
                rbScrap.isChecked  ->
                    com.example.easy_billing.repository.InventoryReductionRepository.ClearReason.SCRAP
                else -> {
                    Toast.makeText(this, "Select reason", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val product = db.productDao().getById(productId) ?: return@launch
                    val repo = com.example.easy_billing.repository.InventoryReductionRepository.get(this@InventoryActivity)
                    val result = repo.clearRemainingStock(
                        productId   = productId,
                        productName = product.name,
                        hsnCode     = product.hsnCode,
                        reason      = reason,
                        purchaseTaxCgst = product.cgstPercentage,
                        purchaseTaxSgst = product.sgstPercentage,
                        purchaseTaxIgst = product.igstPercentage
                    )

                    withContext(Dispatchers.Main) {
                        loadInventory()
                        val msg = when (result) {
                            is com.example.easy_billing.repository.InventoryReductionRepository.ClearStockResult.Cleared ->
                                "Cleared ${formatStock(result.quantity)} units"
                            else -> "No stock to clear"
                        }
                        Toast.makeText(this@InventoryActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    SyncManager(this@InventoryActivity).syncInventory()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryActivity, "Error", Toast.LENGTH_SHORT).show()
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