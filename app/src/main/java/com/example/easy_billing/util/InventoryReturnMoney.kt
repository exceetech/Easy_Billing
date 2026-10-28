package com.example.easy_billing.util

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.repository.CreditAdjustmentRepository
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
import com.example.easy_billing.repository.CreditAdjustmentRepository.Source
import com.example.easy_billing.repository.InventoryReductionRepository.ReturnPosting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The money side of a "return to supplier" made from the Inventory page (batch return, or clear stock as return).
 *
 * The stock and the debit-note rows are already saved. Each slice of the return is then settled the same way a
 * return made from the purchase screen is:
 *  - a slice that belongs to a purchase invoice follows how THAT purchase was paid: the part on credit lowers the
 *    supplier's balance (asking cash-or-advance only on an overshoot), and the part paid in cash/UPI/card/bank is
 *    a supplier refund, recorded on the "How did the supplier refund you?" sheet. A "refund still due" note is
 *    saved before that sheet opens, so closing it never loses the amount;
 *  - a slice that can't be traced to an invoice has no payment history: if the owner chose a credit account on the
 *    Return screen it lowers that account, otherwise it is taken as a cash refund and the same sheet asks for it.
 *
 * Slices run one after another so two sheets are never on screen at once. [onDone] runs after the last one.
 *
 * Safety net: every slice is written down (phone storage) BEFORE its prompts start and struck off when they finish.
 * If the app is closed in between, [replayPending] finishes the leftovers the next time the Inventory page opens,
 * skipping whatever already happened (a ledger row for the slice means its credit step is done; the refund sheet
 * checks for its own earlier rows).
 */
object InventoryReturnMoney {

    private const val PREFS = "pending_inventory_returns"
    private const val KEY = "items"

    private data class Pending(
        val shopId: Int, val purchaseId: Int?, val amount: Double, val documentId: Int,
        val isCredit: Boolean, val creditAccountId: Int?, val tries: Int = 0
    )

    // ── tiny persistent list ─────────────────────────────────────────────────────────────────────────────

