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

    private val VALID_GST_RATES = setOf(
        0.0, 0.1, 0.25, 1.0, 1.5, 3.0, 5.0, 6.0, 7.5, 12.0, 18.0, 28.0, 40.0
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
        validateB2CL(report.b2cl, issues)
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

    private fun validateB2CL(rows: List<B2CLRow>, out: MutableList<ValidationIssue>) {
        for (row in rows) {
            if (row.invoiceNumber.isBlank()) {
                out.add(ValidationIssue("B2CL", Severity.ERROR, "Invoice number is blank.", ""))
            }
            if (row.invoiceValue <= 250_000.0) {
                out.add(ValidationIssue("B2CL", Severity.WARNING,
                    "Invoice value ₹${row.invoiceValue} is ≤ ₹2.5L — should this be B2CS?",
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
            if (row.taxableValue < 0.0) {
                out.add(ValidationIssue("B2CS", Severity.ERROR,
                    "Negative taxable value in B2CS.", row.placeOfSupply))
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
