package com.example.easy_billing.util

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.StoreInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round

object InvoicePdfGenerator {

    fun generatePdfFromBill(
        context: Context,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?   // ✅ FROM ROOM
    ) {

        // ✅ UI SETTINGS ONLY (allowed)
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val footerMessage = prefs.getString("footer_message", "Thank You! Visit Again")
        val showPhone = prefs.getBoolean("show_phone", true)
        val showGstin = prefs.getBoolean("show_gstin", true)
        val showDiscount = prefs.getBoolean("show_discount", true)
        val roundOff = prefs.getBoolean("round_off", false)

        // ✅ STORE INFO FROM ROOM
        val storeName = storeInfo?.name ?: "My Store"
        val storeAddress = storeInfo?.address ?: ""
        val storePhone = storeInfo?.phone ?: ""
        val storeGstin = storeInfo?.gstin ?: ""

        val currencySymbol = CurrencyHelper.getCurrencySymbol(context)

        val pageWidth = 300f
        val leftMargin = 10f
        val rightMargin = pageWidth - 10f

        val document = PdfDocument()
        val paint = Paint()

        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 14f

        // ✅ SAFE HEIGHT
        val pageHeight = 1200 + billItems.size * 30

        val pageInfo = PdfDocument.PageInfo.Builder(
            pageWidth.toInt(),
            pageHeight,
            1
        ).create()

        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        var y = 40

        fun dashedLine() {
            val dashPaint = Paint()
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            canvas.drawLine(leftMargin, y.toFloat(), rightMargin, y.toFloat(), dashPaint)
            y += 18
        }

        fun centerText(text: String, size: Float, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface =
                if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                else Typeface.MONOSPACE

            val width = paint.measureText(text)
            canvas.drawText(text, (pageWidth - width) / 2, y.toFloat(), paint)
            y += size.toInt() + 6
        }

        // ================= HEADER =================

        centerText(storeName, 22f, true)

        if (storeAddress.isNotEmpty()) centerText(storeAddress, 14f)
        if (showPhone && storePhone.isNotEmpty()) centerText("Phone : $storePhone", 14f)
        if (showGstin && storeGstin.isNotEmpty()) centerText("GSTIN : $storeGstin", 14f)

        dashedLine()

        // ================= BILL INFO =================

        centerText("Invoice : ${bill.billNumber}", 14f)
        centerText("Date : ${bill.date}", 14f)

        dashedLine()

        // ================= TABLE HEADER =================

        val colItem = leftMargin
        val colQty = 115f
        val colRate = 165f
        val colAmount = rightMargin

        val itemColumnWidth = (colQty - colItem - 10).toInt()

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        val rateHeader = "Rate($currencySymbol)"
        val amtHeader = "Amt($currencySymbol)"

        canvas.drawText("Item", colItem, y.toFloat(), paint)
        canvas.drawText("Qty", colQty, y.toFloat(), paint)
        canvas.drawText(rateHeader, colRate, y.toFloat(), paint)

        val amtWidth = paint.measureText(amtHeader)
        canvas.drawText(amtHeader, colAmount - amtWidth, y.toFloat(), paint)

        y += 20
        dashedLine()

        paint.typeface = Typeface.MONOSPACE

        // ================= ITEMS =================

        billItems.forEach {

            val words = it.productName.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""

            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"

                if (paint.measureText(testLine) <= itemColumnWidth) {
                    currentLine = testLine
                } else {
                    lines.add(currentLine)
                    currentLine = word
                }
            }

            if (currentLine.isNotEmpty()) lines.add(currentLine)

            canvas.drawText(lines[0], colItem, y.toFloat(), paint)
            canvas.drawText(it.quantity.toString(), colQty, y.toFloat(), paint)

            val rateText = "%.2f".format(it.price)
            canvas.drawText(rateText, colRate, y.toFloat(), paint)

            val amountText = "%.2f".format(it.subTotal)
            val amountWidth = paint.measureText(amountText)
            canvas.drawText(amountText, colAmount - amountWidth, y.toFloat(), paint)

            y += 20

            for (i in 1 until lines.size) {
                canvas.drawText(lines[i], colItem, y.toFloat(), paint)
                y += 18
            }
        }

        dashedLine()

        // ================= SUMMARY =================

        val subTotal = billItems.sumOf { it.subTotal }

        canvas.drawText("SubTotal", colItem, y.toFloat(), paint)
        val sub = "$currencySymbol%.2f".format(subTotal)
        canvas.drawText(sub, colAmount - paint.measureText(sub), y.toFloat(), paint)
        y += 22

        canvas.drawText("GST", colItem, y.toFloat(), paint)
        val gst = "$currencySymbol%.2f".format(bill.gst)
        canvas.drawText(gst, colAmount - paint.measureText(gst), y.toFloat(), paint)
        y += 22

        if (showDiscount) {
            canvas.drawText("Discount", colItem, y.toFloat(), paint)
            val disc = "$currencySymbol%.2f".format(bill.discount)
            canvas.drawText(disc, colAmount - paint.measureText(disc), y.toFloat(), paint)
            y += 22
        }

        // ================= TOTAL =================

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 18f

        canvas.drawText("TOTAL", colItem, y.toFloat(), paint)

        var finalTotal = bill.total
        if (roundOff) finalTotal = round(finalTotal)

        val total = "$currencySymbol%.2f".format(finalTotal)
        canvas.drawText(total, colAmount - paint.measureText(total), y.toFloat(), paint)

        y += 40
        dashedLine()

