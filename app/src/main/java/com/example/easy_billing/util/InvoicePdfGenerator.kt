package com.example.easy_billing.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round

object InvoicePdfGenerator {

    fun generatePdfFromBill(
        context: Context,
        bill: Bill,
        billItems: List<BillItem>
    ) {

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val storeName = prefs.getString("store_name", "")
        val storeAddress = prefs.getString("store_address", "")
        val storePhone = prefs.getString("store_phone", "")
        val storeGstin = prefs.getString("store_gstin", "")
        val footerMessage = prefs.getString("footer_message", "Thank You! Visit Again")

        val showLogo = prefs.getBoolean("show_logo", true)
        val showGstin = prefs.getBoolean("show_gstin", true)
        val showPhone = prefs.getBoolean("show_phone", true)
        val showDiscount = prefs.getBoolean("show_discount", true)
        val roundOff = prefs.getBoolean("round_off", false)

        val pageWidth = 300f
        val leftMargin = 10f
        val rightMargin = pageWidth - 10f

        val document = PdfDocument()
        val paint = Paint()

        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 14f

        val pageHeight = 900 + billItems.size * 22

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

            canvas.drawLine(
                leftMargin,
                y.toFloat(),
                rightMargin,
                y.toFloat(),
                dashPaint
            )

            y += 18
        }

        fun centerText(text: String, size: Float, bold: Boolean = false) {

            paint.textSize = size
            paint.typeface =
                if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                else Typeface.MONOSPACE

            val width = paint.measureText(text)

            canvas.drawText(
                text,
                (pageWidth - width) / 2,
                y.toFloat(),
                paint
            )

            y += size.toInt() + 6
        }

        // HEADER
        centerText(storeName ?: "", 22f, true)

        if (!storeAddress.isNullOrEmpty())
            centerText(storeAddress, 14f)

        if (showPhone && !storePhone.isNullOrEmpty())
            centerText("Phone : $storePhone", 14f)

        if (showGstin && !storeGstin.isNullOrEmpty())
            centerText("GSTIN : $storeGstin", 14f)

        dashedLine()

        // INVOICE INFO
        centerText("Invoice : ${bill.billNumber}", 14f)
        centerText("Date : ${bill.date}", 14f)

        dashedLine()

        val colItem = leftMargin
        val colQty = 115f
        val colRate = 165f
        val colAmount = rightMargin

        val itemColumnWidth = (colQty - colItem - 10).toInt()

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        canvas.drawText("Item", colItem, y.toFloat(), paint)
        canvas.drawText("Qty", colQty, y.toFloat(), paint)
        canvas.drawText("Rate", colRate, y.toFloat(), paint)

        val amtHeader = "Amt"
        val amtWidth = paint.measureText(amtHeader)

        canvas.drawText(
            amtHeader,
            colAmount - amtWidth,
            y.toFloat(),
            paint
        )

        y += 20

        dashedLine()

        paint.typeface = Typeface.MONOSPACE

        // ITEMS
        billItems.forEach {

            val words = it.productName.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""

            for (word in words) {

                val testLine =
                    if (currentLine.isEmpty()) word
                    else "$currentLine $word"

                val width = paint.measureText(testLine)

                if (width <= itemColumnWidth) {
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

            canvas.drawText(
                amountText,
                colAmount - amountWidth,
                y.toFloat(),
                paint
            )

            y += 20

            for (i in 1 until lines.size) {

                canvas.drawText(
                    lines[i],
                    colItem,
                    y.toFloat(),
                    paint
                )

                y += 18
            }
        }

        dashedLine()

        // SUBTOTAL
        val subTotal = billItems.sumOf { it.subTotal }

        canvas.drawText("SubTotal", colItem, y.toFloat(), paint)

        val sub = "%.2f".format(subTotal)
        val subWidth = paint.measureText(sub)

        canvas.drawText(
            sub,
            colAmount - subWidth,
            y.toFloat(),
            paint
        )

        y += 22

        // GST
        canvas.drawText("GST", colItem, y.toFloat(), paint)

        val gst = "%.2f".format(bill.gst)
        val gstWidth = paint.measureText(gst)

        canvas.drawText(
            gst,
            colAmount - gstWidth,
            y.toFloat(),
            paint
        )

        y += 22

        // DISCOUNT
        if (showDiscount) {

            canvas.drawText("Discount", colItem, y.toFloat(), paint)

            val disc = "%.2f".format(bill.discount)
            val discWidth = paint.measureText(disc)

            canvas.drawText(
                disc,
                colAmount - discWidth,
                y.toFloat(),
                paint
            )

            y += 22
        }

        // TOTAL
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 18f

        canvas.drawText("TOTAL", colItem, y.toFloat(), paint)

        var finalTotal = bill.total

        if (roundOff) {
            finalTotal = round(finalTotal)
        }

        val total = "%.2f".format(finalTotal)
        val totalWidth = paint.measureText(total)

        canvas.drawText(
            total,
            colAmount - totalWidth,
            y.toFloat(),
            paint
        )

        y += 40

        dashedLine()

        centerText(footerMessage ?: "Thank You! Visit Again", 14f)

        document.finishPage(page)

        val shopName = (storeName ?: "Shop").replace(" ", "_")

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateString = dateFormat.format(Date())

        val fileName =
            "${shopName}_${bill.billNumber}_${dateString}_${System.currentTimeMillis()}.pdf"

        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            fileName
        )

        val fileOutput = FileOutputStream(file)
        document.writeTo(fileOutput)
        fileOutput.close()
        document.close()

        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager

        val printAdapter = PdfPrintAdapter(
            context,
            file.absolutePath,
            "invoice",
            bill.billNumber.toLong()
        )

        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(
            fileName,
            printAdapter,
            printAttributes
        )
    }
}