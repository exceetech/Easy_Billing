package com.example.easy_billing.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.easy_billing.util.appNow

/**
 * Stage 3C. When a price / GST-rate correction (PDN) is saved, the pens of
 * that bill that were already SOLD or SCRAPPED ("gone") had their cost
 * difference booked straight to profit as a gain / loss. This row remembers
 * how many pens that was ([goneQty]) and the per-pen gross difference
 * ([deltaGross], signed new − old), so a later customer return of those
 * pens can take the difference back exactly once (see [CostCorrectionClaim]).
 */
@Entity(
    tableName = "pdn_gone_pool",
    indices = [Index(value = ["purchase_invoice_id", "product_id"])]
)
data class PdnGonePool(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** purchase_return_table.id of the (first) note row of this correction. */
    @ColumnInfo(name = "note_id") val noteId: Int,
    @ColumnInfo(name = "purchase_invoice_id") val purchaseInvoiceId: Int,
    @ColumnInfo(name = "product_id") val productId: Int,
    @ColumnInfo(name = "gone_qty") val goneQty: Double,
    /** Signed gross change per pen (negative = price went down). */
    @ColumnInfo(name = "delta_gross") val deltaGross: Double,
    @ColumnInfo(name = "note_date") val noteDate: Long,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)

/**
 * A customer return (or cancelled sale) of pens that were sold BEFORE a
 * PDN. The pens come back at the corrected cost, so the earlier gain / loss
 * on them is reversed here. [amount] is a profit-VARIANCE value with the
 * usual sign: positive = loss, negative = gain.
 * Never more than the pool's [PdnGonePool.goneQty] in total.
 */
@Entity(
    tableName = "cost_correction_claim",
    indices = [
        Index(value = ["pool_id"]),
        Index(value = ["allocation_id"]),
        Index(value = ["claim_date"])
    ]
)
data class CostCorrectionClaim(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "allocation_id") val allocationId: Int,
    @ColumnInfo(name = "pool_id") val poolId: Int,
    @ColumnInfo(name = "note_id") val noteId: Int,
    val qty: Double,
    val amount: Double,
    @ColumnInfo(name = "claim_date") val claimDate: Long = appNow(),
    /** SALES_RETURN | BILL_CANCEL */
    @ColumnInfo(name = "source_type") val sourceType: String,
    /** bill_items.id the pens were sold on. */
    @ColumnInfo(name = "source_id") val sourceId: Int,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false
)

@Dao
interface CostCorrectionDao {

    @Insert suspend fun insertPool(pool: PdnGonePool): Long
    @Insert suspend fun insertClaim(claim: CostCorrectionClaim): Long

    @Query("SELECT * FROM pdn_gone_pool WHERE purchase_invoice_id = :invoiceId AND product_id = :productId ORDER BY note_date ASC, id ASC")
    suspend fun poolsFor(invoiceId: Int, productId: Int): List<PdnGonePool>

    @Query("SELECT COALESCE(SUM(qty), 0.0) FROM cost_correction_claim WHERE pool_id = :poolId")
    suspend fun claimedQty(poolId: Int): Double

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM cost_correction_claim WHERE claim_date >= :from AND claim_date < :to")
    suspend fun claimVarianceBetween(from: Long, to: Long): Double

    @Query("SELECT * FROM cost_correction_claim WHERE allocation_id = :allocationId")
    suspend fun claimsForAllocation(allocationId: Int): List<CostCorrectionClaim>

    /* ─── Stage 3D sync helpers ─── */

    @Query("SELECT * FROM pdn_gone_pool WHERE is_synced = 0")
    suspend fun unsyncedPools(): List<PdnGonePool>

    @Query("SELECT * FROM cost_correction_claim WHERE is_synced = 0")
    suspend fun unsyncedClaims(): List<CostCorrectionClaim>

    @Query("UPDATE pdn_gone_pool SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markPoolsSynced(ids: List<Int>)

    @Query("UPDATE cost_correction_claim SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markClaimsSynced(ids: List<Int>)

    @Query("SELECT * FROM pdn_gone_pool")
    suspend fun allPools(): List<PdnGonePool>

    @Query("SELECT * FROM cost_correction_claim")
    suspend fun allClaims(): List<CostCorrectionClaim>

    @Query("DELETE FROM cost_correction_claim")
    suspend fun deleteAllClaims()

    @Query("DELETE FROM pdn_gone_pool")
    suspend fun deleteAllPools()
}
