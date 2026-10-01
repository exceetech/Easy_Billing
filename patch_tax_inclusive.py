import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old = """                                if (etSelling.text.isNullOrBlank() && match.price > 0)
                                    etSelling.setText(match.price.toString())
                                if (etBrand.text.isNullOrBlank() && !match.brand.isNullOrBlank())
                                    etBrand.setText(match.brand, false)
                                if (etVariant.text.isNullOrBlank() && !match.variant.isNullOrBlank())
                                    etVariant.setText(match.variant, false)
                                // Only override tax inclusive if selling was blank to keep them in sync
                                if (etSelling.text.isNullOrBlank())
                                    switchTaxInclusive.isChecked = match.isTaxInclusive"""

new = """                                val sellingWasBlank = etSelling.text.isNullOrBlank()
                                if (sellingWasBlank) {
                                    if (match.price > 0) etSelling.setText(match.price.toString())
                                    switchTaxInclusive.isChecked = match.isTaxInclusive
                                }
                                if (etBrand.text.isNullOrBlank() && !match.brand.isNullOrBlank())
                                    etBrand.setText(match.brand, false)
                                if (etVariant.text.isNullOrBlank() && !match.variant.isNullOrBlank())
                                    etVariant.setText(match.variant, false)"""

content = content.replace(old, new)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
