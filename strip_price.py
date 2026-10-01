import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

# 1. Remove from history block in onProductSettled
old_history = """                                val sellingWasBlank = etSelling.text.isNullOrBlank()
                                if (sellingWasBlank) {
                                    if (match.price > 0) etSelling.setText(trimNum(match.price))
                                    switchTaxInclusive.isChecked = match.isTaxInclusive
                                }"""
new_history = ""
content = content.replace(old_history, new_history)

# 2. Remove from onVariantSettled local match
old_variant = """                                applyingAutofill = true
                                etSelling.setText(trimNum(match.price))
                                etBrand.setText(match.brand.orEmpty(), false)
                                switchTaxInclusive.isChecked = match.isTaxInclusive"""
new_variant = """                                applyingAutofill = true
                                etBrand.setText(match.brand.orEmpty(), false)"""
content = content.replace(old_variant, new_variant)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
