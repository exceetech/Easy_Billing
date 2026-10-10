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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scrap report PDF, drawn in the same style as the Profit report PDF
 * (serif store header, teal rule, outlined pill, champagne boxes, striped table,
 * amber totals box, Scalancer footer). Returns the file so the caller can share it.
 */
object ScrapPdfGenerator {

    suspend fun generate(
        activity: Activity,
        rows: List<ScrapRow>,
        periodText: String,
        revenue: Double?,
        recent: List<ScrapRow> = emptyList()
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

        // ── Numbers ──
        val total = rows.sumOf { it.loss }
        val times = ScrapData.events(rows)
        val byProduct = rows.groupBy { it.name }.map { (n, l) ->
            Triple(n, l, l.sumOf { it.loss })
        }.sortedByDescending { it.third }
        val reasons = listOf("Expired", "Damaged", "Spoiled", "Other")
        fun reasonLabel(r: String) = activity.getString(when (r) {
            "Expired" -> R.string.scrap_reason_expired
            "Damaged" -> R.string.scrap_reason_damaged
            "Spoiled" -> R.string.scrap_reason_spoiled
            else -> R.string.scrap_reason_other
        })
        fun qtyText(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

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

                val pill = activity.getString(R.string.scrap_pdf_title).uppercase()
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
                canvas.drawText("$storeName — continued", margin, 30f, paint)
                paint.color = teal
                paint.strokeWidth = 1.6f
                canvas.drawLine(margin, 40f, pageWidth - margin, 40f, paint)
                y = 40f + 26f
            }
        }

        drawHeaderBand(false)

        // ── Summary boxes: scrap summary (left), by reason (right) ──
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
            canvas.drawText(activity.getString(R.string.scrap_pdf_box_summary), c1, boxTop + 16f, paint)
            canvas.drawText(activity.getString(R.string.scrap_pdf_box_reason), c2, boxTop + 16f, paint)

            fun kv(lx: Float, rx: Float, ly: Float, label: String, value: String, vc: Int = ink) {
                paint.typeface = fontRegular; paint.textSize = 10f; paint.color = ink
                canvas.drawText(label, lx, ly, paint)
                paint.typeface = fontMedium; paint.color = vc
                canvas.drawText(value, rx - paint.measureText(value), ly, paint)
            }
            val l1 = boxTop + 32f
            val share = if (revenue != null && revenue > 0) "%.1f%%".format(total / revenue * 100) else "—"
            kv(c1, c1r, l1, activity.getString(R.string.scrap_pdf_money_lost), "-" + money(total), lossColor)
            kv(c1, c1r, l1 + lineH, activity.getString(R.string.scrap_events_label).lowercase().replaceFirstChar { it.uppercase() }, times.toString())
            kv(c1, c1r, l1 + lineH * 2, activity.getString(R.string.scrap_share_label).lowercase().replaceFirstChar { it.uppercase() }, share)

            // top three reasons by loss
            val top = reasons.map { it to rows.filter { r -> r.reason == it }.sumOf { r -> r.loss } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
            top.forEachIndexed { i, (r, v) -> kv(c2, c2r, l1 + lineH * i, reasonLabel(r), money(v)) }
            y = boxTop + boxH + 22f
        }

        // ── Product table ──
        // # | PRODUCT | REASON | QTY | TIMES | LAST | AVG/TIME | LOSS | SHARE
        // Every column gets a box sized from its widest text, spread across the page with equal
        // space between boxes. Amount columns (AVG/TIME, LOSS) are right-aligned; everything else
        // (headings included) is centred in its box.
        val dayFmt = com.example.easy_billing.util.AppTime.formatter("dd MMM")
        val dateParse = com.example.easy_billing.util.AppTime.isoDate()

        fun textW(t: String, face: Typeface, size: Float): Float {
            paint.typeface = face; paint.textSize = size
            return paint.measureText(t)
        }
        val hdrFace = fontSemibold
        val hdrSize = 8f
        fun colW(header: String, values: List<String>, face: Typeface, size: Float, cap: Float = 1000f): Float =
            minOf(cap, maxOf(textW(header, hdrFace, hdrSize), values.maxOfOrNull { textW(it, face, size) } ?: 0f))

        /** Box x-positions and widths for the given natural widths; only [flex] columns shrink to fit. */
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

