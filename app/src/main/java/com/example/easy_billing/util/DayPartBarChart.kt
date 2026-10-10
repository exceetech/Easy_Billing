package com.example.easy_billing.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.utils.MPPointD

/**
 * BarChart that can paint soft Morning / Afternoon / Evening bands behind the
 * bars (hourly view only). The bands follow zoom and drag because they are
 * positioned through the chart's own transformer.
 */
class DayPartBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BarChart(context, attrs, defStyleAttr) {

    var showDayParts = false
    /** Order: morning, afternoon, evening, night. */
    var partLabels: List<String> = listOf("MORNING", "AFTERNOON", "EVENING", "NIGHT")

    private val density = resources.displayMetrics.density
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A89C84")
        textAlign = Paint.Align.CENTER
        textSize = 6.5f * resources.displayMetrics.scaledDensity
    }
    private val rect = RectF()

    // from-hour, to-hour (exclusive), light translucent tint, label index
    private class Band(val from: Float, val to: Float, val argb: Int, val labelIdx: Int)

    private val bands = listOf(
        Band(0f, 5f,   Color.parseColor("#168FA3B8"), 3),   // night (small hours)
        Band(5f, 12f,  Color.parseColor("#1CF2C75C"), 0),   // morning
        Band(12f, 17f, Color.parseColor("#1477B8A6"), 1),   // afternoon
        Band(17f, 21f, Color.parseColor("#18E0955A"), 2),   // evening
        Band(21f, 24f, Color.parseColor("#168FA3B8"), 3)    // night (late)
    )

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (showDayParts && data != null) {
            val t = getTransformer(YAxis.AxisDependency.LEFT)
            val r = viewPortHandler.contentRect
            canvas.save()
            canvas.clipRect(r)
            bands.forEach { band ->
                val p0 = t.getPixelForValues(band.from - 0.5f, 0f)
                val p1 = t.getPixelForValues(band.to - 0.5f, 0f)
                rect.set(p0.x.toFloat(), r.top, p1.x.toFloat(), r.bottom)
                bandPaint.color = band.argb
                canvas.drawRect(rect, bandPaint)

                val label = partLabels.getOrNull(band.labelIdx).orEmpty()
                val left = maxOf(rect.left, r.left)
                val right = minOf(rect.right, r.right)
                if (label.isNotEmpty() && right - left > textPaint.measureText(label) + 6f * density) {
                    canvas.drawText(label, (left + right) / 2f, r.top + textPaint.textSize + 3f * density, textPaint)
                }
                MPPointD.recycleInstance(p0)
                MPPointD.recycleInstance(p1)
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }
}
