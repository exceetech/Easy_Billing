package com.example.easy_billing.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide "can we reach OUR server" signal, distinct from
 * SessionTimeoutGuard/BaseActivity's "does the DEVICE have internet" signal.
 *
 * Before this existed, a real backend outage was surfaced completely
 * inconsistently depending on which code path happened to notice it:
 * NetworkReceiver showed a one-shot Toast (only if the OS fired a
 * network-regain event while the outage was ongoing), SplashActivity showed
 * nothing at all for the identical situation, and SyncWorker/SyncCoordinator
 * retried silently forever with no user-facing signal. A sustained outage
 * could easily produce zero visible indication anywhere in the app.
 *
 * Every place that talks to the backend and gets back something other than
 * "device has no internet" (NetworkReceiver, SplashActivity's getProfile()
 * check) reports into this single StateFlow instead of handling it locally.
 * DashboardActivity observes it and shows/hides a persistent banner
 * (bannerBackendUnreachable in activity_dashboard.xml) instead of relying on
 * a Toast that's easy to miss and says nothing about whether the problem is
 * still ongoing.
 *
 * [checkIfDue] additionally makes this the single shared, throttled gate the
 * offline-session-timeout flow (BaseActivity.checkOfflineSession() /
 * SessionTimeoutGuard) uses to implement "check internet, THEN check
 * server, only then treat the session as alive" — the original intended
 * design. Before this, the offline-timeout only ever checked device-level
 * internet (NET_CAPABILITY_VALIDATED) and refreshed LAST_ONLINE off that
 * alone, so a permanently-dead backend never actually expired a session as
 * long as the device had generic internet from any source. Centralizing the
 * throttle here means every guarded screen across the whole app shares ONE
 * real server probe at most every [MIN_CHECK_INTERVAL_MS], instead of each
 * of the ~30 guarded screens independently hammering the server on its own
 * 5-second tick.
 */
object BackendHealthStatus {

    private val _isReachable = MutableStateFlow(true)
    val isReachable: StateFlow<Boolean> = _isReachable

    /** Call with true on any successful backend response, false on a genuine
     *  reachability failure (timeout, connection refused, 5xx, etc.) — NOT
     *  for "device has no internet," which is a different concern entirely
     *  and already has its own dedicated handling. */
    fun report(reachable: Boolean) {
        _isReachable.value = reachable
    }

    private val checkMutex = Mutex()
    private var lastCheckedAt = 0L
    private const val MIN_CHECK_INTERVAL_MS = 20_000L

    /**
     * Call right after a successful login. Without this, a stale cached
     * Status from BEFORE the login (e.g. the UNAUTHORIZED or UNREACHABLE
     * verdict that caused the forced logout the user is now recovering
     * from) can survive into the fresh session: if the user manages to log
     * back in within the same [MIN_CHECK_INTERVAL_MS] throttle window, the
     * very next checkIfDue() tick on the new session returns that stale
     * verdict instead of running a fresh check — even though the login
     * request itself just proved the server is reachable and the new token
     * is valid. That immediately force-logs the user out again, and since
     * nothing ever resets the stale cache, retrying produces the same
     * result every time inside that window: an apparent infinite
     * "session expired" loop that a real, successful login can't escape.
     * A successful login IS a fresh, authoritative "server reachable, token
     * valid" signal, so stamp it directly rather than waiting for the next
     * throttled probe to eventually catch up.
     */
    fun markVerifiedNow() {
        lastCheckedAt = System.currentTimeMillis()
        _lastStatus.value = BackendReachabilityChecker.Status.OK
        _isReachable.value = true
    }

    /** Last real Status this ran, so a throttled (skipped) call still has
     *  something meaningful to fall back on instead of a bare boolean. */
    private val _lastStatus = MutableStateFlow<BackendReachabilityChecker.Status?>(null)
    val lastStatus: StateFlow<BackendReachabilityChecker.Status?> = _lastStatus

    /**
     * Runs a fresh server probe only if more than [MIN_CHECK_INTERVAL_MS] has
     * passed since the last one anywhere in the app; otherwise returns the
     * cached last-known Status immediately with no network call. This is what
     * lets the offline-timeout's 5-second local tick actually gate on server
     * reachability without turning into a server-hammering loop — the tick
     * still runs every 5s, but the real network probe underneath it is
     * throttled app-wide.
     *
     * Returns the actual [BackendReachabilityChecker.Status] rather than a
     * plain boolean — this matters. An earlier version of this function
     * returned Boolean derived from [isReachable], which reports true for
     * BOTH a genuinely healthy session (OK) AND a rejected one (UNAUTHORIZED
     * — the server responded, so it counts as "reachable"). Callers using
     * that boolean to decide "is this session still alive" would treat a
     * revoked/expired token exactly like a healthy one and keep resetting
     * the offline-timeout forever, since a boolean can't distinguish the two.
     * Callers must now branch on the real Status and treat UNAUTHORIZED as
     * an immediate logout, not as "session confirmed alive."
     */
    suspend fun checkIfDue(context: Context): BackendReachabilityChecker.Status {
        // The mutex is held across the ENTIRE check — including the slow
        // network call — not just the throttle decision. An earlier version
        // released the lock immediately after recording lastCheckedAt, then
        // made the network call outside it; a second caller arriving during
        // that in-flight call (very plausible right after cold start, since
        // BaseActivity's 5s loop and DashboardActivity's own 20s loop both
        // start ticking within the same onResume()) would see "already
        // checked recently" and fall back to `_lastStatus.value ?: OK` —
        // handing out a fabricated OK even though the real, still-pending
        // result might resolve to UNAUTHORIZED or UNREACHABLE moments later.
        // Holding the lock for the whole operation means a concurrent caller
        // simply suspends until the in-flight check finishes, then reads the
        // now-genuinely-fresh _lastStatus — it can no longer observe a state
        // where "recently checked" is true but the result isn't in yet.
        return checkMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastCheckedAt < MIN_CHECK_INTERVAL_MS) {
                // Genuinely throttled against a COMPLETED check — any
                // in-flight check would still be holding this lock, so if
                // we got here, _lastStatus is guaranteed up to date (or
                // there has truly never been a check yet, see below).
                return@withLock _lastStatus.value ?: BackendReachabilityChecker.Status.OK
            }
            lastCheckedAt = now
            // BackendReachabilityChecker.check() calls report() internally
            // for OK/UNREACHABLE/UNAUTHORIZED (NO_INTERNET leaves the cached
            // isReachable value untouched — that's the device-offline case, a
            // different concern, not a server-reachability answer either way).
            val status = BackendReachabilityChecker.check(context)
            _lastStatus.value = status
            status
        }
    }
}
