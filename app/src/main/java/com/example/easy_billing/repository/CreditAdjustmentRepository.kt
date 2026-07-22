package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.CreditAccount
import com.example.easy_billing.db.CreditTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one place that decides how a bill adjustment touches a credit account.
 *
 * A credit note, a debit note and a bill cancellation can land on a bill that
 * was sold on credit. The guiding rule:
 *
 *   **A return first pays down whatever the customer still owes. Only money
 *   left over, after his dues reach zero, is a real choice — cash in his hand,
 *   or an advance we now owe him.**
 *
 * So the owner is asked *nothing* in the common case. Owes ₹900, returns ₹80 →
 * the ₹80 just comes off, balance ₹820, no dialog. The question appears only
 * when a return would overshoot his debt, because that leftover is the only
 * ambiguous money. A debit note (an extra charge) never asks — it always adds.
 *
 * Two invariants live here so no screen can get them wrong:
 *
 *  1. **Cap.** A return can never reduce Billed by more than the bill still has
 *     on credit, nor more than the return's own value. Debt-clearing is capped
 *     further by what he actually owes; the rest is the excess.
 *
 *  2. **Idempotency.** Each source document (this exact credit note / debit
 *     note / cancellation) posts at most one transaction, ever. A double-tap,
 *     a sync retry, or a re-opened screen cannot charge the account twice.
 */
object CreditAdjustmentRepository {

    /** Rounding floor — below this, an amount is treated as zero money. */
    private const val EPS = 0.005

    /**
     * Every bill/purchase event that can move a credit balance.
     *
     * [raises] is the only thing the shared decision logic cares about: does
     * this push the balance up (a new charge) or down (a return/cancel)?
     * [txnType] is what gets written to the ledger — the sales side has its own
     * types, the purchase side reuses the existing PURCHASE_CREDIT /
     * PURCHASE_RETURN so the tiles read them with no change. [docPrefix] makes
     * the per-document idempotency key unique across kinds.
     */
    enum class Kind(val txnType: String, val docPrefix: String, val raises: Boolean) {
        // ── Sales side ──
        SALE_RETURN("SALE_RETURN", "CN", false),     // credit note — lowers debt
        BILL_CANCEL("BILL_CANCEL", "CANCEL", false), // cancelled bill — lowers debt
        DEBIT_NOTE("DEBIT_NOTE", "DN", true),        // extra charge — raises debt

        // ── Purchase side (party flipped: "debt" is what you owe a supplier) ──
        PURCHASE_RETURN("PURCHASE_RETURN", "PRET", false),   // goods sent back — lowers payable
        PURCHASE_DEBIT_NOTE("PURCHASE_RETURN", "PDN", false), // debit note to supplier — lowers payable
        PURCHASE_CANCEL("PURCHASE_RETURN", "PCAN", false),   // cancelled purchase — lowers payable
        PURCHASE_CREDIT_NOTE("PURCHASE_CREDIT", "PCN", true) // supplier charges more — raises payable
    }

    /** What a transaction is tied to. */
    sealed class Source {
        data class Bill(val billId: Int) : Source()
        data class Purchase(val purchaseId: Int) : Source()

        /**
         * A return booked straight against a supplier account, not against one
         * purchase invoice (the clear-stock and batch-return flows). There is
         * no per-invoice figure to clamp to, so the only ceiling is the
         * account's own balance — you still can't silently push a supplier into
         * owing you; an overshoot becomes the cash-vs-advance question.
         */
        data class SupplierAccount(val accountId: Int) : Source()
    }

    /**
     * What the caller should do about one adjustment, worked out from the
     * numbers. The caller shows a dialog only for [Choice]; everything else is
     * either silent or nothing.
     */
    sealed class Plan {
        /** Cash bill, or a credit bill from before the link existed. Do nothing. */
        object NotLinked : Plan()

        /**
         * A debit note. It always raises the debt by [amount] — no question,
         * because an extra charge has nowhere ambiguous to go.
         */
        data class AddCharge(val amount: Double) : Plan()

        /**
         * A return that is fully absorbed by the customer's dues. Just reduce
         * the balance by [reduce]; no dialog, because there is no leftover.
         */
        data class Auto(val reduce: Double) : Plan()

