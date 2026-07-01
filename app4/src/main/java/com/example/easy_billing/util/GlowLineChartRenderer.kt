package com.example.easy_billing.util

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * LineChartRenderer that blurs the dataset labelled "glow" so a thick,
 * translucent copy of the line renders as a soft glow beneath the crisp line.
 *
 * Requires the chart view to use LAYER_TYPE_SOFTWARE (BlurMaskFilter is
 * ignored on hardware layers).
 */
class GlowLineChartRenderer(
    chart: LineDataProvider,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : LineChartRenderer(chart, animator, viewPortHandler) {

    private val glow = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)

    override fun drawDataSet(c: Canvas, dataSet: ILineDataSet) {
        if (dataSet.label == "glow") {
            mRenderPaint.maskFilter = glow
            super.drawDataSet(c, dataSet)
            mRenderPaint.maskFilter = null
        } else {
            super.drawDataSet(c, dataSet)
        }
    }
}
