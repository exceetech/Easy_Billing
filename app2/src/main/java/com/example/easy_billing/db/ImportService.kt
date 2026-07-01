package com.example.easy_billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

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