        /**
         * A return that overshoots the dues. [reduce] clears the remaining debt
         * (may be zero if he was already square); [excess] is the leftover that
         * is either cash out or an advance. This is the only case that asks.
         */
        data class Choice(
            val account: CreditAccount,
            val reduce: Double,
            val excess: Double
        ) : Plan()
    }

    /** Outcome of committing an adjustment. */
    sealed class PostResult {
        /** Posted. [appliedAmount] is the signed magnitude written (always ≥0). */
        data class Ok(val appliedAmount: Double) : PostResult()

        /** This document already posted once — nothing done, nothing wrong. */
        object AlreadyPosted : PostResult()

        /** Nothing to record (e.g. pure cash with no debt to clear). Benign. */
        object Nothing : PostResult()

        /** No signed-in shop. Refused rather than filed against a fallback. */
        object NoShop : PostResult()

        /** The bill or its account could not be found. */
        object NotFound : PostResult()
    }

    private fun shopIdOrNull(context: Context): Int? =
        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getInt("SHOP_ID", -1)
            .takeIf { it > 0 }

    /** Stable per-document id, e.g. "CN:123", used as the idempotency key. */
    fun docId(kind: Kind, documentLocalId: Int): String =
        "${kind.docPrefix}:$documentLocalId"

    // ─────────────────────────────────────────────────────────────────────
    //  THE SHARED CORE — the only place the split arithmetic lives.
    //  Sales and purchase both funnel through decide() and commitSource(), so
    //  the proof (identity holds, never below zero, never double-post) covers
    //  both sides at once.
    // ─────────────────────────────────────────────────────────────────────

    /** Where a source's link and its account live, resolved once. */
    private data class Resolved(val accId: Int, val account: CreditAccount, val reference: String?)

    /** Loads the account a bill/purchase is charged to, or null if not on credit. */
    private suspend fun resolve(context: Context, source: Source, shop: Int): Resolved? {
        val db = AppDatabase.getDatabase(context)
        return when (source) {
            is Source.Bill -> {
                val bill = db.billDao().getBillById(source.billId)
                val accId = bill.creditAccountId
                if (bill.paymentMethod != "CREDIT" || accId == null) return null
                val account = db.creditAccountDao().getById(accId, shop) ?: return null
                Resolved(accId, account, bill.billNumber.trim().ifBlank { null })
            }
            is Source.Purchase -> {
                val p = db.purchaseDao().getById(source.purchaseId) ?: return null
                val accId = p.creditAccountId
                if (!p.isCredit || accId == null) return null
                val account = db.creditAccountDao().getById(accId, shop) ?: return null
                Resolved(accId, account, p.invoiceNumber.trim().ifBlank { null })
            }
            is Source.SupplierAccount -> {
                val account = db.creditAccountDao().getById(source.accountId, shop) ?: return null
                Resolved(source.accountId, account, "Return")
            }
        }
    }

    /**
     * How much of a source is still sitting as debt on its account — the signed
     * sum of that source's own transactions. Raising types add, lowering types
     * subtract; account-level rows (PAY, SETTLE) aren't tied to a source and are
     * ignored. Floored at zero.
     */
    private suspend fun remainingForSource(context: Context, source: Source, shop: Int): Double {
        val dao = AppDatabase.getDatabase(context).creditTransactionDao()
        // An account-level return has no single invoice to bound it — its only
        // ceiling is the account balance, applied in decide(). Signal that with
        // an effectively unbounded remaining so the cap falls to the value.
        if (source is Source.SupplierAccount) return Double.MAX_VALUE

        val txns = when (source) {
            is Source.Bill -> dao.getByBill(source.billId, shop)
            is Source.Purchase -> dao.getByPurchase(source.purchaseId, shop)
            is Source.SupplierAccount -> emptyList() // unreachable, handled above
        }
        var remaining = 0.0
        for (t in txns) {
            remaining += when (t.type) {
                "ADD", "PURCHASE_CREDIT", "DEBIT_NOTE" -> t.amount
                "SALE_RETURN", "BILL_CANCEL", "PURCHASE_RETURN" -> -t.amount
                else -> 0.0
            }
        }
        return if (remaining < 0) 0.0 else remaining
    }

