package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import com.example.easy_billing.AccountSuspendedActivity
import org.json.JSONObject
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects the backend's "this shop is suspended" answer and shows the blocking
 * [AccountSuspendedActivity].
 *
 * An admin suspends a shop from the admin console. From then on EVERY shop API
 * (and login) answers HTTP 403 with
 * `{"detail": {"error": "ACCOUNT_SUSPENDED", "message": "..."}}`.
 *
 * That 403 must NOT be handled like the other 403s in this app (device mismatch,
 * subscription missing, "session expired"):
 *  - it must not log the user out or clear their Quick Unlock PIN, because a
 *    suspension is temporary and reversible;
 *  - it must not wipe or touch local data (unsynced bills stay on the device).
 * So every place that reacts to a 403 first asks [isSuspended].
 */
object AccountSuspension {

    private const val ERROR_CODE = "ACCOUNT_SUSPENDED"
    private const val DEBOUNCE_MS = 10_000L
    private val lastLaunchAt = AtomicLong(0L)

    /** Latest message from the server, kept so callers that only know "suspended" can show it. */
    @Volatile
    var lastMessage: String? = null
        private set

    /** Returns the owner-facing message if [body] is the suspension answer, else null. */
    fun messageFrom(body: String?): String? {
        if (body.isNullOrBlank() || !body.contains(ERROR_CODE)) return null
        return try {
            val detail = JSONObject(body).optJSONObject("detail") ?: return null
            if (detail.optString("error") != ERROR_CODE) return null
            val message = detail.optString("message").ifBlank { null }
                ?: "This account has been suspended. Please contact support."
            lastMessage = message
            message
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True if [e] is the suspension 403. Reads a COPY of the error body, so the
     * normal error parsing that runs afterwards still sees it.
     */
    fun isSuspended(e: Throwable): Boolean {
        if (e !is HttpException || e.code() != 403) return false
        val source = try {
            e.response()?.errorBody()?.source()
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            source.request(Long.MAX_VALUE)
            messageFrom(source.buffer.clone().readUtf8()) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the blocking screen. Skips the launch if it is already on screen, and
     * (unless [force]) if one was launched in the last few seconds, so several
     * requests failing at once do not stack screens.
     */
    fun show(context: Context, message: String? = lastMessage, force: Boolean = false) {
        if (AccountSuspendedActivity.isVisible) return
        if (!force) {
            val now = System.currentTimeMillis()
            val last = lastLaunchAt.get()
            if (now - last <= DEBOUNCE_MS || !lastLaunchAt.compareAndSet(last, now)) return
        } else {
            lastLaunchAt.set(System.currentTimeMillis())
        }
        val intent = Intent(context.applicationContext, AccountSuspendedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AccountSuspendedActivity.EXTRA_MESSAGE, message ?: lastMessage)
        }
        context.applicationContext.startActivity(intent)
    }
}
