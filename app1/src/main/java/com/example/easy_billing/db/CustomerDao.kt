package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: Customer): Long

    /** Alias matching the spec naming. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(customer: Customer): Long

    @Update
    suspend fun update(customer: Customer)

    /**
     * Lookup by mobile number, ignoring type. With separate B2C/B2B rows
     * a phone may match more than one — prefers the most recently updated.
     */
    @Query("SELECT * FROM customers WHERE phone = :phone AND shop_id = :shopId AND isActive = 1 ORDER BY updated_at DESC LIMIT 1")
    suspend fun getByPhone(phone: String, shopId: Int): Customer?

    /** Primary lookup — by mobile number AND type (B2C / B2B) within the shop. */
    @Query("SELECT * FROM customers WHERE phone = :phone AND customer_type = :type AND shop_id = :shopId AND isActive = 1 LIMIT 1")
    suspend fun getByPhoneAndType(phone: String, type: String, shopId: Int): Customer?

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Customer?

    @Query("SELECT * FROM customers WHERE serverId = :serverId AND shop_id = :shopId LIMIT 1")
    suspend fun getByServerId(serverId: Int, shopId: Int): Customer?

    @Query("""
        SELECT * FROM customers
        WHERE shop_id = :shopId AND isActive = 1
        AND (phone LIKE :query OR name LIKE :query OR business_name LIKE :query)
        ORDER BY updated_at DESC
    """)
    suspend fun search(query: String, shopId: Int): List<Customer>

    @Query("SELECT * FROM customers WHERE shop_id = :shopId AND isActive = 1 ORDER BY updated_at DESC")
    suspend fun getAllForShop(shopId: Int): List<Customer>

    @Query("SELECT * FROM customers WHERE serverId IS NULL AND isActive = 1")
    suspend fun getUnsynced(): List<Customer>

    @Query("UPDATE customers SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Int, serverId: Int)

    /**
     * Insert-or-update by (shopId, phone) atomically. Returns the local
     * row id. Existing rows keep their id (and serverId unless [resetSync]
     * forces a re-push). Used by the invoice save flow.
     */
    @Transaction
    suspend fun upsertByPhone(customer: Customer, resetSync: Boolean = true): Int {
        val existing = getByPhone(customer.phone, customer.shopId)
        return if (existing == null) {
            val rowId = insert(customer)
            if (rowId == -1L) {
                // Lost a race — re-read.
                getByPhone(customer.phone, customer.shopId)?.id ?: 0
            } else rowId.toInt()
        } else {
            val merged = customer.copy(
                id = existing.id,
                serverId = if (resetSync) null else existing.serverId,
                createdAt = existing.createdAt
            )
            update(merged)
            existing.id
        }
    }
}