    /**
     * The pure decision — no I/O. Given the account, how much of this source is
     * still on credit, what is owed right now, the document's value and whether
     * it raises or lowers, return the [Plan]. This is the arithmetic that was
     * proven against the scenario simulation.
     */
    private fun decide(
        account: CreditAccount,
        remaining: Double,
        due: Double,
        value: Double,
        raises: Boolean
    ): Plan {
        val v = if (value < 0) 0.0 else value

        // A charge only ever adds. No split, no dialog.
        if (raises) return if (v <= EPS) Plan.NotLinked else Plan.AddCharge(v)

        // A return can reduce by at most its own value and at most what this
        // source still has on credit.
        val cap = minOf(v, remaining)
        if (cap <= EPS) return Plan.Choice(account, reduce = 0.0, excess = v)

        // Debt-clearing part is capped by what is actually owed right now.
        val reduce = if (due <= EPS) 0.0 else minOf(cap, due)
        val excess = cap - reduce
        return if (excess <= EPS) Plan.Auto(reduce) else Plan.Choice(account, reduce, excess)
    }

    /** Generalized planner. Pure read. Used directly by the shared prompt. */
    suspend fun planForSource(
        context: Context, source: Source, kind: Kind, amount: Double
    ): Plan = withContext(Dispatchers.IO) {
        val shop = shopIdOrNull(context) ?: return@withContext Plan.NotLinked
        val r = resolve(context, source, shop) ?: return@withContext Plan.NotLinked
        val remaining = remainingForSource(context, source, shop)
        decide(r.account, remaining, r.account.dueAmount, amount, kind.raises)
    }

    /** Generalized committer. Writes one ledger row and moves the balance. */
    suspend fun commitSource(
        context: Context, source: Source, kind: Kind, magnitude: Double, documentLocalId: Int
    ): PostResult = withContext(Dispatchers.IO) {
        val shop = shopIdOrNull(context) ?: return@withContext PostResult.NoShop
        val txnDao = AppDatabase.getDatabase(context).creditTransactionDao()

        val doc = docId(kind, documentLocalId)
        if (txnDao.countForDoc(doc, shop) > 0) return@withContext PostResult.AlreadyPosted

        val amount = if (magnitude < 0) 0.0 else magnitude
        if (amount <= EPS) return@withContext PostResult.Nothing

        val r = resolve(context, source, shop) ?: return@withContext PostResult.NotFound

        AppDatabase.getDatabase(context).creditAccountDao()
            .addToDue(r.accId, if (kind.raises) amount else -amount, shop)
        txnDao.insert(
            CreditTransaction(
                accountId = r.accId,
                shopId = shop,
                amount = amount,
                type = kind.txnType,
                billId = (source as? Source.Bill)?.billId,
                purchaseId = (source as? Source.Purchase)?.purchaseId,
                sourceDoc = doc,
                referenceInvoice = r.reference
            )
        )
        SyncManager(context).syncCredit()
        PostResult.Ok(amount)
    }

    // ── Public API — thin wrappers so callers name their side explicitly ──

    /** Plan an adjustment for a sales bill. */
    suspend fun planFor(context: Context, billId: Int, kind: Kind, amount: Double): Plan =
        planForSource(context, Source.Bill(billId), kind, amount)

    /** Plan an adjustment for a purchase. */
    suspend fun planForPurchase(context: Context, purchaseId: Int, kind: Kind, amount: Double): Plan =
        planForSource(context, Source.Purchase(purchaseId), kind, amount)

    /** Reduce a bill's account balance (return / cancel). */
    suspend fun commitReduce(
        context: Context, billId: Int, kind: Kind, billedReduction: Double, documentLocalId: Int
    ): PostResult = commitSource(context, Source.Bill(billId), kind, billedReduction, documentLocalId)

    /** Raise a bill's account balance (debit note). */
    suspend fun commitAdd(
        context: Context, billId: Int, amount: Double, documentLocalId: Int
    ): PostResult = commitSource(context, Source.Bill(billId), Kind.DEBIT_NOTE, amount, documentLocalId)

    /** Reduce a purchase's supplier balance (return / debit note / cancel). */
    suspend fun commitReducePurchase(
        context: Context, purchaseId: Int, kind: Kind, reduction: Double, documentLocalId: Int
    ): PostResult = commitSource(context, Source.Purchase(purchaseId), kind, reduction, documentLocalId)

    /** Raise a purchase's supplier balance (supplier credit-charge note). */
    suspend fun commitAddPurchase(
        context: Context, purchaseId: Int, kind: Kind, amount: Double, documentLocalId: Int
    ): PostResult = commitSource(context, Source.Purchase(purchaseId), kind, amount, documentLocalId)
}
