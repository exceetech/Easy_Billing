package com.example.easy_billing.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import com.example.easy_billing.R
import com.example.easy_billing.ScrapData
import com.example.easy_billing.ScrapRow
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.OverviewResponse
import com.example.easy_billing.network.PeakHourResponse
import com.example.easy_billing.network.TopProductResponse
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Extra numbers for the longer sales report; everything else is derived from the main data. */
data class ReportsPdfExtra(
    val dayRows: List<Triple<String, Int, Double>> = emptyList(),   // label, bills, sales
    val byMonth: Boolean = false,
    val scrap: List<ScrapRow> = emptyList(),
    val notSold: List<String> = emptyList()
)

/**
 * Sales report PDF for the Reports page, drawn in the same style as the Profit and Scrap PDFs.
 * Built entirely on the device from the numbers the Reports tabs already load.
 */
object ReportsPdfGenerator {

    fun hour12(hour: Int, ctx: Activity): String {
        val h = hour % 24
        val n = if (h % 12 == 0) 12 else h % 12
        return "$n " + ctx.getString(if (h < 12) R.string.scrap_am else R.string.scrap_pm)
    }

    suspend fun generate(
        activity: Activity,
        overview: OverviewResponse,
        products: List<TopProductResponse>,
        hours: List<PeakHourResponse>,
        periodText: String,
        extra: ReportsPdfExtra = ReportsPdfExtra()
    ): File {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 32f
        val tracking = 0.015f

        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.letterSpacing = tracking

        val fontRegular = ResourcesCompat.getFont(activity, R.font.googlesans_regular) ?: Typeface.DEFAULT
        val fontMedium = ResourcesCompat.getFont(activity, R.font.googlesans_medium) ?: Typeface.DEFAULT_BOLD
        val fontSemibold = ResourcesCompat.getFont(activity, R.font.googlesans_semibold) ?: Typeface.DEFAULT_BOLD
        val fontSerif = Typeface.SERIF

        val teal = Color.parseColor("#0F6E56")
        val champagne = Color.parseColor("#F3ECDD")
        val champagneLabel = Color.parseColor("#8A6526")
        val zebra = Color.parseColor("#FAF8F3")
        val muted = Color.parseColor("#8A8272")
        val ink = Color.parseColor("#1A1A18")
        val hairline = Color.parseColor("#E4DFD0")
        val amberBorder = Color.parseColor("#C9922E")
        val lossColor = Color.parseColor("#A32D2D")
        val cur = CurrencyHelper.getCurrencySymbol(activity)
        fun money(v: Double) = "$cur%.2f".format(v)
        fun qtyText(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

        fun vector(resId: Int): Bitmap? = try {
            val d = androidx.core.content.ContextCompat.getDrawable(activity, resId)!!
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 100
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 100
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                d.setBounds(0, 0, w, h); d.draw(Canvas(it))
            }
        } catch (e: Exception) { null }
        val brandIcon = vector(R.drawable.ic_scalancer_emblem)
        val brandWordmark = vector(R.drawable.ic_scalancer_wordmark)

        val storeInfo = AppDatabase.getDatabase(activity).storeInfoDao().get()
        val storeName = storeInfo?.name ?: activity.getString(R.string.invoice_pdf_store_default_name)
        val generatedText = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 0f

        fun truncate(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val ell = "…"
            val fit = paint.breakText(text, true, maxWidth - paint.measureText(ell), null)
            return if (fit <= 0) ell else text.substring(0, fit) + ell
        }

        fun drawBrandFooter() {
            paint.style = Paint.Style.FILL
            val iconSize = 16f; val iconGap = 4f; val labelSize = 6f; val labelGap = 3f; val wmH = 7f
            val aspect = if (brandWordmark != null && brandWordmark.height > 0)
                brandWordmark.width.toFloat() / brandWordmark.height else 12.5f
            val wmW = wmH * aspect
            val wmBottom = pageHeight - 12f
            val wmTop = wmBottom - wmH
            paint.typeface = fontMedium
            paint.textSize = labelSize
            paint.letterSpacing = 0.16f
            val label = activity.getString(R.string.powered_by_label)
            val labelW = paint.measureText(label)
            val labelBase = wmTop - labelGap
            val iconBottom = labelBase - labelSize - iconGap
            if (brandIcon != null) {
                val l = (pageWidth - iconSize) / 2f
                canvas.drawBitmap(brandIcon, null, RectF(l, iconBottom - iconSize, l + iconSize, iconBottom), paint)
            }
            paint.color = Color.parseColor("#5A7A85")
            canvas.drawText(label, (pageWidth - labelW) / 2f, labelBase, paint)
            paint.letterSpacing = tracking
            if (brandWordmark != null) {
                val l = (pageWidth - wmW) / 2f
                canvas.drawBitmap(brandWordmark, null, RectF(l, wmTop, l + wmW, wmBottom), paint)
            }
        }

        fun drawHeaderBand(continuation: Boolean) {
            if (!continuation) {
                paint.color = ink
                paint.typeface = fontSerif
                paint.textSize = 21f
                canvas.drawText(storeName, (pageWidth - paint.measureText(storeName)) / 2f, 44f, paint)

                paint.color = muted
                paint.typeface = fontRegular
                paint.textSize = 10f
                val addr = listOfNotNull(
                    storeInfo?.address?.takeIf { it.isNotBlank() },
                    storeInfo?.phone?.takeIf { it.isNotBlank() }?.let { "Phone $it" },
                    storeInfo?.gstin?.takeIf { it.isNotBlank() }?.let { "GSTIN $it" }
                ).joinToString("   ·   ")
                if (addr.isNotEmpty()) canvas.drawText(addr, (pageWidth - paint.measureText(addr)) / 2f, 62f, paint)

                paint.color = teal
                paint.strokeWidth = 1.6f
                canvas.drawLine(margin, 78f, pageWidth - margin, 78f, paint)

                val pill = activity.getString(R.string.reports_pdf_title).uppercase()
                paint.typeface = fontMedium
                paint.textSize = 10f
                val padX = 12f; val pillTop = 94f; val pillH = 22f
                val pillW = paint.measureText(pill) + padX * 2
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRoundRect(margin, pillTop, margin + pillW, pillTop + pillH, pillH / 2, pillH / 2, paint)
                paint.style = Paint.Style.FILL
                canvas.drawText(pill, margin + padX, pillTop + pillH / 2 + 3.5f, paint)

                val labelY = pillTop + 8f
                val valueY = pillTop + 22f
                val genCenter = pageWidth - margin - 55f
                val periodCenter = genCenter - 130f
                fun meta(cx: Float, label: String, value: String) {
                    paint.typeface = fontMedium; paint.textSize = 8.5f; paint.color = muted
                    canvas.drawText(label, cx - paint.measureText(label) / 2f, labelY, paint)
                    paint.typeface = fontRegular; paint.textSize = 11f; paint.color = ink
                    canvas.drawText(value, cx - paint.measureText(value) / 2f, valueY, paint)
                }
                meta(periodCenter, activity.getString(R.string.invoice_pdf_profit_period_label), periodText)
                meta(genCenter, activity.getString(R.string.invoice_pdf_profit_generated_label), generatedText)
                y = pillTop + pillH + 24f
            } else {
                paint.color = ink
                paint.typeface = fontMedium
                paint.textSize = 11f
                canvas.drawText("$storeName — $periodText", margin, 30f, paint)
                paint.color = teal
                paint.strokeWidth = 1.6f
                canvas.drawLine(margin, 40f, pageWidth - margin, 40f, paint)
                y = 40f + 26f
            }
        }

        fun breakPage() {
            drawBrandFooter()
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            drawHeaderBand(true)
        }

        var tableHeader: () -> Unit = {}
        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - 90f) {
                breakPage()
                tableHeader()
            }
        }

        drawHeaderBand(false)

        // ── Two champagne boxes: sales, and what was kept ──
        run {
            val boxTop = y
            val boxW = (pageWidth - margin * 2 - 14f) / 2f
            val lineH = 15f
            val boxH = 32f + 3 * lineH + 14f
            paint.style = Paint.Style.FILL
            paint.color = champagne
            canvas.drawRoundRect(margin, boxTop, margin + boxW, boxTop + boxH, 8f, 8f, paint)
            canvas.drawRoundRect(margin + boxW + 14f, boxTop, margin + boxW * 2 + 14f, boxTop + boxH, 8f, 8f, paint)
            val c1 = margin + 12f; val c1r = margin + boxW - 12f
            val c2 = margin + boxW + 14f + 12f; val c2r = margin + boxW * 2 + 14f - 12f
            paint.color = champagneLabel
            paint.typeface = fontSemibold
            paint.textSize = 8.5f
            canvas.drawText(activity.getString(R.string.reports_pdf_box_sales), c1, boxTop + 16f, paint)
            canvas.drawText(activity.getString(R.string.reports_pdf_box_kept), c2, boxTop + 16f, paint)

            fun kv(lx: Float, rx: Float, ly: Float, label: String, value: String, vc: Int = ink) {
                paint.typeface = fontRegular; paint.textSize = 10f; paint.color = ink
                canvas.drawText(label, lx, ly, paint)
                paint.typeface = fontMedium; paint.color = vc
                canvas.drawText(value, rx - paint.measureText(value), ly, paint)
            }
            val l1 = boxTop + 32f
            kv(c1, c1r, l1, activity.getString(R.string.reports_pdf_revenue), money(overview.total_revenue))
            kv(c1, c1r, l1 + lineH, activity.getString(R.string.reports_pdf_bills), overview.total_bills.toString())
            kv(c1, c1r, l1 + lineH * 2, activity.getString(R.string.reports_pdf_average), money(overview.average_bill))
            kv(c2, c2r, l1, activity.getString(R.string.reports_pdf_returns), "-" + money(overview.returns_total), lossColor)
            val cancelled = activity.getString(R.string.reports_pdf_cancelled) +
                if (overview.cancelled_count > 0) " (${overview.cancelled_count})" else ""
            kv(c2, c2r, l1 + lineH, cancelled, "-" + money(overview.cancelled_amount), lossColor)
            val net = overview.total_revenue - overview.returns_total - overview.cancelled_amount
            kv(c2, c2r, l1 + lineH * 2, activity.getString(R.string.reports_pdf_net), money(net), teal)
            y = boxTop + boxH + 14f

            // Change versus the previous period, when there is something to compare with.
            if (overview.prev_revenue > 0) {
                val pct = (overview.total_revenue - overview.prev_revenue) / overview.prev_revenue * 100
                val txt = activity.getString(R.string.reports_pdf_growth,
                    (if (pct >= 0) "+" else "-") + "%.0f%%".format(kotlin.math.abs(pct)))
                paint.typeface = fontMedium; paint.textSize = 9.5f
                paint.color = if (pct >= 0) teal else lossColor
                canvas.drawText(txt, margin, y + 4f, paint)
                y += 12f
            }
            y += 14f
        }

        fun sectionTitle(t: String) {
            paint.style = Paint.Style.FILL
            paint.color = teal
            paint.typeface = fontSemibold
            paint.textSize = 11f
            canvas.drawText(t.uppercase(), margin, y, paint)
            y += 16f
        }

        fun textW(t: String, face: Typeface, size: Float): Float {
            paint.typeface = face; paint.textSize = size
            return paint.measureText(t)
        }
        val hdrSize = 8f
        fun colW(header: String, values: List<String>, face: Typeface, size: Float, cap: Float = 1000f): Float =
            minOf(cap, maxOf(textW(header, fontSemibold, hdrSize), values.maxOfOrNull { textW(it, face, size) } ?: 0f))

        fun fitCols(natural: List<Float>, flex: List<Int>): Pair<List<Float>, List<Float>> {
            val w = natural.toMutableList()
            val avail = pageWidth - margin * 2
            val minGap = 10f
            val over = w.sum() + minGap * (w.size - 1) - avail
            if (over > 0) {
                val flexSum = flex.sumOf { w[it].toDouble() }.toFloat()
                flex.forEach { w[it] = (w[it] - over * w[it] / flexSum).coerceAtLeast(36f) }
            }
            val gap = ((avail - w.sum()) / (w.size - 1)).coerceAtLeast(minGap)
            val xs = ArrayList<Float>()
            var x = margin
            w.forEach { xs.add(x); x += it + gap }
            return xs to w
        }

        /** Draws one table; [rightCols] are right-aligned, the others centred. */
        fun drawTable(headers: List<String>, rows: List<List<String>>, flex: List<Int>, rightCols: Set<Int>,
                      faces: List<Typeface>, firstColMuted: Boolean) {
            val naturals = headers.indices.map { i ->
                colW(headers[i], rows.map { it[i] }, faces[i], 9.5f, if (i in flex) 180f else 1000f)
            }
            val (xs, ws) = fitCols(naturals, flex)
            fun put(t: String, i: Int) {
                val shown = truncate(t, ws[i])
                val x = if (i in rightCols) xs[i] + ws[i] - paint.measureText(shown)
                        else xs[i] + (ws[i] - paint.measureText(shown)) / 2f
                canvas.drawText(shown, x, y, paint)
            }
            tableHeader = {
                paint.color = teal
                paint.typeface = fontSemibold
                paint.textSize = hdrSize
                headers.indices.forEach { put(headers[it], it) }
                y += 6f
                paint.strokeWidth = 1.4f
                canvas.drawLine(margin, y, pageWidth - margin, y, paint)
                y += 18f
            }
            tableHeader()
            rows.forEachIndexed { r, cells ->
                ensureSpace(24f)
                if (r % 2 == 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = zebra
                    canvas.drawRect(margin, y - 12f, pageWidth - margin, y + 8f, paint)
                }
                cells.indices.forEach { i ->
                    paint.typeface = faces[i]
                    paint.textSize = 9.5f
                    paint.color = if (i == 0 && firstColMuted) muted else ink
                    put(cells[i], i)
                }
                y += 22f
            }
            paint.color = hairline
            paint.strokeWidth = 0.7f
            canvas.drawLine(margin, y - 8f, pageWidth - margin, y - 8f, paint)
            tableHeader = {}
        }

        // ── Compared with before ──
        if (overview.prev_revenue > 0 || overview.prev_bills > 0) {
            ensureSpace(110f)
            sectionTitle(activity.getString(R.string.reports_pdf_cmp_title))
            fun chg(c: Double, p: Double) =
                if (p > 0) (if (c >= p) "+" else "-") + "%.0f%%".format(kotlin.math.abs((c - p) / p * 100)) else "—"
            drawTable(
                listOf("", activity.getString(R.string.reports_pdf_col_now),
                    activity.getString(R.string.reports_pdf_col_before),
                    activity.getString(R.string.reports_pdf_col_change)),
                listOf(
                    listOf(activity.getString(R.string.reports_pdf_revenue), money(overview.total_revenue),
                        money(overview.prev_revenue), chg(overview.total_revenue, overview.prev_revenue)),
                    listOf(activity.getString(R.string.reports_pdf_bills), overview.total_bills.toString(),
                        overview.prev_bills.toString(), chg(overview.total_bills.toDouble(), overview.prev_bills.toDouble())),
                    listOf(activity.getString(R.string.reports_pdf_average), money(overview.average_bill),
                        money(overview.prev_avg), chg(overview.average_bill, overview.prev_avg))
                ),
                flex = listOf(0), rightCols = emptySet(),
                faces = listOf(fontMedium, fontRegular, fontRegular, fontSemibold), firstColMuted = false
            )
            y += 22f
        }

        // ── Day by day / month by month ──
        if (extra.dayRows.isNotEmpty()) {
            ensureSpace(110f)
            sectionTitle(activity.getString(if (extra.byMonth) R.string.reports_pdf_months_title else R.string.reports_pdf_days_title))
            val withSales = extra.dayRows.filter { it.third > 0 }
            if (withSales.isNotEmpty()) {
                val best = withSales.maxByOrNull { it.third }!!
                val worst = withSales.minByOrNull { it.third }!!
                val avg = extra.dayRows.sumOf { it.third } / extra.dayRows.size
                val txt = activity.getString(R.string.reports_pdf_day_summary,
                    best.first, money(best.third), worst.first, money(worst.third), money(avg))
                paint.style = Paint.Style.FILL
                paint.typeface = fontRegular; paint.textSize = 9f; paint.color = muted
                canvas.drawText(truncate(txt, pageWidth - margin * 2), margin, y, paint)
                y += 18f
            }
            drawTable(
                listOf(activity.getString(R.string.reports_pdf_col_date),
                    activity.getString(R.string.reports_pdf_col_bills),
                    activity.getString(R.string.reports_pdf_col_revenue)),
                extra.dayRows.map { listOf(it.first, it.second.toString(), money(it.third)) },
                flex = listOf(0), rightCols = setOf(2),
                faces = listOf(fontMedium, fontRegular, fontSemibold), firstColMuted = false
            )
            y += 22f
        }

        // ── Best sellers (by money) ──
        if (products.isNotEmpty()) {
            sectionTitle(activity.getString(R.string.reports_pdf_top_title))
            val top = products.sortedByDescending { it.revenue }.take(15)
            val total = products.sumOf { it.revenue }
            drawTable(
                listOf("#", activity.getString(R.string.invoice_pdf_profit_col_product),
                    activity.getString(R.string.reports_pdf_col_qty),
                    activity.getString(R.string.reports_pdf_col_bills),
                    activity.getString(R.string.reports_pdf_col_revenue),
                    activity.getString(R.string.reports_pdf_col_share)),
                top.mapIndexed { i, p ->
                    val label = if (!p.variant.isNullOrBlank()) "${p.product} (${p.variant})" else p.product
                    listOf("${i + 1}", label, qtyText(p.quantity) + " " + p.unit, p.frequency.toString(), money(p.revenue),
                        if (total > 0) "%.1f%%".format(p.revenue / total * 100) else "—")
                },
                flex = listOf(1), rightCols = setOf(4),
                faces = listOf(fontRegular, fontMedium, fontRegular, fontRegular, fontSemibold, fontRegular),
                firstColMuted = true
            )
            y += 22f
        }


        // ── Other rankings: by amount sold, by number of bills ──
        if (products.size >= 2) {
            fun rankTable(title: String, list: List<TopProductResponse>) {
                ensureSpace(110f)
                sectionTitle(title)
                drawTable(
                    listOf("#", activity.getString(R.string.invoice_pdf_profit_col_product),
                        activity.getString(R.string.reports_pdf_col_qty),
                        activity.getString(R.string.reports_pdf_col_bills),
                        activity.getString(R.string.reports_pdf_col_revenue)),
                    list.mapIndexed { i, p ->
                        val label = if (!p.variant.isNullOrBlank()) "${p.product} (${p.variant})" else p.product
                        listOf("${i + 1}", label, qtyText(p.quantity) + " " + p.unit, p.frequency.toString(), money(p.revenue))
                    },
                    flex = listOf(1), rightCols = setOf(4),
                    faces = listOf(fontRegular, fontMedium, fontRegular, fontRegular, fontSemibold), firstColMuted = true
                )
                y += 22f
            }
            rankTable(activity.getString(R.string.products_sort_fast), products.sortedByDescending { it.quantity }.take(5))
            rankTable(activity.getString(R.string.products_sort_popular), products.sortedByDescending { it.frequency }.take(5))
        }

        // ── Slowest sellers + products that did not sell ──
        if (products.size >= 6) {
            ensureSpace(110f)
            sectionTitle(activity.getString(R.string.reports_pdf_slow_title))
            drawTable(
                listOf("#", activity.getString(R.string.invoice_pdf_profit_col_product),
                    activity.getString(R.string.reports_pdf_col_qty),
                    activity.getString(R.string.reports_pdf_col_bills),
                    activity.getString(R.string.reports_pdf_col_revenue)),
                products.sortedBy { it.revenue }.take(5).mapIndexed { i, p ->
                    val label = if (!p.variant.isNullOrBlank()) "${p.product} (${p.variant})" else p.product
                    listOf("${i + 1}", label, qtyText(p.quantity) + " " + p.unit, p.frequency.toString(), money(p.revenue))
                },
                flex = listOf(1), rightCols = setOf(4),
                faces = listOf(fontRegular, fontMedium, fontRegular, fontRegular, fontSemibold), firstColMuted = true
            )
            y += 22f
        }
        if (extra.notSold.isNotEmpty()) {
            ensureSpace(80f)
            sectionTitle(activity.getString(R.string.reports_pdf_notsold_title))
            paint.style = Paint.Style.FILL
            paint.typeface = fontRegular; paint.textSize = 9.5f; paint.color = ink
            val names = extra.notSold.take(40)
            val maxW = pageWidth - margin * 2
            val lines = ArrayList<String>()
            var line = ""
            names.forEachIndexed { i, n ->
                val piece = (if (line.isEmpty()) "" else ", ") + n
                if (line.isNotEmpty() && paint.measureText(line + piece) > maxW) { lines.add("$line,"); line = n } else line += piece
                if (i == names.lastIndex) lines.add(line)
            }
            if (extra.notSold.size > 40) lines[lines.lastIndex] = lines.last() + "  " +
                activity.getString(R.string.products_not_sold_more, extra.notSold.size - 40)
            lines.forEach { l ->
                ensureSpace(16f)
                paint.typeface = fontRegular; paint.textSize = 9.5f; paint.color = ink
                canvas.drawText(l, margin, y, paint)
                y += 15f
            }
            y += 14f
        }

        // ── Time of day ──
        run {
            val total = hours.sumOf { it.revenue }
            if (total > 0) {
                fun partOf(h: Int) = when (h) { in 5..11 -> 0; in 12..16 -> 1; in 17..20 -> 2; else -> 3 }
                val names = listOf(R.string.fragment_overview_busy_morning, R.string.fragment_overview_busy_afternoon,
                    R.string.fragment_overview_busy_evening, R.string.fragment_overview_busy_night)
                val spans = listOf(5 to 12, 12 to 17, 17 to 21, 21 to 5)
                ensureSpace(130f)
                sectionTitle(activity.getString(R.string.reports_pdf_tod_title))
                drawTable(
                    listOf(activity.getString(R.string.reports_pdf_col_part), activity.getString(R.string.reports_pdf_col_time),
                        activity.getString(R.string.reports_pdf_col_bills), activity.getString(R.string.reports_pdf_col_revenue),
                        activity.getString(R.string.reports_pdf_col_share)),
                    (0..3).map { i ->
                        val l = hours.filter { partOf(it.hour) == i }
                        val rev = l.sumOf { it.revenue }
                        listOf(activity.getString(names[i]).lowercase().replaceFirstChar { it.uppercase() },
                            hour12(spans[i].first, activity) + " – " + hour12(spans[i].second, activity),
                            l.sumOf { it.bills }.toString(), money(rev), "%.1f%%".format(rev / total * 100))
                    },
                    flex = listOf(1), rightCols = setOf(3),
                    faces = listOf(fontMedium, fontRegular, fontRegular, fontSemibold, fontRegular), firstColMuted = false
                )
                y += 22f
            }
        }
        // ── Busy hours ──
        val byHour = hours.groupBy { it.hour }
            .map { (h, l) -> Triple(h, l.sumOf { it.bills }, l.sumOf { it.revenue }) }
            .filter { it.third > 0 || it.second > 0 }
            .sortedByDescending { it.third }
            .take(8)
        if (byHour.isNotEmpty()) {
            ensureSpace(90f)
            sectionTitle(activity.getString(R.string.reports_pdf_busy_title))
            val all = hours.sumOf { it.revenue }
            drawTable(
                listOf("#", activity.getString(R.string.reports_pdf_col_time),
                    activity.getString(R.string.reports_pdf_col_bills),
                    activity.getString(R.string.reports_pdf_col_revenue),
                    activity.getString(R.string.reports_pdf_col_share)),
                byHour.mapIndexed { i, (h, b, rev) ->
                    listOf("${i + 1}", hour12(h, activity) + " – " + hour12(h + 1, activity), b.toString(), money(rev),
                        if (all > 0) "%.1f%%".format(rev / all * 100) else "—")
                },
                flex = listOf(1), rightCols = setOf(3),
                faces = listOf(fontRegular, fontMedium, fontRegular, fontSemibold, fontRegular),
                firstColMuted = true
            )
            y += 14f
        }


        // ── Scrap in this period ──
        if (extra.scrap.isNotEmpty()) {
            ensureSpace(120f)
            sectionTitle(activity.getString(R.string.reports_pdf_scrap_title))
            val loss = extra.scrap.sumOf { it.loss }
            val share = if (overview.total_revenue > 0) "%.1f%%".format(loss / overview.total_revenue * 100) else "—"
            fun cap(r: Int) = activity.getString(r).lowercase().replaceFirstChar { it.uppercase() }
            fun kvLine(label: String, value: String, vc: Int) {
                paint.style = Paint.Style.FILL
                paint.typeface = fontRegular; paint.textSize = 10f; paint.color = ink
                canvas.drawText(label, margin + 4f, y, paint)
                paint.typeface = fontMedium; paint.color = vc
                canvas.drawText(value, pageWidth - margin - 4f - paint.measureText(value), y, paint)
                y += 8f
                paint.color = hairline; paint.strokeWidth = 0.7f
                canvas.drawLine(margin, y, pageWidth - margin, y, paint)
                y += 14f
            }
            kvLine(activity.getString(R.string.scrap_pdf_money_lost), "-" + money(loss), lossColor)
            kvLine(cap(R.string.scrap_events_label), ScrapData.events(extra.scrap).toString(), ink)
            kvLine(activity.getString(R.string.scrap_pdf_products_label), extra.scrap.map { it.name }.distinct().size.toString(), ink)
            kvLine(cap(R.string.scrap_share_label), share, ink)
            y += 10f
        }

        ensureSpace(60f)
        paint.color = teal
        paint.strokeWidth = 1.6f
        canvas.drawLine(margin, y, pageWidth - margin, y, paint)
        y += 20f
        paint.color = muted
        paint.typeface = fontRegular
        paint.textSize = 9.5f
        val footer = activity.getString(R.string.invoice_pdf_footer_default)
        canvas.drawText(footer, (pageWidth - paint.measureText(footer)) / 2f, y, paint)

        drawBrandFooter()
        document.finishPage(page)

        val dir = File(activity.cacheDir, "sales_reports").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val safeName = storeName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Store" }
        val file = File(dir, "${safeName}_Sales_Report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }
}
