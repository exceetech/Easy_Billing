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
        val confirmWidth = minOf(
            (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            dp(330)
        )
        dialog.window?.setLayout(confirmWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /**
     * Theme-matched confirmation dialog (centred card, title + message, Cancel / confirm).
     * The confirm action is styled as destructive (red).
     */
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(22), dp(15), dp(22), dp(14))
        }

        card.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A18"))
        })
        card.addView(TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(Color.parseColor("#6E6A60"))
            setLineSpacing(dp(3).toFloat(), 1f)
            (layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )).also { it.topMargin = dp(4); layoutParams = it }
        })

        val dialog = Dialog(context)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(14) }
        }
        val cancel = TextView(context).apply {
            text = "Cancel"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A18"))
            setBackgroundResource(R.drawable.bg_imp_filter)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }
        val confirm = TextView(context).apply {
            text = confirmLabel
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundResource(R.drawable.bg_confirm_danger)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).also { it.marginStart = dp(10) }
        }
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener { dialog.dismiss(); onConfirm() }
        buttons.addView(cancel)
        buttons.addView(confirm)
        card.addView(buttons)

        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
