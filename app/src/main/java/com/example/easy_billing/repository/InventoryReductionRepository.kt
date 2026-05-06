package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.PurchaseReturn
import com.example.easy_billing.db.ScrapEntry
import com.example.easy_billing.util.GstEngine
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
     * Auto-stamps shop_id + state from the local store_info /
     * gst_profile so the row is push-ready for the backend.
     */
    suspend fun recordPurchaseReturn(entry: PurchaseReturn): Int = db.withTransaction {
        val (shopId, state) = currentShopAndState()
        val toInsert = entry.copy(
            shopId = entry.shopId.ifBlank { shopId },
            state  = entry.state.ifBlank { state }
        )
        val id = db.purchaseReturnDao().insert(toInsert).toInt()
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
        val (shopId, state) = currentShopAndState()
        val toInsert = entry.copy(
            shopId = entry.shopId.ifBlank { shopId },
            state  = entry.state.ifBlank { state }
        )
        val id = db.scrapDao().insert(toInsert).toInt()
        entry.productId?.let {
            InventoryManager.reduceStock(
                db = db, productId = it, quantity = entry.quantity, type = "LOSS"
            )
        }
        id
    }

    /** Resolve shop_id (gstin) + state (gst_profile.stateCode → name). */
    private suspend fun currentShopAndState(): Pair<String, String> {
        val store = db.storeInfoDao().get()
        val gst   = db.gstProfileDao().get()
        val shopId = gst?.shopId?.takeIf { it.isNotBlank() }
            ?: store?.gstin.orEmpty()
        val stateCode = gst?.stateCode?.takeIf { it.isNotBlank() }
            ?: GstEngine.getStateCode(store?.gstin)
        val stateName = GstEngine.INDIA_STATES[stateCode] ?: stateCode
        return shopId to stateName
    }

    /**
     * Reduces stock for [productId] by a specific [quantity], routing
     * it to either `purchase_return_table` or `scrap_table`. The
     * taxable amount and taxes are derived from the current
     * inventory's average cost.
     */
    suspend fun reduceStockByReason(
        productId: Int,
        productName: String,
        hsnCode: String?,
        quantity: Double,
        reason: ClearReason,
        purchaseTaxCgst: Double = 0.0,
        purchaseTaxSgst: Double = 0.0,
        purchaseTaxIgst: Double = 0.0
    ): Boolean = db.withTransaction {

        val inventory = db.inventoryDao().getInventory(productId)
            ?: return@withTransaction false

        val available = inventory.currentStock
        if (available < quantity) return@withTransaction false

        val avgCost = inventory.averageCost
        val invoiceValue = quantity * avgCost

        val totalTaxAmount = when {
            purchaseTaxIgst > 0 -> {
                invoiceValue * purchaseTaxIgst / (100 + purchaseTaxIgst)
            }

            else -> {
                invoiceValue *
                        (purchaseTaxCgst + purchaseTaxSgst) /
                        (100 + purchaseTaxCgst + purchaseTaxSgst)
            }
        }

        val taxableAmount = invoiceValue - totalTaxAmount
        val cgstAmt = taxableAmount * purchaseTaxCgst / 100.0
        val sgstAmt = taxableAmount * purchaseTaxSgst / 100.0
        val igstAmt = taxableAmount * purchaseTaxIgst / 100.0

        when (reason) {
            ClearReason.PURCHASE_RETURN -> db.purchaseReturnDao().insert(
                PurchaseReturn(
                    productId        = productId,
                    productName      = productName,
                    hsnCode          = hsnCode,
                    quantityReturned = quantity,
                    taxableAmount    = taxableAmount,
                    invoiceValue     = invoiceValue,
                    cgstPercentage   = purchaseTaxCgst,
                    sgstPercentage   = purchaseTaxSgst,
                    igstPercentage   = purchaseTaxIgst,
                    cgstAmount       = cgstAmt,
                    sgstAmount       = sgstAmt,
                    igstAmount       = igstAmt
                )
            )
            ClearReason.SCRAP -> db.scrapDao().insert(
                ScrapEntry(
                    productId      = productId,
                    productName    = productName,
                    hsnCode        = hsnCode,
                    quantity       = quantity,
                    taxableAmount  = taxableAmount,
                    invoiceValue   = invoiceValue,
                    cgstPercentage = purchaseTaxCgst,
                    sgstPercentage = purchaseTaxSgst,
                    igstPercentage = purchaseTaxIgst,
                    cgstAmount     = cgstAmt,
                    sgstAmount     = sgstAmt,
                    igstAmount     = igstAmt,
                    reason         = "Manual reduction"
                )
            )
        }

        InventoryManager.reduceStock(
            db = db, productId = productId, quantity = quantity,
            type = if (reason == ClearReason.PURCHASE_RETURN) "RETURN" else "LOSS"
        )
        true
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

        val totalTaxAmount = when {
            purchaseTaxIgst > 0 -> {
                invoiceValue * purchaseTaxIgst / (100 + purchaseTaxIgst)
            }

            else -> {
                invoiceValue *
                        (purchaseTaxCgst + purchaseTaxSgst) /
                        (100 + purchaseTaxCgst + purchaseTaxSgst)
            }
        }

        val taxableAmount = invoiceValue - totalTaxAmount
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
