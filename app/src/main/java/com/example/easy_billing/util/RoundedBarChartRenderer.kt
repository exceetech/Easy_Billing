package com.example.easy_billing.util

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

class RoundedBarChartRenderer(
    chart: BarChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : BarChartRenderer(chart, animator, viewPortHandler) {

    private val radius = 20f

    override fun drawDataSet(c: Canvas, dataSet: com.github.mikephil.charting.interfaces.datasets.IBarDataSet, index: Int) {

        val buffer = mBarBuffers[index]
        val trans = mChart.getTransformer(dataSet.axisDependency)

        for (j in 0 until buffer.size() step 4) {

            val left = buffer.buffer[j]
            val top = buffer.buffer[j + 1]
            val right = buffer.buffer[j + 2]
            val bottom = buffer.buffer[j + 3]

            val rect = RectF(left, top, right, bottom)

            c.drawRoundRect(rect, radius, radius, mRenderPaint)
        }
    }
}