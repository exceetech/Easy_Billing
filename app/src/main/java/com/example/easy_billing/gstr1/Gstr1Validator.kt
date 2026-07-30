package com.example.easy_billing.gstr1

/**
 * Gstr1Validator
 *
 * Validates a [Gstr1Report] and returns a list of [ValidationIssue].
 * Each issue is either a WARNING (non-blocking) or an ERROR (blocks export).
 *
 * Called before CSV/Excel export. The UI shows issues per section with
 * a coloured badge.
 */
object Gstr1Validator {

    enum class Severity { ERROR, WARNING }

    data class ValidationIssue(
        val section: String,
        val severity: Severity,
        val message: String,
        val rowHint: String = ""   // e.g. invoice number or note number
    )

    data class ValidationResult(
        val issues: List<ValidationIssue>
    ) {
        val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }
        val hasWarnings: Boolean get() = issues.any { it.severity == Severity.WARNING }
        val errorCount: Int get() = issues.count { it.severity == Severity.ERROR }
        val warningCount: Int get() = issues.count { it.severity == Severity.WARNING }

        fun forSection(section: String) = issues.filter { it.section == section }
        fun isClean(section: String) = issues.none { it.section == section }
    }

    /**
     * Rates that shouldn't raise an "unusual rate" warning.
     *
     * Kept identical to VALID_GST_RATES in the server's gst_service.py — the
     * two sets had drifted (this one lacked 9 and 14, the server's lacked 1 and
     * 40), so the same report could be clean on one path and warned on the
     * other. This is the union: deliberately permissive, because the check is
     * advisory and a false warning on a legitimate rate is worse than staying
     * quiet. A genuinely odd rate (13%, 22%, …) is still caught.
     */
    private val VALID_GST_RATES = setOf(
        0.0, 0.1, 0.25, 1.0, 1.5, 3.0, 5.0, 6.0, 7.5, 9.0, 12.0, 14.0, 18.0, 28.0, 40.0
    )

    private val GSTIN_REGEX = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$")

    fun validate(report: Gstr1Report): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        if (report.gstin.isBlank()) {
            issues.add(ValidationIssue("General", Severity.ERROR, "Filer GSTIN is missing. Please configure GST Profile."))
        } else if (!GSTIN_REGEX.matches(report.gstin)) {
            issues.add(ValidationIssue("General", Severity.ERROR, "Filer GSTIN '${report.gstin}' is not in valid format."))
        }

        validateB2B(report.b2b, issues)
        validateB2CL(report.b2cl, b2clThresholdFor(report), issues)
        validateB2CS(report.b2cs, issues)
        validateCdnr(report.cdnr, issues)
        validateCdnur(report.cdnur, issues)
        validateHsn(report.hsnB2B, "HSN(B2B)", issues)
        validateHsn(report.hsnB2C, "HSN(B2C)", issues)
        validateDocs(report.docs, issues)

        return ValidationResult(issues)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun validateB2B(rows: List<B2BRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (!GSTIN_REGEX.matches(row.gstin)) {
                out.add(ValidationIssue("B2B", Severity.ERROR,
                    "Invalid recipient GSTIN '${row.gstin}'", row.invoiceNumber))
            }
            if (row.invoiceNumber.isBlank()) {
                out.add(ValidationIssue("B2B", Severity.ERROR, "Invoice number is blank.", ""))
            }
            if (row.invoiceDate.isBlank()) {
                out.add(ValidationIssue("B2B", Severity.ERROR,
                    "Invoice date missing for ${row.invoiceNumber}", row.invoiceNumber))
            }
            if (row.invoiceValue <= 0.0) {
                out.add(ValidationIssue("B2B", Severity.WARNING,
                    "Invoice value is zero or negative.", row.invoiceNumber))
            }
            if (row.placeOfSupply.isBlank()) {
                out.add(ValidationIssue("B2B", Severity.ERROR,
                    "Place of Supply missing.", row.invoiceNumber))
            }
            if (row.rate !in VALID_GST_RATES) {
                out.add(ValidationIssue("B2B", Severity.WARNING,
                    "Unusual GST rate ${row.rate}% for invoice ${row.invoiceNumber}.", row.invoiceNumber))
            }
            if (row.taxableValue < 0.0) {
                out.add(ValidationIssue("B2B", Severity.ERROR,
                    "Negative taxable value.", row.invoiceNumber))
            }
        }
    }

    /**
     * B2CL threshold that applied to the period being validated.
     *
     * Notification 12/2024-Central Tax cut it from Rs 2.5 lakh to Rs 1 lakh
     * with effect from 1 Aug 2024, and the cutover falls MID-way through
     * FY 2024-25 — so this is resolved per period, not per financial year.
     * This check used to hardcode Rs 2.5 lakh, which wrongly warned that every
     * correctly-filed B2CL invoice between Rs 1 lakh and Rs 2.5 lakh "should
     * be B2CS". Mirrors b2cl_threshold_for() in the server's gst_routes.py.
     */
    private fun b2clThresholdFor(report: Gstr1Report): Double {
        val fyStart = report.financialYear.substringBefore("-").toIntOrNull() ?: return 100_000.0
        val monthOfPeriod = when (report.period) {
            "April" -> 4; "May" -> 5; "June" -> 6; "July" -> 7
            "August" -> 8; "September" -> 9; "October" -> 10
            "November" -> 11; "December" -> 12
            "January" -> 1; "February" -> 2; "March" -> 3
            "Apr-Jun" -> 4; "Jul-Sep" -> 7; "Oct-Dec" -> 10; "Jan-Mar" -> 1
            else -> return 100_000.0
        }
        // Jan/Feb/Mar of a financial year fall in the NEXT calendar year.
        val year = if (monthOfPeriod <= 3) fyStart + 1 else fyStart
        val onOrAfterCutover = year > 2024 || (year == 2024 && monthOfPeriod >= 8)
        return if (onOrAfterCutover) 100_000.0 else 250_000.0
    }

    private fun validateB2CL(
        rows: List<B2CLRow>,
        threshold: Double,
        out: MutableList<ValidationIssue>
    ) {
        val limitLabel = if (threshold >= 250_000.0) "₹2.5L" else "₹1L"
        for (row in rows) {
            if (row.invoiceNumber.isBlank()) {
                out.add(ValidationIssue("B2CL", Severity.ERROR, "Invoice number is blank.", ""))
            }
            if (row.invoiceValue <= threshold) {
                out.add(ValidationIssue("B2CL", Severity.WARNING,
                    "Invoice value ₹${row.invoiceValue} is ≤ $limitLabel — should this be B2CS?",
                    row.invoiceNumber))
            }
            if (row.placeOfSupply.isBlank()) {
                out.add(ValidationIssue("B2CL", Severity.ERROR,
                    "Place of Supply missing.", row.invoiceNumber))
            }
            if (row.rate !in VALID_GST_RATES) {
                out.add(ValidationIssue("B2CL", Severity.WARNING,
                    "Unusual GST rate ${row.rate}%.", row.invoiceNumber))
            }
        }
    }

    private fun validateB2CS(rows: List<B2CSRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.placeOfSupply.isBlank()) {
                out.add(ValidationIssue("B2CS", Severity.ERROR,
                    "Place of Supply missing for B2CS row.", ""))
            }
            // A negative B2CS bucket used to be flagged as an ERROR, which
            // blocked export. It is now legitimate: Table 7 is reported NET of
            // credit/debit notes on small B2C sales, so a month where refunds
            // exceeded sales genuinely nets negative and the portal accepts it.
            // Kept as an informational warning — worth a second look, but not
            // a reason to stop filing.
            if (row.taxableValue < 0.0) {
                out.add(ValidationIssue("B2CS", Severity.WARNING,
                    "Negative net for ${row.placeOfSupply} at ${row.rate}% — refunds " +
                    "exceeded sales this period. Valid, but worth confirming.",
                    row.placeOfSupply))
            }
        }
    }

    private fun validateCdnr(rows: List<CdnrRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (!GSTIN_REGEX.matches(row.gstin)) {
                out.add(ValidationIssue("CDNR", Severity.ERROR,
                    "Invalid recipient GSTIN '${row.gstin}'", row.noteNumber))
            }
            if (row.noteNumber.isBlank()) {
                out.add(ValidationIssue("CDNR", Severity.ERROR, "Note number is blank.", ""))
            }
            if (row.noteValue <= 0.0) {
                out.add(ValidationIssue("CDNR", Severity.WARNING,
                    "Note value is zero.", row.noteNumber))
            }
        }
    }

    private fun validateCdnur(rows: List<CdnurRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.noteNumber.isBlank()) {
                out.add(ValidationIssue("CDNUR", Severity.ERROR, "Note number is blank.", ""))
            }
            if (row.urType.isBlank()) {
                out.add(ValidationIssue("CDNUR", Severity.ERROR,
                    "UR Type is blank.", row.noteNumber))
            }
        }
    }

    private fun validateHsn(rows: List<HsnRow>, section: String, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.hsn.isBlank() || row.hsn == "N/A") {
                out.add(ValidationIssue(section, Severity.WARNING,
                    "HSN code is missing for product '${row.description}'.", ""))
            }
            if (row.totalQuantity <= 0.0) {
                out.add(ValidationIssue(section, Severity.WARNING,
                    "Zero quantity for HSN ${row.hsn}.", row.hsn))
            }
        }
    }

    private fun validateDocs(rows: List<DocsRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.natureOfDoc.isBlank()) {
                out.add(ValidationIssue("DOCS", Severity.ERROR, "Nature of document is blank.", ""))
            }
            if (row.totalNumber <= 0) {
                out.add(ValidationIssue("DOCS", Severity.WARNING,
                    "Document series '${row.natureOfDoc}' has 0 documents.", ""))
            }
        }
    }
}
