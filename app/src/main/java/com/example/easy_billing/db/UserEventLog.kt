package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A local breadcrumb entry — one row per meaningful user action (screen
 * opened, button tapped, validation failure, sync/exception error).
 *
 * Purely a support/debugging trail, NOT analytics: when a shop reports a
 * bug, these rows let support reconstruct exactly what the shop did
 * leading up to the report, to tell a real app bug from an expected
 * validation error / user mistake.
 *
 * Two tiers, both stored in this same table but handled very differently:
 *  - "error" / "validation_failed" rows are low-volume and sync to the
 *    backend automatically in the background (see SyncManager.syncUserEvents
 *    and UserEventLogDao.getUnsynced, which deliberately excludes "action"
 *    rows) — these are what GET /admin/events shows without asking the
 *    shop owner to do anything.
 *  - "action" rows (screen opens, every Save/Cancel/Confirm/Delete tap —
 *    full click-level detail) are LOCAL ONLY and never auto-sync; the
 *    volume is too high to stream continuously without bloating the
 *    server table. They only leave the device when the shop owner (or
 *    support, walking them through it) triggers the one-shot "Send
 *    diagnostic report" action — see util/DiagnosticReportUploader.kt —
 *    which uploads the whole local table silently to the short-retention
 *    `diagnostic_reports` table.
 *
 * Deliberately capped locally (see UserEventLogDao.trimToMostRecent) and
 * only ever holds non-sensitive detail — [detail] should be a category or
 * code ("otp_invalid", "quantity_exceeds_stock"), never a raw password,
 * OTP, token, or full card/GSTIN value.
 */
@Entity(
    tableName = "user_event_logs",
    indices = [
        Index(value = ["is_synced"]),
        Index(value = ["created_at"])
    ]
)
data class UserEventLog(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** "action" (local-only, click-level), "validation_failed", or "error" (both sync automatically). */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /** Which screen this happened on, e.g. "InventoryActivity". */
    val screen: String? = null,

    /** Short, non-sensitive detail — a category/code, not raw user input. */
    val detail: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = appNow(),

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)
