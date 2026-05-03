package com.example.easy_billing.util

import com.example.easy_billing.db.Bill
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.GstSalesRecord
import com.example.easy_billing.db.StoreInfo
import java.util.UUID

object GstEngine {

    val INDIA_STATES = mapOf(
        "01" to "Jammu & Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab",
        "04" to "Chandigarh", "05" to "Uttarakhand", "06" to "Haryana",
        "07" to "Delhi", "08" to "Rajasthan", "09" to "Uttar Pradesh",
        "10" to "Bihar", "11" to "Sikkim", "12" to "Arunachal Pradesh",
        "13" to "Nagaland", "14" to "Manipur", "15" to "Mizoram", "16" to "Tripura",
        "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal",
        "20" to "Jharkhand", "21" to "Odisha", "22" to "Chhattisgarh",
        "23" to "Madhya Pradesh", "24" to "Gujarat", "25" to "Daman & Diu",
        "26" to "Dadra & Nagar Haveli", "27" to "Maharashtra", "28" to "Andhra Pradesh (Old)",
        "29" to "Karnataka", "30" to "Goa", "31" to "Lakshadweep", "32" to "Kerala",
        "33" to "Tamil Nadu", "34" to "Puducherry", "35" to "Andaman & Nicobar",
        "36" to "Telangana", "37" to "Andhra Pradesh"
    )

    fun getStateCode(gstin: String?): String {
        if (gstin.isNullOrBlank() || gstin.length < 2) return ""
        return gstin.substring(0, 2)
    }

    fun isIntrastate(sellerStateCode: String, buyerStateCode: String): Boolean {
        if (buyerStateCode.isBlank()) return true // default to intrastate for B2C if unknown
        return sellerStateCode == buyerStateCode
    }

    /**
     * Reverse lookup: state name → 2-digit code.
     *
     * Tolerant of casing and trailing whitespace. Returns null if
     * the input doesn't match any known state — caller should treat
     * "unknown" as "can't auto-detect intra-vs-inter" and prompt
     * the user.
     */
    fun getStateCodeFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val normalized = name.trim().lowercase()
        return INDIA_STATES.entries.firstOrNull { (_, n) ->
            n.lowercase() == normalized
        }?.key
    }

    /**
     * Convenience: same-state check using free-text state names on
     * either side. Returns true only if both names resolve to the
     * same 2-digit code; false otherwise (including when either is
     * unknown — caller can decide whether to default to intra).
     */
    fun isSameStateByName(stateA: String?, stateB: String?): Boolean {
        val a = getStateCodeFromName(stateA) ?: return false
        val b = getStateCodeFromName(stateB) ?: return false
        return a == b
    }

    data class GstBreakup(
        val taxableValue: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val totalTax: Double,
        val grandTotal: Double,
        val supplyType: String
    )

    fun calculateGstSplit(taxableValue: Double, gstRate: Double, supplyType: String): GstBreakup {
        if (gstRate <= 0 || taxableValue <= 0) {
            return GstBreakup(taxableValue, 0.0, 0.0, 0.0, 0.0, taxableValue, supplyType)
        }

        val totalTax = Math.round(taxableValue * gstRate / 100.0 * 100.0) / 100.0

        val (cgst, sgst, igst) = if (supplyType == "intrastate") {
            val half = Math.round(totalTax / 2.0 * 100.0) / 100.0
            val otherHalf = Math.round((totalTax - half) * 100.0) / 100.0
            Triple(half, otherHalf, 0.0)
        } else {
            Triple(0.0, 0.0, totalTax)
        }

        return GstBreakup(
            taxableValue = Math.round(taxableValue * 100.0) / 100.0,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            totalTax = totalTax,
            grandTotal = Math.round((taxableValue + totalTax) * 100.0) / 100.0,
            supplyType = supplyType
        )
    }

    fun buildSalesRecords(
        bill: Bill,
        items: List<BillItem>,
        storeInfo: StoreInfo,
        deviceId: String
    ): List<GstSalesRecord> {
        val records = mutableListOf<GstSalesRecord>()
        
        for (item in items) {
            records.add(
                GstSalesRecord(
                    id = UUID.randomUUID().toString(),
                    invoiceNumber = bill.billNumber,
                    invoiceDate = System.currentTimeMillis(), // We can parse from bill.date if needed
                    customerType = bill.customerType,
                    customerGstin = bill.customerGstin,
                    placeOfSupply = bill.placeOfSupply.ifBlank { storeInfo.stateCode },
                    supplyType = bill.supplyType,
                    hsnCode = item.hsnCode,
                    productName = item.productName,
                    quantity = item.quantity,
                    unit = item.unit,
                    taxableValue = item.taxableValue,
                    gstRate = item.gstRate,
                    cgstAmount = item.cgstAmount,
                    sgstAmount = item.sgstAmount,
                    igstAmount = item.igstAmount,
                    totalAmount = item.subTotal,
                    syncStatus = "pending",
                    deviceId = deviceId
                )
            )
        }
        return records
    }
}
