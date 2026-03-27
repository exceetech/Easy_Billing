package com.example.easy_billing.util

import android.content.Context
import android.widget.TextView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class BarChartMarker(context: Context) :
    MarkerView(context, R.layout.marker_bar_chart) {

    private val tvPrimary: TextView = findViewById(R.id.tvPrimary)
    private val tvSecondary: TextView = findViewById(R.id.tvSecondary)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {

        val value = e?.y ?: 0f

        when (highlight?.dataSetIndex) {

            0 -> {
                // 🔵 Revenue → use CurrencyHelper
                tvPrimary.text = CurrencyHelper.format(context, value.toDouble())
                tvSecondary.text = "Revenue"
            }

            1 -> {
                // 🟣 Bills → plain count
                tvPrimary.text = value.toInt().toString()
                tvSecondary.text = "Bills"
            }
        }

        super.refreshContent(e, highlight)
    }

    // 🔥 Perfect positioning above bar
    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2).toFloat(), -height.toFloat() - 12f)
    }
}