    private fun load(activity: AppCompatActivity): MutableList<Pending> = try {
        val raw = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null)
        val out = mutableListOf<Pending>()
        if (raw != null) {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Pending(
                    o.getInt("shop"), if (o.has("pid")) o.getInt("pid") else null, o.getDouble("amt"), o.getInt("doc"),
                    o.optBoolean("cr", false), if (o.has("acc")) o.getInt("acc") else null, o.optInt("try", 0)
                )
            }
        }
        out
    } catch (_: Exception) { mutableListOf() }

    private fun save(activity: AppCompatActivity, items: List<Pending>) {
        try {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("shop", it.shopId); it.purchaseId?.let { v -> put("pid", v) }
                    put("amt", it.amount); put("doc", it.documentId); put("cr", it.isCredit)
                    it.creditAccountId?.let { v -> put("acc", v) }
                    put("try", it.tries)
                })
            }
            activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun shopId(activity: AppCompatActivity): Int =
        activity.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE).getInt("SHOP_ID", -1)

    private fun strike(activity: AppCompatActivity, doc: Int, purchaseId: Int?) {
        val list = load(activity)
        list.removeAll { it.documentId == doc && it.purchaseId == purchaseId }
        save(activity, list)
        CashExcessMemo.forget(activity, doc)
    }

    /** The owner closed the refund sheet: keep the slice to ask again next time, but only a few times. */
    private fun keepForLater(activity: AppCompatActivity, doc: Int, purchaseId: Int?) {
        val list = load(activity)
        val i = list.indexOfFirst { it.documentId == doc && it.purchaseId == purchaseId }
        if (i < 0) return
        if (list[i].tries + 1 >= MAX_TRIES) list.removeAt(i) else list[i] = list[i].copy(tries = list[i].tries + 1)
        save(activity, list)
    }

    private const val MAX_TRIES = 3

    // ── normal run ───────────────────────────────────────────────────────────────────────────────────────

    fun run(
        activity: AppCompatActivity,
        postings: List<ReturnPosting>,
        isCredit: Boolean,
        creditAccountId: Int?,
        onDone: () -> Unit
    ) {
        val queue = postings.filter { it.amount > 0.004 }
        val shop = shopId(activity)
        // Write every slice down first, so a closed app can finish them later.
        val list = load(activity)
        queue.forEach { p ->
            list.removeAll { it.documentId == p.documentId && it.purchaseId == p.purchaseId }
            list += Pending(shop, p.purchaseId, p.amount, p.documentId, isCredit, creditAccountId)
        }
        save(activity, list)

        // Several invoices in one return: ask "how did the supplier refund you?" once and reuse the answer.
        BatchRefundSession.docs = if (queue.size > 1) queue.map { it.documentId }.toSet() else emptySet()
        BatchRefundSession.modes = null

        fun next(i: Int) {
            if (i >= queue.size || activity.isFinishing) { BatchRefundSession.end(); onDone(); return }
            val p = queue[i]
            val after = { strikeIt: Boolean ->
                if (strikeIt) strike(activity, p.documentId, p.purchaseId) else keepForLater(activity, p.documentId, p.purchaseId)
                next(i + 1)
            }
            settle(activity, Pending(shop, p.purchaseId, p.amount, p.documentId, isCredit, creditAccountId), after)
        }
        next(0)
    }

    /** One slice, fresh. */
    private fun settle(activity: AppCompatActivity, p: Pending, after: (Boolean) -> Unit) {
        if (p.purchaseId != null) {
            CreditAdjustmentPrompt.handlePurchase(
                activity = activity, purchaseId = p.purchaseId, kind = Kind.PURCHASE_RETURN,
                amount = p.amount, documentLocalId = p.documentId, onDone = { after(true) }
            )
        } else if (p.isCredit && p.creditAccountId != null) {
            CreditAdjustmentPrompt.handleAccountReturn(
                activity = activity, accountId = p.creditAccountId, amount = p.amount,
                documentLocalId = p.documentId, onDone = { after(true) }
            )
        } else {
            // No invoice and no credit account: the supplier is taken to have paid it back. There is no purchase
            // screen to show a "refund still due" note on, so if the sheet is closed without recording, the slice is
            // offered again the next time the Inventory page opens (a few times at most).
            PurchaseMoneyPrompt.askRefund(
                activity, Source.SupplierAccount(-1), Kind.PURCHASE_RETURN, p.documentId,
                0.0, p.amount
            ) {
                activity.lifecycleScope.launch {
                    val recorded = withContext(Dispatchers.IO) {
                        try {
                            AppDatabase.getDatabase(activity).moneyEntryDao()
                                .forParent(MoneySource.PURCHASE_RETURN, p.documentId)
                                .any { it.direction == MoneyDirection.IN && it.reason == MoneyReason.PURCHASE_REFUND }
                        } catch (_: Exception) { true }
                    }
                    after(recorded)
                }
            }
        }
    }

    // ── safety net ───────────────────────────────────────────────────────────────────────────────────────

    /** Finishes slices left over from an earlier session. Call once when the Inventory page opens. */
    fun replayPending(activity: AppCompatActivity) {
        // A recovery run always asks its own sheets: never reuse an answer left over from an unfinished run.
        BatchRefundSession.end()
        val mine = load(activity).filter { it.shopId == shopId(activity) }
        if (mine.isEmpty()) return
        activity.lifecycleScope.launch {
            fun next(i: Int) {
                if (i >= mine.size || activity.isFinishing) return
                val p = mine[i]
                val after = { strikeIt: Boolean ->
                    if (strikeIt) strike(activity, p.documentId, p.purchaseId) else keepForLater(activity, p.documentId, p.purchaseId)
                    next(i + 1)
                }
                activity.lifecycleScope.launch {
                    // Did this slice's credit step already happen? Its ledger row carries the slice's document id.
                    val creditPosted = withContext(Dispatchers.IO) {
                        try {
                            AppDatabase.getDatabase(activity).creditTransactionDao()
                                .getForDoc(CreditAdjustmentRepository.docId(Kind.PURCHASE_RETURN, p.documentId), p.shopId)
                                .sumOf { it.amount }
                        } catch (_: Exception) { 0.0 }
                    }
                    when {
                        // Credit part already lowered the balance. Work out what the first run saw before it: how much of the
                        // slice was on credit, so the rest is the paid part; plus the "settle in cash" answer if one was given.
                        creditPosted > 0.004 && p.purchaseId != null -> {
                            val remainingNow = CreditAdjustmentRepository.creditShareOf(
                                activity, Source.Purchase(p.purchaseId), 1e12
                            )
                            val creditBefore = minOf(p.amount, remainingNow + creditPosted)
                            val paidPart = p.amount - creditBefore
                            val cash = minOf(CashExcessMemo.recall(activity, p.documentId), creditBefore)
                            if (paidPart > 0.004 || cash > 0.004) PurchaseMoneyPrompt.askRefund(
                                activity, Source.Purchase(p.purchaseId), Kind.PURCHASE_RETURN, p.documentId,
                                paidPart, cash
                            ) { after(true) } else after(true)
                        }
                        // Credit account already adjusted; only a "settle in cash" answer can still be open.
                        creditPosted > 0.004 -> {
                            val cash = minOf(CashExcessMemo.recall(activity, p.documentId), p.amount)
                            if (cash > 0.004) PurchaseMoneyPrompt.askRefund(
                                activity, Source.SupplierAccount(-1), Kind.PURCHASE_RETURN, p.documentId, 0.0, cash
                            ) { after(true) } else after(true)
                        }
                        else -> settle(activity, p, after)
                    }
                }
            }
            next(0)
        }
    }
}

/**
 * Remembers a "settle the extra in cash" answer on a supplier return, until that return's money steps are finished.
 * Only the safety net above reads it, so a closed app can still ask for the cash refund the owner agreed to.
 */
object CashExcessMemo {
    private const val PREFS = "return_cash_excess"

    fun remember(context: android.content.Context, documentId: Int, amount: Double) {
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(documentId.toString(), PaymentSplit.round2(amount).toString()).apply()
        } catch (_: Exception) { }
    }

    fun recall(context: android.content.Context, documentId: Int): Double = try {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(documentId.toString(), null)?.toDoubleOrNull() ?: 0.0
    } catch (_: Exception) { 0.0 }

    fun forget(context: android.content.Context, documentId: Int) {
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove(documentId.toString()).apply()
        } catch (_: Exception) { }
    }
}
