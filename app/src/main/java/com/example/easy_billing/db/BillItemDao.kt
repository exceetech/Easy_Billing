package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao // 🔥 REQUIRED
interface BillItemDao {

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    suspend fun getItemsForBill(billId: Int): List<BillItem>

    @Query("UPDATE bill_items SET is_synced = 1 WHERE billId = :billId")
    suspend fun markItemsSynced(billId: Int)

    @Insert
    suspend fun insertAll(items: List<BillItem>)

    @Query("DELETE FROM bill_items")
    suspend fun deleteAll()

    @Query("""
SELECT 
    productId,
    productName,
    variant,
    SUM(quantity) as totalQty,
    SUM(subTotal) as revenue,
    SUM(costPriceUsed) as cost,
    SUM(profit) as profit
FROM bill_items
GROUP BY productId, variant
ORDER BY profit DESC
""")
    suspend fun getProductProfit(): List<ProductProfitRaw>

    @Query("""
SELECT 
    bi.productName AS productName,
    bi.variant AS variant,
    SUM(bi.quantity) AS totalQty,
    SUM(bi.subTotal) AS revenue,
    SUM(bi.costPriceUsed) AS cost,
    SUM(bi.profit) AS profit,
    b.date AS billDate
FROM bill_items bi
INNER JOIN bills b ON bi.billId = b.id
GROUP BY bi.productId, bi.variant, b.date
""")
    suspend fun getAllProductProfitRaw(): List<ProductProfitWithDate>

    @Query("SELECT * FROM bill_items")
    suspend fun getAllItems(): List<BillItem>
}