package com.example.easy_billing.gstr2

import com.example.easy_billing.util.appNow

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted GSTR-2 draft.
 *
 * One row per FY + period + GSTIN combination. Overwriting the same
 * combination simply replaces the JSON blob.
 *
 * [reportJson] is the Gson-serialised [Gstr2Report].
 */
@Entity(tableName = "gstr2_drafts")
data class Gstr2DraftEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "gstin")
    val gstin: String,

    @ColumnInfo(name = "financial_year")
    val financialYear: String,          // "2025-26"

    @ColumnInfo(name = "period")
    val period: String,                  // "April" / "Apr-Jun"

    @ColumnInfo(name = "return_type")
    val returnType: String,             // "Monthly" / "Quarterly"

    @ColumnInfo(name = "report_json")
    val reportJson: String,

    @ColumnInfo(name = "generated_at")
    val generatedAt: Long = appNow(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = appNow()
)
