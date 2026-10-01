import re

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

# 1. Add initial defaults after findViewByIds
init_marker = """        ivMoreTaxDetailsChevron = findViewById(R.id.ivMoreTaxDetailsChevron)"""
init_patch = """        ivMoreTaxDetailsChevron = findViewById(R.id.ivMoreTaxDetailsChevron)

        // VISUAL DEFAULTS: Auto-fill these so the user is reassured they don't have to guess
        spinnerSupplyClass.text = "TAXABLE"
        etCessRate.setText("0")
        com.example.easy_billing.util.UqcMapper.codeToDisplay("NOS")?.let { spinnerUqc.text = it }"""
content = content.replace(init_marker, init_patch)

# 2. Update etUnit listener to auto-map UQC
unit_listener = """            showSortStylePopup(etUnit, units, unitDisplay(etUnit.text.toString())) { picked ->
                etUnit.text = picked
                unitUserSet = true
            }"""
unit_patch = """            showSortStylePopup(etUnit, units, unitDisplay(etUnit.text.toString())) { picked ->
                etUnit.text = picked
                unitUserSet = true
                
                // Auto-map the official UQC when unit is picked
                val uqcCode = com.example.easy_billing.util.UqcMapper.fromUnit(picked)
                com.example.easy_billing.util.UqcMapper.codeToDisplay(uqcCode)?.let { spinnerUqc.text = it }
            }"""
content = content.replace(unit_listener, unit_patch)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
