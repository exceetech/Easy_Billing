package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SupplierDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(supplier: Supplier): Long

    @Update
    suspend fun update(supplier: Supplier)

    /** Most-recently-used first — a shop buys from the same few suppliers. */
    @Query("""
        SELECT * FROM supplier_table
        WHERE isActive = 1 AND shopId = :shopId
        ORDER BY lastUsedAt DESC
    """)
    suspend fun getAll(shopId: Int): List<Supplier>

    @Query("""
        SELECT * FROM supplier_table
        WHERE isActive = 1 AND shopId = :shopId
          AND (name LIKE :query OR gstin LIKE :query OR state LIKE :query)
        ORDER BY lastUsedAt DESC
    """)
    suspend fun search(query: String, shopId: Int): List<Supplier>

    /** The identity lookup. */
    @Query("""
        SELECT * FROM supplier_table
        WHERE gstin = :gstin AND shopId = :shopId AND isActive = 1
        LIMIT 1
    """)
    suspend fun getByGstin(gstin: String, shopId: Int): Supplier?

    /**
     * Name lookup. Returns a **list** on purpose: two suppliers can share a
     * name (different branches / GSTINs), and the caller must not autofill
     * unless exactly one row comes back.
     */
    @Query("""
        SELECT * FROM supplier_table
        WHERE nameKey = :nameKey AND shopId = :shopId AND isActive = 1
        ORDER BY lastUsedAt DESC
    """)
    suspend fun getByName(nameKey: String, shopId: Int): List<Supplier>

    @Query("""
        SELECT * FROM supplier_table
        WHERE id = :id AND shopId = :shopId AND isActive = 1
        LIMIT 1
    """)
    suspend fun getById(id: Int, shopId: Int): Supplier?

    @Query("UPDATE supplier_table SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: Int, at: Long)

    /**
     * Everything waiting to be pushed.
     *
     * Keyed on `isSynced` rather than `serverId IS NULL` (the rule the
     * customer master uses) because a supplier is *edited* as well as
     * created — a renamed or re-stated supplier already has a serverId
     * but still needs to go up. `SupplierRepository.remember` clears the
     * flag on every write.
     */
    @Query("SELECT * FROM supplier_table WHERE isSynced = 0 AND isActive = 1")
    suspend fun getUnsynced(): List<Supplier>

    @Query("UPDATE supplier_table SET isSynced = 1, serverId = :serverId WHERE id = :id")
    suspend fun markSynced(id: Int, serverId: Int?)

    @Query("""
        SELECT * FROM supplier_table
        WHERE serverId = :serverId AND shopId = :shopId
        LIMIT 1
    """)
    suspend fun getByServerId(serverId: Int, shopId: Int): Supplier?

    /**
     * Like [getByGstin] but ignores `isActive`, so a pull re-adopts a row
     * that was deactivated locally instead of inserting a duplicate that
     * would collide with the unique (shopId, gstin) index.
     */
    @Query("""
        SELECT * FROM supplier_table
        WHERE gstin = :gstin AND shopId = :shopId
        LIMIT 1
    """)
    suspend fun getByGstinAny(gstin: String, shopId: Int): Supplier?

    @Query("""
        SELECT * FROM supplier_table
        WHERE nameKey = :nameKey AND gstin IS NULL AND shopId = :shopId
    """)
    suspend fun getUnregisteredByName(nameKey: String, shopId: Int): List<Supplier>

    @Query("UPDATE supplier_table SET isActive = 0 WHERE id = :id AND shopId = :shopId")
    suspend fun deactivate(id: Int, shopId: Int)
}
