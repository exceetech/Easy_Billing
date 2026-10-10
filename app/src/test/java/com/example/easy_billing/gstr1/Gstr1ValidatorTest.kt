package com.example.easy_billing.gstr1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the HSN / product checks in [Gstr1Validator]. */
class Gstr1ValidatorTest {

    private fun hsn(code: String, desc: String = "Soap", uqc: String = "NOS", rate: Double = 18.0, taxable: Double = 100.0) =
        HsnRow(
            hsn = code, description = desc, uqc = uqc, totalQuantity = 1.0, totalValue = 118.0,
            taxableValue = taxable, igstAmount = 0.0, cgstAmount = 9.0, sgstAmount = 9.0, cessAmount = 0.0, rate = rate
        )

    private fun report(vararg rows: HsnRow) = Gstr1Report(
        gstin = "29ABCDE1234F1Z5", financialYear = "2026-27", period = "April", returnType = "Monthly",
        generatedAt = 0L, hsnB2C = rows.toList()
    )

    private fun keys(r: Gstr1Report) = Gstr1Validator.validate(r).issues.map { it.key }

    @Test fun goodProduct_hasNoIssues() {
        assertTrue(Gstr1Validator.validate(report(hsn("1234"))).issues.isEmpty())
    }

    @Test fun shortHsn_isAnError() {
        val result = Gstr1Validator.validate(report(hsn("12")))
        assertTrue(result.hasErrors)
        assertTrue("hsn_short" in result.issues.map { it.key })
    }

    @Test fun nonNumericHsn_isAnError() {
        val result = Gstr1Validator.validate(report(hsn("12AB")))
        assertTrue(result.hasErrors)
        assertTrue("hsn_digits" in result.issues.map { it.key })
    }

    @Test fun missingDescriptionAndUnit_areWarnings() {
        val result = Gstr1Validator.validate(report(hsn("1234", desc = "", uqc = "")))
        assertFalse(result.hasErrors)
        assertEquals(setOf("hsn_desc_missing", "hsn_uqc_missing"), result.issues.map { it.key }.toSet())
    }

    @Test fun zeroRateSale_warns() {
        val result = Gstr1Validator.validate(report(hsn("1234", rate = 0.0)))
        assertFalse(result.hasErrors)
        assertTrue("hsn_zero_rate" in keys(report(hsn("1234", rate = 0.0))))
    }

    @Test fun badShopGstin_isAnError() {
        val r = Gstr1Report(gstin = "BAD", financialYear = "2026-27", period = "April", returnType = "Monthly", generatedAt = 0L)
        assertTrue("gstin_invalid" in keys(r))
    }
}
