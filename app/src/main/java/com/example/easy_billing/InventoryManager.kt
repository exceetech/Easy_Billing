package com.example.easy_billing

import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Inventory
import com.example.easy_billing.db.InventoryLog
import com.example.easy_billing.db.InventoryTransaction

object InventoryManager {

    // ================= ADD STOCK =================
    suspend fun addStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        costPrice: Double
    ) {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0 && costPrice > 0) { "Invalid quantity or cost price" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val existing = inventoryDao.getInventory(productId)

        if (existing == null) {

            // 🔥 FIRST STOCK ENTRY
            inventoryDao.insert(
                Inventory(
                    productId = productId,
                    currentStock = quantity,
                    averageCost = costPrice,
                    isActive = true,
                    isSynced = false
                )
            )

        } else {

            val oldStock = existing.currentStock
            val oldAvg = existing.averageCost

            val newStock = oldStock + quantity

            val newAvg =
                ((oldStock * oldAvg) + (quantity * costPrice)) / newStock

            inventoryDao.update(
                existing.copy(
                    currentStock = newStock,
                    averageCost = newAvg,
                    isActive = true,
                    isSynced = false
                )
            )
        }

        // 🔥 LOG (ONLY PLACE WHERE LOG IS CREATED)
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = "ADD",
                quantity = quantity,
                price = costPrice,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "PURCHASE",
                quantity = quantity,
                costPrice = costPrice,
                totalCost = quantity * costPrice
            )
        )
    }

    // ================= REDUCE STOCK =================
    suspend fun reduceStock(
        db: AppDatabase,
        productId: Int,
        quantity: Double,
        type: String = "SALE" // SALE / LOSS / ADJUST
    ) {

        require(productId > 0) { "Invalid productId" }
        require(quantity > 0) { "Invalid quantity" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        if (inventory.currentStock < quantity) {
            throw Exception("Insufficient stock")
        }

        val newStock = inventory.currentStock - quantity
        val avgCost = inventory.averageCost

        inventoryDao.update(
            inventory.copy(
                currentStock = newStock,
                isSynced = false
            )
        )

        // 🔥 LOG (VERY IMPORTANT)
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = type, // SALE / LOSS / ADJUST
                quantity = quantity,
                price = avgCost,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = type,
                quantity = quantity,
                costPrice = avgCost,
                totalCost = quantity * avgCost
            )
        )
    }

    // ================= CLEAR STOCK =================
    suspend fun clearStock(
        db: AppDatabase,
        productId: Int,
        type: String = "ADJUST" // or LOSS
    ) {

        require(productId > 0) { "Invalid productId" }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        val oldStock = inventory.currentStock
        val avgCost = inventory.averageCost

        if (oldStock <= 0) return

        inventoryDao.update(
            inventory.copy(
                currentStock = 0.0,
                isSynced = false
            )
        )

        // 🔥 LOG
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = type,
                quantity = oldStock,
                price = avgCost,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "CLEAR",
                quantity = oldStock,
                costPrice = avgCost,
                totalCost = oldStock * avgCost
            )
        )
    }

    // ================= GET STOCK =================
    suspend fun getTotalStock(db: AppDatabase, productId: Int): Double {
        return db.inventoryDao().getTotalQuantity(productId) ?: 0.0
    }
}