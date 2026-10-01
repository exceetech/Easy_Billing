import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

# Revert in onVariantSettled
old_variant = """                                spinnerSupplyClassPurchase.setText(SupplyClassMapper.codeToDisplay(match.supplyClassification) ?: "", false)
                                etCategoryPurchase.setText(match.category, false)
                                switchRawMaterial.isChecked = match.isRawMaterial
                                applyingAutofill = false"""
new_variant = """                                spinnerSupplyClassPurchase.setText(SupplyClassMapper.codeToDisplay(match.supplyClassification) ?: "", false)
                                etCategoryPurchase.setText(match.category, false)
                                applyingAutofill = false"""

content = content.replace(old_variant, new_variant)

# Revert in onProductSettled
old_product = """                                if (spinnerSupplyClassPurchase.text.isNullOrBlank() && !match.supplyClassification.isNullOrBlank())
                                    SupplyClassMapper.codeToDisplay(match.supplyClassification)?.let { spinnerSupplyClassPurchase.setText(it, false) }
                                if (etCategoryPurchase.text.isNullOrBlank() && !match.category.isNullOrBlank())
                                    etCategoryPurchase.setText(match.category, false)
                                // Soft autofill for toggle (only turn it on if it matches history)
                                if (match.isRawMaterial && !switchRawMaterial.isChecked) {
                                    switchRawMaterial.isChecked = true
                                }
                            } finally {"""
new_product = """                                if (spinnerSupplyClassPurchase.text.isNullOrBlank() && !match.supplyClassification.isNullOrBlank())
                                    SupplyClassMapper.codeToDisplay(match.supplyClassification)?.let { spinnerSupplyClassPurchase.setText(it, false) }
                                if (etCategoryPurchase.text.isNullOrBlank() && !match.category.isNullOrBlank())
                                    etCategoryPurchase.setText(match.category, false)
                            } finally {"""
                            
content = content.replace(old_product, new_product)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
