package com.example.easy_billing.util

import android.app.DatePickerDialog
import android.content.Context
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reusable invoice-date picker.
 *
 * Used by every screen that captures a purchase / stock-add:
 *   • Add Purchase Invoice
 *   • Add Purchased Product
 *   • Inventory → Add Stock
 *
 * Display format on screen:    `dd/MM/yyyy`
 * Internal canonical format:   `yyyy-MM-dd` (ISO date)
 *
 * Selections are stored as **epoch millis at midnight UTC** for the
 * chosen calendar day, so values are stable across timezones when
 * synced to the backend. Future dates are blocked by the picker.
 */
object InvoiceDatePicker {

    private val UI_FMT = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Format an epoch-millis day as `dd/MM/yyyy` for display. */
    fun formatForDisplay(millis: Long?): String =
        if (millis == null) "" else UI_FMT.format(Date(millis))

    /** Format an epoch-millis day as `yyyy-MM-dd` (ISO) for storage. */
    fun formatIso(millis: Long?): String =
        if (millis == null) "" else ISO_FMT.format(Date(millis))

    /** Parse `dd/MM/yyyy` back to epoch millis (or null if unparseable). */
    fun parseDisplay(text: String?): Long? = runCatching {
        if (text.isNullOrBlank()) null else UI_FMT.parse(text.trim())?.time
    }.getOrNull()

    /**
     * Show the picker and call [onPicked] with the selected day's
     * epoch-millis (UTC midnight). Future dates are disabled.
     *
     * @param initialMillis the day to open on. Defaults to today.
     */
    fun show(
        context: Context,
        initialMillis: Long? = null,
        onPicked: (millisUtcMidnight: Long) -> Unit
    ) {
        val cal = Calendar.getInstance().apply {
            if (initialMillis != null) timeInMillis = initialMillis
        }
        val dlg = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                // Normalise to UTC midnight so the stored millis is
                // an unambiguous calendar day regardless of where the
                // device sits.
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(year, month, dayOfMonth, 0, 0, 0)
                }
                onPicked(utc.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        // Hard guard against future dates — the picker simply won't
        // accept anything after today (device clock).
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    /**
     * Convenience wiring for a `TextInputEditText` / `EditText` that
     * should open the picker on tap (no keyboard) and render the
     * result as `dd/MM/yyyy`. Returns a `() -> Long?` that callers
     * use to pull the selected epoch-millis at submit time.
     */
    fun bind(
        field: EditText,
        initialMillis: Long? = null
    ): () -> Long? {
        // Block soft keyboard — this field is picker-only.
        field.isFocusable = false
        field.isCursorVisible = false
        field.isFocusableInTouchMode = false
        field.keyListener = null

        var selected: Long? = initialMillis
        selected?.let { field.setText(formatForDisplay(it)) }

        val open: () -> Unit = {
            show(field.context, selected) { picked ->
                selected = picked
                field.setText(formatForDisplay(picked))
                field.error = null
            }
        }
        field.setOnClickListener { open() }
        // Some Material TextInputLayout setups still let the field
        // grab focus through accessibility — handle that too.
        field.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) open() }

        return { selected }
    }
}
