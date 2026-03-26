package com.example.easy_billing.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
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
}