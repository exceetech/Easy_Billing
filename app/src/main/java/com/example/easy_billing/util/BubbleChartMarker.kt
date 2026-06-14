package com.example.easy_billing.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.example.easy_billing.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.BubbleEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight

/**
 * Speech-bubble tooltip for the peak-hours bubble chart.
 *   x    = hour        -> title (e.g. "4pm")
 *   y    = revenue     -> "Revenue: ₹3,200"
 *   size = bill count  -> "Bills: 11"
 *
 * The card and its arrow are rendered as ONE seamless filled shape (no border /
 * no seam line). Placement is dynamic and side-based:
 *   • bubble on the RIGHT half  -> card sits to the LEFT, arrow points right.
 *   • bubble on the LEFT half   -> card sits to the RIGHT, arrow points left.
 * If the preferred side doesn't fit, it falls back to the other side. The card
 * is centered vertically on the bubble and clamped inside the chart, with the
 * arrow sliding to stay aimed at the bubble.
 */
class BubbleChartMarker(context: Context) :
    MarkerView(context, R.layout.marker_peak_bubble) {

    private val tvHour: TextView = findViewById(R.id.tvHour)
    private val tvRevenue: TextView = findViewById(R.id.tvRevenue)
    private val tvBills: TextView = findViewById(R.id.tvBills)
    private val colorSwatch: View = findViewById(R.id.colorSwatch)

    private val d = context.resources.displayMetrics.density

    // Unified card/arrow geometry
    private val cardColor  = Color.parseColor("#1B263F")
    private val corner     = 14f * d   // card corner radius
    private val arrowDepth = 9f  * d   // how far the arrow tip sticks out sideways
    private val arrowSpan  = 16f * d   // arrow base height (along the card edge)
    private val gap        = 4f  * d   // space between arrow tip and bubble
    private val edgePad    = 8f  * d   // min distance the card keeps from chart edges

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cardColor
        style = Paint.Style.FILL
    }
    private val rectF = RectF()

    // Same palette as the chart
    private val morningColor   = Color.parseColor("#2F93E0")
    private val afternoonColor = Color.parseColor("#D68A1E")
    private val eveningColor   = Color.parseColor("#8B5CF6")

    private fun colorForHour(hour: Int): Int = when {
        hour < 12  -> morningColor
        hour <= 15 -> afternoonColor
        else       -> eveningColor
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "12am"
        hour < 12  -> "${hour}am"
        hour == 12 -> "12pm"
        else       -> "${hour - 12}pm"
    }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        val hour    = e?.x?.toInt() ?: 0
        val revenue = e?.y?.toDouble() ?: 0.0
        val bills   = (e as? BubbleEntry)?.size?.toInt() ?: 0

        tvHour.text    = formatHour(hour)
        tvRevenue.text = "Revenue: " + CurrencyHelper.format(context, revenue)
        tvBills.text   = "Bills: $bills"

        colorSwatch.background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 4f * d
            setColor(colorForHour(hour))
        }

        // Text width changes per point, so re-measure & lay out the card.
        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layout(0, 0, measuredWidth, measuredHeight)

        super.refreshContent(e, highlight)
    }

    override fun draw(canvas: Canvas, posX: Float, posY: Float) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cw = chartView?.width?.toFloat()  ?: (posX + w + gap + arrowDepth + edgePad)
        val ch = chartView?.height?.toFloat() ?: (posY + h)

        // Does the card fit on a given side of the bubble?
        val fitsLeft  = (posX - gap - arrowDepth - w) >= edgePad            // card LEFT of bubble
        val fitsRight = (posX + gap + arrowDepth + w) <= (cw - edgePad)     // card RIGHT of bubble
        val preferLeft = posX > cw / 2f                                     // right-half bubble -> card on left

        val cardOnLeft = when {
            preferLeft  && fitsLeft  -> true
            !preferLeft && fitsRight -> false
            fitsLeft  -> true
            fitsRight -> false
            else      -> preferLeft
        }

        // Vertical: center on the bubble, clamp inside the chart.
        val maxTop  = (ch - h - edgePad).coerceAtLeast(edgePad)
        val cardTop = (posY - h / 2f).coerceIn(edgePad, maxTop)

        // Horizontal anchor + arrow tip.
        val cardLeft: Float
        val baseX: Float       // card edge the arrow grows from
        val tipX: Float
        if (cardOnLeft) {
            tipX     = posX - gap
            baseX    = tipX - arrowDepth
            cardLeft = (baseX - w).coerceAtLeast(edgePad)
        } else {
            tipX     = posX + gap
            baseX    = tipX + arrowDepth
            cardLeft = (baseX).coerceAtMost(cw - w - edgePad)
        }

        // Arrow tip Y tracks the bubble but stays off the rounded corners.
        val minTipY = cardTop + corner + arrowSpan / 2f
        val maxTipY = cardTop + h - corner - arrowSpan / 2f
        val tipY    = posY.coerceIn(minTipY.coerceAtMost(maxTipY), maxTipY.coerceAtLeast(minTipY))

        // Build the unified shape: rounded card + triangular arrow (one filled path).
        rectF.set(cardLeft, cardTop, cardLeft + w, cardTop + h)
        val shape = Path().apply { addRoundRect(rectF, corner, corner, Path.Direction.CW) }
        val arrow = Path().apply {
            moveTo(baseX, tipY - arrowSpan / 2f)
            lineTo(baseX, tipY + arrowSpan / 2f)
            lineTo(tipX, tipY)
            close()
        }
        shape.op(arrow, Path.Op.UNION)
        canvas.drawPath(shape, shapePaint)

        // Draw the text/swatch on top of the filled shape (card bg is transparent).
        val save = canvas.save()
        canvas.translate(cardLeft, cardTop)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
