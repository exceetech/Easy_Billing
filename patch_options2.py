import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_options = """    /**
     * The eligibility options, in display order — must match
     * PurchaseRepository.isAssetLine and backend purchase_routes.py's
     * comparisons verbatim, so no casing/spacing variation here.
     */
    private val eligibilityOptions = listOf(
        "Inputs", "Capital goods", "Input services", "Ineligible", "None"
    )

    /** Reads the currently selected eligibility text; defaults to "Inputs" if empty/unrecognized. */
    private fun getSelectedEligibility(spinner: AutoCompleteTextView): String {
        val text = spinner.text?.toString()?.trim()
        return if (text.isNullOrEmpty() || text !in eligibilityOptions) "Inputs" else text
    }

    /** Sets the spinner text to [value], defaulting to "Inputs" for an unknown value. */
    private fun setSelectedEligibility(spinner: AutoCompleteTextView, value: String) {
        val resolved = if (value in eligibilityOptions) value else "Inputs"
        spinner.setText(resolved, false)
    }"""

new_options = """    /** Mapper to translate clean, non-jargon UI labels into strict backend GST codes. */
    private object EligibilityMapper {
        private val codeToDisplay = mapOf(
            "Inputs" to "For Resale (Inputs)",
            "Capital goods" to "For Shop Use (Capital)",
            "Input services" to "For Business (Services)",
            "Ineligible" to "Not Eligible",
            "None" to "None"
        )
        private val displayToCode = codeToDisplay.entries.associate { it.value to it.key }

        val displayOptions = codeToDisplay.values.toList()

        fun getDisplay(code: String): String = codeToDisplay[code] ?: "For Resale (Inputs)"
        fun getCode(display: String?): String = displayToCode[display?.trim()] ?: "Inputs"
    }

    /** Returns the backend code (e.g. "Inputs") for whatever display string is in the spinner. */
    private fun getSelectedEligibility(spinner: AutoCompleteTextView): String {
        val text = spinner.text?.toString()?.trim()
        return EligibilityMapper.getCode(text)
    }

    /** Sets the spinner text using a backend code (e.g. "Inputs" -> "For Resale (Inputs)"). */
    private fun setSelectedEligibility(spinner: AutoCompleteTextView, backendCode: String) {
        spinner.setText(EligibilityMapper.getDisplay(backendCode), false)
    }"""

content = content.replace(old_options, new_options)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
