package com.example.easy_billing

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.util.PdfPrintAdapter
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class BillDetailsActivity : AppCompatActivity() {

    private lateinit var tvBillInfo: TextView
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
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(billId)
            val items = db.billDao().getItemsForBill(billId)

            tvBillInfo.text = "Invoice #${bill.billNumber}\nDate: ${bill.date}"
            tvSubTotal.text = "Subtotal: ₹%.2f".format(bill.subTotal)
            tvGst.text = "GST: ₹%.2f".format(bill.gst)
            tvDiscount.text = "Discount: ₹%.2f".format(bill.discount)
            tvTotal.text = "Total: ₹%.2f".format(bill.total)

            rvBillItems.adapter = BillDetailsAdapter(items)
        }
    }

    private fun generatePdfAndPrint() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@BillDetailsActivity)
            val bill = db.billDao().getBillById(billId)
            val billItems = db.billDao().getItemsForBill(billId)

            generatePdfFromBill(bill, billItems)
        }
    }

    private fun generatePdfFromBill(bill: Bill, billItems: List<BillItem>) {
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

        canvas.drawText("Subtotal: ₹${bill.subTotal}", 10f, y.toFloat(), paint)
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

        try {
            document.writeTo(FileOutputStream(file))
            document.close()

            Toast.makeText(this, "Invoice PDF saved", Toast.LENGTH_LONG).show()

            val printManager = getSystemService(PRINT_SERVICE) as PrintManager
            val printAdapter = PdfPrintAdapter(this, file.absolutePath, "1234", bill.billNumber.toLong())

            printManager.print("Invoice", printAdapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating PDF", Toast.LENGTH_SHORT).show()
        }
    }
}