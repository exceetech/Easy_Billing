package com.example.easy_billing.util

import android.content.Context
import android.widget.TextView
import com.example.easy_billing.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * Small marker shown above a tapped bar on the Sales bar chart.
 * The bar's [Entry.data] carries a Pair(label, bills); y is the revenue.
 */
class BarValueMarker(context: Context) :
    MarkerView(context, R.layout.marker_sales_bar) {

    private val tvBarLabel: TextView = findViewById(R.id.tvBarLabel)
    private val tvBarValue: TextView = findViewById(R.id.tvBarValue)
    private val tvBarBills: TextView = findViewById(R.id.tvBarBills)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val revenue = e?.y?.toDouble() ?: 0.0
        val data = e?.data as? Pair<*, *>
        val label = data?.first as? String ?: ""
        val bills = data?.second as? Int ?: 0

        tvBarLabel.text = label
        tvBarValue.text = CurrencyHelper.format(context, revenue)
        tvBarBills.text = "$bills bills"

        // Re-measure since text width changes per bar.
        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layout(0, 0, measuredWidth, measuredHeight)

        super.refreshContent(e, highlight)
    }

    // Centered above the bar; MarkerView clamps it inside the chart bounds.
    override fun getOffset(): MPPointF =
        MPPointF(-(width / 2f), -height.toFloat() - 8f)
}
