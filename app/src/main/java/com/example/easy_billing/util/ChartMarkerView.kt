package com.example.easy_billing.util

import android.content.Context
import android.widget.TextView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class ChartMarkerView(context: Context) :
    MarkerView(context, R.layout.marker_view) {

    private val tvValue: TextView = findViewById(R.id.tvValue)
    private val tvIndex: TextView = findViewById(R.id.tvIndex)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {

        val value = e?.y ?: 0f
        val index = e?.x?.toInt() ?: 0

        tvValue.text = CurrencyHelper.format(context, value.toDouble())
        tvIndex.text = "On: $index"

        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2).toFloat(), -height.toFloat() - 10)
    }
}
