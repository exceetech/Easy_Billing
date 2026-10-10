package com.example.easy_billing.gstr1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the GSTR-1 JSON layout built by [Gstr1JsonExporter.build] / [Gstr1JsonExporter.fp]. */
class Gstr1JsonExporterTest {

    private val shopGstin = "29ABCDE1234F1Z5"   // Karnataka (state code 29)

    private fun report(period: String = "April", fy: String = "2026-27", b2b: List<B2BRow> = emptyList()) = Gstr1Report(
        gstin = shopGstin, financialYear = fy, period = period,
        returnType = if (period.contains("-")) "Quarterly" else "Monthly", generatedAt = 0L, b2b = b2b
    )

    private fun row(pos: String) = B2BRow(
        gstin = "27ABCDE1234F1Z7", receiverName = "Cust", invoiceNumber = "INV-1", invoiceDate = "05-Apr-26",
        invoiceValue = 1180.0, placeOfSupply = pos, reverseCharge = "N", applicableRate = "",
        invoiceType = "Regular", ecomGstin = "", rate = 18.0, taxableValue = 1000.0, cessAmount = 0.0
    )

    @Suppress("UNCHECKED_CAST")
    private fun firstItemDetail(json: Map<String, Any>): Map<String, Any> {
        val b2b = json["b2b"] as List<Map<String, Any>>
        val inv = (b2b[0]["inv"] as List<Map<String, Any>>)[0]
        val itm = (inv["itms"] as List<Map<String, Any>>)[0]
        return itm["itm_det"] as Map<String, Any>
    }

    @Test fun fp_monthly() {
        assertEquals("042026", Gstr1JsonExporter.fp(report("April")))
        assertEquals("032027", Gstr1JsonExporter.fp(report("March")))
        assertEquals("122026", Gstr1JsonExporter.fp(report("December")))
    }

    @Test fun fp_quarterly_usesLastMonthOfQuarter() {
        assertEquals("062026", Gstr1JsonExporter.fp(report("Apr-Jun")))
        assertEquals("092026", Gstr1JsonExporter.fp(report("Jul-Sep")))
        assertEquals("032027", Gstr1JsonExporter.fp(report("Jan-Mar")))
    }

    @Test fun sameState_splitsIntoCgstAndSgst() {
        val d = firstItemDetail(Gstr1JsonExporter.build(report(b2b = listOf(row("29-Karnataka")))))
        assertEquals(90.0, d["camt"] as Double, 0.001)
        assertEquals(90.0, d["samt"] as Double, 0.001)
        assertFalse(d.containsKey("iamt"))
    }

    @Test fun otherState_usesIgst() {
        val d = firstItemDetail(Gstr1JsonExporter.build(report(b2b = listOf(row("27-Maharashtra")))))
        assertEquals(180.0, d["iamt"] as Double, 0.001)
        assertFalse(d.containsKey("camt"))
    }

    @Test fun headerAndDates() {
        val json = Gstr1JsonExporter.build(report(b2b = listOf(row("29-Karnataka"))))
        assertEquals(shopGstin, json["gstin"])
        assertEquals("042026", json["fp"])
        @Suppress("UNCHECKED_CAST")
        val inv = ((json["b2b"] as List<Map<String, Any>>)[0]["inv"] as List<Map<String, Any>>)[0]
        assertEquals("05-04-2026", inv["idt"])
        assertEquals("29", inv["pos"])
    }

    @Test fun emptySectionsAreOmitted() {
        val json = Gstr1JsonExporter.build(report())
        assertFalse(json.containsKey("b2b"))
        assertTrue(json.containsKey("gstin"))
    }
}
