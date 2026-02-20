package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.util.PdfPrintAdapter
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager


class InvoiceActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var etGst: EditText
    private lateinit var etDiscount: EditText
    private lateinit var items: List<CartItem>

    private var isBillSaved = false
    private var savedBillId: Int = -1

    private lateinit var btnConfirm: Button
    private lateinit var btnPrint: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        etGst = findViewById(R.id.etGst)
        etDiscount = findViewById(R.id.etDiscount)

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val defaultGst = prefs.getFloat("default_gst", 0f)

        if (defaultGst != 0f) {
            etGst.setText(defaultGst.toString())
        }

        val rvItems = findViewById<RecyclerView>(R.id.rvInvoiceItems)
        tvTotal = findViewById(R.id.tvTotal)
        val tvBillInfo = findViewById<TextView>(R.id.tvBillInfo)

        btnConfirm = findViewById(R.id.btnConfirm)
        btnPrint = findViewById(R.id.btnPrint)
        val btnClose = findViewById<Button>(R.id.btnClose)

        btnPrint.isEnabled = false   // 🔥 disabled initially

        etGst = findViewById(R.id.etGst)
        etDiscount = findViewById(R.id.etDiscount)

        @Suppress("UNCHECKED_CAST")
        items = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("CART_ITEMS", ArrayList::class.java) as? List<CartItem>
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("CART_ITEMS") as? List<CartItem>
        } ?: emptyList()

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = InvoiceAdapter(items)

        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val billNo = System.currentTimeMillis().toString().takeLast(6)
        tvBillInfo.text = "Invoice #$billNo\nDate: $date"

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

            if (isBillSaved) {
                setResult(RESULT_OK)   // clear cart
            } else {
                setResult(RESULT_CANCELED)   // do NOT clear cart
            }

            finish()
        }
    }

    private fun calculateTotal() {
        val subTotal = items.sumOf { it.subTotal() }
        val gstPercent = etGst.text.toString().toDoubleOrNull() ?: 0.0
        val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

        val gstAmount = (subTotal * gstPercent) / 100
        val finalTotal = subTotal + gstAmount - discount

        tvTotal.text = "Total: ₹%.2f".format(finalTotal)
    }

    private fun saveBill() {

        if (isBillSaved) {
            Toast.makeText(this, "Bill already saved", Toast.LENGTH_SHORT).show()
            return
        }

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

            // 🔥 IMPORTANT FIX
            savedBillId = billId
            isBillSaved = true

            runOnUiThread {
                btnConfirm.isEnabled = false
                btnPrint.isEnabled = true
                Toast.makeText(this@InvoiceActivity, "Bill Saved Successfully", Toast.LENGTH_SHORT).show()
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
            val billItems = db.billDao().getItemsForBill(savedBillId)

            generatePdfFromBill(bill, billItems)
        }
    }

    private fun drawMultilineTextAddress(
        text: String,
        yStart: Int,
        maxWidth: Int,
        canvas: android.graphics.Canvas,
        paint: Paint
    ): Int {

        var y = yStart
        val words = text.split(" ")
        var line = ""

        for (word in words) {

            val testLine = if (line.isEmpty()) word else "$line $word"
            val width = paint.measureText(testLine)

            if (width <= maxWidth) {
                line = testLine
            } else {
                // Draw current line centered
                val lineWidth = paint.measureText(line)
                canvas.drawText(
                    line,
                    (canvas.width - lineWidth) / 2,
                    y.toFloat(),
                    paint
                )
                line = word
                y += 18
            }
        }

        if (line.isNotEmpty()) {
            val lineWidth = paint.measureText(line)
            canvas.drawText(
                line,
                (canvas.width - lineWidth) / 2,
                y.toFloat(),
                paint
            )
            y += 18
        }

        return y
    }

    private fun drawMultilineText(
        text: String,
        x: Float,
        yStart: Int,
        maxWidth: Int,
        canvas: android.graphics.Canvas,
        paint: Paint
    ): Int {

        var y = yStart
        val words = text.split(" ")
        var line = ""

        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            val width = paint.measureText(testLine)

            if (width < maxWidth) {
                line = testLine
            } else {
                canvas.drawText(line, x, y.toFloat(), paint)
                line = word
                y += 15
            }
        }

        if (line.isNotEmpty()) {
            canvas.drawText(line, x, y.toFloat(), paint)
            y += 15
        }

        return y
    }
    private fun generatePdfFromBill(
        bill: Bill,
        billItems: List<BillItem>
    ) {

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val printerType = prefs.getString("printer_layout", "80mm")
        val storeName = prefs.getString("store_name", "Easy Billing")
        val storeAddress = prefs.getString("store_address", "")
        val storePhone = prefs.getString("store_phone", "")
        val storeGstin = prefs.getString("store_gstin", "")
        val footerMessage = prefs.getString("invoice_footer", "Thank You! Visit Again")

        // ===== DESIGN SWITCHES =====
        val showGstin = prefs.getBoolean("show_gstin", true)
        val showPhone = prefs.getBoolean("show_phone", true)
        val showDiscount = prefs.getBoolean("show_discount", true)
        val roundOffEnabled = prefs.getBoolean("round_off", false)
        val centerHeader = prefs.getBoolean("center_header", true)

        val pageWidth = if (printerType == "A4") 595 else 300
        val leftMargin = if (printerType == "A4") 50f else 20f
        val rightMargin = leftMargin

        val itemHeight = billItems.size * 20
        val pageHeight = 800 + itemHeight

        val document = PdfDocument()
        val paint = Paint()

        val pageInfo = PdfDocument.PageInfo.Builder(
            pageWidth,
            pageHeight,
            1
        ).create()

        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        var y = 50

        fun drawLine() {
            canvas.drawLine(
                leftMargin,
                y.toFloat(),
                pageWidth - rightMargin,
                y.toFloat(),
                paint
            )
            y += 15
        }

        fun drawSeparator() {
            canvas.drawLine(
                30f,
                y.toFloat(),
                (pageWidth - 30).toFloat(),
                y.toFloat(),
                paint
            )
            y += 20
        }

        fun drawHeader(text: String, size: Float, bold: Boolean = false) {
            paint.textSize = size
            paint.isFakeBoldText = bold

            if (centerHeader) {
                val width = paint.measureText(text)
                canvas.drawText(text, (pageWidth - width) / 2, y.toFloat(), paint)
            } else {
                canvas.drawText(text, leftMargin, y.toFloat(), paint)
            }

            y += (size + 8).toInt()
        }

        // ================= HEADER =================

        drawHeader(storeName ?: "Easy Billing", 22f, true)

        paint.textSize = 12f
        paint.isFakeBoldText = false

        if (!storeAddress.isNullOrEmpty()) {
            y = drawMultilineTextAddress(
                storeAddress,
                y,
                pageWidth - 60,
                canvas,
                paint
            )
        }

        if (showPhone && !storePhone.isNullOrEmpty()) {
            drawHeader("Phone: $storePhone", 12f)
        }

        if (showGstin && !storeGstin.isNullOrEmpty()) {
            drawHeader("GSTIN: $storeGstin", 12f)
        }

        y += 10
        drawSeparator()

        // ================= BILL INFO =================

        paint.textSize = 11f
        paint.isFakeBoldText = false

        val invoiceText = "Invoice No: ${bill.billNumber}"
        canvas.drawText(invoiceText, leftMargin, y.toFloat(), paint)

        val dateText = "Date: ${bill.date}"
        val dateWidth = paint.measureText(dateText)
        canvas.drawText(
            dateText,
            pageWidth - dateWidth - rightMargin,
            y.toFloat(),
            paint
        )

        y += 25
        drawLine()

        // ================= TABLE HEADER =================

        paint.isFakeBoldText = true
        paint.textSize = 12f

        val colItem = leftMargin
        val colQty = pageWidth - 220f
        val colRate = pageWidth - 150f
        val colAmount = pageWidth - 40f   // right aligned anchor

        canvas.drawText("Item", colItem, y.toFloat(), paint)
        canvas.drawText("Qty", colQty, y.toFloat(), paint)
        canvas.drawText("Rate", colRate, y.toFloat(), paint)

        val amountHeaderWidth = paint.measureText("Amount")
        canvas.drawText(
            "Amount",
            colAmount - amountHeaderWidth,
            y.toFloat(),
            paint
        )

        y += 15
        drawLine()

        paint.isFakeBoldText = false
        paint.textSize = 11f

        // ================= ITEMS =================

        billItems.forEach {

            y = drawMultilineText(
                it.productName,
                colItem,
                y,
                (colQty - colItem - 10).toInt(),
                canvas,
                paint
            )

            canvas.drawText(
                it.quantity.toString(),
                colQty,
                (y - 15).toFloat(),
                paint
            )

            // RIGHT ALIGNED RATE
            val rateText = "%.2f".format(it.price)
            val rateWidth = paint.measureText(rateText)
            canvas.drawText(
                rateText,
                colRate + 50 - rateWidth,
                (y - 15).toFloat(),
                paint
            )

            // RIGHT ALIGNED AMOUNT
            val amountText = "%.2f".format(it.subTotal)
            val amountWidth = paint.measureText(amountText)
            canvas.drawText(
                amountText,
                colAmount - amountWidth,
                (y - 15).toFloat(),
                paint
            )

            y += 5
        }

        drawLine()

        // ================= TOTAL SECTION =================

        paint.textSize = 12f
        paint.isFakeBoldText = false

        fun drawRightAligned(label: String, value: Double) {
            canvas.drawText(label, colItem, y.toFloat(), paint)

            val text = "%.2f".format(value)
            val width = paint.measureText(text)

            canvas.drawText(
                text,
                colAmount - width,
                y.toFloat(),
                paint
            )

            y += 18
        }

        drawRightAligned("SubTotal", bill.subTotal)
        drawRightAligned("GST", bill.gst)

        if (showDiscount && bill.discount > 0) {
            drawRightAligned("Discount", bill.discount)
        }

        drawLine()

        paint.textSize = 16f
        paint.isFakeBoldText = true

        val finalTotal = if (roundOffEnabled) {
            kotlin.math.round(bill.total)
        } else {
            bill.total
        }

        val totalText = "%.2f".format(finalTotal)
        val totalWidth = paint.measureText(totalText)

        canvas.drawText("TOTAL", colItem, y.toFloat(), paint)
        canvas.drawText(
            totalText,
            colAmount - totalWidth,
            y.toFloat(),
            paint
        )

        y += 40

        // ================= FOOTER =================

        drawHeader(footerMessage ?: "Thank You! Visit Again", 12f)

        document.finishPage(page)

        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "Invoice_${bill.billNumber}.pdf"
        )

        document.writeTo(FileOutputStream(file))
        document.close()

        Toast.makeText(this, "Professional Invoice Created", Toast.LENGTH_LONG).show()

        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val printAdapter = PdfPrintAdapter(
            this,
            file.absolutePath,
            "1234",
            bill.billNumber.toLong()
        )

        printManager.print("Invoice", printAdapter, PrintAttributes.Builder().build())
    }
}