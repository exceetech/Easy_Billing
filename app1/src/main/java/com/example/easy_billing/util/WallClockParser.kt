package com.example.easy_billing.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, Android-free parser for time values pulled from the backend.
 *
 * Kept dependency-free (only java.text/java.util) so it can be unit-tested on
 * the plain JVM without Robolectric. [SyncManager] delegates to it:
 *  • event times (created_at, …) are parsed in the shop timezone (AppTime.ZONE)
 *  • cursor times (updated_at) are parsed in UTC
 */
object WallClockParser {

    private val PATTERNS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")

    /**
     * @param raw  a numeric epoch-millis string (returned as-is), or a naive
     *             wall-clock string like "2026-06-24T00:10:00" / "2026-06-24 00:10:00".
     * @param zone the timezone the wall clock should be interpreted in.
     * @return epoch-millis, or null if [raw] is blank/unparseable.
     */
    fun parse(raw: String?, zone: TimeZone): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        s.toLongOrNull()?.let { return it }                       // already epoch-ms
        val core = s.replace('T', ' ')
            .substringBefore('.').substringBefore('+').substringBefore('Z').trim()
        for (pattern in PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = zone
                    isLenient = false
                }
                return fmt.parse(core)?.time
            } catch (_: Exception) { /* try next pattern */ }
        }
        return null
    }
}
