package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide, throttled cache of the shop's subscription tier — mirrors
 * BackendHealthStatus.checkIfDue()'s exact shape (same mutex-across-the-
 * whole-operation pattern, same "return cached value if checked recently"
 * throttle), applied to tier/status instead of raw reachability.
 *
 * Why this exists (onboarding/subscription plan §5.2/§5.3/§6.5): the app
 * is offline-first, so a device that cached "premium" before losing
 * connectivity would otherwise keep showing Premium screens (GST/profit/
 * AI) indefinitely. This gives that cache a bounded staleness window
 * instead — every UI-gating check re-verifies against the server at most
 * every [MIN_CHECK_INTERVAL_MS], falling back to the last known value
 * only when a fresh check can't be made (offline, or throttled).
 *
 * IMPORTANT: this is the UI-side convenience gate only, not the real
 * security boundary — a modified client could ignore this entirely. The
 * actual enforcement is server-side (require_premium_tier on the
 * backend, see dependencies.py); every GST/profit/AI endpoint already
 * rejects a Base-tier shop regardless of what this cache says. This
 * class exists purely so the app doesn't show a screen the server is
 * about to refuse anyway, and so an upgrade prompt can appear at the
 * point of tap instead of after a failed network call.
 *
 * Also worth noting: this cache's WORST-CASE staleness while genuinely
 * offline is already bounded by the existing offline-session-timeout
 * (BaseActivity/SessionTimeoutGuard force a logout after
 * SESSION_OFFLINE_LIMIT_MS with no server contact) — once that fires,
 * the whole session ends regardless of this cache, so no separate
 * staleness clock is needed here beyond the live-check throttle below.
 */
object SubscriptionTierCache {

    data class TierInfo(val tier: String?, val status: String)

    private val _lastTierInfo = MutableStateFlow<TierInfo?>(null)
    val lastTierInfo: StateFlow<TierInfo?> = _lastTierInfo

    private val checkMutex = Mutex()
    private var lastCheckedAt = 0L
    private const val MIN_CHECK_INTERVAL_MS = 60_000L

    /**
     * Returns the current tier/status, doing a real network call only if
     * more than [MIN_CHECK_INTERVAL_MS] has passed since the last one
     * anywhere in the app (shared throttle, same reasoning as
     * BackendHealthStatus — many guarded entry points shouldn't each
     * hammer the server on their own clock). On network failure, falls
     * back to the last known value rather than denying access outright —
     * a momentary connectivity blip shouldn't lock a paying user out of
     * a screen they're entitled to.
     */
    suspend fun checkIfDue(context: Context): TierInfo? {
        return checkMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastCheckedAt < MIN_CHECK_INTERVAL_MS && _lastTierInfo.value != null) {
                return@withLock _lastTierInfo.value
            }

            val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@withLock _lastTierInfo.value

            try {
                val res = RetrofitClient.api.getSubscription(token)
                lastCheckedAt = now
                val info = TierInfo(res.tier, res.status)
                _lastTierInfo.value = info
                info
            } catch (e: Exception) {
                // Offline or transient failure — serve the last known
                // value rather than treating this as "no access". Does
                // NOT update lastCheckedAt, so the next call retries
                // immediately instead of honoring the throttle against a
                // check that never actually completed.
                _lastTierInfo.value
            }
        }
    }

    /** True only when a genuinely current (or best-effort cached) check
     *  says premium. Callers needing the up-to-date answer should call
     *  [checkIfDue] directly instead of reading a stale synchronous flag. */
    fun isPremiumCached(): Boolean = _lastTierInfo.value?.tier == "premium"
}
