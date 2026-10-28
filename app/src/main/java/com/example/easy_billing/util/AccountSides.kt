package com.example.easy_billing.util

import com.example.easy_billing.db.AppDatabase

/** Customer / supplier side of every credit account of a shop, read from the ledger. Never throws. */
object AccountSides {
    suspend fun load(db: AppDatabase, shopId: Int): Map<Int, CreditSide.Side> =
        try {
            db.creditTransactionDao().accountTypes(shopId)
                .groupBy({ it.accountId }, { it.type })
                .mapValues { CreditSide.of(it.value) }
        } catch (e: Exception) {
            emptyMap()
        }
}
