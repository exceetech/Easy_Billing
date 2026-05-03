package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductDao {

    @Insert
    suspend fun insert(product: Product): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: Product): Long

    @Update
    suspend fun update(product: Product): Int

    /* ------------ Reads ------------ */

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getAll(): List<Product>

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAllWithInactive(): List<Product>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Product?

    @Query("SELECT * FROM products WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Int): Product?

    /** All locally-created products that haven't been pushed yet. */
    @Query("SELECT * FROM products WHERE serverId IS NULL")
    suspend fun getUnsynced(): List<Product>

    @Query("UPDATE products SET serverId = :serverId WHERE id = :localId")
    suspend fun setServerId(localId: Int, serverId: Int)

    /* ------------ Shop scoping ------------ */

    /**
     * Active products for a single shop. Pass an empty string to
     * include rows that pre-date the v15 migration (shopId = '').
     */
    @Query(
        """
        SELECT * FROM products
         WHERE isActive = 1
           AND (shop_id = :shopId OR shop_id = '')
         ORDER BY name ASC
        """
    )
    suspend fun getAllForShop(shopId: String): List<Product>

    /** Restricted edit — for purchased products we only allow these. */
    @Query(
        """
        UPDATE products
           SET price            = :price,
               cgst_percentage  = :cgst,
               sgst_percentage  = :sgst,
               igst_percentage  = :igst,
               defaultGstRate   = :defaultGst,
               hsnCode          = :hsnCode
         WHERE id = :id
        """
    )
    suspend fun updateSalesFields(
        id: Int,
        price: Double,
        cgst: Double,
        sgst: Double,
        igst: Double,
        defaultGst: Double,
        hsnCode: String?
    )

    @Query(
        """
        SELECT * FROM products
         WHERE name = :name
           AND ((variant IS NULL AND :variant IS NULL) OR variant = :variant)
         LIMIT 1
        """
    )
    suspend fun getByNameAndVariant(name: String, variant: String?): Product?

    /* ------------ Soft delete ------------ */

    @Query("UPDATE products SET isActive = 0 WHERE id = :productId")
    suspend fun deactivate(productId: Int)

    @Query("UPDATE products SET isActive = 1 WHERE id = :productId")
    suspend fun activate(productId: Int)

    /* ------------ Auto-fill helpers ------------
     * These power the "type a product name or HSN, get its tax
     * rates back" flow on the AddProduct + Purchase screens. We
     * intentionally only look at the LOCAL shop_product table —
     * global products do not store tax. */

    @Query(
        """
        SELECT * FROM products
         WHERE LOWER(name) = LOWER(:name) AND isActive = 1
         ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun findByName(name: String): Product?

    @Query(
        """
        SELECT * FROM products
         WHERE hsnCode = :hsn AND isActive = 1
         ORDER BY id DESC LIMIT 1
        """
    )
    suspend fun findByHsn(hsn: String): Product?

    /** Distinct names for autocomplete dropdowns. */
    @Query("SELECT DISTINCT name FROM products WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getDistinctNames(): List<String>

    /** Distinct variants seen across the catalogue (for variant dropdown). */
    @Query(
        """
        SELECT DISTINCT variant FROM products
         WHERE variant IS NOT NULL AND variant != '' AND isActive = 1
         ORDER BY variant ASC
        """
    )
    suspend fun getDistinctVariants(): List<String>
}