        val pEvents = byProduct.map { ScrapData.events(it.second) }
        val pLast = byProduct.map { (_, l, _) ->
            val d = l.maxOf { it.date }
            try { dayFmt.format(dateParse.parse(d)!!) } catch (e: Exception) { d }
        }
        val pMainReason = byProduct.map { (_, l, _) ->
            reasonLabel(reasons.maxByOrNull { r -> l.filter { it.reason == r }.sumOf { it.loss } } ?: "Other")
        }
        val (px, pw) = fitCols(listOf(
            textW("${byProduct.size}", fontRegular, 9f).coerceAtLeast(textW("#", hdrFace, hdrSize)),
            colW(activity.getString(R.string.invoice_pdf_profit_col_product), byProduct.map { it.first }, fontMedium, 9.5f, 170f),
            colW(activity.getString(R.string.scrap_pdf_col_reason), pMainReason, fontRegular, 9f),
            colW(activity.getString(R.string.scrap_pdf_col_qty), byProduct.map { qtyText(it.second.sumOf { e -> e.qty }) }, fontRegular, 9.5f),
            colW(activity.getString(R.string.scrap_pdf_col_times), pEvents.map { it.toString() } + times.toString(), fontRegular, 9.5f),
            colW(activity.getString(R.string.scrap_pdf_col_last), pLast, fontRegular, 9.5f),
            colW(activity.getString(R.string.scrap_pdf_col_avg), byProduct.mapIndexed { i, p -> "$cur%.0f".format(p.third / pEvents[i].coerceAtLeast(1)) }, fontRegular, 9.5f),
            colW(activity.getString(R.string.scrap_pdf_col_loss), byProduct.map { "-" + money(it.third) } + ("-" + money(total)), fontSemibold, 9.5f),
            colW(activity.getString(R.string.scrap_pdf_col_share), byProduct.map { if (total > 0) "%.1f%%".format(it.third / total * 100) else "—" } + "100%", fontRegular, 9.5f)
        ), listOf(1, 2))

        fun ctr(t: String, i: Int) {
            val shown = truncate(t, pw[i])
            canvas.drawText(shown, px[i] + (pw[i] - paint.measureText(shown)) / 2f, y, paint)
        }
        fun rgt(t: String, i: Int) = canvas.drawText(t, px[i] + pw[i] - paint.measureText(t), y, paint)

        fun drawTableHeader() {
            paint.color = teal
            paint.typeface = fontSemibold
            paint.textSize = hdrSize
            ctr("#", 0)
            ctr(activity.getString(R.string.invoice_pdf_profit_col_product), 1)
            ctr(activity.getString(R.string.scrap_pdf_col_reason), 2)
            ctr(activity.getString(R.string.scrap_pdf_col_qty), 3)
            ctr(activity.getString(R.string.scrap_pdf_col_times), 4)
            ctr(activity.getString(R.string.scrap_pdf_col_last), 5)
            rgt(activity.getString(R.string.scrap_pdf_col_avg), 6)
            rgt(activity.getString(R.string.scrap_pdf_col_loss), 7)
            ctr(activity.getString(R.string.scrap_pdf_col_share), 8)
            y += 6f
            paint.strokeWidth = 1.4f
            canvas.drawLine(margin, y, pageWidth - margin, y, paint)
            y += 18f
        }

        var recentMode = false
        var recentHeader: () -> Unit = {}

