package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.max
import kotlin.random.Random

/**
 * Doodle wallpaper — thin-line food / cafe / grocery / POS icons scattered
 * with random size + rotation, plus filler dots, hollow circles and tiny
 * sparkles, in a faint warm tone. Seamless across any view size, drawn from
 * vector path data (no bitmaps). Deterministic seed → never reshuffles.
 */
class DoodleBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private val tint = Color.parseColor("#AE9F84")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }

    private val pathData = arrayOf(
        "M4 8h11v5a4 4 0 0 1 -4 4H8a4 4 0 0 1 -4 -4z M15 9h2a2 2 0 0 1 0 4h-2 M7 4c0 1 1 1 1 2 M10 4c0 1 1 1 1 2",
        "M6 8h9l-1 9a2 2 0 0 1 -2 2H9a2 2 0 0 1 -2 -2z M5.5 6h11l-.6 2H6.1z",
        "M9 3h3v3l1 2v10a2 2 0 0 1 -2 2H10a2 2 0 0 1 -2 -2V8l1 -2z",
        "M5 13c0 -2 2 -3 7 -3s7 1 7 3v5H5z M5 14c1 1 2 1 3 0s2 -1 3 0 2 1 3 0 2 -1 3 0 M12 6v4 M12 6l-1.5 -1.5",
        "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0 -16z M9 10h.01 M14 9h.01 M15 13h.01 M10 15h.01 M12 12h.01",
        "M9 11l3 9 3 -9 M8 11a4 4 0 0 1 8 0z",
        "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0 -16z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0 -6z",
        "M12 4l8 14H4z M9 11h.01 M13 12h.01 M11 15h.01",
        "M4 12c3 -5 11 -5 14 0 -3 5 -11 5 -14 0z M18 12l3 -3v6z M9 11h.01",
        "M12 8c-3 -2 -6 0 -6 4s3 6 6 6 6 -2 6 -6 -3 -6 -6 -4z M12 8c0 -2 1 -3 3 -3",
        "M7 17l8 -8 2 2 -8 8z M15 9l2 -3 M15 9l3 -2 M12 6l1 -2",
        "M5 16c2 -8 12 -8 14 0 -4 -3 -10 -3 -14 0z M6 15l-1 2 M18 15l1 2",
        "M5 11a7 4 0 0 1 14 0v6a1 1 0 0 1 -1 1H6a1 1 0 0 1 -1 -1z M8 12v5 M12 12v5 M16 12v5",
        "M8 3v6 M11 3v6 M9.5 9v11 M16 3c-2 0 -2 5 0 6v11",
        "M7 18h10v-3a4 4 0 1 0 -3 -7 4 4 0 0 0 -4 0 4 4 0 1 0 -3 7z M7 18v2h10v-2",
        "M6 14a6 6 0 0 1 12 0v1l1 2H5l1 -2z M10 19a2 2 0 0 0 4 0",
        "M6 3v18l2 -1 2 1 2 -1 2 1 2 -1 2 1V3l-2 1 -2 -1 -2 1 -2 -1 -2 1z M9 8h6 M9 12h6",
        "M4 4h7l9 9 -7 7 -9 -9z M7.5 7.5h.01",
        "M6 8h12l-1 11H7z M9 8a3 3 0 0 1 6 0",
        "M4 4h2l2 11h9l2 -7H7 M9 19a1 1 0 1 0 .01 0 M17 19a1 1 0 1 0 .01 0",
        "M5 10h14l-1 8H6z M8 10l2 -4 M16 10l-2 -4 M9 13v3 M12 13v3 M15 13v3",
        "M12 5v13 M7 18h10 M5 8h14 M5 8l-2 5h4z M19 8l-2 5h4z M12 5l-7 3 M12 5l7 3",
        "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0 -16z M9 12h6 M12 9v6",
        "M3 6h18v12H3z M3 10h18 M6 14h4"
    )
    private val paths: Array<Path> = pathData.map { PathParser.createPathFromPathData(it) }.toTypedArray()
    private val sparkle: Path = PathParser.createPathFromPathData("M12 3l1 8 8 1 -8 1 -1 8 -1 -8 -8 -1 8 -1z")

    private class Icon(val x: Float, val y: Float, val p: Path, val size: Float, val rot: Float, val a: Int)
    private class Fill(val x: Float, val y: Float, val r: Float, val a: Int, val kind: Int) // 0 dot,1 ring,2 sparkle

    private val icons = ArrayList<Icon>()
    private val fills = ArrayList<Fill>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        icons.clear(); fills.clear()
        val r = Random(7)
        val cell = 46f * d
        val jit = 14f * d
        var idx = 0
        var y = -16f * d
        while (y < h + 30 * d) {
            val rowOdd = (Math.round(y / cell) % 2 != 0)
            var x = -16f * d
            while (x < w + 30 * d) {
                val ix = x + (if (rowOdd) cell / 2 else 0f) + (r.nextFloat() * 2 - 1) * jit
                val iy = y + (r.nextFloat() * 2 - 1) * jit
                val size = (13f + r.nextFloat() * 30f) * d
                val rot = r.nextFloat() * 44f - 22f
                val a = (118 + r.nextFloat() * 70).toInt()
                icons.add(Icon(ix, iy, paths[idx % paths.size], size, rot, a))
                idx += 5
                x += cell
            }
            idx += 2
            y += cell
        }
        // fillers
        val fcell = 22f * d
        var fy = 0f
        while (fy < h) {
            var fx = 0f
            while (fx < w) {
                val rr = r.nextFloat()
                val dx = fx + (r.nextFloat() * 2 - 1) * 9 * d
                val dy = fy + (r.nextFloat() * 2 - 1) * 9 * d
                when {
                    rr < 0.28f -> fills.add(Fill(dx, dy, (1.2f + r.nextFloat() * 1.3f) * d, 132, 0))
                    rr < 0.38f -> fills.add(Fill(dx, dy, (2.2f + r.nextFloat() * 2f) * d, 118, 1))
                    rr < 0.45f -> fills.add(Fill(dx, dy, (4f + r.nextFloat() * 3.5f) * d, 118, 2))
                }
                fx += fcell
            }
            fy += fcell
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // fillers behind
        for (f in fills) {
            when (f.kind) {
                0 -> { dotFill.alpha = f.a; canvas.drawCircle(f.x, f.y, f.r, dotFill) }
                1 -> { stroke.alpha = f.a; stroke.strokeWidth = 1f * d; canvas.drawCircle(f.x, f.y, f.r, stroke) }
                2 -> {
                    stroke.alpha = f.a; stroke.strokeWidth = 1f * d
                    val s = (f.r * 2f) / 24f
                    canvas.save(); canvas.translate(f.x, f.y); canvas.scale(s, s); canvas.translate(-12f, -12f)
                    canvas.drawPath(sparkle, stroke); canvas.restore()
                }
            }
        }
        for (ic in icons) {
            val s = ic.size / 24f
            stroke.alpha = ic.a
            stroke.strokeWidth = (1.15f * d) / max(0.0001f, s) // ~constant 1.15px regardless of icon size
            canvas.save()
            canvas.translate(ic.x, ic.y)
            canvas.rotate(ic.rot)
            canvas.scale(s, s)
            canvas.translate(-12f, -12f)
            canvas.drawPath(ic.p, stroke)
            canvas.restore()
        }
    }
}
