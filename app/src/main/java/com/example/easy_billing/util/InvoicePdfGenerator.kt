package com.example.easy_billing.util

import android.app.Activity
import android.content.Context
import com.example.easy_billing.R
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.example.easy_billing.ShopManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.GstSalesInvoice
import com.example.easy_billing.db.StoreInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round

object InvoicePdfGenerator {

    /**
     * Single print engine for both GST modes.
     *
     * [gstScheme] is the GST mode **saved with the invoice** (from
     * [com.example.easy_billing.db.GstSalesInvoice.gstScheme]) — NOT the
     * current shop setting. This guarantees historical accuracy: an old
     * Regular-GST bill always reprints as Regular even if the shop has
     * since switched to Composition, and vice-versa.
     *
     * No GST is recalculated here. Regular bills render the per-line tax
     * breakdown and CGST/SGST/IGST totals already persisted on [bill] /
     * [billItems]; Composition bills render a plain amount-only layout
     * with no tax columns or tax summary anywhere.
     */
    fun generatePdfFromBill(
        context: Context,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstScheme: String? = null,
        gstInvoice: GstSalesInvoice? = null,
        printerLayout: String = "80mm",
        // Additive, defaults to true so every existing call site (print
        // button flows) behaves byte-for-byte as before. Only the new
        // "send to customer" flow passes false, to get the saved File
        // back without popping the system print dialog.
        printAfterSave: Boolean = true
    ): File {
        // Only branches when printerLayout == "A4" — every other value
        // (including the "80mm" default) falls through to the exact same
        // pageWidth/pageInfo/PrintAttributes code that already existed, so
        // the 80mm path is byte-for-byte unchanged.
        val isA4 = printerLayout.equals("A4", ignoreCase = true)

        // ── Resolve GST mode from the value saved with the invoice ──
        // Fall back (only when scheme is genuinely missing) to the
        // amounts already stored on the bill — never to product or
        // current-shop inference.
        val isComposition = when {
            !gstScheme.isNullOrBlank() ->
                gstScheme.contains("compos", ignoreCase = true)
            else ->
                bill.gst <= 0.0 &&
                    bill.cgstAmount <= 0.0 &&
                    bill.sgstAmount <= 0.0 &&
                    bill.igstAmount <= 0.0
        }

        // ✅ UI SETTINGS ONLY (allowed)
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val footerMessage = prefs.getString("footer_message", context.getString(R.string.pdf_footer_default_message))
        val showPhone = prefs.getBoolean("show_phone", true)
        val showGstin = prefs.getBoolean("show_gstin", true)
        val showDiscount = prefs.getBoolean("show_discount", true)
        val roundOff = prefs.getBoolean("round_off", false)

        // ✅ STORE INFO FROM ROOM
        val storeName = storeInfo?.name ?: context.getString(R.string.invoice_pdf_store_default_name)
        val storeAddress = storeInfo?.address ?: ""
        val storePhone = storeInfo?.phone ?: ""
        val storeGstin = storeInfo?.gstin ?: ""

        val currencySymbol = CurrencyHelper.getCurrencySymbol(context)

        val document = PdfDocument()

        // A4 gets its own dedicated bordered-table design (see
        // drawA4InvoicePages below). The 80mm thermal path keeps its exact
        // dimensions/spacing untouched below — only its typography changed
        // (per explicit request) from monospace to the app's Google Sans
        // family + serif accents, matching the visual style approved for
        // the A4 design. Never change pageWidth, margins, or any y-advance
        // amount in the code below — only Typeface/color/line-style.
        if (isA4) {
            drawA4InvoicePages(
                context, document, bill, billItems, storeInfo, gstInvoice,
                isComposition, footerMessage, roundOff, currencySymbol, showDiscount
            )
            return saveAndPrint(context, document, storeName, bill, isA4 = true, printAfterSave = printAfterSave)
        }

        // 300f is the original, untouched 80mm thermal width.
        val pageWidth = 300f
        val leftMargin = 10f
        val rightMargin = pageWidth - 10f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val receiptFontRegular = ResourcesCompat.getFont(context, R.font.googlesans_regular) ?: Typeface.DEFAULT
        val receiptFontMedium = ResourcesCompat.getFont(context, R.font.googlesans_medium) ?: Typeface.DEFAULT_BOLD
        val receiptFontSerifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val discountColor80 = Color.parseColor("#A32D2D")
        val mutedColor80 = Color.parseColor("#5A5A55")
        val inkColor80 = Color.BLACK

        paint.typeface = receiptFontRegular
        paint.color = inkColor80
        paint.textSize = 14f

        // ✅ SAFE HEIGHT — Regular bills print extra per-line tax rows,
        // so they need more vertical space per item than Composition.
        val perItemHeight = if (isComposition) 90 else 150
        val pageHeight = 1500 + billItems.size * perItemHeight

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
            dashPaint.color = mutedColor80
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            canvas.drawLine(leftMargin, y.toFloat(), rightMargin, y.toFloat(), dashPaint)
            y += 18
        }

