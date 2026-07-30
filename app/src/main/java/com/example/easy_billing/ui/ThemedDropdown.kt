package com.example.easy_billing.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
     * Champagne-themed centred action sheet (for "Send report"): matches the
     * app's standard confirm-dialog shell (dialog_verify_password / dialog_change_pin)
     * — centered teal icon badge, serif-accent title, muted subtitle, a single
     * bordered list of options, and a quiet Cancel text link. Exclusive to the
     * Send-report flow — does not share bg_pos_dropdown with [show]/[showConfirm].
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
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(22), dp(20), dp(14))
        }

        card.addView(FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_circle_soft_teal)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(12) }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lucide_send_teal)
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER }
            })
        })

        card.addView(TextView(context).apply {
            val spannable = android.text.SpannableStringBuilder("Send ")
            val italicStart = spannable.length
            spannable.append("report")
            spannable.setSpan(
                android.text.style.StyleSpan(Typeface.ITALIC),
                italicStart, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#0F6E56")),
                italicStart, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = spannable
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A18"))
        })
        card.addView(TextView(context).apply {
            text = "Choose a period to email as a PDF"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9A8F79"))
            setPadding(0, dp(4), 0, dp(16))
        })

        val dialog = Dialog(context)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EFE9DA"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        options.forEachIndexed { i, label ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 13.5f
                setTextColor(Color.parseColor("#1A1A18"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                setColorFilter(Color.parseColor("#9A8F79"))
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            row.setOnClickListener { onSelect(i); dialog.dismiss() }
            list.addView(row)
            if (i != options.lastIndex) {
                list.addView(View(context).apply {
                    setBackgroundColor(Color.parseColor("#EFE9DA"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1) / 2
                    )
                })
            }
        }
        card.addView(list)

        card.addView(TextView(context).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9A8F79"))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(6) }
            setOnClickListener { dialog.dismiss() }
        })

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

    /**
     * Champagne-themed result dialog for async actions (e.g. "Report sent" /
     * "Send failed"): same confirm-dialog shell as [showActionSheet] — a
     * centred icon badge, serif-accent title, muted subtitle, and a single
     * solid CTA button.
     */
    fun showResultDialog(
        context: Context,
        success: Boolean,
        titlePlain: String,
        titleAccent: String,
        subtitle: String,
        buttonLabel: String = "Done",
        onDismiss: () -> Unit = {}
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val accentColor = if (success) "#0F6E56" else "#791F1F"
        val badgeBg      = if (success) "#DDEEEE" else "#FBEDED"
        val buttonBg     = if (success) "#0F6E56" else "#791F1F"
        val icon         = if (success) R.drawable.ic_lc_circle_check else R.drawable.ic_lucide_circle_x

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        card.addView(FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(badgeBg))
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(14) }
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.parseColor(accentColor))
                layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
            })
        })

        card.addView(TextView(context).apply {
            val spannable = android.text.SpannableStringBuilder("$titlePlain ")
            val italicStart = spannable.length
            spannable.append(titleAccent)
            spannable.setSpan(
                android.text.style.StyleSpan(Typeface.ITALIC),
                italicStart, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor(accentColor)),
                italicStart, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = spannable
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A18"))
        })
        card.addView(TextView(context).apply {
            text = subtitle
            textSize = 12.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.parseColor("#9A8F79"))
            setPadding(0, dp(6), 0, dp(20))
        })

        val dialog = Dialog(context)

        card.addView(TextView(context).apply {
            text = buttonLabel
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(buttonBg))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener { dialog.dismiss(); onDismiss() }
        })

        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = minOf(
            (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            dp(330)
        )
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}
