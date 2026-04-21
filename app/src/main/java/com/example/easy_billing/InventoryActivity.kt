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
import com.example.easy_billing.db.InventoryLog
import com.example.easy_billing.db.LossEntry
import com.example.easy_billing.db.Product
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
                    .filter { it.trackInventory } // 🔥 only tracked products
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
                val variant = it.variant?.lowercase() ?: ""

                name.contains(currentQuery) || variant.contains(currentQuery)
            }
        }

        adapter.updateData(filtered)
    }

    // ================= ADD STOCK =================

    private fun showAddStockDialog(product: Product) {

        val view = layoutInflater.inflate(R.layout.dialog_add_stock, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etQty = view.findViewById<EditText>(R.id.etQty)
        val etCost = view.findViewById<EditText>(R.id.etCost)

        etQty.filters = arrayOf(InputFilter.LengthFilter(5))
        etCost.filters = arrayOf(InputFilter.LengthFilter(7))

        val btnAdd = view.findViewById<Button>(R.id.btnAdd)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        val allowDecimal = isDecimalAllowed(product.unit)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnAdd.setOnClickListener {

            val qtyText = etQty.text.toString().trim()
            val cost = etCost.text.toString().toDoubleOrNull() ?: 0.0

            if (qtyText.isEmpty()) {
                etQty.error = "Enter quantity"
                return@setOnClickListener
            }

            // 🔥 UNIT CHECK
            if (!allowDecimal && qtyText.contains(".")) {
                etQty.error = "Decimal not allowed for ${product.unit}"
                return@setOnClickListener
            }

            val qty = qtyText.toDoubleOrNull() ?: 0.0

            // ❌ Invalid quantity
            if (qty <= 0) {
                etQty.error = "Invalid quantity"
                return@setOnClickListener
            }

            // ❌ MAX LIMIT (QUANTITY)
            if (qty > 10000) {
                etQty.error = "Max allowed is 10,000"
                return@setOnClickListener
            }

            // ❌ Invalid cost
            if (cost <= 0) {
                etCost.error = "Enter valid cost"
                return@setOnClickListener
            }

            // ❌ MAX LIMIT (COST)
            if (cost > 1_000_000) {
                etCost.error = "Max cost is 10,00,000"
                return@setOnClickListener
            }

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    InventoryManager.addStock(
                        db = db,
                        productId = product.id,
                        quantity = qty,
                        costPrice = cost
                    )

                    db.inventoryLogDao().insert(
                        InventoryLog(
                            productId = product.id,
                            type = "ADD",
                            quantity = qty,
                            price = cost,
                            date = System.currentTimeMillis()
                        )
                    )

                    runOnUiThread {
                        loadInventory()
                        Toast.makeText(this@InventoryActivity, "Stock added", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@InventoryActivity, "Failed to add stock", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    // ================= REDUCE STOCK =================

    private fun showReduceStockDialog(product: Product, currentStock: Double) {

        val view = layoutInflater.inflate(R.layout.dialog_reduce_stock, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etQty = view.findViewById<EditText>(R.id.etQty)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnRemove = view.findViewById<Button>(R.id.btnRemove)

        val rbDamage = view.findViewById<RadioButton>(R.id.rbDamage)
        val rbAdjust = view.findViewById<RadioButton>(R.id.rbAdjust)

        val allowDecimal = isDecimalAllowed(product.unit)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnRemove.setOnClickListener {

            val qtyText = etQty.text.toString().trim()

            if (qtyText.isEmpty()) {
                etQty.error = "Enter quantity"
                return@setOnClickListener
            }

            if (!allowDecimal && qtyText.contains(".")) {
                etQty.error = "Decimal not allowed"
                return@setOnClickListener
            }

            val qty = qtyText.toDoubleOrNull() ?: 0.0

            if (qty <= 0) {
                etQty.error = "Invalid quantity"
                return@setOnClickListener
            }

            if (qty > currentStock) {
                etQty.error = "Exceeds stock"
                return@setOnClickListener
            }

            val type = when {
                rbDamage.isChecked -> "LOSS"
                rbAdjust.isChecked -> "ADJUST"
                else -> {
                    Toast.makeText(this, "Select reason", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {

                val inventory = db.inventoryDao().getInventory(product.id)
                val avgCost = inventory?.averageCost ?: 0.0

                // 🔥 LOSS VALUE
                val lossAmount = qty * avgCost

                try {

                    // 🔥 REDUCE STOCK
                    InventoryManager.reduceStock(
                        db = db,
                        productId = product.id,
                        quantity = qty
                    )

                    // 🔥 SAVE LOG (VERY IMPORTANT)
                    db.inventoryLogDao().insert(
                        InventoryLog(
                            productId = product.id,
                            type = type, // LOSS or ADJUST
                            quantity = qty,
                            price = avgCost,
                            date = System.currentTimeMillis()
                        )
                    )

                    // 🔥 ONLY LOSS AFFECTS PROFIT
                    if (type == "LOSS") {

                        db.lossDao().insert(
                            LossEntry(
                                productId = product.id,
                                amount = lossAmount,
                                reason = "Damaged/Expired",
                                date = System.currentTimeMillis().toString()
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        loadInventory()
                        Toast.makeText(this@InventoryActivity, "Stock updated", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryActivity, "Error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    // ================= CLEAR STOCK =================

    private fun showClearStockDialog(productId: Int) {

        val view = layoutInflater.inflate(R.layout.dialog_clear_stock, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnClear = view.findViewById<Button>(R.id.btnClear)

        val rbDamage = view.findViewById<RadioButton>(R.id.rbDamage)
        val rbAdjust = view.findViewById<RadioButton>(R.id.rbAdjust)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {

            val type = when {
                rbDamage.isChecked -> "LOSS"
                rbAdjust.isChecked -> "ADJUST"
                else -> {
                    Toast.makeText(this, "Select reason", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {

                try {

                    val inventory = db.inventoryDao().getInventory(productId)

                    val currentStock = inventory?.currentStock ?: 0.0
                    val avgCost = inventory?.averageCost ?: 0.0

                    if (currentStock <= 0) return@launch

                    val lossAmount = currentStock * avgCost

                    // 🔥 CLEAR STOCK
                    InventoryManager.clearStock(db, productId)

                    // 🔥 LOG IT
                    db.inventoryLogDao().insert(
                        InventoryLog(
                            productId = productId,
                            type = type,
                            quantity = currentStock,
                            price = avgCost,
                            date = System.currentTimeMillis()
                        )
                    )

                    // 🔥 IF LOSS → IMPACT PROFIT
                    if (type == "LOSS") {

                        db.lossDao().insert(
                            LossEntry(
                                productId = productId,
                                amount = lossAmount,
                                reason = "Stock cleared",
                                date = System.currentTimeMillis().toString()
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        loadInventory()
                        Toast.makeText(
                            this@InventoryActivity,
                            "Stock cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InventoryActivity, "Error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    // ================= ERROR HANDLER =================

    private suspend fun showError(message: String?) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@InventoryActivity,
                message ?: "Something went wrong",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isDecimalAllowed(unit: String?): Boolean {
        return when (unit?.lowercase()) {
            "kilogram", "kg", "litre", "l" -> true
            else -> false
        }
    }
}
