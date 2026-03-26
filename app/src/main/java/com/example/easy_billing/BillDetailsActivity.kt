package com.example.easy_billing

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.util.InvoicePdfGenerator
import kotlinx.coroutines.launch
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.CurrencyHelper

class BillDetailsActivity : BaseActivity() {

    private lateinit var tvBillInfo: TextView

    private lateinit var tvStoreName: TextView
    private lateinit var tvSubTotal: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvDiscount: TextView
    private lateinit var tvTotal: TextView
    private lateinit var rvBillItems: RecyclerView
    private lateinit var btnPrint: Button
    private lateinit var btnClose: Button

    private var billId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_details)

        tvBillInfo = findViewById(R.id.tvBillInfo)
        tvStoreName = findViewById(R.id.tvStoreName)
        tvSubTotal = findViewById(R.id.tvSubTotal)
        tvGst = findViewById(R.id.tvGst)
        tvDiscount = findViewById(R.id.tvDiscount)
        tvTotal = findViewById(R.id.tvTotal)
        rvBillItems = findViewById(R.id.rvBillItems)
        btnPrint = findViewById(R.id.btnPrint)
        btnClose = findViewById(R.id.btnClose)

        billId = intent.getIntExtra("BILL_ID", -1)

        if (billId == -1) {
            Toast.makeText(this, "Invalid Bill ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        rvBillItems.layoutManager = LinearLayoutManager(this)

        loadBillDetails()

        btnPrint.setOnClickListener {
            generatePdfAndPrint()
        }

        btnClose.setOnClickListener {
            finish()
        }
    }

    private fun loadBillDetails() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val response = RetrofitClient.api.getBillDetails(
                    "Bearer $token",
                    billId
                )

                val bill = response.bill
                val items = response.items

                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                val storeName = prefs.getString("store_name", "My Store")

                tvStoreName.text = storeName

                tvBillInfo.text =
                    "Invoice #${bill.bill_number}\nDate: ${bill.created_at}"

                val subtotal = bill.total_amount - bill.gst + bill.discount

                tvSubTotal.text = "Subtotal: ${CurrencyHelper.format(this@BillDetailsActivity, subtotal)}"
                tvGst.text = "GST: ${CurrencyHelper.format(this@BillDetailsActivity, bill.gst)}"
                tvDiscount.text = "Discount: ${CurrencyHelper.format(this@BillDetailsActivity, bill.discount)}"
                tvTotal.text = "Total: ${CurrencyHelper.format(this@BillDetailsActivity, bill.total_amount)}"

                rvBillItems.adapter = BillDetailsAdapter(items)

            } catch (e: Exception) {

                e.printStackTrace()

                Toast.makeText(
                    this@BillDetailsActivity,
                    "Failed to load bill details",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun generatePdfAndPrint() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val db = AppDatabase.getDatabase(this@BillDetailsActivity)

                val response = RetrofitClient.api.getBillDetails(
                    "Bearer $token",
                    billId
                )

                val bill = Bill(
                    id = response.bill.bill_id,
                    billNumber = response.bill.bill_number,
                    date = response.bill.created_at,
                    subTotal = response.bill.total_amount,
                    gst = response.bill.gst,
                    discount = response.bill.discount,
                    total = response.bill.total_amount,
                    paymentMethod = response.bill.payment_method
                )

                val billItems = response.items.map {
                    BillItem(
                        billId = response.bill.bill_id,
                        productId = it.shop_product_id,
                        productName = it.product_name,
                        price = it.price,
                        quantity = it.quantity,
                        subTotal = it.subtotal
                    )
                }

                val storeInfo = db.storeInfoDao().get()
                
                InvoicePdfGenerator.generatePdfFromBill(
                    context = this@BillDetailsActivity,
                    bill = bill,
                    billItems = billItems,
                    storeInfo = storeInfo
                )

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@BillDetailsActivity,
                    "Failed to generate invoice",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
