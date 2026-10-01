import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_click = """                etProduct.setText(row.name)
                etBrand.setText(row.brand.orEmpty())
                etVariant.setText(row.variant.orEmpty())"""
new_click = """                etProduct.setText(row.name)
                etBrand.setText(row.brand.orEmpty(), false)
                etVariant.setText(row.variant.orEmpty(), false)"""

content = content.replace(old_click, new_click)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
