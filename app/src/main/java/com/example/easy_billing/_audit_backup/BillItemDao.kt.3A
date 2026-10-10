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
    productName,
    variant,
    unit,
    SUM(quantity) as totalQty,
    SUM(subTotal) as revenue,
    SUM(costPriceUsed) as cost,
    SUM(profit) as profit,
    0.0 AS added,
    0.0 AS sold,
    0.0 AS remaining,
    0.0 AS lossQty,
    0.0 AS lossAmount
FROM bill_items
GROUP BY productName, variant, unit
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

    /**
     * One row per product with lifetime units sold, revenue and profit.
     * Single GROUP BY so the Dashboard can precompute sort keys cheaply.
     *
     * Audit Round 2 (2026-07-23): re-verification found this query had NO
     * join to `bills` and no cancelled-bill filter, despite an earlier audit
     * (Report 3, issue R-1) recording this as already fixed on 2026-07-22.
     * It was not — confirmed by reading the actual query text, which summed
     * every bill_items row unconditionally. The Dashboard's "Most Profitable
     * / Top Revenue" tile ranking (DashboardActivity.kt, sortByProfit/
     * sortByRevenue) was therefore still counting cancelled bills' items.
     * Now joins `bills` and excludes cancelled ones, matching the pattern
     * already used elsewhere. NOTE: the local `bills` table has no `active`
     * column (that's a backend-only field — see Bill.kt) — cancellation is
     * tracked locally purely via `is_cancelled`, so that's the only filter
     * needed/available here.
     */
    // Deep-dive fix, Issue 3: this used to sum bill_items only, so the
    // Dashboard's Best Selling / Top Revenue / Most Profitable sort never
    // reflected a Credit Note return (should reduce a product's standing)
    // or a Debit Note (extra billed quantity — should increase it). Now
    // unions in both, signed the same way the backend /profit report does
    // after the deep-dive Issue 1 fix: Credit Notes ("C") subtract,
    // Debit Notes ("D") add. Notes are matched to their original bill via
    // credit_notes.originalInvoiceId and excluded if that bill is
    // cancelled, matching the existing bill_items filter.
    @Query("""
        SELECT productId,
               COALESCE(SUM(qty), 0)     AS qty,
               COALESCE(SUM(revenue), 0) AS revenue,
               COALESCE(SUM(profit), 0)  AS profit
        FROM (
            SELECT bi.productId AS productId,
                   bi.quantity  AS qty,
                   bi.subTotal  AS revenue,
                   bi.profit    AS profit
            FROM bill_items bi
            INNER JOIN bills b ON bi.billId = b.id
            WHERE b.is_cancelled = 0

            UNION ALL

            SELECT cni.productId AS productId,
                   -cni.quantityReturned AS qty,
                   -cni.totalAmount AS revenue,
                   -(cni.totalAmount - cni.costPriceUsed) AS profit
            FROM credit_note_items cni
            INNER JOIN credit_notes cn ON cni.noteId = cn.id
            INNER JOIN bills bc ON cn.originalInvoiceId = bc.id
            WHERE cn.noteType = 'C' AND bc.is_cancelled = 0

            UNION ALL

            SELECT cni2.productId AS productId,
                   cni2.quantityReturned AS qty,
                   cni2.totalAmount AS revenue,
                   (cni2.totalAmount - cni2.costPriceUsed) AS profit
            FROM credit_note_items cni2
            INNER JOIN credit_notes cn2 ON cni2.noteId = cn2.id
            INNER JOIN bills bd ON cn2.originalInvoiceId = bd.id
            WHERE cn2.noteType = 'D' AND bd.is_cancelled = 0
        )
        GROUP BY productId
    """)
    suspend fun getSalesAggByProduct(): List<ProductSalesAgg>

    // ── Shop-scoped aggregates for AI insights (filtered + grouped in SQL) ──

    @Query("SELECT COUNT(*) FROM bill_items WHERE productId IN (:productIds)")
    suspend fun countItemsForProducts(productIds: List<Int>): Int

    @Query("""
        SELECT productName AS productName, COALESCE(SUM(profit), 0) AS total
        FROM bill_items WHERE productId IN (:productIds)
        GROUP BY productName ORDER BY total ASC LIMIT 1
    """)
    suspend fun worstProfitForProducts(productIds: List<Int>): NameAgg?
}

/** Lightweight result holder for grouped name → value aggregates. */
data class NameAgg(
    val productName: String,
    val total: Double
)