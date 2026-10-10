package com.example.easy_billing.gstr1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [GstSimpleMath] — the numbers shown on the owner-facing GST card. */
class GstSimpleMathTest {

    private fun b2b(taxable: Double, rate: Double) = B2BRow(
        gstin = "29ABCDE1234F1Z5", receiverName = "Cust", invoiceNumber = "INV-1", invoiceDate = "05-Apr-26",
        invoiceValue = taxable * (1 + rate / 100), placeOfSupply = "29-Karnataka", reverseCharge = "N",
        applicableRate = "", invoiceType = "Regular", ecomGstin = "", rate = rate,
        taxableValue = taxable, cessAmount = 0.0
    )

    private fun note(type: String, taxable: Double, rate: Double) = CdnrRow(
        gstin = "29ABCDE1234F1Z5", receiverName = "Cust", noteNumber = "CN-1", noteDate = "06-Apr-26",
        noteType = type, placeOfSupply = "29-Karnataka", reverseCharge = "N", noteSupplyType = "Regular",
        noteValue = taxable * (1 + rate / 100), applicableRate = "", rate = rate,
        taxableValue = taxable, cessAmount = 0.0
    )

    private fun report(b2b: List<B2BRow> = emptyList(), cdnr: List<CdnrRow> = emptyList()) = Gstr1Report(
        gstin = "29ABCDE1234F1Z5", financialYear = "2026-27", period = "April", returnType = "Monthly",
        generatedAt = 0L, b2b = b2b, cdnr = cdnr
    )

    @Test fun creditNotesReduceSalesAndTax() {
        val r = report(b2b = listOf(b2b(1000.0, 18.0)), cdnr = listOf(note("C", 200.0, 18.0)))
        assertEquals(800.0, GstSimpleMath.netTaxable(r), 0.001)
        assertEquals(144.0, GstSimpleMath.netTax(r), 0.001)
    }

    @Test fun debitNotesIncreaseSalesAndTax() {
        val r = report(b2b = listOf(b2b(1000.0, 18.0)), cdnr = listOf(note("D", 100.0, 18.0)))
        assertEquals(1100.0, GstSimpleMath.netTaxable(r), 0.001)
        assertEquals(198.0, GstSimpleMath.netTax(r), 0.001)
    }

    @Test fun isNil_onlyWhenNothingToReport() {
        assertTrue(GstSimpleMath.isNil(report()))
        assertFalse(GstSimpleMath.isNil(report(b2b = listOf(b2b(10.0, 5.0)))))
        assertFalse(GstSimpleMath.isNil(report(cdnr = listOf(note("C", 10.0, 5.0)))))
    }
}
