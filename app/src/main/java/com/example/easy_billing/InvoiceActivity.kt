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
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class InvoiceActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvBillInfo: TextView
    private lateinit var etGst: EditText
    private lateinit var etDiscount: EditText
    private lateinit var rgPaymentMethod: RadioGroup
    private lateinit var items: List<CartItem>

    private lateinit var btnConfirm: Button
    private lateinit var btnPrint: Button

    private var savedBillId: Int = -1
    private var isBillSaved = false
    private var billNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        tvTotal = findViewById(R.id.tvTotal)
        tvBillInfo = findViewById(R.id.tvBillInfo)
        etGst = findViewById(R.id.etGst)
        etDiscount = findViewById(R.id.etDiscount)
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod)

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

        billNumber = System.currentTimeMillis().toString().takeLast(6)

        tvBillInfo.text = "Invoice #$billNumber\nDate: $date"

        calculateTotal()

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { calculateTotal() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        etGst.addTextChangedListener(watcher)
        etDiscount.addTextChangedListener(watcher)

        btnConfirm.setOnClickListener { saveBill() }
        btnPrint.setOnClickListener { generatePdfAndPrint() }

        btnClose.setOnClickListener {
            if (isBillSaved) setResult(RESULT_OK) else setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun calculateTotal() {

        val subTotal = items.sumOf { it.subTotal() }
        val gstPercent = etGst.text.toString().toDoubleOrNull() ?: 0.0
        val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

        val gstAmount = (subTotal * gstPercent) / 100
        val total = subTotal + gstAmount - discount

        tvTotal.text = "Total: ₹%.2f".format(total)
    }

    private fun saveBill() {

        if (isBillSaved) {
            Toast.makeText(this, "Bill already saved", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

            val subTotal = items.sumOf { it.subTotal() }
            val gstPercent = etGst.text.toString().toDoubleOrNull() ?: 0.0
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

            val gstAmount = (subTotal * gstPercent) / 100
            val total = subTotal + gstAmount - discount

            try {

                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)

                val requestItems = items.map {
                    BillItemRequest(
                        shop_product_id = it.product.id,
                        quantity = it.quantity
                    )
                }

                val request = CreateBillRequest(
                    bill_number = billNumber,
                    items = requestItems,
                    payment_method = getPaymentMethod(),
                    gst = gstAmount,
                    discount = discount,
                    total_amount = total
                )

                val response = RetrofitClient.api.createBill(
                    "Bearer $token",
                    request
                )

                billNumber = response.bill_number

            } catch (e: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this@InvoiceActivity,
                        "Backend failed (offline mode)",
                        Toast.LENGTH_SHORT
                    ).show()
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
                    paymentMethod = getPaymentMethod()
                )
            ).toInt()

            val billItems = items.map {
                BillItem(
                    billId = billId,
                    productName = it.product.name,
                    price = it.product.price,
                    quantity = it.quantity,
                    subTotal = it.subTotal()
                )
            }

            db.billDao().insertItems(billItems)

            savedBillId = billId
            isBillSaved = true

            runOnUiThread {

                tvBillInfo.text = "Invoice #$billNumber\nDate: $date"

                btnConfirm.isEnabled = false
                btnPrint.isEnabled = true

                Toast.makeText(
                    this@InvoiceActivity,
                    "Bill Saved Successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun generatePdfAndPrint() {

        if (savedBillId == -1) {
            Toast.makeText(this, "Please save bill first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val bill = db.billDao().getBillById(savedBillId)
            val items = db.billDao().getItemsForBill(savedBillId)

            InvoicePdfGenerator.generatePdfFromBill(
                this@InvoiceActivity,
                bill,
                items
            )
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