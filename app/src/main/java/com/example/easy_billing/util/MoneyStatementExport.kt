package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.core.content.res.ResourcesCompat
import com.example.easy_billing.R
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shop details printed at the top of the statement. */
data class StatementShop(val name: String, val address: String, val phone: String, val gstin: String)

/**
 * The full money statement as an Excel file (3 sheets), an A4 landscape PDF, and a print job.
 * All three are drawn from the same [MoneyStatementData], so the figures always agree.
 */
object MoneyStatementExport {

    suspend fun shop(context: Context): StatementShop = withContext(Dispatchers.IO) {
        val s = AppDatabase.getDatabase(context).storeInfoDao().get()
        StatementShop(
            name = s?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.invoice_pdf_store_default_name),
            address = s?.address.orEmpty().trim(), phone = s?.phone.orEmpty().trim(), gstin = s?.gstin.orEmpty().trim()
        )
    }

    private fun outDir(context: Context) = File(context.cacheDir, "money_statements").apply { mkdirs() }

    /** Same pattern as the sales report file: <ShopName>_Money_Statement_<time>.<ext>. Old files are cleared after a day. */
    private fun statementFile(context: Context, shop: StatementShop, ext: String): File {
        val dir = outDir(context)
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val safeName = shop.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Store" }
        return File(dir, "${safeName}_Money_Statement_${System.currentTimeMillis()}.$ext")
    }

    private fun numberFormat(): NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    // ───────────────────────────────── PDF ─────────────────────────────────

    fun pdf(context: Context, d: MoneyStatementData, shop: StatementShop): File {
        val pages = render(context, d, shop, null, 0)
        val doc = PdfDocument()
        render(context, d, shop, doc, pages)
        val file = statementFile(context, shop, "pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Lays the statement out. With [doc] null nothing is drawn and only the page count is returned. */
    private fun render(context: Context, d: MoneyStatementData, shop: StatementShop, doc: PdfDocument?, totalPages: Int): Int {
        val pw = 842; val ph = 595; val m = 28f
        val contentW = pw - 2 * m
        val nf = numberFormat()
        fun n(v: Double) = nf.format(v)
        fun money(v: Double) = CurrencyHelper.format(context, v)
        fun font(res: Int, fallback: Typeface): Typeface = try { ResourcesCompat.getFont(context, res) ?: fallback } catch (_: Exception) { fallback }
        val regular = font(R.font.googlesans_regular, Typeface.DEFAULT)
        val medium = font(R.font.googlesans_medium, Typeface.DEFAULT_BOLD)
        val semibold = font(R.font.googlesans_semibold, Typeface.DEFAULT_BOLD)
        val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val ink = Color.parseColor("#1A1A18"); val muted = Color.parseColor("#8C8576"); val soft = Color.parseColor("#6B6657")
        val green = Color.parseColor("#0F6E56"); val greenD = Color.parseColor("#085041"); val red = Color.parseColor("#791F1F")
        val mint = Color.parseColor("#E3F3EE"); val champ = Color.parseColor("#FBF7EC"); val line = Color.parseColor("#E4DCC8")
        val amberBg = Color.parseColor("#FAEEDA"); val amber = Color.parseColor("#8A6526"); val zebra = Color.parseColor("#FCFAF4")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var pageNo = 0
        var y = 0f

        fun fit(s: String, maxW: Float): String {
            if (s.isEmpty() || paint.measureText(s) <= maxW) return s
            var t = s
            while (t.isNotEmpty() && paint.measureText("$t…") > maxW) t = t.dropLast(1)
            return "$t…"
        }
        fun text(s: String, x: Float, size: Float, tf: Typeface, color: Int, right: Boolean = false, maxW: Float = 0f) {
            val c = canvas ?: return
            paint.style = Paint.Style.FILL; paint.typeface = tf; paint.textSize = size; paint.color = color
            val t = if (maxW > 0f) fit(s, maxW) else s
            paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(t, x, y, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        fun rect(x: Float, top: Float, w: Float, h: Float, fill: Int, stroke: Int? = null, r: Float = 0f) {
            val c = canvas ?: return
            paint.style = Paint.Style.FILL; paint.color = fill
            if (r > 0f) c.drawRoundRect(x, top, x + w, top + h, r, r, paint) else c.drawRect(x, top, x + w, top + h, paint)
            if (stroke != null) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.8f; paint.color = stroke
                if (r > 0f) c.drawRoundRect(x, top, x + w, top + h, r, r, paint) else c.drawRect(x, top, x + w, top + h, paint)
                paint.style = Paint.Style.FILL
            }
        }
        fun hline(yy: Float, color: Int, w: Float = 0.8f) {
            val c = canvas ?: return
            paint.style = Paint.Style.STROKE; paint.strokeWidth = w; paint.color = color
            c.drawLine(m, yy, pw - m, yy, paint); paint.style = Paint.Style.FILL
        }
        fun footer() {
            y = ph - 18f
            hline(ph - 28f, line)
            text(context.getString(R.string.mstmt_footer_note), m, 8f, regular, muted)
            text(context.getString(R.string.mstmt_page, pageNo, totalPages), pw - m, 8f, regular, muted, right = true)
        }
        fun endPage() {
            val p = page ?: return
            footer(); doc?.finishPage(p); page = null; canvas = null
        }

        // Table columns (relative widths, scaled to the page)
        val rel = floatArrayOf(22f, 54f, 44f, 108f, 70f, 98f, 42f, 62f, 62f, 70f, 70f, 74f)
        val scale = contentW / rel.sum()
        val colW = FloatArray(rel.size) { rel[it] * scale }
        val colX = FloatArray(rel.size).also { var x = m; for (i in rel.indices) { it[i] = x; x += colW[i] } }
        val heads = listOf(R.string.mstmt_col_no, R.string.mstmt_col_date, R.string.mstmt_col_time, R.string.mstmt_col_type,
            R.string.mstmt_col_ref, R.string.mstmt_col_party, R.string.mstmt_col_mode, R.string.mstmt_col_in,
            R.string.mstmt_col_out, R.string.mstmt_col_cash_bal, R.string.mstmt_col_bank_bal, R.string.mstmt_col_total_bal)
        val rightCols = setOf(7, 8, 9, 10, 11)
        val rowH = 17f

        fun tableHeader() {
            rect(m, y, contentW, 18f, green)
            y += 12.5f
            for (i in heads.indices) {
                val s = context.getString(heads[i])
                if (i in rightCols) text(s, colX[i] + colW[i] - 5f, 8f, semibold, Color.WHITE, true, colW[i] - 8f)
                else text(s, colX[i] + 4f, 8f, semibold, Color.WHITE, false, colW[i] - 6f)
            }
            y += 5.5f
        }
        fun newPage(first: Boolean, withTable: Boolean) {
            endPage()
            pageNo++
            if (doc != null) { page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create()); canvas = page!!.canvas }
            y = m
            if (!first) {
                y += 10f
                text(shop.name + " · " + context.getString(R.string.money_statement_title), m, 10f, semibold, greenD)
                text(d.periodText, pw - m, 9f, regular, soft, right = true)
                y += 8f; hline(y, green, 1.2f); y += 10f
            }
            if (withTable) tableHeader()
        }
        fun ensure(h: Float, withTable: Boolean = false) { if (y + h > ph - 38f) newPage(false, withTable) }

        // ── Page 1 header ──
        newPage(true, false)
        y += 14f
        text(shop.name, m, 18f, serif, greenD, maxW = contentW * 0.58f)
        val t = context.getString(R.string.money_statement_title).uppercase()
        text(t, pw - m, 14f, semibold, Color.parseColor("#123B31"), true)
        y += 14f
        val top2 = y
        val shopLines = ArrayList<String>()
        if (shop.address.isNotBlank()) shopLines += shop.address.replace('\n', ' ')
        val pg = ArrayList<String>()
        if (shop.phone.isNotBlank()) pg += context.getString(R.string.mstmt_phone, shop.phone)
        if (shop.gstin.isNotBlank()) pg += context.getString(R.string.mstmt_gstin, shop.gstin)
        if (pg.isNotEmpty()) shopLines += pg.joinToString("  ·  ")
        for (s in shopLines) { text(s, m, 9f, regular, soft, maxW = contentW * 0.58f); y += 12f }
        val afterShop = y
        y = top2
        text(context.getString(R.string.mstmt_period, d.periodText), pw - m, 9f, regular, soft, true); y += 12f
        text(context.getString(R.string.mstmt_created, AppTime.formatter("dd MMM yyyy, hh:mm a").format(Date(d.createdAt))),
            pw - m, 9f, regular, soft, true)
        y = maxOf(afterShop, y) + 6f
        hline(y, green, 1.6f); y += 12f

        // ── Four summary boxes ──
        run {
            val gap = 10f; val bw = (contentW - 3 * gap) / 4f; val bh = 56f
            data class Box(val label: String, val value: String, val sub: String, val color: Int, val bg: Int)
            val boxes = listOf(
                Box(context.getString(R.string.money_stmt_opening), money(d.opening.net),
                    context.getString(R.string.mstmt_cash_bank, money(d.opening.cashNet), money(d.opening.bankNet)), ink, champ),
                Box(context.getString(R.string.money_row_in), money(d.earnings.inTotal),
                    context.getString(R.string.mstmt_cash_bank, money(d.earnings.inCash), money(d.earnings.inBank)), green, champ),
                Box(context.getString(R.string.money_row_out), money(d.earnings.outTotal),
                    context.getString(R.string.mstmt_cash_bank, money(d.earnings.outCash), money(d.earnings.outBank)), red, champ),
                Box(context.getString(R.string.money_stmt_closing), money(d.closing.net),
                    context.getString(R.string.mstmt_pocket_bank, money(d.closing.cashNet), money(d.closing.bankNet)),
                    if (d.closing.net < -0.004) red else greenD, mint)
            )
            boxes.forEachIndexed { i, b ->
                val x = m + i * (bw + gap)
                rect(x, y, bw, bh, b.bg, line, 6f)
                val save = y
                y = save + 15f; text(b.label.uppercase(), x + 9f, 7.5f, semibold, muted, maxW = bw - 16f)
                y = save + 33f; text(b.value, x + 9f, 15f, serif, b.color, maxW = bw - 16f)
                y = save + 47f; text(b.sub, x + 9f, 7.5f, regular, soft, maxW = bw - 16f)
                y = save
            }
            y += bh + 10f
        }
        val addedNet = d.openingAdded.net
        if (kotlin.math.abs(addedNet) > 0.004) {
            text(context.getString(R.string.mstmt_opening_note, money(addedNet)), m, 8f, regular, muted, maxW = contentW)
            y += 12f
        }

        // ── The table ──
        y += 2f
        ensure(rowH * 3 + 18f)
        tableHeader()
        for ((idx, r) in d.rows.withIndex()) {
            ensure(rowH, true)
            val bg = when { r.waiting -> amberBg; r.opening -> mint; idx % 2 == 1 -> zebra; else -> -1 }
            if (bg != -1) rect(m, y, contentW, rowH, bg)
            val base = y + 11.5f
            val keep = y; y = base
            val c0 = if (r.waiting) amber else ink
            text(r.no.toString(), colX[0] + 4f, 8f, regular, muted)
            val dt = Date(r.at)
            text(AppTime.formatter("dd MMM").format(dt), colX[1] + 4f, 8f, regular, c0, maxW = colW[1] - 6f)
            text(AppTime.formatter("hh:mm a").format(dt), colX[2] + 4f, 8f, regular, c0, maxW = colW[2] - 6f)
            text(r.type, colX[3] + 4f, 8f, medium, c0, maxW = colW[3] - 6f)
            text(r.ref, colX[4] + 4f, 8f, regular, c0, maxW = colW[4] - 6f)
            text(r.party, colX[5] + 4f, 8f, regular, c0, maxW = colW[5] - 6f)
            text(r.mode, colX[6] + 4f, 8f, regular, c0, maxW = colW[6] - 6f)
            if (r.moneyIn > 0.0) text(n(r.moneyIn), colX[7] + colW[7] - 5f, 8f, medium, if (r.waiting) amber else green, true)
            if (r.moneyOut > 0.0) text(n(r.moneyOut), colX[8] + colW[8] - 5f, 8f, medium, if (r.waiting) amber else red, true)
            if (r.waiting) {
                text(context.getString(R.string.mstmt_waiting), colX[11] + colW[11] - 5f, 8f, regular, amber, true)
            } else {
                text(n(r.cashBal), colX[9] + colW[9] - 5f, 8f, regular, ink, true)
                text(n(r.bankBal), colX[10] + colW[10] - 5f, 8f, regular, ink, true)
                text(n(r.totalBal), colX[11] + colW[11] - 5f, 8f, medium, ink, true)
            }
            y = keep + rowH
            hline(y, line, 0.4f)
        }
        if (d.rows.isEmpty()) {
            ensure(rowH * 2)
            y += 14f; text(context.getString(R.string.money_stmt_empty), m + 4f, 9f, regular, muted); y += 8f
        }
        // Totals row
        ensure(rowH + 6f, true)
        rect(m, y, contentW, rowH + 2f, mint)
        hline(y, green, 1.2f)
        val tb = y + 12f; val tk = y; y = tb
        text(context.getString(R.string.mstmt_total_row), colX[0] + 4f, 8f, semibold, Color.parseColor("#123B31"), maxW = colX[7] - m - 8f)
        text(n(d.earnings.inTotal), colX[7] + colW[7] - 5f, 8.5f, semibold, green, true)
        text(n(d.earnings.outTotal), colX[8] + colW[8] - 5f, 8.5f, semibold, red, true)
        text(n(d.closing.cashNet), colX[9] + colW[9] - 5f, 8.5f, semibold, ink, true)
        text(n(d.closing.bankNet), colX[10] + colW[10] - 5f, 8.5f, semibold, ink, true)
        text(n(d.closing.net), colX[11] + colW[11] - 5f, 8.5f, semibold, ink, true)
        y = tk + rowH + 2f

        // ── By type | By mode and still to settle ──
        val half = (contentW - 18f) / 2f
        val leftRows = d.byType.size
        val rightRows = d.byMode.size + 3
        ensure(30f + maxOf(leftRows, rightRows) * 14f + 10f)
        y += 18f
        val sec = y
        fun title(s: String, x: Float) { text(s.uppercase(), x, 8f, semibold, green) }
        // left: by type
        title(context.getString(R.string.mstmt_by_type), m); y += 14f
        for ((label, v) in d.byType) {
            text(label, m, 8.5f, regular, ink, maxW = half - 90f)
            text((if (v < 0) "-" else "") + n(kotlin.math.abs(v)), m + half, 8.5f, medium, if (v < 0) red else green, true)
            y += 13f; hline(y - 9f, line, 0.4f)
        }
        val leftEnd = y
        // right: by mode + still to settle
        y = sec
        val rx = m + half + 18f
        title(context.getString(R.string.mstmt_by_mode), rx); y += 14f
        for (b in d.byMode) {
            text(b.mode, rx, 8.5f, regular, ink)
            text(n(b.moneyIn), rx + half - 90f, 8.5f, medium, green, true)
            text(n(b.moneyOut), rx + half, 8.5f, medium, red, true)
            y += 13f; hline(y - 9f, line, 0.4f)
        }
        y += 8f
        title(context.getString(R.string.mstmt_to_settle), rx); y += 14f
        fun settleRow(label: String, v: Double, color: Int) {
            text(label, rx, 8.5f, regular, ink, maxW = half - 90f)
            text(n(v), rx + half, 8.5f, medium, color, true)
            y += 13f; hline(y - 9f, line, 0.4f)
        }
        settleRow(context.getString(R.string.mstmt_upi_waiting), d.waitingTotal, amber)
        settleRow(context.getString(R.string.mstmt_to_give), d.toGiveTotal, red)
        settleRow(context.getString(R.string.mstmt_to_receive), d.toReceiveTotal, green)
        y = maxOf(leftEnd, y) + 6f

        // ── Detail of what is still to settle ──
        if (d.settle.isNotEmpty()) {
            ensure(40f)
            y += 10f
            title(context.getString(R.string.mstmt_to_settle), m); y += 14f
            for (s in d.settle) {
                ensure(14f)
                val col = when (s.kind) { 1 -> red; 2 -> green; else -> amber }
                text(AppTime.formatter("dd MMM yyyy").format(Date(s.at)), m, 8.5f, regular, soft)
                text(s.type, m + 78f, 8.5f, medium, ink, maxW = 190f)
                text(s.ref, m + 280f, 8.5f, regular, ink, maxW = 90f)
                text(s.party, m + 376f, 8.5f, regular, ink, maxW = 250f)
                text(n(s.amount), pw - m, 8.5f, medium, col, true)
                y += 13f; hline(y - 9f, line, 0.4f)
            }
        }

        // ── Signature lines ──
        ensure(46f)
        y += 30f
        paint.textAlign = Paint.Align.LEFT
        for ((i, label) in listOf(R.string.mstmt_prepared, R.string.mstmt_checked).withIndex()) {
            val x = pw - m - 150f - i * 190f
            canvas?.let { c -> paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.7f; paint.color = Color.parseColor("#BBBBBB"); c.drawLine(x, y, x + 150f, y, paint); paint.style = Paint.Style.FILL }
            val s = context.getString(label)
            paint.typeface = regular; paint.textSize = 8f
            val w = paint.measureText(s)
            val keep = y; y += 11f
            text(s, x + 75f - w / 2f, 8f, regular, soft)
            y = keep
        }

        endPage()
        return pageNo
    }

    // ───────────────────────────────── Print ─────────────────────────────────

    /** Hands an already created PDF file to the Android print dialog. */
    class PdfFilePrintAdapter(private val file: File, private val jobName: String) : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(jobName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    // ───────────────────────────────── Excel ─────────────────────────────────

    fun xlsx(context: Context, d: MoneyStatementData, shop: StatementShop): File {
        val wb = XSSFWorkbook()
        val money = "#,##0.00"

        val cache = HashMap<String, CellStyle>()
        fun style(fill: IndexedColors? = null, fmt: String? = null, bold: Boolean = false, white: Boolean = false,
                  size: Short = 10, right: Boolean = false, border: Boolean = true, tint: IndexedColors? = null): CellStyle {
            val key = "$fill|$fmt|$bold|$white|$size|$right|$border|$tint"
            return cache.getOrPut(key) {
                val font = wb.createFont().apply {
                    this.bold = bold; fontHeightInPoints = size
                    this.color = when { white -> IndexedColors.WHITE.index; tint != null -> tint.index; else -> IndexedColors.BLACK.index }
                }
                wb.createCellStyle().apply {
                    setFont(font)
                    if (fill != null) { fillForegroundColor = fill.index; fillPattern = FillPatternType.SOLID_FOREGROUND }
                    if (fmt != null) dataFormat = wb.creationHelper.createDataFormat().getFormat(fmt)
                    if (right) alignment = HorizontalAlignment.RIGHT
                    if (border) { borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN; borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
                        bottomBorderColor = IndexedColors.GREY_25_PERCENT.index; topBorderColor = IndexedColors.GREY_25_PERCENT.index
                        leftBorderColor = IndexedColors.GREY_25_PERCENT.index; rightBorderColor = IndexedColors.GREY_25_PERCENT.index }
                }
            }
        }
        fun wall(ms: Long): Date {
            val src = Calendar.getInstance(AppTime.ZONE).apply { timeInMillis = ms }
            val dst = Calendar.getInstance(); dst.clear()
            dst.set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            return dst.time
        }
        fun put(sheet: Sheet, r: Int, c: Int, v: Any?, st: CellStyle? = null) {
            val row = sheet.getRow(r) ?: sheet.createRow(r)
            val cell = row.createCell(c)
            when (v) {
                null -> {}
                is Double -> cell.setCellValue(v)
                is Date -> cell.setCellValue(v)
                else -> cell.setCellValue(v.toString())
            }
            if (st != null) cell.cellStyle = st
        }
        val head = style(IndexedColors.DARK_GREEN, bold = true, white = true)
        val title = style(bold = true, size = 14, border = false, tint = IndexedColors.DARK_GREEN)
        val sub = style(size = 10, border = false, tint = IndexedColors.GREY_50_PERCENT)

        fun banner(sheet: Sheet, heading: String): Int {
            put(sheet, 0, 0, shop.name, title)
            val info = listOf(shop.address, shop.phone.takeIf { it.isNotBlank() }?.let { context.getString(R.string.mstmt_phone, it) },
                shop.gstin.takeIf { it.isNotBlank() }?.let { context.getString(R.string.mstmt_gstin, it) })
                .filter { !it.isNullOrBlank() }.joinToString("  ·  ")
            put(sheet, 1, 0, info, sub)
            put(sheet, 2, 0, heading + " — " + context.getString(R.string.mstmt_period, d.periodText), style(bold = true, border = false))
            put(sheet, 3, 0, context.getString(R.string.mstmt_created, AppTime.formatter("dd MMM yyyy, hh:mm a").format(Date(d.createdAt))), sub)
            return 5
        }

        // ── Sheet 1: Statement ──
        val s1 = wb.createSheet(context.getString(R.string.mstmt_sheet_statement))
        var r = banner(s1, context.getString(R.string.money_statement_title))
        val cols = listOf(R.string.mstmt_col_no, R.string.mstmt_col_date, R.string.mstmt_col_time, R.string.mstmt_col_type,
            R.string.mstmt_col_ref, R.string.mstmt_col_party, R.string.mstmt_col_mode, R.string.mstmt_col_in, R.string.mstmt_col_out,
            R.string.mstmt_col_cash_bal, R.string.mstmt_col_bank_bal, R.string.mstmt_col_total_bal, R.string.mstmt_col_status)
        val headRow = r
        cols.forEachIndexed { i, id -> put(s1, r, i, context.getString(id), head) }
        r++
        val first = r
        val timeFmt = AppTime.formatter("hh:mm a")
        for (x in d.rows) {
            val fill = when { x.waiting -> IndexedColors.LEMON_CHIFFON; x.opening -> IndexedColors.LIGHT_TURQUOISE; else -> null }
            val tx = style(fill); val mn = style(fill, money, right = true); val dt = style(fill, "dd-mmm-yyyy")
            put(s1, r, 0, x.no.toDouble(), tx)
            put(s1, r, 1, wall(x.at), dt)
            put(s1, r, 2, timeFmt.format(Date(x.at)), tx)
            put(s1, r, 3, x.type, tx); put(s1, r, 4, x.ref, tx); put(s1, r, 5, x.party, tx); put(s1, r, 6, x.mode, tx)
            put(s1, r, 7, if (x.moneyIn > 0.0) x.moneyIn else null, mn)
            put(s1, r, 8, if (x.moneyOut > 0.0) x.moneyOut else null, mn)
            if (x.waiting) { put(s1, r, 9, null, mn); put(s1, r, 10, null, mn); put(s1, r, 11, null, mn) }
            else { put(s1, r, 9, x.cashBal, mn); put(s1, r, 10, x.bankBal, mn); put(s1, r, 11, x.totalBal, mn) }
            put(s1, r, 12, context.getString(if (x.waiting) R.string.mstmt_waiting else R.string.mstmt_received), tx)
            r++
        }
        val tot = style(IndexedColors.LIGHT_TURQUOISE, bold = true); val totM = style(IndexedColors.LIGHT_TURQUOISE, money, bold = true, right = true)
        for (c in 0..12) put(s1, r, c, null, tot)
        put(s1, r, 0, context.getString(R.string.mstmt_total_row), tot)
        put(s1, r, 7, d.earnings.inTotal, totM); put(s1, r, 8, d.earnings.outTotal, totM)
        put(s1, r, 9, d.closing.cashNet, totM); put(s1, r, 10, d.closing.bankNet, totM); put(s1, r, 11, d.closing.net, totM)
        s1.createFreezePane(0, headRow + 1)
        if (r > first) s1.setAutoFilter(CellRangeAddress(headRow, r - 1, 0, cols.size - 1))
        val widths = intArrayOf(5, 13, 10, 24, 14, 22, 10, 14, 14, 16, 16, 16, 12)
        widths.forEachIndexed { i, w -> s1.setColumnWidth(i, w * 256) }

        // ── Sheet 2: Summary ──
        val s2 = wb.createSheet(context.getString(R.string.mstmt_sheet_summary))
        r = banner(s2, context.getString(R.string.mstmt_sheet_summary))
        listOf("", context.getString(R.string.money_col_cash), context.getString(R.string.money_col_bank), context.getString(R.string.money_col_total))
            .forEachIndexed { i, s -> put(s2, r, i, s, head) }
        r++
        fun sumRow(label: String, cash: Double, bank: Double, bold: Boolean = false, fill: IndexedColors? = null) {
            put(s2, r, 0, label, style(fill, bold = bold)); put(s2, r, 1, cash, style(fill, money, bold, right = true))
            put(s2, r, 2, bank, style(fill, money, bold, right = true)); put(s2, r, 3, PaymentSplit.round2(cash + bank), style(fill, money, bold, right = true)); r++
        }
        sumRow(context.getString(R.string.money_stmt_opening), d.opening.cashNet, d.opening.bankNet)
        sumRow(context.getString(R.string.money_row_in), d.earnings.inCash, d.earnings.inBank)
        sumRow(context.getString(R.string.money_row_out), d.earnings.outCash, d.earnings.outBank)
        if (kotlin.math.abs(d.openingAdded.cashNet) > 0.004 || kotlin.math.abs(d.openingAdded.bankNet) > 0.004)
            sumRow(context.getString(R.string.money_reason_opening), d.openingAdded.cashNet, d.openingAdded.bankNet)
        sumRow(context.getString(R.string.money_stmt_closing), d.closing.cashNet, d.closing.bankNet, true, IndexedColors.LIGHT_TURQUOISE)
        r++
        put(s2, r, 0, context.getString(R.string.mstmt_by_type), head); put(s2, r, 1, "", head); r++
        for ((label, v) in d.byType) { put(s2, r, 0, label, style()); put(s2, r, 1, v, style(null, money, right = true)); r++ }
        r++
        put(s2, r, 0, context.getString(R.string.mstmt_by_mode), head); put(s2, r, 1, context.getString(R.string.mstmt_col_in), head)
        put(s2, r, 2, context.getString(R.string.mstmt_col_out), head); r++
        for (b in d.byMode) { put(s2, r, 0, b.mode, style()); put(s2, r, 1, b.moneyIn, style(null, money, right = true)); put(s2, r, 2, b.moneyOut, style(null, money, right = true)); r++ }
        r++
        put(s2, r, 0, context.getString(R.string.mstmt_to_settle), head); put(s2, r, 1, "", head); r++
        put(s2, r, 0, context.getString(R.string.mstmt_upi_waiting), style()); put(s2, r, 1, d.waitingTotal, style(null, money, right = true)); r++
        put(s2, r, 0, context.getString(R.string.mstmt_to_give), style()); put(s2, r, 1, d.toGiveTotal, style(null, money, right = true)); r++
        put(s2, r, 0, context.getString(R.string.mstmt_to_receive), style()); put(s2, r, 1, d.toReceiveTotal, style(null, money, right = true)); r++
        intArrayOf(34, 16, 16, 16).forEachIndexed { i, w -> s2.setColumnWidth(i, w * 256) }

        // ── Sheet 3: Still to settle ──
        val s3 = wb.createSheet(context.getString(R.string.mstmt_sheet_settle))
        r = banner(s3, context.getString(R.string.mstmt_to_settle))
        listOf(R.string.mstmt_col_date, R.string.mstmt_col_type, R.string.mstmt_col_ref, R.string.mstmt_col_party, R.string.mstmt_col_amount)
            .forEachIndexed { i, id -> put(s3, r, i, context.getString(id), head) }
        r++
        if (d.settle.isEmpty()) put(s3, r, 0, context.getString(R.string.mstmt_none), style(border = false))
        for (s in d.settle) {
            put(s3, r, 0, wall(s.at), style(null, "dd-mmm-yyyy")); put(s3, r, 1, s.type, style()); put(s3, r, 2, s.ref, style())
            put(s3, r, 3, s.party, style()); put(s3, r, 4, s.amount, style(null, money, right = true)); r++
        }
        intArrayOf(14, 36, 16, 26, 14).forEachIndexed { i, w -> s3.setColumnWidth(i, w * 256) }

        val file = statementFile(context, shop, "xlsx")
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        return file
    }
}
