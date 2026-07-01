package com.example.easy_billing

import android.content.Context
import com.example.easy_billing.network.AiInsight

/**
 * Lightweight persistence for the AI-insight notification sheet.
 *
 * Two id sets are kept in SharedPreferences:
 *  • dismissed — insights the user cleared (never shown again until the
 *    backend produces a genuinely new one).
 *  • seen      — insights the user has already looked at (drives the unread
 *    count badge on the header bell).
 *
 * An insight's id is a stable hash of its type + title, so the same insight
 * keeps the same id across refreshes and doesn't reappear after being cleared.
 */
class NotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_notifications", Context.MODE_PRIVATE)

    private fun idOf(insight: AiInsight): String =
        "${insight.type}|${insight.title}".hashCode().toString()

    private fun dismissed(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet())

    private fun seen(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet())

    /** Insights still worth showing (not cleared by the user), order preserved. */
    fun active(all: List<AiInsight>): List<AiInsight> {
        val d = dismissed()
        return all.filterNot { idOf(it) in d }
    }

    /** Number of active insights the user hasn't seen yet → the badge count. */
    fun unseenCount(all: List<AiInsight>): Int {
        val d = dismissed()
        val s = seen()
        return all.count { idOf(it) !in d && idOf(it) !in s }
    }

    /** Mark every currently-active insight as seen (badge → 0). */
    fun markAllSeen(all: List<AiInsight>) {
        val s = seen()
        active(all).forEach { s.add(idOf(it)) }
        prefs.edit().putStringSet(KEY_SEEN, s).apply()
    }

    /** Clear a single insight. */
    fun dismiss(insight: AiInsight) {
        val d = dismissed()
        d.add(idOf(insight))
        prefs.edit().putStringSet(KEY_DISMISSED, d).apply()
    }

    /** Clear every active insight at once. */
    fun clearAll(all: List<AiInsight>) {
        val d = dismissed()
        active(all).forEach { d.add(idOf(it)) }
        prefs.edit().putStringSet(KEY_DISMISSED, d).apply()
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_ids"
        const val KEY_SEEN = "seen_ids"
    }
}
