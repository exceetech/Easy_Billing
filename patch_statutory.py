import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_sig = """    private fun applyStatutoryFrom(
        v: VariantResponse,
        etHsn: TextInputEditText,
        etSCgst: TextInputEditText,
        etSSgst: TextInputEditText,
        etUnit: AutoCompleteTextView,
        spinnerUqc: AutoCompleteTextView,
        etHsnDesc: TextInputEditText,
        etCessRate: TextInputEditText
    ) {"""
new_sig = """    private fun applyStatutoryFrom(
        v: VariantResponse,
        etHsn: TextInputEditText,
        etSCgst: TextInputEditText,
        etSSgst: TextInputEditText,
        etUnit: AutoCompleteTextView,
        spinnerUqc: AutoCompleteTextView,
        etHsnDesc: TextInputEditText,
        etCessRate: TextInputEditText,
        etBrand: AutoCompleteTextView,
        etVariant: AutoCompleteTextView
    ) {"""

content = content.replace(old_sig, new_sig)

old_body = """        applyingAutofill = true
        try {
            if (!unitUserSet && v.unit.isNotBlank() && !v.unit.equals("unit", true))
                etUnit.setText(v.unit, false)"""
new_body = """        applyingAutofill = true
        try {
            if (etBrand.text.isNullOrBlank() && !v.brand.isNullOrBlank()) etBrand.setText(v.brand)
            if (etVariant.text.isNullOrBlank() && !v.variant_name.isNullOrBlank()) etVariant.setText(v.variant_name, false)
            if (!unitUserSet && v.unit.isNotBlank() && !v.unit.equals("unit", true))
                etUnit.setText(v.unit, false)"""

content = content.replace(old_body, new_body)

content = content.replace("spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase\n                                    )", "spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase,\n                                        etBrand, etVariant\n                                    )")
content = content.replace("spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase\n                        )", "spinnerUqcPurchase, etHsnDescPurchase, etCessRatePurchase,\n                            etBrand, etVariant\n                        )")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
