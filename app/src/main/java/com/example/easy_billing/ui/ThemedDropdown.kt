package com.example.easy_billing.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.example.easy_billing.R

/**
 * Theme-matched dropdown: a rounded white card with styled rows and a blue check on the
 * selected item — same look as the Place-of-supply / FY-filter dropdowns.
 */
object ThemedDropdown {

    fun show(
        anchor: View,
        options: List<String>,
        selectedIndex: Int,
        rightAlign: Boolean = false,
        minWidthDp: Int = 160,
        onSelect: (Int) -> Unit
    ) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val width = maxOf(anchor.width, dp(minWidthDp))
        val popup = PopupWindow(
            container, width, ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        options.forEachIndexed { i, label ->
            val isSel = i == selectedIndex
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor(if (isSel) "#185FA5" else "#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (isSel) {
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(Color.parseColor("#185FA5"))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener { onSelect(i); popup.dismiss() }
            container.addView(row)
        }

        val xoff = if (rightAlign) anchor.width - width else 0
        popup.showAsDropDown(anchor, xoff, dp(6))
    }

    /**
     * Theme-matched centred selection card (for action pickers like "Send report").
     * Same rounded card + row styling as [show]; shown as a centred dialog with a title.
     */
    fun showActionSheet(
        context: Context,
        title: String,
        options: List<String>,
        onSelect: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(8), dp(14), dp(8), dp(8))
        }

        card.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A18"))
            setPadding(dp(8), 0, dp(8), dp(10))
        })

        val dialog = Dialog(context)

        options.forEachIndexed { i, label ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.setOnClickListener { onSelect(i); dialog.dismiss() }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.82f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
