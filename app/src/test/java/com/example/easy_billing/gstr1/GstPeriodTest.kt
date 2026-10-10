package com.example.easy_billing.gstr1

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [GstPeriod] — the naming shared by the GST screen and the reminder worker. */
class GstPeriodTest {

    @Test fun fy_switchesInApril() {
        assertEquals("2025-26", GstPeriod.fy(2026, 2))   // March 2026
        assertEquals("2026-27", GstPeriod.fy(2026, 3))   // April 2026
        assertEquals("2026-27", GstPeriod.fy(2027, 0))   // January 2027
    }

    @Test fun periodName_monthlyAndQuarterly() {
        assertEquals("April", GstPeriod.periodName(3, false))
        assertEquals("March", GstPeriod.periodName(2, false))
        assertEquals("Apr-Jun", GstPeriod.periodName(5, true))
        assertEquals("Jan-Mar", GstPeriod.periodName(0, true))
    }

    @Test fun dueFor_monthly_isPreviousMonth() {
        // GSTR-1 due in October 2026 is for September 2026 (FY 2026-27)
        assertEquals("2026-27" to "September", GstPeriod.dueFor(2026, 9, false))
        // January 2027 is due for December 2026
        assertEquals("2026-27" to "December", GstPeriod.dueFor(2027, 0, false))
        // April 2026 is due for March 2026 (previous FY)
        assertEquals("2025-26" to "March", GstPeriod.dueFor(2026, 3, false))
    }

    @Test fun dueFor_quarterly_isPreviousQuarter() {
        assertEquals("2026-27" to "Jul-Sep", GstPeriod.dueFor(2026, 9, true))   // October 2026
        assertEquals("2025-26" to "Jan-Mar", GstPeriod.dueFor(2026, 3, true))   // April 2026
        assertEquals("2026-27" to "Oct-Dec", GstPeriod.dueFor(2027, 0, true))   // January 2027
    }

    @Test fun filedKey_isStable() {
        assertEquals("filed_2026-27_April", GstPeriod.filedKey("2026-27", "April"))
    }
}
