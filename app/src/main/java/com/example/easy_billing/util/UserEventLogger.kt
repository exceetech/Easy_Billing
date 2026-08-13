package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.UserEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A single call site for recording a "breadcrumb" — what the user was
 * doing right before something failed or looked wrong. NOT analytics:
 * this exists so that when a shop reports a bug, support can pull up
 * that shop's recent events (see the backend's GET /admin/events) and
 * tell whether they followed the right steps and the app failed them, or
 * they hit an expected validation error — a user mistake, not a bug.
 *
 * Fire-and-forget by design: [log] never blocks or throws back into the
 * caller — a failure to write a breadcrumb must never break the actual
 * user-facing action it's describing. Writes to the local Room table
 * only.
 *
 * Two tiers — see UserEventLog.kt for the full rationale:
 *  - [logError] / [logValidationFailed] sync to the backend automatically
 *    (SyncManager.syncUserEvents), low volume, always available for a
 *    support lookup.
 *  - [logAction] is LOCAL ONLY — full click-level detail (screen opens,
 *    every Save/Cancel/Confirm/Delete tap). Never auto-syncs; only leaves
 *    the device via the one-shot "Send diagnostic report" action (see
 *    DiagnosticReportUploader.kt).
 *
 * Call [init] once from Application.onCreate before any [log] call —
 * same pattern as [AppClock.init] / [AppTime.init].
 *
 * IMPORTANT: [detail] may include real business data (product names,
 * prices, customer name/phone, GSTIN, form field values, etc.) — that's
 * the point, it's what makes a support read-through useful. The ONLY
 * things that must never appear here are password, OTP, and auth
 * token/secret values — those stay masked or omitted everywhere, no
 * exceptions.
 */
object UserEventLogger {

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * @param eventType one of "screen_opened", "action_tapped",
     *   "validation_failed", "error" (free-form, but keep to this small
     *   vocabulary so the admin-side event list stays easy to scan).
     * @param screen which screen/activity this happened on, e.g.
     *   "InventoryActivity" — pass the class's simple name.
     * @param detail short, non-sensitive category/code — see class doc.
     */
    fun log(eventType: String, screen: String? = null, detail: String? = null) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                AppDatabase.getDatabase(ctx).userEventLogDao().insert(
                    UserEventLog(eventType = eventType, screen = screen, detail = detail)
                )
            } catch (_: Exception) {
                // Never let a breadcrumb-logging failure surface anywhere —
                // this is a support convenience, not a critical path.
            }
        }
    }

    /** Convenience for the very common "an exception was caught" case. */
    fun logError(screen: String, detail: String) = log("error", screen, detail)

    /** Convenience for a validation failure the user needs to fix themselves. */
    fun logValidationFailed(screen: String, detail: String) = log("validation_failed", screen, detail)

    /**
     * Local-only click-level breadcrumb — screen opened, or a terminal
     * button tapped (Save/Add, Cancel, Confirm, Delete). Deliberately NOT
     * for every field edit/keystroke/focus change — that would be noise,
     * not a readable trail. Never syncs automatically; see class doc.
     */
    fun logAction(screen: String, detail: String) = log("action", screen, detail)
}
