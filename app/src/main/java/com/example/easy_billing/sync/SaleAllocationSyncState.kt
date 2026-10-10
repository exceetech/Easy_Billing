package com.example.easy_billing.sync

import android.content.Context
import java.util.UUID

/**
 * Stage 3D hardening: per-install identity and pull cursors for the
 * allocation / pool / claim sync.
 *
 * - [origin] is a random id for this install's local database. The server keeps
 *   rows by (shop, origin, local id), so a reinstall or a wipe (ids restart at 1)
 *   can never overwrite rows an earlier install pushed.
 * - The cursors remember the highest server row already pulled, so a pull only
 *   downloads what is new.
 *
 * [reset] must run wherever the local database is wiped.
 */
object SaleAllocationSyncState {

    private const val PREFS = "sale_alloc_sync"
    private const val KEY_ORIGIN = "origin"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun origin(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_ORIGIN, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "").take(32)
        p.edit().putString(KEY_ORIGIN, fresh).apply()
        return fresh
    }

    fun cursor(context: Context, shopId: Int, name: String): Int =
        prefs(context).getInt("cursor_${shopId}_$name", 0)

    fun setCursor(context: Context, shopId: Int, name: String, value: Int) {
        prefs(context).edit().putInt("cursor_${shopId}_$name", value).apply()
    }

    /** Call when the local database is wiped: new origin, pull everything again. */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
