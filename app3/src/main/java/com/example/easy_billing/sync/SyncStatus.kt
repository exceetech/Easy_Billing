package com.example.easy_billing.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A lightweight, in-memory snapshot of how sync is doing.
 *
 * This is intentionally NOT persisted — no new Room columns, no schema
 * migration. It is recomputed cheaply (a handful of COUNT(*) queries) at
 * the end of each sync pass via [SyncManager.refreshSyncStatus] and exposed
 * as a [StateFlow] so any screen can observe it.
 *
 *   • [pending]         — rows waiting to upload (normal; transient).
 *   • [failed]          — rows that errored and are being retried. A non-zero
 *                         value that does not drain over time is a real problem.
 *   • [blockedByProduct] — products not yet uploaded. Every bill / inventory
 *                         log / purchase that references one of these is stuck
 *                         behind it until the product syncs (see Issue 4).
 */
data class SyncSnapshot(
    val pending: Int = 0,
    val failed: Int = 0,
    val blockedByProduct: Int = 0,
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null
) {
    /** True when there is something the user may need to know about. */
    val hasProblems: Boolean get() = failed > 0 || blockedByProduct > 0

    /** True when everything local has reached the server. */
    val isFullySynced: Boolean get() = pending == 0 && failed == 0 && blockedByProduct == 0

    /** A short, human-readable line for a banner / toast. */
    fun summary(): String = when {
        blockedByProduct > 0 && failed > 0 ->
            "$failed record(s) failed and $blockedByProduct product(s) still uploading — will retry"
        blockedByProduct > 0 ->
            "$blockedByProduct product(s) still uploading — dependent records will sync after"
        failed > 0 ->
            "$failed record(s) couldn't sync — will retry automatically"
        pending > 0 ->
            "$pending record(s) waiting to sync"
        else -> "All data synced"
    }
}

/** Process-wide, observable sync status. Safe to read/collect from any thread. */
object SyncState {
    private val _flow = MutableStateFlow(SyncSnapshot())
    val flow: StateFlow<SyncSnapshot> = _flow.asStateFlow()

    /** Current snapshot without collecting the flow. */
    val current: SyncSnapshot get() = _flow.value

    internal fun update(snapshot: SyncSnapshot) { _flow.value = snapshot }
}
