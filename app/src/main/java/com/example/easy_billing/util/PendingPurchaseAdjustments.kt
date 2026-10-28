package com.example.easy_billing.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash safety net for the money step of a PURCHASE document (cancel, debit note, credit note, correction).
 *
 * The document itself is saved first; the supplier-balance / refund question follows on screen. If the app is
 * killed before that question finished, nothing says it was missed. So the step is written down here when it
 * starts and struck off when it ends. [CreditAdjustmentPrompt.replayPendingPurchases] finishes what is left, but
 * ONLY when nothing of it had been written yet (otherwise it was already under way and its own notes cover it).
 * Supplier returns from the Inventory page keep their own list ([InventoryReturnMoney]) and are not stored here.
 */
object PendingPurchaseAdjustments {

    private const val PREFS = "pending_purchase_adjustments"
    private const val KEY = "items"
    private const val MAX_TRIES = 3

    data class Item(
        val shopId: Int, val purchaseId: Int, val kind: String,
        val amount: Double, val doc: Int, val tries: Int = 0
    )

    private fun load(context: Context): MutableList<Item> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val out = mutableListOf<Item>()
        if (raw != null) {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Item(o.getInt("shop"), o.getInt("pid"), o.getString("kind"), o.getDouble("amt"), o.getInt("doc"), o.optInt("try", 0))
            }
        }
        out
    } catch (_: Exception) { mutableListOf() }

    private fun save(context: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("shop", it.shopId); put("pid", it.purchaseId); put("kind", it.kind)
                    put("amt", it.amount); put("doc", it.doc); put("try", it.tries)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun same(a: Item, shop: Int, purchaseId: Int, kind: String, doc: Int) =
        a.shopId == shop && a.purchaseId == purchaseId && a.kind == kind && a.doc == doc

    /** Writes the step down; an existing entry keeps its retry count. */
    fun remember(context: Context, shop: Int, purchaseId: Int, kind: String, amount: Double, doc: Int) {
        if (shop <= 0) return
        val list = load(context)
        if (list.any { same(it, shop, purchaseId, kind, doc) }) return
        list += Item(shop, purchaseId, kind, amount, doc)
        save(context, list)
    }

    fun forget(context: Context, shop: Int, purchaseId: Int, kind: String, doc: Int) {
        val list = load(context)
        if (list.removeAll { same(it, shop, purchaseId, kind, doc) }) save(context, list)
    }

    fun forShop(context: Context, shop: Int): List<Item> = load(context).filter { it.shopId == shop }

    /** Counts one replay attempt; drops the entry once it has had [MAX_TRIES] so it never nags forever. */
    fun countAttempt(context: Context, item: Item) {
        val list = load(context)
        val i = list.indexOfFirst { same(it, item.shopId, item.purchaseId, item.kind, item.doc) }
        if (i < 0) return
        if (list[i].tries + 1 >= MAX_TRIES) list.removeAt(i) else list[i] = list[i].copy(tries = list[i].tries + 1)
        save(context, list)
    }
}
