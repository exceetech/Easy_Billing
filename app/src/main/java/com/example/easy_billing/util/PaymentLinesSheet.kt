package com.example.easy_billing.util

import android.app.Activity
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.easy_billing.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * The shared "payment lines" bottom sheet (payment & cash-flow tracking).
 *
 * Shows the amount to settle and one line per mode. The owner types an amount on each line he used;
 * "Fill" puts whatever is still missing on that line. Confirm is enabled only when the lines add up
 * to exactly the total (checked by [PaymentSplit.validate], to the paisa).
 *
 * Used for: split bills at the counter now, and refunds / dues / purchase payments in later phases.
 */
object PaymentLinesSheet {

    private val GREEN = Color.parseColor("#137333")
    private val RED = Color.parseColor("#B3261E")
    private val GREY = Color.parseColor("#5A6872")

    fun modeLabel(activity: android.content.Context, mode: String): String = when (mode) {
        MoneyMode.CASH -> activity.getString(R.string.cash)
        MoneyMode.UPI -> activity.getString(R.string.upi)
        MoneyMode.CARD -> activity.getString(R.string.card)
        MoneyMode.BANK -> activity.getString(R.string.payment_mode_bank)
        MoneyMode.CREDIT -> activity.getString(R.string.invoice_payment_credit)
        else -> mode
    }

    private fun iconFor(mode: String): Int = when (mode) {
        MoneyMode.CASH -> R.drawable.ic_lucide_cash
        MoneyMode.UPI -> R.drawable.ic_lucide_device_mobile
        MoneyMode.CARD -> R.drawable.ic_lucide_credit_card
        MoneyMode.BANK -> R.drawable.ic_lc_landmark
        else -> R.drawable.ic_lc_clock
    }

