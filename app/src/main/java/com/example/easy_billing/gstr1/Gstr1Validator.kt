package com.example.easy_billing.gstr1

/**
 * Gstr1Validator
 *
 * Validates a [Gstr1Report] and returns a list of [ValidationIssue].
 * Each issue is either a WARNING (non-blocking) or an ERROR (blocks export).
 *
 * Every issue carries a stable [ValidationIssue.key] plus [ValidationIssue.args] so the
 * screen can show it in plain words in the owner's language (see
 * GstReportsActivity.issueText). [ValidationIssue.message] is the English fallback.
 */
object Gstr1Validator {

    enum class Severity { ERROR, WARNING }

    data class ValidationIssue(
        val section: String,
        val severity: Severity,
        val message: String,
        val rowHint: String = "",          // e.g. invoice number or note number
        val key: String = "",              // stable id used to look up the plain-word text
        val args: List<String> = emptyList()
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

    private fun MutableList<ValidationIssue>.issue(
        section: String, sev: Severity, key: String, english: String,
        hint: String = "", vararg args: String
    ) {
        add(ValidationIssue(section, sev, english, hint, key, args.toList()))
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
            issues.issue("General", Severity.ERROR, "gstin_missing",
                "Filer GSTIN is missing. Please configure GST Profile.")
        } else if (!GSTIN_REGEX.matches(report.gstin)) {
            issues.issue("General", Severity.ERROR, "gstin_invalid",
                "Filer GSTIN '${report.gstin}' is not in valid format.", "", report.gstin)
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
                out.issue("B2B", Severity.ERROR, "cust_gstin_invalid",
                    "Invalid recipient GSTIN '${row.gstin}'", row.invoiceNumber, row.gstin)
            }
            if (row.invoiceNumber.isBlank()) {
                out.issue("B2B", Severity.ERROR, "inv_no_blank", "Invoice number is blank.")
            }
            if (row.invoiceDate.isBlank()) {
                out.issue("B2B", Severity.ERROR, "inv_date_missing",
                    "Invoice date missing for ${row.invoiceNumber}", row.invoiceNumber, row.invoiceNumber)
            }
            if (row.invoiceValue <= 0.0) {
                out.issue("B2B", Severity.WARNING, "inv_value_zero",
                    "Invoice value is zero or negative.", row.invoiceNumber)
            }
            if (row.placeOfSupply.isBlank()) {
                out.issue("B2B", Severity.ERROR, "pos_missing",
                    "Place of Supply missing.", row.invoiceNumber)
            }
            if (row.rate !in VALID_GST_RATES) {
                out.issue("B2B", Severity.WARNING, "rate_unusual",
                    "Unusual GST rate ${row.rate}% for invoice ${row.invoiceNumber}.",
                    row.invoiceNumber, "${row.rate}")
            }
            if (row.taxableValue < 0.0) {
                out.issue("B2B", Severity.ERROR, "taxable_negative",
                    "Negative taxable value.", row.invoiceNumber)
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
                out.issue("B2CL", Severity.ERROR, "inv_no_blank", "Invoice number is blank.")
            }
            if (row.invoiceValue <= threshold) {
                out.issue("B2CL", Severity.WARNING, "b2cl_small",
                    "Invoice value ₹${row.invoiceValue} is ≤ $limitLabel — should this be B2CS?",
                    row.invoiceNumber, "${row.invoiceValue}", limitLabel)
            }
            if (row.placeOfSupply.isBlank()) {
                out.issue("B2CL", Severity.ERROR, "pos_missing",
                    "Place of Supply missing.", row.invoiceNumber)
            }
            if (row.rate !in VALID_GST_RATES) {
                out.issue("B2CL", Severity.WARNING, "rate_unusual",
                    "Unusual GST rate ${row.rate}%.", row.invoiceNumber, "${row.rate}")
            }
        }
    }

    private fun validateB2CS(rows: List<B2CSRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.placeOfSupply.isBlank()) {
                out.issue("B2CS", Severity.ERROR, "b2cs_pos_missing",
                    "Place of Supply missing for B2CS row.")
            }
            // A negative B2CS bucket used to be flagged as an ERROR, which
            // blocked export. It is now legitimate: Table 7 is reported NET of
            // credit/debit notes on small B2C sales, so a month where refunds
            // exceeded sales genuinely nets negative and the portal accepts it.
            // Kept as an informational warning — worth a second look, but not
            // a reason to stop filing.
            if (row.taxableValue < 0.0) {
                out.issue("B2CS", Severity.WARNING, "b2cs_negative",
                    "Negative net for ${row.placeOfSupply} at ${row.rate}% — refunds " +
                    "exceeded sales this period. Valid, but worth confirming.",
                    row.placeOfSupply, row.placeOfSupply, "${row.rate}")
            }
        }
    }

    private fun validateCdnr(rows: List<CdnrRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (!GSTIN_REGEX.matches(row.gstin)) {
                out.issue("CDNR", Severity.ERROR, "cust_gstin_invalid",
                    "Invalid recipient GSTIN '${row.gstin}'", row.noteNumber, row.gstin)
            }
            if (row.noteNumber.isBlank()) {
                out.issue("CDNR", Severity.ERROR, "note_no_blank", "Note number is blank.")
            }
            if (row.noteValue <= 0.0) {
                out.issue("CDNR", Severity.WARNING, "note_value_zero",
                    "Note value is zero.", row.noteNumber)
            }
        }
    }

    private fun validateCdnur(rows: List<CdnurRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.noteNumber.isBlank()) {
                out.issue("CDNUR", Severity.ERROR, "note_no_blank", "Note number is blank.")
            }
            if (row.urType.isBlank()) {
                out.issue("CDNUR", Severity.ERROR, "ur_type_blank",
                    "UR Type is blank.", row.noteNumber)
            }
        }
    }

    private fun validateHsn(rows: List<HsnRow>, section: String, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.hsn.isBlank() || row.hsn == "N/A") {
                out.issue(section, Severity.WARNING, "hsn_missing",
                    "HSN code is missing for product '${row.description}'.", "", row.description)
            }
            if (row.totalQuantity <= 0.0) {
                out.issue(section, Severity.WARNING, "hsn_qty_zero",
                    "Zero quantity for HSN ${row.hsn}.", row.hsn, row.hsn)
            }
            // The GST portal wants a numeric HSN of at least 4 digits (6 if yearly sales are above Rs 5 crore).
            val code = row.hsn.trim()
            if (code.isNotBlank() && code != "N/A") {
                if (!code.all { it.isDigit() }) {
                    out.issue(section, Severity.ERROR, "hsn_digits",
                        "HSN code '$code' must contain only digits.", code, code)
                } else if (code.length < 4) {
                    out.issue(section, Severity.ERROR, "hsn_short",
                        "HSN code '$code' is too short. Use at least 4 digits.", code, code)
                }
            }
            if (row.description.isBlank()) {
                out.issue(section, Severity.WARNING, "hsn_desc_missing",
                    "Product description is missing for HSN ${row.hsn}.", row.hsn, row.hsn)
            }
            if (row.uqc.isBlank()) {
                out.issue(section, Severity.WARNING, "hsn_uqc_missing",
                    "Unit of measure is missing for HSN ${row.hsn}.", row.hsn, row.hsn)
            }
            if (row.rate == 0.0 && row.taxableValue > 0.0) {
                out.issue(section, Severity.WARNING, "hsn_zero_rate",
                    "HSN ${row.hsn} is sold at 0% GST. Check it is really nil-rated or exempt.",
                    row.hsn, row.hsn)
            }
        }
    }

    private fun validateDocs(rows: List<DocsRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.natureOfDoc.isBlank()) {
                out.issue("DOCS", Severity.ERROR, "doc_nature_blank", "Nature of document is blank.")
            }
            if (row.totalNumber <= 0) {
                out.issue("DOCS", Severity.WARNING, "doc_zero",
                    "Document series '${row.natureOfDoc}' has 0 documents.", "", row.natureOfDoc)
            }
        }
    }
}
