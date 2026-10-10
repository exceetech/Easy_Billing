package com.example.easy_billing.util

import android.content.Context
import android.widget.TextView
import com.example.easy_billing.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * Dark tooltip bubble for the Sales trend line chart — same layout and
 * positioning as the Profit Analytics trend marker (marker_trend_tooltip).
 */
class ChartMarkerView(
    context: Context,
    private val labels: List<String>
) : MarkerView(context, R.layout.marker_trend_tooltip) {

    private val tvLabel: TextView = findViewById(R.id.tvMarkerLabel)
    private val tvAmount: TextView = findViewById(R.id.tvMarkerAmount)
    private var lastIndex = -1

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val i = e?.x?.toInt() ?: -1
        lastIndex = i
        tvLabel.text = labels.getOrNull(i) ?: ""
        tvAmount.text = CurrencyHelper.format(context, (e?.y ?: 0f).toDouble())
        super.refreshContent(e, highlight)
    }

    // Centered above the point; the last two points open to the left so the
    // bubble is never clipped at the right edge.
    override fun getOffset(): MPPointF {
        val nearRightEdge = labels.isNotEmpty() && lastIndex >= labels.size - 2
        val offsetX = if (nearRightEdge) -width.toFloat() - 6f else -(width / 2f)
        return MPPointF(offsetX, -height.toFloat() - 10f)
    }
}
