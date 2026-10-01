import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_history = """                                if (etBrand.text.isNullOrBlank() && !match.brand.isNullOrBlank())
                                    etBrand.setText(match.brand)"""
new_history = """                                if (etBrand.text.isNullOrBlank() && !match.brand.isNullOrBlank())
                                    etBrand.setText(match.brand, false)
                                if (etVariant.text.isNullOrBlank() && !match.variant.isNullOrBlank())
                                    etVariant.setText(match.variant, false)"""

content = content.replace(old_history, new_history)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
