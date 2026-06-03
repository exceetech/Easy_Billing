package com.example.easy_billing.gstr1

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted GSTR-1 draft.
 *
 * One row per FY + period + GSTIN combination. Overwriting the same
 * combination simply replaces the JSON blob — use [OnConflictStrategy.REPLACE].
 *
 * [reportJson] is the Gson-serialised [Gstr1Report]. Reading it back via
 * [Gstr1Report.fromJson] reconstructs the full report without any DB join.
 */
@Entity(tableName = "gstr1_drafts")
data class Gstr1DraftEntity(

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
    val generatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