    /**
     * @param modes        the lines offered, in order (may include [MoneyMode.CREDIT])
     * @param initial      lines to start from; when empty, [defaultMode] is pre-filled with the whole total
     * @param onConfirm    called with the non-empty lines (same-mode merged) that add up to [total]
     * @param onCancel     called when the sheet is dismissed without confirming
     */
    fun show(
        activity: Activity,
        title: String,
        total: Double,
        modes: List<String>,
        initial: List<PayLine>,
        defaultMode: String,
        confirmLabel: String = activity.getString(R.string.invoice_split_confirm),
        onConfirm: (List<PayLine>) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun font(id: Int) = androidx.core.content.res.ResourcesCompat.getFont(activity, id)
        val INK = Color.parseColor("#1A1A18")
        val MUTED = Color.parseColor("#76867C")
        val BRAND = Color.parseColor("#0F6E56")
        val ERR = Color.parseColor("#B3261E")

        val dialog = BottomSheetDialog(activity)
        var confirmed = false

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(14), px(22), px(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FCFEFD"))
                cornerRadii = floatArrayOf(px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), 0f, 0f, 0f, 0f)
            }
        }

        // Drag handle
        root.addView(View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D5DDD8")); cornerRadius = px(2).toFloat()
            }
        }, LinearLayout.LayoutParams(px(40), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(16) })

        // Title: the last word in gold italic serif, like the app's other dialogs.
        val words = title.trim().split(" ")
        val head = if (words.size > 1) words.dropLast(1).joinToString(" ") + " " else ""
        val tail = words.last()
        root.addView(TextView(activity).apply {
            val sb = android.text.SpannableStringBuilder(head + tail)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#854F0B")),
                head.length, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                head.length, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.TypefaceSpan("serif"),
                head.length, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 21f
            setTextColor(INK)
            typeface = font(R.font.googlesans_bold)
        })
        root.addView(TextView(activity).apply {
            val amount = CurrencyHelper.format(activity, total)
            val sb = android.text.SpannableStringBuilder(activity.getString(R.string.pay_sheet_total) + " " + amount)
            sb.setSpan(android.text.style.ForegroundColorSpan(INK), sb.length - amount.length, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), sb.length - amount.length, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, px(4), 0, px(8))
        })

        val fields = LinkedHashMap<String, EditText>()
        val startAmounts: Map<String, Double> =
            if (initial.isNotEmpty()) PaymentSplit.merge(initial).associate { it.mode to it.amount }
            else mapOf(defaultMode to total)

        fun linesNow(): List<PayLine> =
            modes.mapNotNull { m ->
                val v = fields[m]?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                if (PaymentSplit.toPaise(v) > 0L) PayLine(m, v) else null
            }

        // The line that absorbs changes made on the others: the default mode (cash), when it is offered.
        val balancer: String? = defaultMode.takeIf { it in modes }
        var autoSetting = false

        val status = TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = font(R.font.googlesans_semibold)
            setPadding(0, px(12), 0, px(2))
        }
        val btnConfirm = MaterialButton(activity).apply {
            text = confirmLabel
            isAllCaps = false
            textSize = 15f
            typeface = font(R.font.googlesans_bold)
            cornerRadius = px(15)
            insetTop = 0; insetBottom = 0
            backgroundTintList = null
            setBackgroundResource(R.drawable.bg_lg_cta_green_16_state)
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#B4BDB8"))))
        }

        // Per-row visuals update together so a row with an amount turns green.
        val circles = HashMap<String, FrameLayout>()
        val icons = HashMap<String, android.widget.ImageView>()
        val cards = HashMap<String, LinearLayout>()
        val boxes = HashMap<String, LinearLayout>()

        fun refresh() {
            val now = linesNow()
            val diffP = now.sumOf { PaymentSplit.toPaise(it.amount) } - PaymentSplit.toPaise(total)
            val ok = PaymentSplit.validate(total, now, modes) == SplitCheck.Ok
            when {
                ok -> { status.text = activity.getString(R.string.pay_sheet_ok); status.setTextColor(BRAND) }
                diffP < 0L -> {
                    status.text = activity.getString(R.string.pay_sheet_left,
                        CurrencyHelper.format(activity, PaymentSplit.fromPaise(-diffP)))
                    status.setTextColor(ERR)
                }
                else -> {
                    status.text = activity.getString(R.string.pay_sheet_over,
                        CurrencyHelper.format(activity, PaymentSplit.fromPaise(diffP)))
                    status.setTextColor(ERR)
                }
            }
            btnConfirm.isEnabled = ok
            for (m in modes) {
                val on = PaymentSplit.toPaise(fields[m]?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0) > 0L
                circles[m]?.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(if (on) "#DDF2EA" else "#EEF2F0"))
                }
                icons[m]?.setColorFilter(if (on) BRAND else MUTED)
                cards[m]?.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(16).toFloat()
                    setColor(Color.parseColor(if (on) "#F3FAF7" else "#FFFFFF"))
                    setStroke(px(2) * 3 / 4, Color.parseColor(if (on) "#0F6E56" else "#1F000000"))
                }
                boxes[m]?.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(12).toFloat()
                    setColor(Color.parseColor(if (on) "#FFFFFF" else "#F7F9F8"))
                    setStroke(px(2) * 3 / 4, Color.parseColor(if (on) "#0F6E56" else "#1F000000"))
                }
            }
        }

        for (m in modes) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(10), px(12), px(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(10) }
            }
            cards[m] = row

            val circle = FrameLayout(activity)
            val icon = android.widget.ImageView(activity).apply { setImageResource(iconFor(m)) }
            circle.addView(icon, FrameLayout.LayoutParams(px(19), px(19), Gravity.CENTER))
            circles[m] = circle; icons[m] = icon
            row.addView(circle, LinearLayout.LayoutParams(px(38), px(38)))

            val names = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) }
            }
            names.addView(TextView(activity).apply {
                text = modeLabel(activity, m)
                textSize = 14.5f
                setTextColor(INK)
                typeface = font(R.font.googlesans_bold)
            })
            if (m == balancer) {
                names.addView(TextView(activity).apply {
                    text = activity.getString(R.string.pay_sheet_balances)
                    textSize = 11f
                    setTextColor(BRAND)
                })
            }
            row.addView(names)

            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), 0, px(10), 0)
            }
            boxes[m] = box
            box.addView(TextView(activity).apply {
                text = "\u20B9"; textSize = 15f; setTextColor(Color.parseColor("#7C8782"))
            })
            val et = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "0.00"
                setHintTextColor(Color.parseColor("#B4BDB8"))
                setTextColor(INK)
                textSize = 18f
                typeface = font(R.font.googlesans_bold)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                background = null
                setPadding(px(6), 0, 0, 0)
                setSingleLine()
                includeFontPadding = false
                // Small, thin cursor (the default one is as tall as the whole line).
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    textCursorDrawable = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.cursor_small)
                }
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                startAmounts[m]?.takeIf { PaymentSplit.toPaise(it) > 0L }?.let { setText("%.2f".format(it)) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        // Typing on any other line takes the amount off the balancing line (cash by default),
                        // so the split stays balanced. If it goes over the total, the line empties and the
                        // normal "too much" check handles it.
                        if (!autoSetting && balancer != null && m != balancer) {
                            val bf = fields[balancer]
                            if (bf != null) {
                                val others = linesNow().filter { it.mode != balancer }
                                val left = PaymentSplit.toPaise(total) - others.sumOf { PaymentSplit.toPaise(it.amount) }
                                autoSetting = true
                                bf.setText(if (left > 0L) "%.2f".format(PaymentSplit.fromPaise(left)) else "")
                                autoSetting = false
                            }
                        }
                        refresh()
                    }
                })
                setOnFocusChangeListener { _, has -> if (has) selectAll() }
            }
            fields[m] = et
            box.addView(et, LinearLayout.LayoutParams(px(84), px(42)))
            row.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(42)))
            root.addView(row)
        }

        root.addView(status)
        btnConfirm.setOnClickListener {
            val merged = PaymentSplit.merge(linesNow())
            if (PaymentSplit.validate(total, merged, modes) != SplitCheck.Ok) return@setOnClickListener
            confirmed = true
            dialog.dismiss()
            onConfirm(merged)
        }
        root.addView(btnConfirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, px(50)).apply { topMargin = px(12) })

        dialog.setOnDismissListener { if (!confirmed) onCancel() }
        dialog.setContentView(root)
        // Let our own rounded background show instead of the default white sheet.
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
        refresh()
    }
}
