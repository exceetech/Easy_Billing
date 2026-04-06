package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.*
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class InvoiceActivity : AppCompatActivity() {

    private lateinit var tvStoreName: TextView
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
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        tvStoreName = findViewById(R.id.tvStoreName)
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

        val subTotal = items.sumOf { it.subTotal() }
        val gstAmount = (subTotal * gstPercent) / 100

        val maxDiscount = subTotal + gstAmount

        var discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

        // 🔥 LIMIT DISCOUNT
        if (discount > maxDiscount) {
            discount = maxDiscount

            etDiscount.setText(maxDiscount.toInt().toString())
            etDiscount.setSelection(etDiscount.text.length)

            Toast.makeText(this, "Discount cannot exceed total", Toast.LENGTH_SHORT).show()
        }

        val total = maxDiscount - discount   // ✅ always ≥ 0

        val formattedSubTotal = CurrencyHelper.format(this, subTotal)
        val formattedGst = CurrencyHelper.format(this, gstAmount)
        val formattedDiscount = CurrencyHelper.format(this, discount)
        val formattedTotal = CurrencyHelper.format(this, total)

        tvTotal.text = """
        Subtotal: $formattedSubTotal
        GST: $formattedGst
        Discount: $formattedDiscount
        Total: $formattedTotal
    """.trimIndent()
    }

    // ================= SAVE BILL =================

    private fun saveBill() {

        if (isBillSaved) return

        isBillSaved = true
        btnConfirm.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val subTotal = items.sumOf { it.subTotal() }
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            val gstAmount = (subTotal * gstPercent) / 100
            val total = subTotal + gstAmount - discount

            // ✅ STEP 1: SAVE LOCALLY FIRST
            val billId = db.billDao().insertBill(
                Bill(
                    billNumber = billNumber,
                    date = date,
                    subTotal = subTotal,
                    gst = gstAmount,
                    discount = discount,
                    total = total,
                    paymentMethod = getPaymentMethod(),
                    isSynced = false
                )
            ).toInt()

            db.billItemDao().insertAll(
                items.map {
                    BillItem(
                        billId = billId,
                        productId = it.product.id,
                        productName = it.product.name,
                        price = it.product.price,
                        quantity = it.quantity,
                        subTotal = it.subTotal(),
                        isSynced = false
                    )
                }
            )

            // ✅ STEP 2: TRY API ONLY IF ONLINE
            try {
                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                val request = CreateBillRequest(
                    bill_number = "",
                    items = items.map {
                        BillItemRequest(it.product.id, it.quantity)
                    },
                    payment_method = getPaymentMethod(),
                    discount = discount,
                    gst = gstAmount,
                    total_amount = total
                )

                val response = RetrofitClient.api.createBill("Bearer $token", request)

                if (response.bill_number.isNotEmpty()) {
                    billNumber = response.bill_number

                    db.billDao().markBillSynced(billId)
                    db.billItemDao().markItemsSynced(billId)
                }

            } catch (_: Exception) {
                // offline → will sync later
            }

            savedBillId = billId

            withContext(Dispatchers.Main) {
                btnPrint.isEnabled = true
                Toast.makeText(this@InvoiceActivity, "Bill Saved", Toast.LENGTH_SHORT).show()
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

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val accounts = db.creditAccountDao().getAll()

            if (accounts.isEmpty()) {
                showAddCustomerDialog()
                return@launch
            }

            val names = accounts.map {
                "${it.name} (${it.phone}) - ₹${it.dueAmount}"
            }.toTypedArray()

            AlertDialog.Builder(this@InvoiceActivity)
                .setTitle("Select Customer")
                .setItems(names) { _, which ->

                    val selected = accounts[which]
                    addCreditAndSaveBill(selected)
                }
                .setPositiveButton("New Customer") { _, _ ->
                    showAddCustomerDialog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addCreditAndSaveBill(account: CreditAccount) {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val subTotal = items.sumOf { it.subTotal() }
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            val gstAmount = (subTotal * gstPercent) / 100
            val total = subTotal + gstAmount - discount

            // ✅ UPDATE ACCOUNT DUE
            val newDue = account.dueAmount + total
            db.creditAccountDao().updateDue(account.id, newDue)

            // ✅ INSERT CREDIT TRANSACTION
            db.creditTransactionDao().insert(
                CreditTransaction(
                    accountId = account.id,
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

        AlertDialog.Builder(this)
            .setTitle("Add Customer")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->

                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()

                if (name.isEmpty() || phone.isEmpty()) {
                    Toast.makeText(this, "Enter all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch(Dispatchers.IO) {

                    val db = AppDatabase.getDatabase(this@InvoiceActivity)

                    val existing = db.creditAccountDao().getByPhone(phone)

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
                                isSynced = false
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
                                isSynced = true
                            )
                        )

                        println("✅ Created account: ${response.id}")

                    } catch (e: Exception) {

                        e.printStackTrace()

                        db.creditAccountDao().insert(
                            CreditAccount(
                                name = name,
                                phone = phone,
                                isSynced = false
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InvoiceActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}