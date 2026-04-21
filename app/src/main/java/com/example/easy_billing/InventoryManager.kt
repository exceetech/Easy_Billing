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

        if (productId <= 0) {
            throw Exception("Invalid productId")
        }

        if (quantity <= 0 || costPrice <= 0) {
            throw Exception("Invalid quantity or cost price")
        }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val existing = inventoryDao.getInventory(productId)

        if (existing == null) {

            // 🔥 FIRST TIME STOCK
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
                    isSynced = false
                )
            )
        }

        // 🔥 ADD THIS BLOCK
        db.inventoryLogDao().insert(
            InventoryLog(
                productId = productId,
                type = "ADD",
                quantity = quantity,
                price = costPrice,
                date = System.currentTimeMillis()
            )
        )

        // 🔥 TRANSACTION LOG
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
        quantity: Double
    ) {

        if (productId <= 0) {
            throw Exception("Invalid productId")
        }

        if (quantity <= 0) {
            throw Exception("Invalid quantity")
        }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        if (inventory.currentStock < quantity) {
            throw Exception("Insufficient stock")
        }

        val newStock = inventory.currentStock - quantity

        inventoryDao.update(
            inventory.copy(
                currentStock = newStock,
                isSynced = false
            )
        )

        // 🔥 TRANSACTION LOG
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "SALE",
                quantity = quantity,
                costPrice = inventory.averageCost,
                totalCost = quantity * inventory.averageCost
            )
        )
    }

    // ================= CLEAR STOCK =================
    suspend fun clearStock(
        db: AppDatabase,
        productId: Int
    ) {

        if (productId <= 0) {
            throw Exception("Invalid productId")
        }

        val inventoryDao = db.inventoryDao()
        val transactionDao = db.inventoryTransactionDao()

        val inventory = inventoryDao.getInventory(productId)
            ?: throw Exception("No inventory found")

        val oldStock = inventory.currentStock

        inventoryDao.update(
            inventory.copy(
                currentStock = 0.0,
                isSynced = false
            )
        )

        // 🔥 TRANSACTION LOG
        transactionDao.insert(
            InventoryTransaction(
                productId = productId,
                type = "CLEAR",
                quantity = oldStock,
                costPrice = inventory.averageCost,
                totalCost = oldStock * inventory.averageCost
            )
        )
    }

    suspend fun getTotalStock(db: AppDatabase, productId: Int): Double {
        return db.inventoryDao().getTotalQuantity(productId) ?: 0.0
    }
}