        fun breakPage(fresh: Boolean = false) {
            drawBrandFooter()
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            drawHeaderBand(!fresh)
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - 170f) {
                breakPage()
                if (recentMode) recentHeader() else drawTableHeader()
            }
        }

        drawTableHeader()
        byProduct.forEachIndexed { index, (name, list, loss) ->
            val rowH = 38f
            ensureSpace(rowH)
            if (index % 2 == 0) {
                paint.style = Paint.Style.FILL
                paint.color = zebra
                canvas.drawRect(margin, y - 12f, pageWidth - margin, y - 12f + rowH - 8f, paint)
            }
            paint.typeface = fontRegular
            paint.textSize = 9f
            paint.color = muted
            ctr("${index + 1}", 0)

            paint.color = ink
            paint.typeface = fontMedium
            paint.textSize = 9.5f
            ctr(name, 1)

            // Main reason in its column; any other reasons in small text under the product name.
            paint.typeface = fontRegular
            paint.textSize = 9f
            paint.color = ink
            ctr(pMainReason[index], 2)
            val others = reasons.filter { r -> list.any { it.reason == r } }
                .sortedByDescending { r -> list.filter { it.reason == r }.sumOf { it.loss } }
                .drop(1)
            if (others.isNotEmpty()) {
                paint.color = muted
                paint.textSize = 7.5f
                val saveY = y
                y += 12f
                ctr("+ " + others.joinToString(" · ") { reasonLabel(it) }, 1)
                y = saveY
            }

            paint.typeface = fontRegular
            paint.textSize = 9.5f
            paint.color = ink
            ctr(qtyText(list.sumOf { it.qty }), 3)
            ctr(pEvents[index].toString(), 4)
            ctr(pLast[index], 5)
            rgt("$cur%.0f".format(loss / pEvents[index].coerceAtLeast(1)), 6)
            paint.color = lossColor
            paint.typeface = fontMedium
            rgt("-" + money(loss), 7)
            paint.color = ink
            paint.typeface = fontRegular
            ctr(if (total > 0) "%.1f%%".format(loss / total * 100) else "—", 8)
            y += rowH
        }

        // Totals row, in the same columns.
        ensureSpace(30f)
        paint.color = teal
        paint.strokeWidth = 1.2f
        canvas.drawLine(margin, y - 12f, pageWidth - margin, y - 12f, paint)
        y += 4f
        paint.typeface = fontSemibold
        paint.textSize = 9.5f
        paint.color = teal
        ctr(activity.getString(R.string.scrap_pdf_total_lost).uppercase(), 1)
        paint.color = ink
        ctr(times.toString(), 4)
        paint.color = lossColor
        rgt("-" + money(total), 7)
        paint.color = ink
        ctr("100%", 8)
        y += 14f

        paint.color = hairline
        paint.strokeWidth = 0.7f
        canvas.drawLine(margin, y, pageWidth - margin, y, paint)
        y += 32f
        ensureSpace(110f)

        // ── Amber totals box ──
        val pad = 14f
        val outerR = pageWidth - margin
        val outerL = outerR - 240f
        val tR = outerR - pad
        val tL = outerL + pad
        val boxTop2 = y - 16f
        paint.typeface = fontRegular
        paint.textSize = 10.5f
        paint.color = muted
        canvas.drawText(activity.getString(R.string.scrap_pdf_products_label), tL, y, paint)
        paint.color = ink
        val pc = byProduct.size.toString()
        canvas.drawText(pc, tR - paint.measureText(pc), y, paint)
        y += 20f
        paint.color = muted
        canvas.drawText(activity.getString(R.string.scrap_pdf_times_label), tL, y, paint)
        paint.color = ink
        val tc = times.toString()
        canvas.drawText(tc, tR - paint.measureText(tc), y, paint)
        y += 12f
        paint.strokeWidth = 1f
        paint.color = hairline
        canvas.drawLine(tL, y, tR, y, paint)
        y += 20f
        paint.color = teal
        paint.typeface = fontMedium
        paint.textSize = 12f
        canvas.drawText(activity.getString(R.string.scrap_pdf_total_lost).uppercase(), tL, y, paint)
        paint.color = lossColor
        paint.typeface = fontSerif
        paint.textSize = 16f
        val totalText = "-" + money(total)
        canvas.drawText(totalText, tR - paint.measureText(totalText), y, paint)
        val boxBottom = y + 10f
        paint.color = amberBorder
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        canvas.drawRoundRect(RectF(outerL, boxTop2, outerR, boxBottom), 10f, 10f, paint)
        paint.style = Paint.Style.FILL
        y = boxBottom + 28f

        // ── Recent scraps (last 3 months, newest first) ──
        if (recent.isNotEmpty()) {
            // Always starts on its own page, with the full report header (not a continuation).
            breakPage(fresh = true)
            paint.style = Paint.Style.FILL
            paint.color = teal
            paint.typeface = fontSemibold
            paint.textSize = 11f
            canvas.drawText(activity.getString(R.string.scrap_pdf_recent_title).uppercase(), margin, y, paint)
            paint.color = muted
            paint.typeface = fontRegular
            paint.textSize = 9f
            canvas.drawText(activity.getString(R.string.scrap_pdf_recent_sub, recent.size), margin, y + 14f, paint)
            y += 36f

            val fullDay = com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy")
            fun timeOf(e: ScrapRow): String {
                val h24 = e.dateTime.getOrNull(13)?.let { e.dateTime.substring(11, 13).toIntOrNull() } ?: 0
                val mm = e.dateTime.getOrNull(15)?.let { e.dateTime.substring(14, 16) } ?: "00"
                val h12 = if (h24 % 12 == 0) 12 else h24 % 12
                return "$h12:$mm " + activity.getString(if (h24 < 12) R.string.scrap_am else R.string.scrap_pm)
            }
            fun dateOf(e: ScrapRow) = try { fullDay.format(dateParse.parse(e.date)!!) } catch (ex: Exception) { e.date }
            // DATE | TIME | PRODUCT | REASON | QTY | LOSS -- same rules: amount right, rest centred.
            val (rx, rw) = fitCols(listOf(
                colW(activity.getString(R.string.scrap_pdf_col_date), recent.map { dateOf(it) }, fontRegular, 9f),
                colW(activity.getString(R.string.scrap_pdf_col_time), recent.map { timeOf(it) }, fontRegular, 9f),
                colW(activity.getString(R.string.invoice_pdf_profit_col_product), recent.map { it.name }, fontMedium, 9f, 190f),
                colW(activity.getString(R.string.scrap_pdf_col_reason), recent.map { reasonLabel(it.reason) }, fontRegular, 9f),
                colW(activity.getString(R.string.scrap_pdf_col_qty), recent.map { qtyText(it.qty) }, fontRegular, 9f),
                colW(activity.getString(R.string.scrap_pdf_col_loss), recent.map { "-" + money(it.loss) }, fontMedium, 9f)
            ), listOf(2))
            fun rctr(t: String, i: Int) {
                val shown = truncate(t, rw[i])
                canvas.drawText(shown, rx[i] + (rw[i] - paint.measureText(shown)) / 2f, y, paint)
            }
            fun rrgt(t: String, i: Int) = canvas.drawText(t, rx[i] + rw[i] - paint.measureText(t), y, paint)

            recentHeader = {
                paint.color = teal
                paint.typeface = fontSemibold
                paint.textSize = hdrSize
                rctr(activity.getString(R.string.scrap_pdf_col_date), 0)
                rctr(activity.getString(R.string.scrap_pdf_col_time), 1)
                rctr(activity.getString(R.string.invoice_pdf_profit_col_product), 2)
                rctr(activity.getString(R.string.scrap_pdf_col_reason), 3)
                rctr(activity.getString(R.string.scrap_pdf_col_qty), 4)
                rrgt(activity.getString(R.string.scrap_pdf_col_loss), 5)
                y += 6f
                paint.strokeWidth = 1.4f
                canvas.drawLine(margin, y, pageWidth - margin, y, paint)
                y += 18f
            }
            recentMode = true
            recentHeader()

            recent.forEachIndexed { index, e ->
                val rowH = 26f
                ensureSpace(rowH)
                if (index % 2 == 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = zebra
                    canvas.drawRect(margin, y - 12f, pageWidth - margin, y - 12f + rowH - 4f, paint)
                }
                paint.typeface = fontRegular
                paint.textSize = 9f
                paint.color = ink
                rctr(dateOf(e), 0)
                paint.color = muted
                rctr(timeOf(e), 1)
                paint.color = ink
                paint.typeface = fontMedium
                rctr(e.name, 2)
                paint.typeface = fontRegular
                rctr(reasonLabel(e.reason), 3)
                rctr(qtyText(e.qty), 4)
                paint.color = lossColor
                paint.typeface = fontMedium
                rrgt("-" + money(e.loss), 5)
                y += rowH
            }
            paint.color = hairline
            paint.strokeWidth = 0.7f
            canvas.drawLine(margin, y - 8f, pageWidth - margin, y - 8f, paint)
            recentMode = false
            ensureSpace(60f)
            y += 14f
        }

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

        val dir = File(activity.cacheDir, "scrap_reports").apply { mkdirs() }
        // Old shared reports (> 1 day) are no longer needed here: the share sheet already copied them.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        val safeName = storeName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Store" }
        val file = File(dir, "${safeName}_Scrap_Report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }
}
