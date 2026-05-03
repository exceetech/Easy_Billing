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

        val view = layoutInflater.inflate(R.layout.dialog_add_stock, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etQty = view.findViewById<EditText>(R.id.etQty)
        val etCost = view.findViewById<EditText>(R.id.etCost)
        val llGstFields = view.findViewById<LinearLayout>(R.id.llGstFields)
        val etVendorGstin = view.findViewById<EditText>(R.id.etVendorGstin)
        val etInvoiceNumber = view.findViewById<EditText>(R.id.etInvoiceNumber)

        var isGstEnabled = false
        var storeStateCode = ""

        lifecycleScope.launch(Dispatchers.IO) {
            val storeInfo = db.storeInfoDao().get()
            isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()
            storeStateCode = storeInfo?.stateCode ?: ""
            
            withContext(Dispatchers.Main) {
                if (isGstEnabled) {
                    llGstFields.visibility = View.VISIBLE
                }
            }
        }

        etQty.filters = arrayOf(InputFilter.LengthFilter(5))
        etCost.filters = arrayOf(InputFilter.LengthFilter(7))

        val btnAdd = view.findViewById<Button>(R.id.btnAdd)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        val allowDecimal = isDecimalAllowed(product.unit)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnAdd.setOnClickListener {

            val qtyText = etQty.text.toString().trim()
            val cost = etCost.text.toString().toDoubleOrNull() ?: 0.0

            if (qtyText.isEmpty()) {
                etQty.error = "Enter quantity"
                return@setOnClickListener
            }

            if (!allowDecimal && qtyText.contains(".")) {
                etQty.error = "Decimal not allowed for ${product.unit}"
                return@setOnClickListener
            }

            val qty = qtyText.toDoubleOrNull() ?: 0.0

            if (qty <= 0) {
                etQty.error = "Invalid quantity"
                return@setOnClickListener
            }

            if (cost <= 0) {
                etCost.error = "Enter valid cost"
                return@setOnClickListener
            }

            val vendorGstin = etVendorGstin.text.toString().trim()
            val invoiceNumber = etInvoiceNumber.text.toString().trim()

            if (isGstEnabled && invoiceNumber.isEmpty()) {
                etInvoiceNumber.error = "Invoice Number required"
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

                    if (isGstEnabled) {
                        val vendorStateCode = GstEngine.getStateCode(vendorGstin)
                        val supplyType = if (GstEngine.isIntrastate(storeStateCode, vendorStateCode)) "intrastate" else "interstate"
                        
                        val taxableValue = qty * cost
                        val breakup = GstEngine.calculateGstSplit(taxableValue, product.defaultGstRate, supplyType)
                        val totalInvoiceValue = taxableValue + breakup.totalTax

                        val purchaseRecord = com.example.easy_billing.db.GstPurchaseRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            vendorGstin = vendorGstin,
                            vendorName = "Vendor", // Can be extended to have a vendor master later
                            invoiceNumber = invoiceNumber,
                            invoiceDate = System.currentTimeMillis(),
                            totalInvoiceValue = totalInvoiceValue,
                            taxableValue = taxableValue,
                            gstRate = product.defaultGstRate,
                            cgstAmount = breakup.cgst,
                            sgstAmount = breakup.sgst,
                            igstAmount = breakup.igst,
                            cessAmount = 0.0,
                            hsnCode = product.hsnCode ?: "",
                            itcEligibility = "Eligible", // Or Ineligible depending on business rules
                            syncStatus = "pending"
                        )
                        db.gstPurchaseRecordDao().insert(purchaseRecord)
                    }

                    withContext(Dispatchers.Main) {
                        loadInventory()
                        Toast.makeText(this@InventoryActivity, "Stock added", Toast.LENGTH_SHORT).show()
                    }

                    SyncManager(this@InventoryActivity).syncInventory()

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
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

        btnCancel.setOnClickListener { dialog.dismiss() }

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
                val lossAmount = qty * avgCost

                try {

                    InventoryManager.reduceStock(
                        db = db,
                        productId = product.id,
                        quantity = qty,
                        type = type
                    )

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

    // ================= CLEAR STOCK =================

    private fun showClearStockDialog(productId: Int) {

        val view = layoutInflater.inflate(R.layout.dialog_clear_stock, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnClear  = view.findViewById<Button>(R.id.btnClear)
        val rbReturn  = view.findViewById<RadioButton>(R.id.rbReturn)
        val rbScrap   = view.findViewById<RadioButton>(R.id.rbScrap)
        val tvStock   = view.findViewById<TextView>(R.id.tvCurrentStock)

        // Show "Quantity = full remaining stock" up-front. Quantity
        // is *not* user-editable per current spec.
        lifecycleScope.launch(Dispatchers.IO) {
            val current = db.inventoryDao().getInventory(productId)?.currentStock ?: 0.0
            withContext(Dispatchers.Main) { tvStock.text = "Remaining: $current" }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {

            val reason = when {
                rbReturn.isChecked ->
                    com.example.easy_billing.repository.InventoryReductionRepository
                        .ClearReason.PURCHASE_RETURN
                rbScrap.isChecked  ->
                    com.example.easy_billing.repository.InventoryReductionRepository
                        .ClearReason.SCRAP
                else -> {
                    Toast.makeText(this, "Select reason", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            dialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val product = db.productDao().getById(productId) ?: return@launch
                    val repo = com.example.easy_billing.repository
                        .InventoryReductionRepository.get(this@InventoryActivity)
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
                            is com.example.easy_billing.repository
                                .InventoryReductionRepository.ClearStockResult.Cleared ->
                                "Cleared ${result.quantity} units"
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