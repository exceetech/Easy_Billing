package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit

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

        billNumber = "xxxxxx"

        tvBillInfo.text = "Invoice #$billNumber\nDate: $date"

        loadStoreInfo()
        loadBillingSettings()

        calculateTotal()

        etDiscount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = calculateTotal()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnConfirm.setOnClickListener { saveBill() }
        btnPrint.setOnClickListener { generatePdfAndPrint() }

        btnClose.setOnClickListener {
            setResult(if (isBillSaved) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    // ================= CALC =================

    private fun calculateTotal() {

        val subTotal = items.sumOf { it.subTotal() }
        val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
        val gstAmount = (subTotal * gstPercent) / 100
        val total = subTotal + gstAmount - discount

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

        if (isBillSaved) {
            Toast.makeText(this, "Bill already saved", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val subTotal = items.sumOf { it.subTotal() }
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            val gstAmount = (subTotal * gstPercent) / 100
            val total = subTotal + gstAmount - discount

            try {
                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                val request = CreateBillRequest(
                    bill_number = billNumber,
                    items = items.map {
                        BillItemRequest(it.product.id, it.quantity)
                    },
                    payment_method = getPaymentMethod(),
                    discount = discount,
                    gst = gstAmount,
                    total_amount = total
                )

                val response = RetrofitClient.api.createBill("Bearer $token", request)
                billNumber = response.bill_number

                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                prefs.edit { putBoolean("ai_reset", false) }

            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InvoiceActivity, "Backend failed (offline mode)", Toast.LENGTH_SHORT).show()
                }
            }

            val billId = db.billDao().insertBill(
                Bill(
                    billNumber = billNumber,
                    date = date,
                    subTotal = subTotal,
                    gst = gstAmount,
                    discount = discount,
                    total = total,
                    paymentMethod = getPaymentMethod(),
                    isSynced = false   // 🔥 important if exists in model
                )
            ).toInt()

            val billItems = items.map {
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

            db.billItemDao().insertAll(billItems)

            savedBillId = billId
            isBillSaved = true

            withContext(Dispatchers.Main) {
                tvBillInfo.text = "Invoice #$billNumber\nDate: $date"
                btnConfirm.isEnabled = false
                btnPrint.isEnabled = true
                Toast.makeText(this@InvoiceActivity, "Bill Saved Successfully", Toast.LENGTH_SHORT).show()
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

                val updated = com.example.easy_billing.db.BillingSettings(
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
            else -> "CASH"
        }
    }
}