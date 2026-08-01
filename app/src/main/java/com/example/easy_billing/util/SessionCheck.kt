package com.example.easy_billing.util

import android.content.Context
import com.example.easy_billing.network.RetrofitClient
import retrofit2.HttpException

/**
 * Shared cold-start session check — used by both SplashActivity (the
 * normal launcher path) and MainActivity.checkExistingSession() (hit
 * directly if Android recreates MainActivity after process death,
 * bypassing Splash entirely). These two call sites previously carried
 * separate hand-copied implementations that drifted: Splash's reset the
 * BackendHealthStatus verified-clock on success and reported backend
 * reachability on failure, MainActivity's didn't. That gap meant the
 * exact "logged out again right after a successful check" bug the
 * verified-clock reset exists to prevent could resurface via the
 * MainActivity path specifically. Factored into one function so both
 * call sites can no longer disagree.
 */
object SessionCheck {

    enum class Result { VALID, ONBOARDING_INCOMPLETE, INVALID_TOKEN, WORKSPACE_CHANGED }

    suspend fun run(context: Context, token: String): Result {
        return try {
            val profile = RetrofitClient.api.getProfile(token)
            // markVerifiedNow(), not just report(true) — this call just
            // proved the token is genuinely valid AND the server is
            // reachable, so it should also reset the shared
            // checkIfDue() throttle clock. Otherwise a stale
            // UNAUTHORIZED/UNREACHABLE verdict cached from before this
            // check (e.g. the app was killed and reopened shortly after
            // a forced logout) could get handed back out by the very
            // next offline-timeout tick on Dashboard, immediately
            // logging the user right back out despite this successful
            // check. See BackendHealthStatus.markVerifiedNow().
            BackendHealthStatus.markVerifiedNow()

            // Onboarding routing gate — checked before letting a valid
            // session through to Dashboard. Respects the kill switch: if
            // the backend has enforcement turned off, this behaves
            // exactly like the old VALID-always-wins path.
            if (profile.onboarding_enforcement_enabled && profile.onboarding_completed_at == null) {
                Result.ONBOARDING_INCOMPLETE
            } else {
                Result.VALID
            }
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> Result.INVALID_TOKEN
                409 -> Result.WORKSPACE_CHANGED
                else -> {
                    // Server responded — it's reachable, just errored (5xx etc.).
                    // Still let the user through to cached Dashboard; only the
                    // banner state changes here, not the routing decision.
                    BackendHealthStatus.report(true)
                    Result.VALID
                }
            }
        } catch (e: Exception) {
            // Could be "device offline" or "our server specifically didn't
            // respond." Only report the latter — checking device
            // connectivity here keeps this consistent with
            // NetworkReceiver.checkBackend()'s same split.
            if (NetworkUtils.isOnline(context)) {
                BackendHealthStatus.report(false)
            }
            Result.VALID // offline or server hiccup → let user into cached Dashboard
        }
    }
}
