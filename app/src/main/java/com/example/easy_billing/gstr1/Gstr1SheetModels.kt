package com.example.easy_billing.gstr1

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  GSTR-1 Sheet-level Data Models
//  Each data class maps 1-to-1 with the GST Offline Tool CSV columns.
//  Gson is used for draft serialisation.
// ─────────────────────────────────────────────────────────────────────────────

/** B2B / SEZ / Deemed-Export invoice row (one per invoice per GST rate slab). */
data class B2BRow(
    @SerializedName("gstin")           val gstin: String,            // GSTIN/UIN of Recipient
    @SerializedName("receiverName")    val receiverName: String,     // Receiver Name
    @SerializedName("invoiceNumber")   val invoiceNumber: String,    // Invoice Number
    @SerializedName("invoiceDate")     val invoiceDate: String,      // Invoice date  dd-MMM-yy
    @SerializedName("invoiceValue")    val invoiceValue: Double,     // Invoice Value (grand total)
    @SerializedName("placeOfSupply")   val placeOfSupply: String,    // "NN-State Name"
    @SerializedName("reverseCharge")   val reverseCharge: String,    // Y / N
    @SerializedName("applicableRate")  val applicableRate: String,   // Applicable % of Tax Rate (blank usually)
    @SerializedName("invoiceType")     val invoiceType: String,      // Regular / SEZ / Deemed Exp
    @SerializedName("ecomGstin")       val ecomGstin: String,        // E-Commerce GSTIN
    @SerializedName("rate")            val rate: Double,             // GST Rate
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** B2CL row (large B2C invoice > ₹2.5L, inter-state, one row per invoice per rate). */
data class B2CLRow(
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("invoiceValue")    val invoiceValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("applicableRate")  val applicableRate: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double,
    @SerializedName("ecomGstin")       val ecomGstin: String
)

/** B2CS row (small B2C, aggregated per Place of Supply per rate). */
data class B2CSRow(
    @SerializedName("type")            val type: String,             // OE = Other / E = E-commerce
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("applicableRate")  val applicableRate: String,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double,
    @SerializedName("ecomGstin")       val ecomGstin: String
)

/** CDNR row — Credit/Debit note for registered recipient (B2B). */
data class CdnrRow(
    @SerializedName("gstin")           val gstin: String,
    @SerializedName("receiverName")    val receiverName: String,
    @SerializedName("noteNumber")      val noteNumber: String,
    @SerializedName("noteDate")        val noteDate: String,
    @SerializedName("noteType")        val noteType: String,         // C / D
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("reverseCharge")   val reverseCharge: String,
    @SerializedName("noteSupplyType")  val noteSupplyType: String,   // Regular / SEZ / Deemed Exp
    @SerializedName("noteValue")       val noteValue: Double,
    @SerializedName("applicableRate")  val applicableRate: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** CDNUR row — Credit/Debit note for unregistered recipient (B2C). */
data class CdnurRow(
    @SerializedName("urType")          val urType: String,           // B2CL / B2CS / EXPORT
    @SerializedName("noteNumber")      val noteNumber: String,
    @SerializedName("noteDate")        val noteDate: String,
    @SerializedName("noteType")        val noteType: String,         // C / D
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("noteValue")       val noteValue: Double,
    @SerializedName("applicableRate")  val applicableRate: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** HSN summary row (shared columns for both B2B and B2C HSN sheets). */
data class HsnRow(
    @SerializedName("hsn")             val hsn: String,
    @SerializedName("description")     val description: String,
    @SerializedName("uqc")             val uqc: String,
    @SerializedName("totalQuantity")   val totalQuantity: Double,
    @SerializedName("totalValue")      val totalValue: Double,       // invoice value
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstAmount")      val igstAmount: Double,
    @SerializedName("cgstAmount")      val cgstAmount: Double,
    @SerializedName("sgstAmount")      val sgstAmount: Double,
    @SerializedName("cessAmount")      val cessAmount: Double,
    @SerializedName("rate")            val rate: Double
)

/** DOCS (Document Issuance Summary) row. */
data class DocsRow(
    @SerializedName("natureOfDoc")     val natureOfDoc: String,      // "Invoices for outward supply" etc.
    @SerializedName("srFrom")          val srFrom: String,           // First invoice number in series
    @SerializedName("srTo")            val srTo: String,             // Last invoice number in series
    @SerializedName("totalNumber")     val totalNumber: Int,
    @SerializedName("cancelled")       val cancelled: Int
)

/** ECO aggregate (Table 14/15 — e-commerce operator summary). */
data class EcoRow(
    @SerializedName("natureOfSupply")  val natureOfSupply: String,
    @SerializedName("ecoGstin")        val ecoGstin: String,
    @SerializedName("ecoName")         val ecoName: String,
    @SerializedName("netValue")        val netValue: Double,
    @SerializedName("igst")            val igst: Double,
    @SerializedName("cgst")            val cgst: Double,
    @SerializedName("sgst")            val sgst: Double,
    @SerializedName("cess")            val cess: Double
)

/** ECO B2B supply row. */
data class EcoB2BRow(
    @SerializedName("supplierGstin")   val supplierGstin: String,
    @SerializedName("supplierName")    val supplierName: String,
    @SerializedName("recipientGstin")  val recipientGstin: String,
    @SerializedName("recipientName")   val recipientName: String,
    @SerializedName("docNumber")       val docNumber: String,
    @SerializedName("docDate")         val docDate: String,
    @SerializedName("supplyValue")     val supplyValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("docType")         val docType: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** ECO B2C supply row. */
data class EcoB2CRow(
    @SerializedName("supplierGstin")   val supplierGstin: String,
    @SerializedName("supplierName")    val supplierName: String,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** ECO URP (Unregistered Person) B2B row. */
data class EcoUrp2BRow(
    @SerializedName("recipientGstin")  val recipientGstin: String,
    @SerializedName("recipientName")   val recipientName: String,
    @SerializedName("docNumber")       val docNumber: String,
    @SerializedName("docDate")         val docDate: String,
    @SerializedName("supplyValue")     val supplyValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("docType")         val docType: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

/** ECO URP B2C row. */
data class EcoUrp2CRow(
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)

// ─────────────────────────────────────────────────────────────────────────────
//  Report container
// ─────────────────────────────────────────────────────────────────────────────

/** Complete GSTR-1 report for one filing period. */
data class Gstr1Report(
    val gstin: String,
    val financialYear: String,         // "2025-26"
    val period: String,                // "April" / "Apr-Jun" etc.
    val returnType: String,            // "Monthly" / "Quarterly"
    val generatedAt: Long = System.currentTimeMillis(),

    val b2b:      List<B2BRow>      = emptyList(),
    val b2cl:     List<B2CLRow>     = emptyList(),
    val b2cs:     List<B2CSRow>     = emptyList(),
    val cdnr:     List<CdnrRow>     = emptyList(),
    val cdnur:    List<CdnurRow>    = emptyList(),
    val hsnB2B:   List<HsnRow>      = emptyList(),
    val hsnB2C:   List<HsnRow>      = emptyList(),
    val docs:     List<DocsRow>     = emptyList(),
    val eco:      List<EcoRow>      = emptyList(),
    val ecoB2B:   List<EcoB2BRow>   = emptyList(),
    val ecoB2C:   List<EcoB2CRow>   = emptyList(),
    val ecoUrp2B: List<EcoUrp2BRow> = emptyList(),
    val ecoUrp2C: List<EcoUrp2CRow> = emptyList()
) {
    fun toJson(): String = Gson().toJson(this)

    /** Summary counts for the UI summary card. */
    val totalTaxable: Double get() =
        b2b.sumOf { it.taxableValue } +
        b2cl.sumOf { it.taxableValue } +
        b2cs.sumOf { it.taxableValue }

    val totalTax: Double get() = b2b.sumOf {
        val rate = it.rate / 100.0
        it.taxableValue * rate
    } + b2cl.sumOf {
        val rate = it.rate / 100.0
        it.taxableValue * rate
    } + b2cs.sumOf {
        val rate = it.rate / 100.0
        it.taxableValue * rate
    }

    val totalInvoiceCount: Int get() = b2b.map { it.invoiceNumber }.distinct().size +
        b2cl.map { it.invoiceNumber }.distinct().size +
        b2cs.size

    val totalCreditNotes: Int get() = cdnr.size + cdnur.size

    companion object {
        fun fromJson(json: String): Gstr1Report =
            Gson().fromJson(json, Gstr1Report::class.java)
    }
}
