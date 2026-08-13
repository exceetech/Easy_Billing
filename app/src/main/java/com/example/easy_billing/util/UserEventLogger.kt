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
 * only; SyncCoordinator uploads unsynced rows to the backend as part of
 * its normal sync pass (see UserEventLogSync.kt).
 *
 * Call [init] once from Application.onCreate before any [log] call —
 * same pattern as [AppClock.init] / [AppTime.init].
 *
 * IMPORTANT: [detail] must be a short, non-sensitive category or code
 * ("otp_invalid", "quantity_exceeds_stock", "sync_failed: purchases") —
 * NEVER a raw password, OTP, token, or full card/GSTIN value.
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
}
