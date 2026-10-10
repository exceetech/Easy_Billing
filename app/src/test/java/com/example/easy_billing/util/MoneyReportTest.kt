package com.example.easy_billing.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyReportTest {

    private fun t(mode: String, dir: String, v: Double) = ModeDirTotal(mode, dir, v)

    @Test fun cash_is_pocket_everything_else_is_bank() {
        val x = MoneyReport.totals(listOf(
            t("CASH", "IN", 300.0), t("UPI", "IN", 200.0), t("CARD", "IN", 50.0),
            t("CASH", "OUT", 100.0), t("BANK", "OUT", 80.0)
        ))
        assertEquals(300.0, x.inCash, 0.0)
        assertEquals(250.0, x.inBank, 0.0)
        assertEquals(100.0, x.outCash, 0.0)
        assertEquals(80.0, x.outBank, 0.0)
        assertEquals(200.0, x.cashNet, 0.0)
        assertEquals(170.0, x.bankNet, 0.0)
        assertEquals(370.0, x.net, 0.0)
        assertEquals(550.0, x.inTotal, 0.0)
        assertEquals(180.0, x.outTotal, 0.0)
    }

    @Test fun closing_is_opening_plus_period() {
        val opening = MoneyReport.totals(listOf(t("CASH", "IN", 1000.0), t("BANK", "IN", 5000.0), t("CASH", "OUT", 200.0)))
        val within = MoneyReport.totals(listOf(t("CASH", "IN", 500.0), t("UPI", "OUT", 150.5)))
        val closing = MoneyReport.closing(opening, within)
        assertEquals(1300.0, closing.cashNet, 0.0)
        assertEquals(4849.5, closing.bankNet, 0.0)
    }

    @Test fun balance_can_go_negative_without_opening() {
        val x = MoneyReport.totals(listOf(t("CASH", "OUT", 40.0)))
        assertEquals(-40.0, x.cashNet, 0.0)
    }

    @Test fun empty_is_zero() {
        val x = MoneyReport.totals(emptyList())
        assertEquals(0.0, x.net, 0.0)
        assertEquals(0.0, x.inTotal, 0.0)
    }

    @Test fun paise_rounding_does_not_drift() {
        val x = MoneyReport.totals(listOf(t("CASH", "IN", 0.1 + 0.2), t("CASH", "OUT", 0.3)))
        assertEquals(0.0, x.cashNet, 0.0)
    }
}
