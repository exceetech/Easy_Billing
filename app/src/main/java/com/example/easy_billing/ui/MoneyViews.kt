package com.example.easy_billing.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.easy_billing.R
import com.example.easy_billing.util.CurrencyHelper

/** Small helpers shared by the Money card and the Statement screen (views are built in code). */
object MoneyViews {
    const val INK = "#1A1A18"
    const val MUTED = "#8C8576"
    const val GREEN = "#0F6E56"
    const val RED = "#B23A3A"

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun text(
        ctx: Context, s: CharSequence, sp: Float = 13f, color: String = INK, bold: Boolean = false,
        gravity: Int = Gravity.START, weight: Float = 0f
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        this.gravity = gravity
        layoutParams = if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun money(ctx: Context, v: Double) = CurrencyHelper.format(ctx, v)

    fun amountColor(v: Double) = if (v < -0.004) RED else INK

    /** One table row: label | cash | bank | total. */
    fun tableRow(
        ctx: Context, label: String, cash: String, bank: String, total: String,
        bold: Boolean = false, color: String = INK, header: Boolean = false
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        val c = if (header) MUTED else color
        val sp = if (header) 11f else 13f
        addView(text(ctx, label, sp, if (header) MUTED else INK, bold, Gravity.START, 1.3f))
        addView(text(ctx, cash, sp, c, bold, Gravity.END, 1f))
        addView(text(ctx, bank, sp, c, bold, Gravity.END, 1f))
        addView(text(ctx, total, sp, c, bold, Gravity.END, 1.1f))
    }

    fun divider(ctx: Context) = android.view.View(ctx).apply {
        setBackgroundColor(Color.parseColor("#F0EADC"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).also {
            it.topMargin = dp(ctx, 6); it.bottomMargin = dp(ctx, 6)
        }
    }

    fun reasonLabel(ctx: Context, reason: String): String = ctx.getString(
        when (reason) {
            "SALE" -> R.string.money_reason_sale
            "SALE_REFUND" -> R.string.money_reason_sale_refund
            "SALE_EXTRA" -> R.string.money_reason_sale_extra
            "DUES_RECEIVED" -> R.string.money_reason_dues_received
            "ADVANCE_REFUND" -> R.string.money_reason_advance_refund
            "PURCHASE_PAID" -> R.string.money_reason_purchase_paid
            "SUPPLIER_PAID" -> R.string.money_reason_supplier_paid
            "PURCHASE_REFUND" -> R.string.money_reason_purchase_refund
            "OPENING" -> R.string.money_reason_opening
            "TRANSFER" -> R.string.money_reason_transfer
            else -> R.string.money_reason_manual
        }
    )
}
