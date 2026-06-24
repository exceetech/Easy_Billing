package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.widget.Toast
import com.example.easy_billing.MainActivity
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for "network regained" and runs a sync pass.
 *
 * Lifecycle hygiene (Issue 17):
 *   • Uses the APPLICATION context, never the Activity that created it, so the
 *     long-lived NetworkManager callback can't leak an Activity.
 *   • Registers the callback exactly ONCE per process. DashboardActivity calls
 *     startListening() on every onResume; previously each call registered a new
 *     callback (a leak + duplicate sync triggers). The [registered] guard makes
 *     repeated calls no-ops.
 *   • [isSyncing] is an AtomicBoolean so the cross-thread check-and-set is safe.
 */
class NetworkReceiver(context: Context) {

    private val appCtx = context.applicationContext

    fun startListening() {
        // Register at most once for the whole process.
        if (!registered.compareAndSet(false, true)) return

        val connectivityManager =
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {

                    // Collapse overlapping triggers into one in-flight sync.
                    if (!isSyncing.compareAndSet(false, true)) return

                    scope.launch {
                        try {
                            delay(1500)

                            when (checkBackend()) {
                                BackendStatus.UNAUTHORIZED -> {
                                    // Token is genuinely invalid (401/403) → log out.
                                    forceLogout("Session expired")
                                    return@launch
                                }
                                BackendStatus.UNREACHABLE -> {
                                    // Transient: server slow/down, timeout, or no real
                                    // connectivity. Do NOT log out — pending rows stay
                                    // put and are retried on the next sync trigger.
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(appCtx, "Server not reachable", Toast.LENGTH_SHORT).show()
                                    }
                                    return@launch
                                }
                                BackendStatus.OK -> { /* proceed to sync below */ }
                            }

                            // ✅ BACKEND OK → SYNC
                            // Route through the coordinator's single-flight lock so a
                            // network-regain sync never races a write-triggered sync and
                            // double-pushes non-bill rows (Sync audit S1).
                            com.example.easy_billing.sync.SyncCoordinator
                                .get(appCtx)
                                .flushPending()
                                .join()

                        } catch (e: Exception) {
                            e.printStackTrace()
                            when {
                                // 409 WORKSPACE_CHANGED: WorkspaceInterceptor already launched
                                // WorkspaceChangedActivity. Don't forceLogout (race condition).
                                e is retrofit2.HttpException && e.code() == 409 -> return@launch
                                // Genuine auth failure → log out.
                                e is retrofit2.HttpException && (e.code() == 401 || e.code() == 403) ->
                                    forceLogout("Session expired")
                                // Anything else (timeout, 5xx, parse, offline) is transient:
                                // never log the user out for it. Unsynced rows remain pending
                                // and retry on the next trigger.
                                else -> android.util.Log.w(
                                    "NetworkReceiver",
                                    "Transient sync error, will retry: ${e.message}"
                                )
                            }
                        } finally {
                            isSyncing.set(false)
                        }
                    }
                }
            }
        )
    }

    // ================= BACKEND CHECK =================

    /** Distinguishes a real auth failure (→ logout) from mere unreachability (→ retry later). */
    private enum class BackendStatus { OK, UNREACHABLE, UNAUTHORIZED }

    private suspend fun checkBackend(): BackendStatus {
        val token = appCtx.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null) ?: return BackendStatus.UNAUTHORIZED

        return try {
            withTimeout(3000) {
                RetrofitClient.api.getSubscription(token)
            }
            BackendStatus.OK
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401 || e.code() == 403) BackendStatus.UNAUTHORIZED
            else BackendStatus.UNREACHABLE
        } catch (e: Exception) {
            // Timeout, no connection, parse error, etc. — not an auth problem.
            BackendStatus.UNREACHABLE
        }
    }

    // ================= FORCE LOGOUT =================

    private fun forceLogout(reason: String) {

        val prefs = appCtx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        // Preserve the stable per-install device id — it is the bill idempotency
        // key (client_device_id), not a credential. Wiping it would let retried
        // bills be re-created as duplicates on the server after the next login.
        val deviceId = prefs.getString("DEVICE_ID", null)
        prefs.edit().clear().apply()
        deviceId?.let { prefs.edit().putString("DEVICE_ID", it).apply() }
        // Drop delta-pull cursors so a different/restored workspace starts fresh
        // and can't skip rows behind a stale cursor (R6).
        appCtx.getSharedPreferences("sync_cursors", Context.MODE_PRIVATE).edit().clear().apply()

        scope.launch(Dispatchers.Main) {
            Toast.makeText(appCtx, reason, Toast.LENGTH_LONG).show()

            val intent = Intent(appCtx, MainActivity::class.java)
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

            appCtx.startActivity(intent)
        }
    }

    private companion object {
        /** One process-wide scope instead of an ad-hoc one per callback. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** True once the single network callback has been registered. */
        private val registered = AtomicBoolean(false)

        /** Guards against overlapping sync passes from rapid network flaps. */
        private val isSyncing = AtomicBoolean(false)
    }
}
