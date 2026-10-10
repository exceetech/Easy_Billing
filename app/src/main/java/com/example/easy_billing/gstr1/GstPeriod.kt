package com.example.easy_billing.gstr1

/**
 * Period naming shared by the GST screen and the reminder worker, so both agree on
 * which return period a "filed" mark belongs to.
 */
object GstPeriod {

    val fiscalMonthNames = listOf(
        "April", "May", "June", "July", "August", "September",
        "October", "November", "December", "January", "February", "March"
    )
    val quarterNames = listOf("Apr-Jun", "Jul-Sep", "Oct-Dec", "Jan-Mar")

    /** Financial year label ("2026-27") for a calendar month ([month0]: 0 = January). */
    fun fy(year: Int, month0: Int): String {
        val start = if (month0 >= 3) year else year - 1
        return "$start-${(start + 1).toString().takeLast(2)}"
    }

    /** "April" for monthly, "Apr-Jun" etc. for the quarter that contains [month0]. */
    fun periodName(month0: Int, quarterly: Boolean): String {
        val fi = (month0 + 9) % 12            // 0 = April
        return if (quarterly) quarterNames[fi / 3] else fiscalMonthNames[fi]
    }

    fun filedKey(fy: String, period: String) = "filed_${fy}_${period}"

    /**
     * The (financial year, period) whose return falls due in the calendar month
     * ([year], [month0]): the previous month, or the previous quarter for quarterly filers.
     */
    fun dueFor(year: Int, month0: Int, quarterly: Boolean): Pair<String, String> {
        val total = year * 12 + month0 - (if (quarterly) 3 else 1)
        val y = total / 12
        val m = total % 12
        return fy(y, m) to periodName(m, quarterly)
    }
}
