package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.*
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreateSaleRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.SaleItemDto
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class InvoiceActivity : AppCompatActivity() {

    private lateinit var tvStoreName: TextView
    private lateinit var tvSubtotal: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvBillInfo: TextView
    private lateinit var etDiscount: EditText

    private lateinit var rgPaymentMethod: RadioGroup
    private lateinit var items: List<CartItem>

    private lateinit var btnConfirm: Button
    private lateinit var btnPrint: Button

    private var gstPercent: Double = 0.0

    private lateinit var tvGstPercent: TextView
    private var savedBillId: Int = -1
    private var isBillSaved = false

    private var isUpdating = false
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        tvStoreName = findViewById(R.id.tvStoreName)
        tvSubtotal = findViewById(R.id.tvSubtotal)
        tvGst = findViewById(R.id.tvGst)
        tvTotal = findViewById(R.id.tvTotal)
        tvBillInfo = findViewById(R.id.tvBillInfo)
        etDiscount = findViewById(R.id.etDiscount)
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod)
        tvGstPercent = findViewById(R.id.tvGstPercent)

        btnConfirm = findViewById(R.id.btnConfirm)
        btnPrint = findViewById(R.id.btnPrint)
        val btnClose = findViewById<Button>(R.id.btnClose)

        btnPrint.isEnabled = false

        val rvItems = findViewById<RecyclerView>(R.id.rvInvoiceItems)

        @Suppress("UNCHECKED_CAST")
        items = intent.getSerializableExtra("CART_ITEMS") as? List<CartItem> ?: emptyList()

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = InvoiceAdapter(items)

        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        billNumber = " "

        tvBillInfo.text = "Date: $date"

        loadStoreInfo()
        loadBillingSettings()

        calculateTotal()

        etDiscount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = calculateTotal()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnConfirm.setOnClickListener {

            if (getPaymentMethod() == "CREDIT") {
                handleCreditFlow()
            } else {
                saveBill()
            }
        }
        btnPrint.setOnClickListener { generatePdfAndPrint() }

        btnClose.setOnClickListener {
            setResult(if (isBillSaved) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    // ================= CALC =================

    private fun calculateTotal() {

        if (isUpdating) return   // ✅ prevent infinite loop

        val subTotal = items.sumOf { it.subTotal() }

        var gstAmount = 0.0
        
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val storeInfo = db.storeInfoDao().get()
            val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()
            
            val sellerStateCode = storeInfo?.stateCode ?: ""
            val buyerStateCode = "" // B2C default for now in UI calc
            val supplyType = if (isGstEnabled) {
                if (GstEngine.isIntrastate(sellerStateCode, buyerStateCode)) "intrastate" else "interstate"
            } else "intrastate"

            var totalGst = 0.0
            
            for (cartItem in items) {
                val itemSubTotal = cartItem.subTotal()
                val itemGstRate = if (isGstEnabled) cartItem.product.defaultGstRate else gstPercent
                val breakup = GstEngine.calculateGstSplit(itemSubTotal, itemGstRate, supplyType)
                totalGst += breakup.totalTax
            }
            
            gstAmount = totalGst

            withContext(Dispatchers.Main) {
                val maxDiscount = subTotal + gstAmount
        
                var discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
        
                // 🔥 LIMIT DISCOUNT
                if (discount > maxDiscount) {
                    discount = maxDiscount
        
                    isUpdating = true
                    etDiscount.setText(maxDiscount.toInt().toString())
                    etDiscount.setSelection(etDiscount.text.length)
                    isUpdating = false
        
                    Toast.makeText(this@InvoiceActivity, "Discount cannot exceed total", Toast.LENGTH_SHORT).show()
                }
        
                val total = maxDiscount - discount
        
                val formattedSubTotal = CurrencyHelper.format(this@InvoiceActivity, subTotal)
                val formattedGst = CurrencyHelper.format(this@InvoiceActivity, gstAmount)
                val formattedTotal = CurrencyHelper.format(this@InvoiceActivity, total)
        
                tvSubtotal.text = formattedSubTotal
                tvGst.text = formattedGst
                tvTotal.text = formattedTotal
            }
        }

        // The UI updates are now handled in the coroutine above.
    }

    // ================= SAVE BILL =================
    private fun saveBill() {

        if (isBillSaved) return

        isBillSaved = true
        btnConfirm.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val db = AppDatabase.getDatabase(this@InvoiceActivity)

                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
                val subTotal = items.sumOf { it.subTotal() }
                val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
                
                // Fetch Store Info for GST Calculation
                val storeInfo = db.storeInfoDao().get()
                val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()
                val sellerStateCode = storeInfo?.stateCode ?: ""
                val customerType = "B2C" // Default for now
                val customerGstin: String? = null
                val buyerStateCode = GstEngine.getStateCode(customerGstin)
                val placeOfSupply = buyerStateCode.ifBlank { sellerStateCode }
                val supplyType = if (isGstEnabled) {
                    if (GstEngine.isIntrastate(sellerStateCode, buyerStateCode)) "intrastate" else "interstate"
                } else "intrastate"
                
                var totalCgstAmount = 0.0
                var totalSgstAmount = 0.0
                var totalIgstAmount = 0.0
                var totalGstAmount = 0.0

                // ================= 🔴 STEP 1: VALIDATE STOCK =================
                for (cartItem in items) {

                    val product = cartItem.product

                    // ✅ ONLY FOR TRACKED PRODUCTS
                    if (product.trackInventory) {

                        val inventory = db.inventoryDao().getInventory(product.id)

                        if (inventory != null && inventory.currentStock < cartItem.quantity) {

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@InvoiceActivity,
                                    "Out of stock: ${product.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            isBillSaved = false
                            btnConfirm.isEnabled = true
                            return@launch
                        }
                    }
                }

                // ================= 🟡 STEP 3: PREPARE ITEMS =================
                val billItems = mutableListOf<BillItem>()
                for (cartItem in items) {

                    val product = cartItem.product
                    val quantity = cartItem.quantity
                    val subtotalItem = cartItem.subTotal()

                    val avgCost = if (product.trackInventory) {
                        db.inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
                    } else 0.0

                    val itemGstRate = if (isGstEnabled) product.defaultGstRate else gstPercent
                    val breakup = GstEngine.calculateGstSplit(subtotalItem, itemGstRate, supplyType)
                    
                    totalCgstAmount += breakup.cgst
                    totalSgstAmount += breakup.sgst
                    totalIgstAmount += breakup.igst
                    totalGstAmount += breakup.totalTax

                    billItems.add(
                        BillItem(
                            billId = 0, // Temporarily set to 0, updated later
                            productId = product.id,
                            productName = product.name,
                            variant = product.variant,
                            unit = product.unit ?: "unit",
                            price = product.price,
                            quantity = quantity,
                            subTotal = subtotalItem,
                            costPriceUsed = avgCost * quantity,
                            profit = subtotalItem - (avgCost * quantity),
                            hsnCode = product.hsnCode ?: "",
                            gstRate = itemGstRate,
                            cgstAmount = breakup.cgst,
                            sgstAmount = breakup.sgst,
                            igstAmount = breakup.igst,
                            taxableValue = breakup.taxableValue,
                            isSynced = false
                        )
                    )
                }

                val total = subTotal + totalGstAmount - discount

                // ================= 🟡 STEP 2: SAVE BILL =================
                val finalBill = Bill(
                    billNumber = billNumber,
                    date = date,
                    subTotal = subTotal,
                    gst = totalGstAmount,
                    discount = discount,
                    total = total,
                    paymentMethod = getPaymentMethod(),
                    customerType = customerType,
                    customerGstin = customerGstin,
                    placeOfSupply = placeOfSupply,
                    supplyType = supplyType,
                    cgstAmount = totalCgstAmount,
                    sgstAmount = totalSgstAmount,
                    igstAmount = totalIgstAmount,
                    isSynced = false
                )
                
                val billId = db.billDao().insertBill(finalBill).toInt()

                // Update bill items with real billId and insert
                val finalBillItems = billItems.map { it.copy(billId = billId) }
                db.billItemDao().insertAll(finalBillItems)

                // ================= 🟢 STEP 3.5: SAVE GST SALES RECORDS =================
                if (isGstEnabled && storeInfo != null) {
                    val deviceId = getSharedPreferences("auth", MODE_PRIVATE).getString("DEVICE_ID", UUID.randomUUID().toString()) ?: ""
                    val gstRecords = GstEngine.buildSalesRecords(
                        bill = finalBill.copy(id = billId), // Make sure it has ID for reference if needed, though invoiceNumber is used
                        items = finalBillItems,
                        storeInfo = storeInfo,
                        deviceId = deviceId
                    )
                    db.gstSalesRecordDao().insertAll(gstRecords)
                }

                savedBillId = billId

                // ================= 🔵 STEP 4: SEND TO BACKEND (PROFIT) =================
                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    if (!token.isNullOrEmpty()) {

                        val saleItems = items.map { cartItem ->

                            val product = cartItem.product

                            val avgCost = if (product.trackInventory) {
                                db.inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
                            } else 0.0

                            SaleItemDto(
                                product_id = product.serverId ?: product.id,
                                quantity = cartItem.quantity,
                                selling_price = product.price,
                                cost_price = avgCost,
                                product_name = product.name,
                                variant = product.variant
                            )
                        }

                        RetrofitClient.api.createSale(
                            "Bearer $token",
                            CreateSaleRequest(saleItems)
                        )
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    // ❗ do not break bill
                }

                // ================= 🟢 STEP 5: UPDATE INVENTORY =================
                for (cartItem in items) {

                    val product = cartItem.product

                    if (product.trackInventory) {

                        val inventory = db.inventoryDao().getInventory(product.id)

                        if (inventory != null) {
                            InventoryManager.reduceStock(
                                db = db,
                                productId = product.id,
                                quantity = cartItem.quantity
                            )
                        }
                    }
                }

                // ================= 🟢 STEP 6: CREATE BILL (BACKEND) =================
                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    val request = CreateBillRequest(
                        bill_number = "",
                        items = items.map {

                            if (it.product.serverId == null) {
                                throw Exception("Product not synced: ${it.product.name}")
                            }

                            BillItemRequest(
                                it.product.serverId,
                                it.quantity,
                                it.product.variant
                            )
                        },
                        payment_method = getPaymentMethod(),
                        discount = discount,
                        gst = totalGstAmount,
                        total_amount = total
                    )

                    val response = RetrofitClient.api.createBill("Bearer $token", request)

                    if (response.bill_number.isNotEmpty()) {

                        db.billDao().updateBillNumber(savedBillId, response.bill_number)
                        db.billDao().markBillSynced(savedBillId)
                        db.billItemDao().markItemsSynced(savedBillId)
                    }

                } catch (_: Exception) {
                    // offline safe
                }

                // ================= UI =================
                withContext(Dispatchers.Main) {
                    btnPrint.isEnabled = true
                    Toast.makeText(this@InvoiceActivity, "Bill Saved", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {

                e.printStackTrace()

                isBillSaved = false

                withContext(Dispatchers.Main) {
                    btnConfirm.isEnabled = true
                    Toast.makeText(
                        this@InvoiceActivity,
                        e.message ?: "Error saving bill",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= PRINT =================

    private fun generatePdfAndPrint() {

        if (savedBillId == -1) {
            Toast.makeText(this, "Please save bill first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val bill = db.billDao().getBillById(savedBillId)
            val items = db.billDao().getItemsForBill(savedBillId)
            val storeInfo = db.storeInfoDao().get()

            withContext(Dispatchers.Main) {
                InvoicePdfGenerator.generatePdfFromBill(this@InvoiceActivity, bill, items, storeInfo)
            }
        }
    }

    // ================= STORE =================

    private fun loadStoreInfo() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            var store = db.storeInfoDao().get()

            if (store == null) {
                store = StoreInfo(
                    name = "My Store",
                    address = "",
                    phone = "",
                    gstin = "",
                    isSynced = false
                )
                db.storeInfoDao().insert(store)
            }

            withContext(Dispatchers.Main) {
                tvStoreName.text = store.name
            }

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getStoreSettings("Bearer $token")

                val updated = StoreInfo(
                    name = response.shop_name ?: "",
                    address = response.store_address ?: "",
                    phone = response.phone ?: "",
                    gstin = response.store_gstin ?: "",
                    isSynced = true
                )

                db.storeInfoDao().insert(updated)

                val refreshed = db.storeInfoDao().get()

                withContext(Dispatchers.Main) {
                    tvStoreName.text = refreshed?.name ?: "My Store"
                }

            } catch (_: Exception) {
                // offline ignore
            }
        }
    }

    // ================= GST =================

    private fun loadBillingSettings() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            // ✅ 1. LOAD FROM ROOM FIRST (OFFLINE SUPPORT)
            val local = db.billingSettingsDao().get()

            withContext(Dispatchers.Main) {
                local?.let {
                    gstPercent = it.defaultGst.toDouble()
                    tvGstPercent.text = "${gstPercent}%"
                    calculateTotal()
                }
            }

            // ✅ 2. TRY SYNC FROM BACKEND (IF ONLINE)
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getBillingSettings("Bearer $token")

                val updated = BillingSettings(
                    defaultGst = response.default_gst,
                    printerLayout = response.printer_layout
                )

                // ✅ UPDATE ROOM
                db.billingSettingsDao().insert(updated)

                withContext(Dispatchers.Main) {
                    gstPercent = updated.defaultGst.toDouble()
                    tvGstPercent.text = "${gstPercent}%"
                    calculateTotal()
                }

            } catch (_: Exception) {
                // offline → ignore
            }
        }
    }

    private fun getPaymentMethod(): String {
        return when (rgPaymentMethod.checkedRadioButtonId) {
            R.id.rbCash -> "CASH"
            R.id.rbUpi -> "UPI"
            R.id.rbCard -> "CARD"
            R.id.rbCredit -> "CREDIT"
            else -> "CASH"
        }
    }

    private fun handleCreditFlow() {

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_customer_picker, null)

        val etSearch = view.findViewById<EditText>(R.id.etSearchCustomer)
        val rvCustomers = view.findViewById<RecyclerView>(R.id.rvCustomers)
        val btnNew = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewCustomer)

        rvCustomers.layoutManager = LinearLayoutManager(this)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                .getInt("SHOP_ID", 1)

            val allCustomers = db.creditAccountDao().getAll(shopId)

            var currentList = allCustomers.toMutableList()

            val adapter = CreditAdapter(currentList) { customer ->
                dialog.dismiss()
                addCreditAndSaveBill(customer)
            }

            rvCustomers.adapter = adapter

            fun updateList(data: List<CreditAccount>) {
                currentList.clear()
                currentList.addAll(data)
                adapter.notifyDataSetChanged()
            }

            // 🔍 SEARCH
            etSearch.addTextChangedListener(object : TextWatcher {

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                    runnable?.let { handler.removeCallbacks(it) }

                    runnable = Runnable {

                        val query = s?.toString()?.trim()?.take(50) ?: ""

                        val result = if (query.isEmpty()) {
                            allCustomers
                        } else {
                            allCustomers.filter {
                                it.name.contains(query, true) ||
                                        it.phone.contains(query)
                            }
                        }

                        updateList(result)
                    }

                    handler.postDelayed(runnable!!, 300)
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }

        btnNew.setOnClickListener {
            dialog.dismiss()
            showAddCustomerDialog()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun addCreditAndSaveBill(account: CreditAccount) {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val subTotal = items.sumOf { it.subTotal() }
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            
            val storeInfo = db.storeInfoDao().get()
            val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()
            
            var totalGst = 0.0
            for (cartItem in items) {
                val itemGstRate = if (isGstEnabled) cartItem.product.defaultGstRate else gstPercent
                val breakup = GstEngine.calculateGstSplit(cartItem.subTotal(), itemGstRate, "intrastate")
                totalGst += breakup.totalTax
            }
            
            val total = subTotal + totalGst - discount

            val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                .getInt("SHOP_ID", 1)

            // ✅ UPDATE ACCOUNT DUE
            val newDue = account.dueAmount + total
            db.creditAccountDao().updateDue(account.id, newDue, shopId)

            db.creditTransactionDao().insert(
                CreditTransaction(
                    accountId = account.id,
                    shopId = shopId,   // 🔥 REQUIRED
                    amount = total,
                    type = "ADD"
                )
            )

            // 🔥 SYNC CREDIT IMMEDIATELY
            SyncManager(this@InvoiceActivity).syncCredit()

            // ✅ NOW SAVE BILL
            withContext(Dispatchers.Main) {
                saveBill()
            }
        }
    }

    private fun showAddCustomerDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_add_customer, null)

        val etName = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {

            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {

                val db = AppDatabase.getDatabase(this@InvoiceActivity)

                val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                    .getInt("SHOP_ID", 1)

                val existing = db.creditAccountDao().getByPhone(phone, shopId)

                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InvoiceActivity, "Customer already exists", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val api = RetrofitClient.api

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                if (token == null) {
                    println("❌ TOKEN NULL")

                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = name,
                            phone = phone,
                            isSynced = false,
                            shopId = shopId
                        )
                    )
                    return@launch
                }

                try {
                    val response = api.createCreditAccount(
                        "Bearer $token",
                        CreateCreditAccountRequest(name, phone)
                    )

                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = response.name,
                            phone = response.phone,
                            dueAmount = response.due_amount,
                            serverId = response.id,
                            isSynced = true,
                            shopId = shopId
                        )
                    )

                    println("✅ Created account: ${response.id}")

                } catch (e: Exception) {

                    e.printStackTrace()

                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = name,
                            phone = phone,
                            isSynced = false,
                            shopId = shopId   // 🔥 REQUIRED
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InvoiceActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    dialog.dismiss() // ✅ manual close
                }
            }
        }

        dialog.show()
    }
}
