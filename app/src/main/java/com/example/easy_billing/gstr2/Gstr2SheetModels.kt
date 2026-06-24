package com.example.easy_billing.gstr2

import com.example.easy_billing.util.appNow

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  GSTR-2 Sheet-level Data Models
//  Each data class maps 1-to-1 with the GST Offline Tool CSV columns for GSTR-2.
// ─────────────────────────────────────────────────────────────────────────────

data class Gstr2B2bRow(
    @SerializedName("supplierGstin")   val supplierGstin: String,
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("invoiceValue")    val invoiceValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("reverseCharge")   val reverseCharge: String,
    @SerializedName("invoiceType")     val invoiceType: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cgstPaid")        val cgstPaid: Double,
    @SerializedName("sgstPaid")        val sgstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCgst")  val availedItcCgst: Double,
    @SerializedName("availedItcSgst")  val availedItcSgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2B2burRow(
    @SerializedName("supplierName")    val supplierName: String,
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("invoiceValue")    val invoiceValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("supplyType")      val supplyType: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cgstPaid")        val cgstPaid: Double,
    @SerializedName("sgstPaid")        val sgstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCgst")  val availedItcCgst: Double,
    @SerializedName("availedItcSgst")  val availedItcSgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2ImpsRow(
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("invoiceValue")    val invoiceValue: Double,
    @SerializedName("placeOfSupply")   val placeOfSupply: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2ImpgRow(
    @SerializedName("portCode")        val portCode: String,
    @SerializedName("billOfEntryNumber") val billOfEntryNumber: String,
    @SerializedName("billOfEntryDate") val billOfEntryDate: String,
    @SerializedName("billOfEntryValue") val billOfEntryValue: Double,
    @SerializedName("documentType")    val documentType: String,
    @SerializedName("sezSupplierGstin") val sezSupplierGstin: String,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2CdnrRow(
    @SerializedName("supplierGstin")   val supplierGstin: String,
    @SerializedName("noteNumber")      val noteNumber: String,
    @SerializedName("noteDate")        val noteDate: String,
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("preGst")          val preGst: String,
    @SerializedName("documentType")    val documentType: String,
    @SerializedName("reason")          val reason: String,
    @SerializedName("supplyType")      val supplyType: String,
    @SerializedName("noteValue")       val noteValue: Double,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cgstPaid")        val cgstPaid: Double,
    @SerializedName("sgstPaid")        val sgstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCgst")  val availedItcCgst: Double,
    @SerializedName("availedItcSgst")  val availedItcSgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2CdnurRow(
    @SerializedName("noteNumber")      val noteNumber: String,
    @SerializedName("noteDate")        val noteDate: String,
    @SerializedName("invoiceNumber")   val invoiceNumber: String,
    @SerializedName("invoiceDate")     val invoiceDate: String,
    @SerializedName("preGst")          val preGst: String,
    @SerializedName("documentType")    val documentType: String,
    @SerializedName("reason")          val reason: String,
    @SerializedName("supplyType")      val supplyType: String,
    @SerializedName("invoiceType")     val invoiceType: String,
    @SerializedName("noteValue")       val noteValue: Double,
    @SerializedName("rate")            val rate: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstPaid")        val igstPaid: Double,
    @SerializedName("cgstPaid")        val cgstPaid: Double,
    @SerializedName("sgstPaid")        val sgstPaid: Double,
    @SerializedName("cessPaid")        val cessPaid: Double,
    @SerializedName("eligibilityForItc") val eligibilityForItc: String,
    @SerializedName("availedItcIgst")  val availedItcIgst: Double,
    @SerializedName("availedItcCgst")  val availedItcCgst: Double,
    @SerializedName("availedItcSgst")  val availedItcSgst: Double,
    @SerializedName("availedItcCess")  val availedItcCess: Double
)

data class Gstr2ExempRow(
    @SerializedName("description")     val description: String,
    @SerializedName("composition")     val composition: Double,
    @SerializedName("nilRated")        val nilRated: Double,
    @SerializedName("exempted")        val exempted: Double,
    @SerializedName("nonGst")          val nonGst: Double
)

data class Gstr2HsnsumRow(
    @SerializedName("hsn")             val hsn: String,
    @SerializedName("description")     val description: String,
    @SerializedName("uqc")             val uqc: String,
    @SerializedName("totalQuantity")   val totalQuantity: Double,
    @SerializedName("totalValue")      val totalValue: Double,
    @SerializedName("taxableValue")    val taxableValue: Double,
    @SerializedName("igstAmount")      val igstAmount: Double,
    @SerializedName("cgstAmount")      val cgstAmount: Double,
    @SerializedName("sgstAmount")      val sgstAmount: Double,
    @SerializedName("cessAmount")      val cessAmount: Double
)


/** Complete GSTR-2 report for one filing period. */
data class Gstr2Report(
    val gstin: String,
    val financialYear: String,
    val period: String,
    val returnType: String,
    val generatedAt: Long = appNow(),

    val b2b:      List<Gstr2B2bRow>      = emptyList(),
    val b2bur:    List<Gstr2B2burRow>    = emptyList(),
    val imps:     List<Gstr2ImpsRow>     = emptyList(),
    val impg:     List<Gstr2ImpgRow>     = emptyList(),
    val cdnr:     List<Gstr2CdnrRow>     = emptyList(),
    val cdnur:    List<Gstr2CdnurRow>    = emptyList(),
    val exemp:    List<Gstr2ExempRow>    = emptyList(),
    val hsnsum:   List<Gstr2HsnsumRow>   = emptyList()
) {
    fun toJson(): String = Gson().toJson(this)

    val totalTaxable: Double get() =
        b2b.sumOf { it.taxableValue } +
        b2bur.sumOf { it.taxableValue } +
        imps.sumOf { it.taxableValue } +
        impg.sumOf { it.taxableValue } +
        cdnr.sumOf { it.taxableValue } +
        cdnur.sumOf { it.taxableValue }

    val totalInvoiceCount: Int get() =
        b2b.map { it.invoiceNumber }.distinct().size +
        b2bur.map { it.invoiceNumber }.distinct().size +
        imps.size + impg.size

    val totalCreditNotes: Int get() = cdnr.size + cdnur.size

    companion object {
        fun fromJson(json: String): Gstr2Report =
            Gson().fromJson(json, Gstr2Report::class.java)
    }
}
