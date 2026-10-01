with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

old_code = """    private fun fillStatutoryFrom(v: VariantResponse, applyUnit: Boolean) {
        if (applyUnit && !unitUserSet && !v.unit.isNullOrBlank() && !v.unit.equals("unit", true))
            etUnit.text = v.unit"""

new_code = """    private fun fillStatutoryFrom(v: VariantResponse, applyUnit: Boolean) {
        if (etBrand.text.isNullOrBlank() && !v.brand.isNullOrBlank()) etBrand.setText(v.brand)
        if (etVariant.text.isNullOrBlank() && v.variant_name.isNotBlank()) etVariant.setText(v.variant_name)
        if (applyUnit && !unitUserSet && !v.unit.isNullOrBlank() && !v.unit.equals("unit", true))
            etUnit.text = v.unit"""

content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
