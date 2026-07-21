package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * A GSTR-2 import-of-services record.
 *
 * **This table has no shopId, unlike every other entity here.** That is
 * safe only because the local database is wiped at both — and only —
 * points where the active shop can change:
 *
 *   * MainActivity, when a login returns a different shop_id than the one
 *     stored (clearAllTables before the new id is saved)
 *   * DataSecurityActivity's factory reset, which archives the shop
 *     server-side and clears locally, with a delete-the-file fallback
 *
 * So rows here always belong to the shop that is currently signed in.
 * If a third way to change shops is ever added — or either wipe is made
 * conditional — this table starts leaking one shop's invoices into
 * another's list, silently, because its queries have no shop filter:
 *
 *     SELECT * FROM import_services ORDER BY invoice_date DESC
 *
 * The fix at that point is a shopId column plus a filter on every query
 * in ImportServiceDao. The server side is already scoped: both routes
 * derive the shop from the bearer token.
 */
@Entity(tableName = "import_services")
data class ImportService(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "invoice_number")
    var invoiceNumber: String,
    
    @ColumnInfo(name = "invoice_date")
    var invoiceDate: Long, // Epoch timestamp in milliseconds
    
    @ColumnInfo(name = "invoice_value")
    var invoiceValue: Double,
    
    @ColumnInfo(name = "place_of_supply")
    var placeOfSupply: String,
    
    @ColumnInfo(name = "rate")
    var rate: Double,
    
    @ColumnInfo(name = "taxable_value")
    var taxableValue: Double,
    
    @ColumnInfo(name = "igst_paid")
    var igstPaid: Double,
    
    @ColumnInfo(name = "cess_paid")
    var cessPaid: Double,
    
    @ColumnInfo(name = "eligibility_for_itc")
    var eligibilityForItc: String = "Inputs",
    
    @ColumnInfo(name = "availed_itc_igst")
    var availedItcIgst: Double,
    
    @ColumnInfo(name = "availed_itc_cess")
    var availedItcCess: Double,
    
    @ColumnInfo(name = "sync_status")
    var syncStatus: String = "pending"
)
