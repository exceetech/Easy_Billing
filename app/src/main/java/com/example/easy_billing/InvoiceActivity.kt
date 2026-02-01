package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.util.InvoiceDataHolder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import com.example.easy_billing.util.LastBillHolder
import com.example.easy_billing.util.PdfPrintAdapter
import java.io.File
import java.io.FileOutputStream

class InvoiceActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var etGst: EditText
    private lateinit var etDiscount: EditText
    private lateinit var items: List<CartItem>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        val rvItems = findViewById<RecyclerView>(R.id.rvInvoiceItems)
        tvTotal = findViewById(R.id.tvTotal)
        val tvBillInfo = findViewById<TextView>(R.id.tvBillInfo)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)

        etGst = findViewById(R.id.etGst)
        etDiscount = findViewById(R.id.etDiscount)

        items = InvoiceDataHolder.cartItems

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = InvoiceAdapter(items)

        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val billNo = System.currentTimeMillis().toString().takeLast(6)
        tvBillInfo.text = "Invoice #$billNo\nDate: $date"

        // Initial total
        calculateTotal()

        // Recalculate when GST / Discount changes
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                calculateTotal()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        etGst.addTextChangedListener(watcher)
        etDiscount.addTextChangedListener(watcher)

        btnConfirm.setOnClickListener {
            saveBill()
        }

        val btnPrint = findViewById<Button>(R.id.btnPrint)

        btnPrint.setOnClickListener {
            generatePdfAndPrint()
        }

        val btnClose = findViewById<Button>(R.id.btnClose)

        btnClose.setOnClickListener {
            finish()   // returns to Dashboard
        }
    }

    // ✅ THIS IS WHERE calculateTotal() BELONGS
    private fun calculateTotal() {
        val subTotal = items.sumOf { it.subTotal() }

        val gstPercent = etGst.text.toString().toDoubleOrNull() ?: 0.0
        val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

        val gstAmount = (subTotal * gstPercent) / 100
        val finalTotal = subTotal + gstAmount - discount

        tvTotal.text = "Total: ₹%.2f".format(finalTotal)
    }

    // ✅ SAVE BILL WITH GST & DISCOUNT
    private fun saveBill() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val billNumber = System.currentTimeMillis().toString().takeLast(6)
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

            val subTotal = items.sumOf { it.subTotal() }
            val gstPercent = etGst.text.toString().toDoubleOrNull() ?: 0.0
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

            val gstAmount = (subTotal * gstPercent) / 100
            val total = subTotal + gstAmount - discount

            val billId = db.billDao().insertBill(
                Bill(
                    billNumber = billNumber,
                    date = date,
                    subTotal = subTotal,
                    gst = gstAmount,
                    discount = discount,
                    total = total
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

            LastBillHolder.lastBillId = billId   // 👈 SAVE BILL ID

            InvoiceDataHolder.cartItems.clear()  // clear cart
            // DO NOT finish immediately
            Toast.makeText(this@InvoiceActivity, "Bill saved. You can print now.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun generatePdfAndPrint() {

        val billId = LastBillHolder.lastBillId
        if (billId == -1) {
            Toast.makeText(this, "No bill to print", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val bill = db.billDao().getBillById(billId)
            val billItems = db.billDao().getItemsForBill(billId)

            generatePdfFromBill(bill, billItems)
        }
    }

    private fun generatePdfFromBill(
        bill: Bill,
        billItems: List<BillItem>
    ) {
        val document = PdfDocument()
        val paint = Paint()

        val pageInfo = PdfDocument.PageInfo.Builder(300, 600, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        var y = 30
        paint.textSize = 14f
        canvas.drawText("Easy Billing", 80f, y.toFloat(), paint)
        y += 20

        paint.textSize = 10f
        canvas.drawText("Invoice #${bill.billNumber}", 10f, y.toFloat(), paint)
        y += 15
        canvas.drawText("Date: ${bill.date}", 10f, y.toFloat(), paint)
        y += 15

        canvas.drawLine(10f, y.toFloat(), 290f, y.toFloat(), paint)
        y += 15

        billItems.forEach {
            canvas.drawText(
                "${it.productName} x${it.quantity}  ₹${it.subTotal}",
                10f,
                y.toFloat(),
                paint
            )
            y += 15
        }

        y += 10
        canvas.drawLine(10f, y.toFloat(), 290f, y.toFloat(), paint)
        y += 15

        canvas.drawText("GST: ₹${bill.gst}", 10f, y.toFloat(), paint)
        y += 15
        canvas.drawText("Discount: ₹${bill.discount}", 10f, y.toFloat(), paint)
        y += 15
        paint.textSize = 12f
        canvas.drawText("TOTAL: ₹${bill.total}", 10f, y.toFloat(), paint)

        document.finishPage(page)

        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "Invoice_${bill.billNumber}.pdf"
        )

        document.writeTo(FileOutputStream(file))
        document.close()

        Toast.makeText(this, "Invoice PDF saved", Toast.LENGTH_LONG).show()
        printPdf(file)
    }

    private fun printPdf(file: File) {
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager

        val printAdapter = PdfPrintAdapter(this, file.absolutePath)

        printManager.print(
            "Invoice",
            printAdapter,
            PrintAttributes.Builder().build()
        )
    }
}