        // Solid rule — used at the two places that read as real section
        // boundaries (item table header, above the grand total) instead of
        // the softer dashed break used everywhere else.
        fun solidLine(strokeWidth: Float = 1.2f) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            linePaint.color = inkColor80
            linePaint.strokeWidth = strokeWidth
            canvas.drawLine(leftMargin, y.toFloat(), rightMargin, y.toFloat(), linePaint)
            y += 18
        }

        fun centerText(text: String, size: Float, bold: Boolean = false, serif: Boolean = false, color: Int = inkColor80) {
            paint.textSize = size
            paint.color = color
            paint.typeface = when {
                serif -> receiptFontSerifBold
                bold -> receiptFontMedium
                else -> receiptFontRegular
            }

            val width = paint.measureText(text)
            canvas.drawText(text, (pageWidth - width) / 2, y.toFloat(), paint)
            y += size.toInt() + 6
        }

        // Right-aligned text at current baseline (does not advance y).
        fun rightText(text: String, size: Float = 14f, bold: Boolean = false, color: Int = inkColor80) {
            paint.textSize = size
            paint.color = color
            paint.typeface = if (bold) receiptFontMedium else receiptFontRegular
            canvas.drawText(text, rightMargin - paint.measureText(text), y.toFloat(), paint)
        }

        fun leftText(text: String, size: Float = 14f, bold: Boolean = false, color: Int = inkColor80) {
            paint.textSize = size
            paint.color = color
            paint.typeface = if (bold) receiptFontMedium else receiptFontRegular
            canvas.drawText(text, leftMargin, y.toFloat(), paint)
        }

        // ================= HEADER =================

        centerText(storeName, 22f, serif = true)

        // Document title reflects the saved GST mode.
        if (!isComposition) centerText(context.getString(R.string.invoice_pdf_tax_invoice_title), 14f, bold = true)

        if (storeAddress.isNotEmpty()) centerText(storeAddress, 14f, color = mutedColor80)
        if (showPhone && storePhone.isNotEmpty()) centerText("Phone : $storePhone", 14f, color = mutedColor80)
        if (showGstin && storeGstin.isNotEmpty()) centerText("GSTIN : $storeGstin", 14f, color = mutedColor80)

        dashedLine()

        // ================= BILL INFO =================

        centerText("Invoice : ${bill.billNumber}", 14f)
        centerText("Date : ${bill.date}", 14f)
        centerText("GST Type : ${if (isComposition) "COMPOSITION" else "REGULAR"}", 14f)

        dashedLine()

        // ================= CUSTOMER DETAILS =================
        // Printed exactly as captured during invoice creation (B2B / B2C).
        // Falls back gracefully to the legacy Bill fields when the GST
        // invoice row is unavailable. Only non-blank fields are shown.
        run {
            val isB2B = (gstInvoice?.invoiceType ?: bill.customerType)
                .equals("B2B", ignoreCase = true)

            centerText("Customer Type : ${if (isB2B) "B2B" else "B2C"}", 14f)

            // Helper to emit a "Label : value" line only when value exists.
            fun detail(label: String, value: String?) {
                if (!value.isNullOrBlank()) centerText("$label : $value", 13f)
            }

            if (isB2B) {
                detail(context.getString(R.string.invoice_pdf_detail_business), gstInvoice?.businessName)
                detail(context.getString(R.string.invoice_pdf_detail_name), gstInvoice?.customerName)
                detail(context.getString(R.string.invoice_pdf_detail_phone), gstInvoice?.customerPhone)
                detail(context.getString(R.string.invoice_pdf_detail_gstin), gstInvoice?.customerGst ?: bill.customerGstin)
                detail(context.getString(R.string.invoice_pdf_detail_state), gstInvoice?.customerState)
            } else {
                detail("Name", gstInvoice?.customerName)
                detail("Phone", gstInvoice?.customerPhone)
                detail("State", gstInvoice?.customerState)
            }

            dashedLine()
        }

        // ================= TABLE HEADER =================

        val colItem = leftMargin
        val colAmount = rightMargin

        paint.typeface = receiptFontMedium
        paint.color = inkColor80
        paint.textSize = 14f

        val amtHeader = "Amt($currencySymbol)"
        canvas.drawText(context.getString(R.string.invoice_pdf_item_description_header), colItem, y.toFloat(), paint)
        canvas.drawText(amtHeader, colAmount - paint.measureText(amtHeader), y.toFloat(), paint)

        y += 20
        solidLine()

        paint.typeface = receiptFontRegular

        // ================= ITEMS =================
        // Composition → name + (qty × rate) + amount    (NO tax columns)
        // Regular     → name + taxable + GST % + GST amount + line total

        billItems.forEach {

            // ✅ NAME + VARIANT
            val displayName = if (!it.variant.isNullOrBlank()) {
                "${it.productName} (${it.variant})"
            } else {
                it.productName
            }

            // ================= FORMAT =================

            val qtyText = if (it.quantity % 1 == 0.0) {
                it.quantity.toInt().toString()
            } else {
                String.format("%.2f", it.quantity).trimEnd('0').trimEnd('.')
            }

            val unit = when (it.unit.lowercase()) {
                "kilogram" -> "kg"
                "gram" -> "g"
                "litre" -> "L"
                "millilitre" -> "ml"
                "piece" -> "pc"
                else -> it.unit
            }

            val rateText = "$currencySymbol%.2f/$unit".format(it.price)

            // ================= LINE 1 (NAME) =================
            paint.typeface = receiptFontMedium
            paint.color = inkColor80
            paint.textSize = 14f
            canvas.drawText(displayName, colItem, y.toFloat(), paint)
            y += 20

            paint.typeface = receiptFontRegular
            paint.textSize = 13f

            // Per-line detail (Option A): gross amount → its share of the bill
            // discount → net taxable → net GST → net total. Every value comes
            // straight from what was saved, so each line foots and the per-line
            // GST equals the bill GST.
            val rawGross     = it.price * it.quantity
            val netTaxable   = it.taxableValue
            val hasDiscount  = netTaxable < rawGross - 0.01
            val grossTaxable = if (hasDiscount) rawGross else netTaxable
            val lineDiscount = grossTaxable - netTaxable
            val gstAmt       = it.cgstAmount + it.sgstAmount + it.igstAmount
            val lineTotal    = netTaxable + gstAmt

            leftText("$qtyText × $rateText", 13f, color = mutedColor80)
            rightText("$currencySymbol%.2f".format(grossTaxable), 13f, color = mutedColor80)
            y += 18

            if (lineDiscount >= 0.01) {
                leftText(context.getString(R.string.invoice_pdf_discount_label), 13f, color = discountColor80)
                rightText("- $currencySymbol%.2f".format(lineDiscount), 13f, color = discountColor80)
                y += 18
            }

            if (isComposition) {
                // Composition: no GST; total is the (discounted) net value.
                rightText("Total $currencySymbol%.2f".format(netTaxable), 13f, bold = true)
                y += 22
            } else {
                val gstPctText =
                    if (it.gstRate % 1 == 0.0) "${it.gstRate.toInt()}%"
                    else String.format("%.2f%%", it.gstRate)

                leftText(context.getString(R.string.invoice_pdf_taxable_label), 13f, color = mutedColor80)
                rightText("$currencySymbol%.2f".format(netTaxable), 13f, color = mutedColor80)
                y += 18

                leftText("GST $gstPctText", 13f, color = mutedColor80)
                rightText("$currencySymbol%.2f".format(gstAmt), 13f, color = mutedColor80)
                y += 18

                rightText("Total $currencySymbol%.2f".format(lineTotal), 13f, bold = true)
                y += 22
            }

            // ================= SEPARATOR =================
            val linePaint = Paint()
            linePaint.color = Color.LTGRAY
            linePaint.strokeWidth = 1f
            canvas.drawLine(colItem, y.toFloat(), colAmount, y.toFloat(), linePaint)
            y += 16
        }

        dashedLine()

        // ================= SUMMARY =================

        paint.typeface = receiptFontRegular
        paint.color = inkColor80
        paint.textSize = 14f

        // Per line already shows gross → discount → net taxable, so the summary
        // totals the NET taxable directly (no separate bill-discount line).
        val taxable  = billItems.sumOf { it.taxableValue }
        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount

        leftText(if (isComposition) context.getString(R.string.invoice_pdf_sub_total_label) else context.getString(R.string.invoice_pdf_taxable_amount_label), 14f)
        rightText("$currencySymbol%.2f".format(taxable), 14f)
        y += 22

        if (!isComposition) {
            // Intra-state → CGST + SGST.  Inter-state → IGST only.
            if (bill.igstAmount > 0.0) {
                leftText(context.getString(R.string.invoice_pdf_igst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.igstAmount), 14f)
                y += 22
            } else {
                leftText(context.getString(R.string.invoice_pdf_cgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.cgstAmount), 14f)
                y += 22
                leftText(context.getString(R.string.invoice_pdf_sgst_label), 14f)
                rightText("$currencySymbol%.2f".format(bill.sgstAmount), 14f)
                y += 22
            }
        }

        var finalTotal = bill.total
        if (roundOff) finalTotal = round(finalTotal)

        // Round-off line so Taxable + tax (+ round off) == TOTAL exactly.
        val computed  = if (isComposition) taxable else taxable + totalTax
        val roundDiff = finalTotal - computed
        if (kotlin.math.abs(roundDiff) >= 0.01) {
            leftText(context.getString(R.string.invoice_pdf_round_off_label), 14f)
            rightText("$currencySymbol%.2f".format(roundDiff), 14f)
            y += 22
        }

        // ================= TOTAL =================
        // A solid rule (not dashed) right above the grand total, same
        // treatment as the item table header — this is the one number on
        // the receipt that should read as final.
        solidLine(1.4f)

        paint.typeface = receiptFontMedium
        paint.color = inkColor80
        paint.textSize = 18f

        canvas.drawText(context.getString(R.string.invoice_pdf_total_header), colItem, y.toFloat(), paint)

        val total = "$currencySymbol%.2f".format(finalTotal)
        canvas.drawText(total, colAmount - paint.measureText(total), y.toFloat(), paint)

        y += 40
        dashedLine()

        // Payment method (Cash / Card / UPI …) — just before the footer.
        if (bill.paymentMethod.isNotBlank()) {
            centerText("Paid Through : ${bill.paymentMethod}", 14f, true)
            y += 4
        }

        // Mandatory composition declaration (only on composition bills).
        if (isComposition) {
            centerText(
                context.getString(R.string.invoice_pdf_composition_declaration_1),
                11f,
                color = mutedColor80
            )
            centerText(
                context.getString(R.string.invoice_pdf_composition_declaration_2),
                11f,
                color = mutedColor80
            )
            y += 6
        }

        centerText(footerMessage ?: context.getString(R.string.invoice_pdf_footer_default), 14f, color = mutedColor80)

        document.finishPage(page)

        // ================= SAVE FILE =================

        val shopNameSafe = storeName
            .trim()
            .replace("\\s+".toRegex(), "_")
            .ifEmpty { "Store" }

        val billNoSafe = bill.billNumber
            ?.trim()
            ?.ifEmpty { "NA" } ?: "NA"

        val dateString = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        val baseName = listOf(shopNameSafe, billNoSafe, dateString)
            .filter { it.isNotBlank() }
            .joinToString("_")

        val fileName = "$baseName.pdf"

        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            fileName
        )

        FileOutputStream(file).use {
            document.writeTo(it)
        }
        document.close()

        // ================= PRINT =================
        // Gated by printAfterSave — false only for the "send to customer"
        // flow, which wants the saved File back without popping the
        // system print dialog. Every existing call site defaults to
        // true, so this block still always runs exactly as before them.
        if (printAfterSave) {
            val printManager = context.getSystemService(PrintManager::class.java)

            val printAdapter = PdfPrintAdapter(
                context,
                file.absolutePath,
                baseName
            )

            // 80mm keeps UNKNOWN_PORTRAIT exactly as before (untouched). A4
            // declares the standard ISO_A4 media size so the print
            // service/dialog treats it as a real A4 page.
            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(if (isA4) PrintAttributes.MediaSize.ISO_A4 else PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            printManager.print(baseName, printAdapter, printAttributes)
        }

        return file
    }

    /**
     * Save + print tail used only by the A4 path — the 80mm path keeps its
     * own inline save/print code untouched above.
     */
    private fun saveAndPrint(
        context: Context,
        document: PdfDocument,
        storeName: String,
        bill: Bill,
        isA4: Boolean,
        printAfterSave: Boolean = true
    ): File {
        val shopNameSafe = storeName.trim().replace("\\s+".toRegex(), "_").ifEmpty { "Store" }
        val billNoSafe = bill.billNumber?.trim()?.ifEmpty { "NA" } ?: "NA"
        val dateString = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val baseName = listOf(shopNameSafe, billNoSafe, dateString).filter { it.isNotBlank() }.joinToString("_")
        val fileName = "$baseName.pdf"

        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        if (printAfterSave) {
            val printManager = context.getSystemService(PrintManager::class.java)
            val printAdapter = PdfPrintAdapter(context, file.absolutePath, baseName)

            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(if (isA4) PrintAttributes.MediaSize.ISO_A4 else PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            printManager.print(baseName, printAdapter, printAttributes)
        }

        return file
    }

    /**
     * Dedicated A4 invoice design: teal header band, billed-to/details
     * cards, a bordered zebra-striped item table, and a solid grand-total
     * pill. Uses the app's bundled Google Sans font family (the same
     * family used in the outgoing HTML emails) with a system serif for
     * the store-name / invoice-number / total accents (substituting the
     * emails' 'Marcellus', which isn't embeddable on Android).
     *
     * Fully independent of the 80mm thermal drawing code above — sharing
     * only the final save/print step.
     */
    private fun drawA4InvoicePages(
        context: Context,
        document: PdfDocument,
        bill: Bill,
        billItems: List<BillItem>,
        storeInfo: StoreInfo?,
        gstInvoice: GstSalesInvoice?,
        isComposition: Boolean,
        footerMessage: String?,
        roundOff: Boolean,
        currencySymbol: String,
        showDiscount: Boolean
    ) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 32f

        val fontRegular = ResourcesCompat.getFont(context, R.font.googlesans_regular) ?: Typeface.DEFAULT
        val fontMedium = ResourcesCompat.getFont(context, R.font.googlesans_medium) ?: Typeface.DEFAULT_BOLD
        val fontSemibold = ResourcesCompat.getFont(context, R.font.googlesans_semibold) ?: Typeface.DEFAULT_BOLD
        val fontSerif = Typeface.SERIF

        val teal = Color.parseColor("#0F6E56")
        val champagne = Color.parseColor("#F3ECDD")
        val champagneLabel = Color.parseColor("#8A6526")
        val zebra = Color.parseColor("#FAF8F3")
        val muted = Color.parseColor("#8A8272")
        val ink = Color.parseColor("#1A1A18")
        val hairline = Color.parseColor("#E4DFD0")

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var y = 0f

        val storeName = storeInfo?.name ?: context.getString(R.string.invoice_pdf_store_default_name)

        fun drawHeaderBand(continuation: Boolean) {
            if (!continuation) {
                // Store name — centered, serif, no color band.
                paint.color = ink
                paint.typeface = fontSerif
                paint.textSize = 21f
                canvas.drawText(storeName, (pageWidth - paint.measureText(storeName)) / 2f, 44f, paint)

                paint.color = muted
                paint.typeface = fontRegular
                paint.textSize = 10f
                val addrLine = listOfNotNull(
                    storeInfo?.address?.takeIf { it.isNotBlank() },
                    storeInfo?.phone?.takeIf { it.isNotBlank() }?.let { "Phone $it" },
                    storeInfo?.gstin?.takeIf { it.isNotBlank() }?.let { "GSTIN $it" }
                ).joinToString("   ·   ")
                canvas.drawText(addrLine, (pageWidth - paint.measureText(addrLine)) / 2f, 62f, paint)

                paint.color = teal
                paint.strokeWidth = 1.6f
                canvas.drawLine(margin, 78f, pageWidth - margin, 78f, paint)

                // Outlined pill — "TAX INVOICE" / "INVOICE" — left of the meta row.
                val pillLabel = if (isComposition) "INVOICE" else "TAX INVOICE"
                paint.typeface = fontMedium
                paint.textSize = 10f
                val pillTextWidth = paint.measureText(pillLabel)
                val pillPadX = 12f
                val pillTop = 94f
                val pillHeight = 22f
                val pillWidth = pillTextWidth + pillPadX * 2
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = teal
                canvas.drawRoundRect(margin, pillTop, margin + pillWidth, pillTop + pillHeight, pillHeight / 2, pillHeight / 2, paint)
                paint.style = Paint.Style.FILL
                paint.color = teal
                canvas.drawText(pillLabel, margin + pillPadX, pillTop + pillHeight / 2 + 3.5f, paint)

                // Invoice no. / Date — each column's label and value are
                // centered under a shared column center, not left-started,
                // so they read as two balanced blocks rather than a
                // left-ragged list.
                val metaRowLabelY = pillTop + 8f
                val metaRowValueY = pillTop + 22f
                val colDateCenter = pageWidth - margin - 55f
                val colInvoiceCenter = colDateCenter - 110f

                fun metaColumnCentered(centerX: Float, label: String, value: String) {
                    paint.typeface = fontMedium
                    paint.textSize = 8.5f
                    paint.color = muted
                    canvas.drawText(label, centerX - paint.measureText(label) / 2f, metaRowLabelY, paint)
                    paint.typeface = fontRegular
                    paint.textSize = 11.5f
                    paint.color = ink
                    canvas.drawText(value, centerX - paint.measureText(value) / 2f, metaRowValueY, paint)
                }

                metaColumnCentered(colInvoiceCenter, "INVOICE NO.", bill.billNumber)
                metaColumnCentered(colDateCenter, "DATE", bill.date)

                y = pillTop + pillHeight + 24f
            } else {
                paint.color = ink
                paint.typeface = fontMedium
                paint.textSize = 11f
                canvas.drawText("$storeName — continued", margin, 30f, paint)
                paint.color = teal
                paint.strokeWidth = 1.6f
                canvas.drawLine(margin, 40f, pageWidth - margin, 40f, paint)
                y = 40f + 26f
            }
        }

        drawHeaderBand(false)

        // ── Billed to / details cards ──
        // Both cards share one top-anchored rhythm — same label baseline,
        // same first content line, same line height — and the card height
        // is derived from whichever side has more lines, so neither box is
        // ever taller/shorter than what it needs and content never
        // overflows the champagne background (which is what read as
        // "misaligned" when the height was a fixed guess).
        run {
            val boxTop = y
            val boxW = (pageWidth - margin * 2 - 14f) / 2f
            val padX = 14f
            val labelTopPad = 16f
            val labelToFirstLine = 32f
            val lineHeight = 14f
            val padBottom = 14f

            val isB2B = (gstInvoice?.invoiceType ?: bill.customerType).equals("B2B", ignoreCase = true)

            val billedLines = if (isB2B) {
                listOfNotNull(gstInvoice?.businessName, gstInvoice?.customerName, gstInvoice?.customerGst ?: bill.customerGstin, gstInvoice?.customerState)
            } else {
                listOfNotNull(gstInvoice?.customerName, gstInvoice?.customerPhone, gstInvoice?.customerState)
            }.ifEmpty { listOf(context.getString(R.string.invoice_pdf_walk_in_customer)) }

            val detailLines = listOfNotNull(
                bill.placeOfSupply.takeIf { it.isNotBlank() }?.let { "Place of supply: $it" },
                bill.paymentMethod.takeIf { it.isNotBlank() }?.let { "Payment: $it" },
                "Type: ${if (isB2B) "B2B" else "B2C"}"
            )

            val maxLines = maxOf(billedLines.size, detailLines.size)
            val boxH = labelToFirstLine + (maxLines - 1) * lineHeight + padBottom

            paint.style = Paint.Style.FILL
            paint.color = champagne
            canvas.drawRoundRect(margin, boxTop, margin + boxW, boxTop + boxH, 8f, 8f, paint)
            canvas.drawRoundRect(margin + boxW + padX, boxTop, margin + boxW + padX + boxW, boxTop + boxH, 8f, 8f, paint)

            val col1X = margin + 12f
            val col2X = margin + boxW + padX + 12f
            val labelY = boxTop + labelTopPad
            val firstLineY = boxTop + labelToFirstLine

            paint.color = champagneLabel
            paint.typeface = fontSemibold
            paint.textSize = 8.5f
            canvas.drawText("BILLED TO", col1X, labelY, paint)
            canvas.drawText("DETAILS", col2X, labelY, paint)

            paint.color = ink
            paint.typeface = fontRegular
            paint.textSize = 10f
            billedLines.forEachIndexed { i, line -> canvas.drawText(line, col1X, firstLineY + i * lineHeight, paint) }
            detailLines.forEachIndexed { i, line -> canvas.drawText(line, col2X, firstLineY + i * lineHeight, paint) }

            y = boxTop + boxH + 22f
        }

        // ── Table columns ── generous gutters so amounts with more
        // digits (larger shops, bigger invoices) still have breathing
        // room and never crowd the neighboring column. Discount gets its
        // own column (rather than a note under the item name) so it reads
        // as a real line item, not an afterthought.
        val colNum = margin
        val colItem = margin + 20f
        val colTotalRight = pageWidth - margin
        val colGst = colTotalRight - 76f
        // GST% is a short value ("18%") sitting right next to Taxable, so
        // it doesn't need as wide a gutter as the money columns — that
        // space is better spent giving Discount/Rate/Qty/HSN more room.
        val colTaxable = colGst - 42f
        val colDiscount = colTaxable - 68f
        val colRate = colDiscount - 74f
        val colQty = colRate - 58f
        val colHsn = colQty - 54f

        // Qty and GST% are short, low-variance values — centering them on
        // their column looks more like a real invoice table than the
        // right-aligned numeric convention used for the money columns.
        fun drawCentered(text: String, centerX: Float, y: Float) {
            canvas.drawText(text, centerX - paint.measureText(text) / 2f, y, paint)
        }

        fun drawTableHeader() {
            paint.color = teal
            paint.typeface = fontSemibold
            paint.textSize = 9.5f
            canvas.drawText("#", colNum, y, paint)
            canvas.drawText(context.getString(R.string.invoice_pdf_item_description_header), colItem, y, paint)
            if (!isComposition) canvas.drawText("HSN", colHsn, y, paint)
            drawCentered("Qty", colQty, y)
            val rateLabel = "Rate ($currencySymbol)"; canvas.drawText(rateLabel, colRate - paint.measureText(rateLabel), y, paint)
            if (showDiscount) {
                val discLabel = "Discount ($currencySymbol)"; canvas.drawText(discLabel, colDiscount - paint.measureText(discLabel), y, paint)
            }
            if (!isComposition) {
                val taxLabel = "Taxable ($currencySymbol)"; canvas.drawText(taxLabel, colTaxable - paint.measureText(taxLabel), y, paint)
                drawCentered("GST%", colGst, y)
            }
            val totalLabel = "Total ($currencySymbol)"; canvas.drawText(totalLabel, colTotalRight - paint.measureText(totalLabel), y, paint)
            y += 6f
            paint.strokeWidth = 1.4f
            canvas.drawLine(margin, y, pageWidth - margin, y, paint)
            y += 16f
        }

        drawTableHeader()

        // Column widths are fixed, so a long product name drawn with a
        // blind take(30) could run past the item column's right edge and
        // visually crowd/overlap the HSN or Qty header — which is what
        // read as columns being "not aligned". Instead, measure and clip
        // to the actual available width for that column, with an ellipsis
        // when it's cut.
        val itemColMaxWidth = (if (isComposition) colQty else colHsn) - colItem - 8f

        fun truncateToWidth(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val ellipsis = "…"
            val ellipsisWidth = paint.measureText(ellipsis)
            var fitChars = paint.breakText(text, true, maxWidth - ellipsisWidth, null)
            if (fitChars <= 0) return ellipsis
            return text.substring(0, fitChars) + ellipsis
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - 170f) {
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                drawHeaderBand(true)
                drawTableHeader()
            }
        }

        val discountColor = Color.parseColor("#A32D2D")

        billItems.forEachIndexed { index, item ->
            // Same derivation the 80mm receipt uses — gross (price × qty)
            // vs. the stored net taxableValue — not the raw discountAmount
            // field, so this always matches what prints on the thermal
            // receipt for the same bill. It's its own column now (not a
            // note under the item name) so it reads as a real line item.
            val rawGross = item.price * item.quantity
            val netTaxable = item.taxableValue
            val lineDiscount = if (netTaxable < rawGross - 0.01) rawGross - netTaxable else 0.0
            val hasLineDiscount = lineDiscount >= 0.01
            val rowHeight = 22f

            ensureSpace(rowHeight)

            val rowTop = y - 10f
            if (index % 2 == 0) {
                paint.style = Paint.Style.FILL
                paint.color = zebra
                canvas.drawRect(margin, rowTop, pageWidth - margin, rowTop + rowHeight - 2f, paint)
            }

            val displayName = if (!item.variant.isNullOrBlank()) "${item.productName} (${item.variant})" else item.productName

            paint.color = ink
            paint.typeface = fontRegular
            paint.textSize = 9.5f
            canvas.drawText("${index + 1}", colNum, y, paint)
            canvas.drawText(truncateToWidth(displayName, itemColMaxWidth), colItem, y, paint)

            if (!isComposition) {
                paint.color = muted
                canvas.drawText(item.hsnCode.takeIf { it.isNotBlank() } ?: "-", colHsn, y, paint)
                paint.color = ink
            }

            val qtyText = if (item.quantity % 1 == 0.0) item.quantity.toInt().toString() else "%.2f".format(item.quantity)
            drawCentered(qtyText, colQty, y)

            val rateText = "%.2f".format(item.price)
            canvas.drawText(rateText, colRate - paint.measureText(rateText), y, paint)

            if (showDiscount) {
                paint.color = if (hasLineDiscount) discountColor else muted
                val discText = if (hasLineDiscount) "-%.2f".format(lineDiscount) else "-"
                canvas.drawText(discText, colDiscount - paint.measureText(discText), y, paint)
                paint.color = ink
            }

            val gstAmt = item.cgstAmount + item.sgstAmount + item.igstAmount
            val lineTotal = item.taxableValue + gstAmt

            if (!isComposition) {
                val taxText = "%.2f".format(item.taxableValue)
                canvas.drawText(taxText, colTaxable - paint.measureText(taxText), y, paint)

                val gstPctText = if (item.gstRate % 1 == 0.0) "${item.gstRate.toInt()}%" else "%.1f%%".format(item.gstRate)
                drawCentered(gstPctText, colGst, y)
            }

            paint.typeface = fontMedium
            val totalText = "%.2f".format(if (isComposition) item.taxableValue else lineTotal)
            canvas.drawText(totalText, colTotalRight - paint.measureText(totalText), y, paint)

            y += rowHeight
        }

        paint.color = hairline
        paint.strokeWidth = 0.7f
        canvas.drawLine(margin, y, pageWidth - margin, y, paint)
        y += 18f

        ensureSpace(140f)

        val taxable = billItems.sumOf { it.taxableValue }
        val totalDiscount = billItems.sumOf { item ->
            val rawGross = item.price * item.quantity
            if (item.taxableValue < rawGross - 0.01) rawGross - item.taxableValue else 0.0
        }
        val grossTaxable = taxable + totalDiscount
        val totalTax = bill.cgstAmount + bill.sgstAmount + bill.igstAmount
        var finalTotal = bill.total
        if (roundOff) finalTotal = Math.round(finalTotal).toDouble()
        val computed = if (isComposition) taxable else taxable + totalTax
        val roundDiff = finalTotal - computed

        val boxRight = pageWidth - margin
        val boxLeft = boxRight - 220f

        fun totalsRow(label: String, value: String, bold: Boolean = false) {
            paint.typeface = if (bold) fontMedium else fontRegular
            paint.color = if (bold) ink else muted
            paint.textSize = 10.5f
            canvas.drawText(label, boxLeft, y, paint)
            paint.color = ink
            canvas.drawText(value, boxRight - paint.measureText(value), y, paint)
            y += 16f
        }

        val hasDiscount = showDiscount && totalDiscount >= 0.01
        if (hasDiscount) {
            totalsRow(context.getString(R.string.invoice_pdf_gross_amount_label), "$currencySymbol%.2f".format(grossTaxable))
            totalsRow(context.getString(R.string.invoice_pdf_discount_label), "- $currencySymbol%.2f".format(totalDiscount))
        }

        totalsRow(
            if (isComposition) context.getString(R.string.invoice_pdf_sub_total_label) else context.getString(R.string.invoice_pdf_taxable_amount_label),
            "$currencySymbol%.2f".format(taxable)
        )

        if (!isComposition) {
            if (bill.igstAmount > 0.0) {
                totalsRow(context.getString(R.string.invoice_pdf_igst_label), "$currencySymbol%.2f".format(bill.igstAmount))
            } else {
                totalsRow(context.getString(R.string.invoice_pdf_cgst_label), "$currencySymbol%.2f".format(bill.cgstAmount))
                totalsRow(context.getString(R.string.invoice_pdf_sgst_label), "$currencySymbol%.2f".format(bill.sgstAmount))
            }
        }
        if (kotlin.math.abs(roundDiff) >= 0.01) {
            totalsRow(context.getString(R.string.invoice_pdf_round_off_label), "$currencySymbol%.2f".format(roundDiff))
        }

        y += 10f
        paint.strokeWidth = 1.2f
        paint.color = teal
        canvas.drawLine(boxLeft, y - 10f, boxRight, y - 10f, paint)
        y += 8f

        paint.color = teal
        paint.typeface = fontMedium
        paint.textSize = 12f
        canvas.drawText(context.getString(R.string.invoice_pdf_total_header), boxLeft, y, paint)
        paint.typeface = fontSerif
        paint.textSize = 16f
        val totalText = "$currencySymbol%.2f".format(finalTotal)
        canvas.drawText(totalText, boxRight - paint.measureText(totalText), y, paint)
        y += 30f

        paint.color = teal
        paint.strokeWidth = 1.6f
        canvas.drawLine(margin, y, pageWidth - margin, y, paint)
        y += 20f

        paint.color = muted
        paint.typeface = fontRegular
        paint.textSize = 9.5f
        val footer = footerMessage ?: context.getString(R.string.invoice_pdf_footer_default)
        canvas.drawText(footer, (pageWidth - paint.measureText(footer)) / 2f, y, paint)

        document.finishPage(page)
    }

    private fun extractUnitAndVariant(name: String): Pair<String, String?> {

        val lower = name.lowercase()

        val unit = when {
            lower.contains("kg") || lower.contains("kilogram") -> "kg"
            lower.contains("litre") || lower.contains("l") -> "L"
            lower.contains("gram") || lower.contains("g") -> "g"
            lower.contains("ml") || lower.contains("millilitre") -> "ml"
            lower.contains("piece") || lower.contains("pc") -> "pc"
            else -> "unit"
        }

        // extract variant inside ()
        val variant = Regex("\\((.*?)\\)").find(name)?.groupValues?.get(1)

        return Pair(unit, variant)
    }

    fun generateLedgerPdf(
        activity: Activity,
        storeInfo: StoreInfo?,
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

        // Page and canvas are mutable because a long ledger spills over.
        //
        // The row loop used to call finishPage() when it ran out of room and
        // then carry on drawing on that same finished page — and finishPage()
        // was called again at the end. Both are illegal: PdfDocument throws,
        // the caller catches it, and the user sees "Print failed". Any customer
        // with roughly two dozen or more entries could never print a statement.
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        )
        var canvas = page.canvas

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

        center(activity.getString(R.string.invoice_pdf_statement_title), 22f, true)

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

        canvas.drawText(activity.getString(R.string.invoice_pdf_table_date_header), colDate, y, paint)
        canvas.drawText(activity.getString(R.string.invoice_pdf_table_type_header), colType, y, paint)

        // Header labels now show the active currency symbol instead of a hardcoded ₹
        val headerSymbol = CurrencyHelper.getCurrencySymbol(activity)
        rightText("${activity.getString(R.string.invoice_pdf_table_dr_header)} ($headerSymbol)", colDrRight, y)
        rightText("${activity.getString(R.string.invoice_pdf_table_cr_header)} ($headerSymbol)", colCrRight, y)
        rightText("${activity.getString(R.string.invoice_pdf_table_balance_header)} ($headerSymbol)", colBalRight, y)

        y += 20
        line()

        paint.typeface = Typeface.MONOSPACE

        // ================= ROWS =================

        /** Closes the current page, opens the next, and repeats the column heads. */
        fun startNextPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            canvas = page.canvas
            y = 50f

            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = 12f
            canvas.drawText("Date", colDate, y, paint)
            canvas.drawText("Type", colType, y, paint)
            val pageSymbol = CurrencyHelper.getCurrencySymbol(activity)
            rightText("Dr ($pageSymbol)", colDrRight, y)
            rightText("Cr ($pageSymbol)", colCrRight, y)
            rightText("Balance ($pageSymbol)", colBalRight, y)
            y += 20
            line()
            paint.typeface = Typeface.MONOSPACE
        }

        rows.forEach {

            // Checked BEFORE drawing, so a row is never written onto a page
            // that has already been closed.
            if (y > pageHeight - 100) startNextPage()

            canvas.drawText(it[0], colDate, y, paint)
            canvas.drawText(it[1], colType, y, paint)

            // ✅ REMOVE currency symbol from values (clean columns)
            val rowSymbol = CurrencyHelper.getCurrencySymbol(activity)
            rightText(it[2].replace(rowSymbol, ""), colDrRight, y)
            rightText(it[3].replace(rowSymbol, ""), colCrRight, y)
            rightText(it[4].replace(rowSymbol, ""), colBalRight, y)

            y += 18
        }

        // The totals block needs about 120pt; move it rather than let it run
        // off the bottom of the last page.
        if (y > pageHeight - 160) startNextPage()

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

        drawTotalRow(activity.getString(R.string.invoice_pdf_total_debit_label), totalDebit)
        drawTotalRow(activity.getString(R.string.invoice_pdf_total_credit_label), totalCredit)

        paint.textSize = 14f
        drawTotalRow(activity.getString(R.string.invoice_pdf_final_balance_label), finalBalance)

        y += 10
        line()

        center(activity.getString(R.string.invoice_pdf_thank_you), 14f)

        document.finishPage(page)

        // ================= SAVE =================

        // Anything that isn't a letter, digit, dash or underscore is stripped.
        // The caller passes "N/A" when a customer has no number, and the slash
        // in it turns this into a path — the parent folder doesn't exist, so
        // the write fails on exactly the customers with no phone recorded.
        val safePhone = phone.replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { activity.getString(R.string.invoice_pdf_customer_fallback) }
        val fileName = "${safePhone}_${System.currentTimeMillis()}.pdf"

        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: activity.filesDir
        dir.mkdirs()

        val file = File(dir, fileName)

        try {
            FileOutputStream(file).use {
                document.writeTo(it)
            }
            document.close()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                "Couldn't save the PDF: ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // ================= PRINT =================

        try {
            // Not every device has a print service — plenty of POS tablets
            // ship without one — and this returns null there rather than
            // throwing something descriptive. Checked explicitly so the user
            // is told what is missing instead of seeing a bare "Print failed".
            val printManager = activity.getSystemService(PrintManager::class.java)
                ?: throw IllegalStateException(activity.getString(R.string.invoice_pdf_no_printing_service))

            val printAdapter = PdfPrintAdapter(
                activity,
                file.absolutePath,
                activity.getString(R.string.invoice_pdf_statement_job_name)
            )

            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            // The job name is shown in the print dialog, so it should read as
            // a name — the file name carries a phone number and a timestamp.
            printManager.print("Statement - $customerName", printAdapter, attributes)

        } catch (e: Exception) {
            e.printStackTrace()
            // The message, not just "Print failed" — two different failure
            // paths used the identical wording, so there was no way to tell
            // which had fired or why.
            Toast.makeText(
                activity,
                "Couldn't print: ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    suspend fun generateProfitPdf(
        activity: Activity,
        rows: List<List<String>>,
        totalProfit: Double,
        totalRevenue: Double,
        totalCost: Double,
        totalExpense: Double,
        totalLoss: Double,
        startDate: String?,
        endDate: String?
    ) {

        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        val document = PdfDocument()
        val paint = Paint()

        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 12f

        var page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        )
        var canvas = page.canvas

        var y = 50f

        // ================= STORE INFO =================
        val db = AppDatabase.getDatabase(activity)
        val storeInfo = db.storeInfoDao().get()

        val storeName = storeInfo?.name ?: "My Store"
        val storeAddress = storeInfo?.address ?: ""
        val storePhone = storeInfo?.phone ?: ""
        val storeGstin = storeInfo?.gstin ?: ""

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
            val dash = Paint().apply {
                pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
            }
            canvas.drawLine(margin, y, pageWidth - margin, y, dash)
            y += 15
        }

        fun newPage() {
            document.finishPage(page)
            page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            )
            canvas = page.canvas
            y = 50f
        }

        // ================= HEADER =================

        center(activity.getString(R.string.invoice_pdf_profit_report_title), 22f, true)
        center(storeName, 18f, true)

        if (storeAddress.isNotEmpty()) center(storeAddress, 14f)
        if (storePhone.isNotEmpty()) center("Phone: $storePhone", 14f)
        if (storeGstin.isNotEmpty()) center("GSTIN: $storeGstin", 14f)

        line()

        if (startDate == "All Time") {
            center(activity.getString(R.string.invoice_pdf_all_time_report), 14f)
        } else {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val pretty = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

            val startPretty = try { pretty.format(parser.parse(startDate!!)!!) } catch (e: Exception) { startDate }

            if (endDate != null && endDate.isNotEmpty()) {
                val endPretty = try { pretty.format(parser.parse(endDate)!!) } catch (e: Exception) { endDate }
                if (startDate == endDate) {
                    center("Date: $startPretty", 14f)
                } else {
                    center("$startPretty to $endPretty", 14f)
                }
            } else {
                center("From: $startPretty", 14f)
            }
        }

        center(
            "Generated: ${
                SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
            }",
            12f
        )

        line()

        // ================= PRODUCTS =================

        rows.forEach { row ->

            val name = row.getOrNull(0) ?: ""
            val qty = row.getOrNull(1) ?: ""
            val unit = row.getOrNull(2) ?: ""
            val revenue = row.getOrNull(2) ?: ""
            val cost = row.getOrNull(3) ?: ""
            val profit = row.getOrNull(4) ?: ""
            val flow = row.getOrNull(5) ?: ""
            val remaining = row.getOrNull(6) ?: ""
            val lossAmt = row.getOrNull(7) ?: ""
            val net = row.getOrNull(8) ?: ""
            val insight = row.getOrNull(9) ?: ""

            // 🔹 PRODUCT NAME
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = 14f
            canvas.drawText(name, margin, y, paint)
            y += 22f

            paint.typeface = Typeface.MONOSPACE
            paint.textSize = 12f

            // 🔹 BASIC DETAILS
            canvas.drawText("Sold Qty     : $qty $unit", margin, y, paint)
            y += 16f

            canvas.drawText("Revenue      : $revenue", margin, y, paint)
            y += 16f

            canvas.drawText("Cost         : $cost", margin, y, paint)
            y += 16f

            canvas.drawText("Profit       : $profit", margin, y, paint)
            y += 18f

            // 🔹 STOCK FLOW
            canvas.drawText("Stock Flow   : $flow", margin, y, paint)
            y += 16f

            canvas.drawText("Remaining    : $remaining", margin, y, paint)
            y += 16f

            canvas.drawText("Loss Amount  : $lossAmt", margin, y, paint)
            y += 18f

            // 🔹 NET + INSIGHT
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

            canvas.drawText("Net Profit   : $net", margin, y, paint)
            y += 16f

            canvas.drawText("Insight      : $insight", margin, y, paint)
            y += 22f

            // 🔹 SEPARATOR
            val sep = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            }

            canvas.drawLine(margin, y, pageWidth - margin, y, sep)
            y += 20f

            // 🔹 PAGE BREAK
            if (y > pageHeight - 120) {
                newPage()
            }
        }

        line()

        // ================= SUMMARY =================

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 13f

        fun drawSummary(label: String, value: Double) {
            val text = "$label:"
            val valueText = "%.2f".format(value)

            val startX = pageWidth - margin - (paint.measureText(text) + 15 + paint.measureText(valueText))

            canvas.drawText(text, startX, y, paint)
            canvas.drawText(valueText, startX + paint.measureText(text) + 15, y, paint)

            y += 20f
        }

        drawSummary(activity.getString(R.string.invoice_pdf_summary_revenue), totalRevenue)
        drawSummary(activity.getString(R.string.invoice_pdf_summary_cost), totalCost)
        drawSummary(activity.getString(R.string.invoice_pdf_summary_expense), totalExpense)
        drawSummary(activity.getString(R.string.invoice_pdf_summary_loss), totalLoss)

        paint.textSize = 15f
        val netProfitFinal = totalRevenue - totalCost - totalLoss
        drawSummary(activity.getString(R.string.invoice_pdf_summary_net_profit), netProfitFinal)

        line()
        center("Thank You!", 14f)

        document.finishPage(page)

        // ================= SAVE =================

        val file = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: activity.filesDir,
            "${storeName}_Profit_${System.currentTimeMillis()}.pdf"
        )

        try {
            FileOutputStream(file).use {
                document.writeTo(it)
            }
            document.close()
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.invoice_pdf_profit_save_failed_toast), Toast.LENGTH_SHORT).show()
            return
        }

        // ================= PRINT =================

        try {
            val printManager =
                activity.getSystemService(Context.PRINT_SERVICE) as PrintManager

            val adapter = PdfPrintAdapter(
                activity,
                file.absolutePath,
                "${storeName}_Profit_${System.currentTimeMillis()}"
            )

            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            printManager.print(activity.getString(R.string.invoice_pdf_profit_print_job_name), adapter, attrs)

        } catch (e: Exception) {
            Log.e("InvoicePdfGenerator", "Print failed", e)
            Toast.makeText(activity, R.string.profitactivity_print_failed, Toast.LENGTH_LONG).show()
        }
    }
}
