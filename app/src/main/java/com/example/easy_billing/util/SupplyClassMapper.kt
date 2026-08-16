package com.example.easy_billing.util

/**
 * Maps the raw GST supply-classification codes stored in the
 * database/backend ("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST")
 * to clean, human-readable display labels for the UI, and back.
 *
 * The stored/submitted value never changes — only what the user
 * sees in the dropdown is affected.
 */
object SupplyClassMapper {

    /** Raw code → display label. */
    private val CODE_TO_LABEL = mapOf(
        "TAXABLE" to "Taxable",
        "NIL_RATED" to "Nil Rated",
        "EXEMPT" to "Exempt",
        "NON_GST" to "Non-GST"
    )

    /** Display labels, in the fixed order the dropdown should show them. */
    val ALL_DISPLAY: List<String> = listOf("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST")
        .map { CODE_TO_LABEL.getValue(it) }

    /**
     * Converts a raw code like "NIL_RATED" to its display label
     * "Nil Rated". Unknown/blank codes are returned unchanged so
     * callers never silently lose data.
     */
    fun codeToDisplay(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return CODE_TO_LABEL[code.trim().uppercase()] ?: code
    }

    /**
     * Converts a display label like "Nil Rated" back to the raw
     * code "NIL_RATED". If [display] is already a raw code (e.g.
     * an old cached value), it's returned uppercased as-is.
     */
    fun displayToCode(display: String?): String? {
        if (display.isNullOrBlank()) return null
        val trimmed = display.trim()
        CODE_TO_LABEL.entries.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }
            ?.let { return it.key }
        return trimmed.uppercase()
    }
}
