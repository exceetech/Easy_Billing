package com.example.easy_billing.util

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.Utils
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * BarChartRenderer that rounds the TOP corners of each bar and preserves
 * per-bar colors (so the "best" bar can be highlighted). Matches the Sales
 * mockup (border-radius 4, flat bottom on the axis).
 */
class RoundedBarChartRenderer(
    chart: BarChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler,
    private val radiusDp: Float = 4f
) : BarChartRenderer(chart, animator, viewPortHandler) {

    private val barPath = Path()
    private val rect = RectF()

    override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
        val trans = mChart.getTransformer(dataSet.axisDependency)
        val radius = Utils.convertDpToPixel(radiusDp)

        // Safety check: ensure buffers are initialized and data is available
        val barData = mChart.barData ?: return
        
        if (mBarBuffers == null || index >= mBarBuffers.size) {
            initBuffers()
        }

        val buffers = mBarBuffers
        if (buffers == null || index >= buffers.size) return
        val buffer = buffers[index] ?: return

        buffer.setPhases(mAnimator.phaseX, mAnimator.phaseY)
        buffer.setDataSet(index)
        buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
        buffer.setBarWidth(barData.barWidth)
        buffer.feed(dataSet)
        trans.pointValuesToPixel(buffer.buffer)

        val isSingleColor = dataSet.colors.size == 1
        if (isSingleColor) mRenderPaint.color = dataSet.color

        var j = 0
        while (j < buffer.size()) {
            if (!mViewPortHandler.isInBoundsLeft(buffer.buffer[j + 2])) { j += 4; continue }
            if (!mViewPortHandler.isInBoundsRight(buffer.buffer[j])) break

            if (!isSingleColor) mRenderPaint.color = dataSet.getColor(j / 4)

            rect.set(buffer.buffer[j], buffer.buffer[j + 1], buffer.buffer[j + 2], buffer.buffer[j + 3])
            barPath.reset()
            barPath.addRoundRect(
                rect,
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                Path.Direction.CW
            )
            c.drawPath(barPath, mRenderPaint)
            j += 4
        }
    }
}
