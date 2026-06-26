package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Low-poly facet background — a warm sand triangle mosaic with hairline
 * white edges. Vertices are jittered on a grid (deterministic seed so it
 * never reshuffles) and re-meshed to fill any view size.
 */
class LowPolyBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private val target = 74f * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val path = Path()

    // Warm sand palette (subtle facet variation).
    private val shades = intArrayOf(
        Color.parseColor("#F4EBD8"), Color.parseColor("#F0E4CC"),
        Color.parseColor("#F5EEDD"), Color.parseColor("#EFE6D2"),
        Color.parseColor("#F6F0E2"), Color.parseColor("#F1E7D2"),
        Color.parseColor("#F4ECD9"), Color.parseColor("#F2E9D6"),
        Color.parseColor("#F6F1E4"), Color.parseColor("#EFE5D0")
    )

    private var pts: Array<Array<FloatArray>>? = null
    private var cols = 0
    private var rows = 0

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        cols = max(2, (w / target).toInt())
        rows = max(2, (h / target).toInt())
        val cw = w.toFloat() / cols
        val ch = h.toFloat() / rows
        val jx = cw * 0.34f
        val jy = ch * 0.34f
        val r = Random(42)
        pts = Array(rows + 1) { j ->
            Array(cols + 1) { i ->
                val onEdge = i == 0 || j == 0 || i == cols || j == rows
                val x = i * cw + if (onEdge) 0f else (r.nextFloat() * 2 - 1) * jx
                val y = j * ch + if (onEdge) 0f else (r.nextFloat() * 2 - 1) * jy
                floatArrayOf(x, y)
            }
        }
    }

    private fun shade(i: Int, j: Int, k: Int): Int =
        shades[abs(i * 73 + j * 31 + k * 17) % shades.size]

    private fun tri(c: Canvas, a: FloatArray, b: FloatArray, cc: FloatArray, color: Int) {
        path.reset()
        path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]); path.lineTo(cc[0], cc[1]); path.close()
        fill.color = color
        c.drawPath(path, fill)
        c.drawPath(path, edge)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = pts ?: return
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val a = p[j][i]; val b = p[j][i + 1]
                val cc = p[j + 1][i + 1]; val dd = p[j + 1][i]
                tri(canvas, a, b, cc, shade(i, j, 0))
                tri(canvas, a, cc, dd, shade(i, j, 1))
            }
        }
    }
}
