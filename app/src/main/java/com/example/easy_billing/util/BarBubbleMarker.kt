package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.TextView
import com.example.easy_billing.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * Small dark bubble with a little pointer, shown above the tapped bar on the
 * Sales bar chart. It is kept fully inside the chart (with a small margin) so
 * the bars near the left/right edge never get a clipped bubble — the pointer
 * still aims at the bar.
 * The bar's [Entry.data] carries Pair(label, bills); y is the revenue.
 */
class BarBubbleMarker(context: Context) :
    MarkerView(context, R.layout.marker_bar_bubble) {

    private val tvLabel: TextView = findViewById(R.id.tvBubbleLabel)
    private val tvValue: TextView = findViewById(R.id.tvBubbleValue)

    private val density = context.resources.displayMetrics.density
    private val margin = 4f * density
    private val caretH = 5f * density
    private val caretW = 6f * density
    private val gap = 3f * density

    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#123B31")
        style = Paint.Style.FILL
    }
    private val caretPath = Path()

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val data = e?.data as? Pair<*, *>
        tvLabel.text = data?.first as? String ?: ""
        tvValue.text = CurrencyHelper.format(context, (e?.y ?: 0f).toDouble())

        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layout(0, 0, measuredWidth, measuredHeight)
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - caretH - gap)

    /** Clamp to the chart with a margin; flip below the bar only if there is no room above. */
    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = chartView?.width?.toFloat() ?: (posX + w)

        var x = -w / 2f
        if (posX + x < margin) x = margin - posX
        else if (posX + x + w > chartW - margin) x = chartW - margin - w - posX

        var y = -h - caretH - gap
        if (posY + y < margin) y = margin - posY
        return MPPointF(x, y)
    }

    override fun draw(canvas: Canvas, posX: Float, posY: Float) {
        val off = getOffsetForDrawingAtPoint(posX, posY)
        val save = canvas.save()
        canvas.translate(posX + off.x, posY + off.y)
        draw(canvas)

        // Pointer: aims at the bar, kept inside the rounded corners of the bubble.
        val minX = caretW + 6f * density
        val cx = (-off.x).coerceIn(minX, width - minX)
        caretPath.reset()
        caretPath.moveTo(cx - caretW, height.toFloat() - 1f)
        caretPath.lineTo(cx, height + caretH)
        caretPath.lineTo(cx + caretW, height.toFloat() - 1f)
        caretPath.close()
        canvas.drawPath(caretPath, caretPaint)
        canvas.restoreToCount(save)
    }
}
