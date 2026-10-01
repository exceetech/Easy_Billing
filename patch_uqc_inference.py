import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_logic = """            if (spinnerUqc.text.isNullOrBlank() && !v.official_uqc.isNullOrBlank())
                UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.setText(it, false) }"""

new_logic = """            if (spinnerUqc.text.isNullOrBlank()) {
                if (!v.official_uqc.isNullOrBlank()) {
                    UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.setText(it, false) }
                } else if (!v.unit.isNullOrBlank()) {
                    val inferred = UqcMapper.fromUnit(v.unit)
                    UqcMapper.codeToDisplay(inferred)?.let { spinnerUqc.setText(it, false) }
                }
            }"""

content = content.replace(old_logic, new_logic)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
