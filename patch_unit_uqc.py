import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace("setupUnitDropdown(etUnit)", "setupUnitDropdown(etUnit, spinnerUqcPurchase)")

old_sig = """    private fun setupUnitDropdown(etUnit: AutoCompleteTextView) {
        etUnit.setText("piece", false)
        etUnit.setOnClickListener {
            showSortStylePopup(etUnit, unitOptions, etUnit.text.toString()) { picked ->
                unitUserSet = true
                etUnit.setText(picked, false)
            }
        }"""
        
new_sig = """    private fun setupUnitDropdown(etUnit: AutoCompleteTextView, spinnerUqc: AutoCompleteTextView) {
        etUnit.setText("piece", false)
        etUnit.setOnClickListener {
            showSortStylePopup(etUnit, unitOptions, etUnit.text.toString()) { picked ->
                unitUserSet = true
                etUnit.setText(picked, false)
                
                val uqcCode = UqcMapper.fromUnit(picked)
                UqcMapper.codeToDisplay(uqcCode)?.let { spinnerUqc.setText(it, false) }
            }
        }"""

content = content.replace(old_sig, new_sig)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
