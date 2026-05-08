package com.example.easy_billing.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GstSalesInvoiceItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<GstSalesInvoiceItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: GstSalesInvoiceItem)

    @Query("SELECT * FROM gst_sales_items_table WHERE gst_invoice_id = :invoiceId")
    suspend fun getByInvoice(invoiceId: Int): List<GstSalesInvoiceItem>
}
