package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Terrazzo wallpaper (dashboard concept #60) — larger "tumbled" gold flakes
 * scattered with random size + rotation across a warm champagne field, in
 * gold / light-gold / soft-sage / dusty-rose tones. Seamless across any view
 * size, deterministic seed so it never reshuffles between frames. Pairs with
 * the champagne base drawable @drawable/bg_dashboard_premium.
 */
class TerrazzoBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density

    // Tumbled-chip palette: warm gold, light gold, soft sage, dusty rose.
    private val palette = intArrayOf(
        Color.parseColor("#C7A155"),
        Color.parseColor("#DBB97E"),
        Color.parseColor("#B9C49E"),
        Color.parseColor("#D7A98C")
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private class Chip(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rot: Float, val color: Int, val a: Int
    )

    private val chips = ArrayList<Chip>()
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        chips.clear()
        val r = Random(11)
        val cell = 64f * d          // spacing between chips
        val jit = 22f * d           // positional jitter
        var idx = 0
        var y = -20f * d
        while (y < h + 40 * d) {
            val rowOdd = (Math.round(y / cell) % 2 != 0)
            var x = -20f * d
            while (x < w + 40 * d) {
                val cx = x + (if (rowOdd) cell / 2 else 0f) + (r.nextFloat() * 2 - 1) * jit
                val cy = y + (r.nextFloat() * 2 - 1) * jit
                val cw = (16f + r.nextFloat() * 18f) * d       // chip width  16–34dp
                val ch = (10f + r.nextFloat() * 12f) * d       // chip height 10–22dp
                val rot = r.nextFloat() * 360f
                val color = palette[idx % palette.size]
                val a = (90 + r.nextFloat() * 60).toInt()      // alpha ~0.35–0.59
                chips.add(Chip(cx, cy, cw, ch, rot, color, a))
                idx++
                x += cell
            }
            idx++
            y += cell
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 3.5f * d
        for (c in chips) {
            fill.color = c.color
            fill.alpha = c.a
            canvas.save()
            canvas.translate(c.x, c.y)
            canvas.rotate(c.rot)
            rect.set(-c.w / 2f, -c.h / 2f, c.w / 2f, c.h / 2f)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.restore()
        }
    }
}
