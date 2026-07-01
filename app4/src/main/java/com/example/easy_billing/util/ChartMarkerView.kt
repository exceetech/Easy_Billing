package com.example.easy_billing.util

import android.content.Context
import android.widget.TextView
import com.example.easy_billing.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * Premium scrubber marker for the Sales trend line chart.
 * Shows the period label (date/month) + revenue, sits above the touched point
 * (drops below near the top edge), and stays clamped inside the chart so it
 * never overlaps the line edges. Slides as the user drags along the line.
 */
class ChartMarkerView(
    context: Context,
    private val labels: List<String>
) : MarkerView(context, R.layout.marker_chart_premium) {

    private val tvDate: TextView = findViewById(R.id.tvMarkDate)
    private val tvValue: TextView = findViewById(R.id.tvMarkValue)

    private val gap = 14f * context.resources.displayMetrics.density

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val i = e?.x?.toInt() ?: 0
        tvDate.text = labels.getOrNull(i) ?: ""
        tvValue.text = CurrencyHelper.format(context, (e?.y ?: 0f).toDouble())

        // Text width changes per point — re-measure so positioning is correct.
        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layout(0, 0, measuredWidth, measuredHeight)

        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - gap)

    /** Keep the pill inside the chart; flip below the point when near the top. */
    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        val w = width.toFloat()
        val h = height.toFloat()
        val cw = chartView?.width?.toFloat() ?: (posX + w)

        var x = -w / 2f
        if (posX + x < 0f) x = -posX
        else if (posX + x + w > cw) x = cw - posX - w

        var y = -h - gap
        if (posY + y < 0f) y = gap   // not enough room above → drop below the point

        return MPPointF(x, y)
    }
}
