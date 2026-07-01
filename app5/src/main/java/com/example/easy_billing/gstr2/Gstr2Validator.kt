package com.example.easy_billing.gstr2

object Gstr2Validator {

    data class ValidationResult(
        val errorCount: Int,
        val warningCount: Int,
        val messages: List<String>
    ) {
        val hasErrors: Boolean get() = errorCount > 0
        val hasWarnings: Boolean get() = warningCount > 0
    }

    fun validate(report: Gstr2Report): ValidationResult {
        var errors = 0
        var warnings = 0
        val msgs = mutableListOf<String>()

        if (report.gstin.isBlank() || report.gstin.length != 15) {
            errors++
            msgs.add("ERROR: Store GSTIN is invalid or missing.")
        }

        // B2B validation
        for (row in report.b2b) {
            if (row.supplierGstin.isBlank()) {
                errors++
                msgs.add("ERROR: B2B Invoice ${row.invoiceNumber} is missing Supplier GSTIN.")
            }
        }

        // IMPG validation
        for (row in report.impg) {
            if (row.portCode.isBlank()) {
                errors++
                msgs.add("ERROR: IMPG Bill of Entry ${row.billOfEntryNumber} is missing Port Code.")
            }
        }

        return ValidationResult(errors, warnings, msgs)
    }
}
