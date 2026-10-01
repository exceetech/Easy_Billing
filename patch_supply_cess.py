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
        etCessRate: TextInputEditText,
        etBrand: AutoCompleteTextView,
        etVariant: AutoCompleteTextView
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
        etVariant: AutoCompleteTextView,
        spinnerSupplyClass: AutoCompleteTextView
    ) {"""
content = content.replace(old_sig, new_sig)

old_body = """            if (etCessRate.text.isNullOrBlank() && v.cess_rate > 0)
                etCessRate.setText(trimNum(v.cess_rate))
        } finally {"""
new_body = """            if (etCessRate.text.isNullOrBlank())
                etCessRate.setText(trimNum(v.cess_rate))
            if (spinnerSupplyClass.text.isNullOrBlank())
                spinnerSupplyClass.setText(SupplyClassMapper.codeToDisplay("TAXABLE"), false)
        } finally {"""
content = content.replace(old_body, new_body)

# Now update the calls!
content = content.replace("etBrand, etVariant\n                                    )", "etBrand, etVariant, spinnerSupplyClassPurchase\n                                    )")
content = content.replace("etBrand, etVariant\n                        )", "etBrand, etVariant, spinnerSupplyClassPurchase\n                        )")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
