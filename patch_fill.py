with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

old_code = """        if (spinnerUqc.text.isNullOrBlank() && !v.official_uqc.isNullOrBlank())
            UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.text = it }"""

new_code = """        val currentUqc = spinnerUqc.text.toString()
        if (currentUqc.isBlank() || currentUqc == com.example.easy_billing.util.UqcMapper.codeToDisplay("NOS")) {
            if (!v.official_uqc.isNullOrBlank()) {
                com.example.easy_billing.util.UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.text = it }
            } else if (applyUnit && !v.unit.isNullOrBlank()) {
                val inferred = com.example.easy_billing.util.UqcMapper.fromUnit(v.unit)
                com.example.easy_billing.util.UqcMapper.codeToDisplay(inferred)?.let { spinnerUqc.text = it }
            }
        }"""

content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
