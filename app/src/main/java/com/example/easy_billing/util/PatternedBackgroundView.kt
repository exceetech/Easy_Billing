package com.example.easy_billing.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Ultra-Premium "Kinetic Editorial" Pattern.
 * Upgrades the typography to a high-fashion, editorial SaaS standard by mixing 
 * ultra-heavy 'sans-serif-black' strokes with elegant 'serif-italic' solids.
 * Switched color palette to Obsidian Black and Platinum/Silver for maximum luxury.
 */
class PatternedBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var timeOffset = 0f
    private var animator: ValueAnimator? = null

    // Platinum on Obsidian color palette (Extremely high-end, clean, no cheap colors)
    private val colorBg = Color.parseColor("#030303") // Deep Obsidian
    private val colorPlatinumSolid = Color.parseColor("#0AFFFFFF") // 4% Platinum (Very subtle)
    private val colorPlatinumStroke = Color.parseColor("#14FFFFFF") // 8% Platinum for sharp edges

    // Elegant Luxury Fashion Font (Serif Italic)
    private val textPaintSerif = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorPlatinumSolid
        textSize = 150f
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        style = Paint.Style.FILL
        letterSpacing = 0.02f
    }

    // Brutalist Modern Tech Font (Sans-Serif Black)
    private val textPaintSansStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorPlatinumStroke
        textSize = 130f
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        letterSpacing = 0.06f
    }

    // Alternating between Editorial Luxury (Serif) and Tech Brutalism (Sans-Serif Stroke)
    private val ribbons = listOf(
        MarqueeRow("EXPOS · RETAIL · GROCERY · MARKETS · EXPOS · RETAIL · ", 1.2f, isStroke = false),
        MarqueeRow("BAKERY · CAFE · BISTRO · RESTAURANT · BAKERY · CAFE · ", -1.5f, isStroke = true),
        MarqueeRow("HOTEL · HOSPITALITY · RESORT · MOTEL · HOTEL · RESORT · ", 1.0f, isStroke = false),
        MarqueeRow("BILLING · INVENTORY · POINT OF SALE · BILLING · POS · ", -1.2f, isStroke = true),
        MarqueeRow("EXPOS · RETAIL · GROCERY · MARKETS · EXPOS · RETAIL · ", 1.4f, isStroke = false),
        MarqueeRow("BAKERY · CAFE · BISTRO · RESTAURANT · BAKERY · CAFE · ", -1.0f, isStroke = true),
        MarqueeRow("HOTEL · HOSPITALITY · RESORT · MOTEL · HOTEL · RESORT · ", 1.2f, isStroke = false),
        MarqueeRow("BILLING · INVENTORY · POINT OF SALE · BILLING · POS · ", -1.4f, isStroke = true)
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                timeOffset += 1.2f // Smooth buttery scrolling
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colorBg)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.save()
        // Tilt the typography to -12 degrees for an aggressive, dynamic editorial look
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-12f)
        canvas.translate(-w / 2f, -h / 2f)

        // Start drawing well above the screen to cover the rotated corners
        var yOffset = -250f
        val rowHeight = 165f

        for (row in ribbons) {
            val paint = if (row.isStroke) textPaintSansStroke else textPaintSerif
            
            // Measure text width to loop seamlessly
            val textWidth = paint.measureText(row.text)
            
            // Calculate pan position based on time and speed
            var xPan = (timeOffset * row.speed) % textWidth
            if (xPan > 0) {
                xPan -= textWidth
            }

            // Draw enough copies of the text to fill the entire width (including rotation overflow)
            var x = xPan - textWidth
            while (x < w * 1.8f) { // Render wide enough to handle the -12 degree rotation
                canvas.drawText(row.text, x, yOffset, paint)
                x += textWidth
            }
            
            yOffset += rowHeight
        }
        
        canvas.restore()

        // Apply a massive cinematic vignette (fade to pure black at edges)
        // This ensures the typography looks like it's emerging from the deep background
        val vignette = Paint()
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, kotlin.math.max(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, colorBg),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, vignette)
    }

    private data class MarqueeRow(val text: String, val speed: Float, val isStroke: Boolean)
}