        centerText(footerMessage ?: "Thank You! Visit Again", 14f)

        document.finishPage(page)

        // ================= SAVE FILE =================

        val shopNameSafe = storeName.replace(" ", "_")
        val dateString = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        val fileName =
            "${shopNameSafe}_${bill.billNumber}_${dateString}_${System.currentTimeMillis()}.pdf"

        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            fileName
        )

        val fileOutput = FileOutputStream(file)
        document.writeTo(fileOutput)
        fileOutput.close()
        document.close()

        // ================= PRINT =================

        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager

        val printAdapter = PdfPrintAdapter(
            context,
            file.absolutePath,
            "invoice",
            bill.billNumber.hashCode().toLong() // ✅ SAFE
        )

        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(fileName, printAdapter, printAttributes)
    }

    fun generateLedgerPdf(
        activity: Activity,
        storeInfo: StoreInfo?,   // 🔥 SAME AS BILL
        customerName: String,
        phone: String,
        rows: List<List<String>>,
        totalDebit: Double,
        totalCredit: Double,
        finalBalance: Double
    ){

        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        val document = PdfDocument()
        val paint = Paint()

        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 12f

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        var y = 50f

        // ================= HELPERS =================

        fun center(text: String, size: Float, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface =
                if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                else Typeface.MONOSPACE

            val width = paint.measureText(text)
            canvas.drawText(text, (pageWidth - width) / 2, y, paint)
            y += size + 10
        }

        fun line() {
            val dash = Paint()
            dash.pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
            canvas.drawLine(margin, y, pageWidth - margin, y, dash)
            y += 15
        }

        fun rightText(text: String, x: Float, y: Float) {
            val width = paint.measureText(text)
            canvas.drawText(text, x - width, y, paint)
        }


        // ================= HEADER (BILL STYLE) =================

        // ===== REPORT TITLE =====

        center("Credit / Debit Report", 22f, true)

        // 🔥 Fetch store info (same as bill)
        val prefs = activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // ================= STORE FROM ROOM =================

        val storeName = storeInfo?.name ?: "My Store"
        val storeAddress = storeInfo?.address ?: ""
        val storePhone = storeInfo?.phone ?: ""
        val storeGstin = storeInfo?.gstin ?: ""

        // ===== STORE DETAILS =====

        center(storeName, 18f, true)

        if (storeAddress.isNotEmpty()) {
            center(storeAddress, 14f)
        }

        if (storePhone.isNotEmpty()) {
            center("Shop Phone: $storePhone", 14f)
        }

        if (storeGstin.isNotEmpty()) {
            center("GSTIN: $storeGstin", 14f)
        }

        line()

        // ===== CUSTOMER DETAILS =====

        y += 5
        center("Customer Name: $customerName", 16f, true)
        center("Customer Phone: $phone", 14f)
        center("Date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}", 12f)
        y += 5

        line()

        // ================= TABLE SETUP =================

        val colDate = margin
        val colType = 170f
        val colDrRight = 360f
        val colCrRight = 440f
        val colBalRight = pageWidth - margin

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 12f

        canvas.drawText("Date", colDate, y, paint)
        canvas.drawText("Type", colType, y, paint)

        // ✅ ₹ ONLY IN HEADER
        rightText("Dr (₹)", colDrRight, y)
        rightText("Cr (₹)", colCrRight, y)
        rightText("Balance (₹)", colBalRight, y)

        y += 20
        line()

        paint.typeface = Typeface.MONOSPACE

        // ================= ROWS =================

        rows.forEach {

            canvas.drawText(it[0], colDate, y, paint)
            canvas.drawText(it[1], colType, y, paint)

            // ✅ REMOVE ₹ from values (clean columns)
            rightText(it[2].replace("₹", ""), colDrRight, y)
            rightText(it[3].replace("₹", ""), colCrRight, y)
            rightText(it[4].replace("₹", ""), colBalRight, y)

            y += 18

            if (y > pageHeight - 100) {
                document.finishPage(page)
            }
        }

        line()

        // ================= TOTALS =================

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 12f

        y += 15

        fun drawTotalRow(label: String, value: Double) {

            val labelText = label
            val valueText = "%.2f".format(value)

            val labelWidth = paint.measureText(labelText)
            val valueWidth = paint.measureText(valueText)

            val spacing = 15f

            val startX = colBalRight - (labelWidth + spacing + valueWidth)

            canvas.drawText(labelText, startX, y, paint)
            canvas.drawText(valueText, startX + labelWidth + spacing, y, paint)

            y += 20
        }

        drawTotalRow("Total Debit:", totalDebit)
        drawTotalRow("Total Credit:", totalCredit)

        paint.textSize = 14f
        drawTotalRow("Final Balance:", finalBalance)

        y += 10
        line()

        center("Thank You!", 14f)

        document.finishPage(page)

        // ================= SAVE =================

        val fileName = "${phone}_${System.currentTimeMillis()}.pdf"

        val file = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: activity.filesDir,
            fileName
        )

        try {
            FileOutputStream(file).use {
                document.writeTo(it)
            }
            document.close()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, "PDF save failed", Toast.LENGTH_SHORT).show()
            return
        }

        // ================= PRINT =================

        try {
            val printManager = activity.getSystemService(PrintManager::class.java)

            val printAdapter = PdfPrintAdapter(
                activity,
                file.absolutePath,
                "ledger",
                System.currentTimeMillis()
            )

            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            printManager.print(fileName, printAdapter, attributes)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, "Print failed", Toast.LENGTH_SHORT).show()
        }
    }
}