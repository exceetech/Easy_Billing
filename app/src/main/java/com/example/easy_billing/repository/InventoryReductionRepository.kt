package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.db.ScrapEntry
import androidx.room.withTransaction

/**
 * Stock-out flows: purchase return, scrap, and "clear remaining
 * stock" (which is just one of the two routed by reason).
 *
 * Quantity for [clearRemainingStock] is intentionally derived from
 * the inventory row — the user does not type it. This matches the
 * spec which says "no manual input" for clear-stock.
 */
class InventoryReductionRepository private constructor(
    private val db: AppDatabase
) {

    enum class ClearReason { PURCHASE_RETURN, SCRAP }

    /**
     * Records a purchase return for an arbitrary quantity.
     */
    suspend fun recordPurchaseReturn(entry: PurchaseReturn): Int = db.withTransaction {
        val id = db.purchaseReturnDao().insert(entry).toInt()
        entry.productId?.let {
            InventoryManager.reduceStock(
                db = db, productId = it, quantity = entry.quantityReturned, type = "RETURN"
            )
        }
        id
    }

    /**
     * Records scrap for an arbitrary quantity.
     */
    suspend fun recordScrap(entry: ScrapEntry): Int = db.withTransaction {
        val id = db.scrapDao().insert(entry).toInt()
        entry.productId?.let {
            InventoryManager.reduceStock(
                db = db, productId = it, quantity = entry.quantity, type = "LOSS"
            )
        }
        id
    }

    /**
     * Clears the entire remaining stock for [productId], routing
     * the entry to either `purchase_return_table` or `scrap_table`
     * based on [reason]. The quantity is whatever's currently in
     * inventory — the caller does not pass one.
     */
    suspend fun clearRemainingStock(
        productId: Int,
        productName: String,
        hsnCode: String?,
        reason: ClearReason,
        purchaseTaxCgst: Double = 0.0,
        purchaseTaxSgst: Double = 0.0,
        purchaseTaxIgst: Double = 0.0,
        supplierGstin: String? = null,
        supplierName: String? = null
    ): ClearStockResult = db.withTransaction {

        val inventory = db.inventoryDao().getInventory(productId)
            ?: return@withTransaction ClearStockResult.NoStock

        val qty = inventory.currentStock
        if (qty <= 0) return@withTransaction ClearStockResult.NoStock

        val avgCost = inventory.averageCost
        val invoiceValue = qty * avgCost
        val taxableAmount = invoiceValue
        val cgstAmt = taxableAmount * purchaseTaxCgst / 100.0
        val sgstAmt = taxableAmount * purchaseTaxSgst / 100.0
        val igstAmt = taxableAmount * purchaseTaxIgst / 100.0

        when (reason) {
            ClearReason.PURCHASE_RETURN -> db.purchaseReturnDao().insert(
                PurchaseReturn(
                    productId        = productId,
                    productName      = productName,
                    hsnCode          = hsnCode,
                    quantityReturned = qty,
                    taxableAmount    = taxableAmount,
                    invoiceValue     = invoiceValue,
                    cgstPercentage   = purchaseTaxCgst,
                    sgstPercentage   = purchaseTaxSgst,
                    igstPercentage   = purchaseTaxIgst,
                    cgstAmount       = cgstAmt,
                    sgstAmount       = sgstAmt,
                    igstAmount       = igstAmt,
                    supplierGstin    = supplierGstin,
                    supplierName     = supplierName
                )
            )
            ClearReason.SCRAP -> db.scrapDao().insert(
                ScrapEntry(
                    productId      = productId,
                    productName    = productName,
                    hsnCode        = hsnCode,
                    quantity       = qty,
                    taxableAmount  = taxableAmount,
                    invoiceValue   = invoiceValue,
                    cgstPercentage = purchaseTaxCgst,
                    sgstPercentage = purchaseTaxSgst,
                    igstPercentage = purchaseTaxIgst,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    reason         = "Stock cleared"
                )
            )
        }

        // Inventory → 0 via the existing InventoryManager.clearStock
        // path so transaction logs stay consistent.
        InventoryManager.clearStock(
            db = db, productId = productId,
            type = if (reason == ClearReason.PURCHASE_RETURN) "RETURN" else "LOSS"
        )

        ClearStockResult.Cleared(qty, reason)
    }

    sealed class ClearStockResult {
        object NoStock : ClearStockResult()
        data class Cleared(val quantity: Double, val reason: ClearReason) : ClearStockResult()
    }

    companion object {
        @Volatile private var INSTANCE: InventoryReductionRepository? = null

        fun get(context: Context): InventoryReductionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InventoryReductionRepository(
                    AppDatabase.getDatabase(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
