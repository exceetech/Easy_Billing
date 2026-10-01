import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_block = """                    // Local purchase history fills anything the catalog didn't.
                    if (named.isEmpty()) {
                        history?.let { match ->
                            applyingAutofill = true
                            try {
                                if (etHsn.text.isNullOrBlank() && !match.hsnCode.isNullOrBlank())
                                    etHsn.setText(match.hsnCode)
                                if (etSCgst.text.isNullOrBlank() && match.cgstPercentage > 0)
                                    etSCgst.setText(match.cgstPercentage.toString())
                                if (etSSgst.text.isNullOrBlank() && match.sgstPercentage > 0)
                                    etSSgst.setText(match.sgstPercentage.toString())
                                if (etSIgst.text.isNullOrBlank() && match.igstPercentage > 0)
                                    etSIgst.setText(match.igstPercentage.toString())
                            } finally {
                                applyingAutofill = false
                            }
                        }
                    }"""

new_block = """                    // Local purchase history fills anything the catalog didn't.
                    if (named.isEmpty()) {
                        history?.let { match ->
                            applyingAutofill = true
                            try {
                                if (etSelling.text.isNullOrBlank() && match.price > 0)
                                    etSelling.setText(match.price.toString())
                                if (etBrand.text.isNullOrBlank() && !match.brand.isNullOrBlank())
                                    etBrand.setText(match.brand)
                                // Only override tax inclusive if selling was blank to keep them in sync
                                if (etSelling.text.isNullOrBlank())
                                    switchTaxInclusive.isChecked = match.isTaxInclusive
                                
                                if (etHsn.text.isNullOrBlank() && !match.hsnCode.isNullOrBlank())
                                    etHsn.setText(match.hsnCode)
                                if (etSCgst.text.isNullOrBlank() && match.cgstPercentage > 0)
                                    etSCgst.setText(match.cgstPercentage.toString())
                                if (etSSgst.text.isNullOrBlank() && match.sgstPercentage > 0)
                                    etSSgst.setText(match.sgstPercentage.toString())
                                if (etSIgst.text.isNullOrBlank() && match.igstPercentage > 0)
                                    etSIgst.setText(match.igstPercentage.toString())
                                    
                                if (!unitUserSet && etUnit.text.isNullOrBlank() && match.unit.isNotBlank())
                                    etUnit.setText(match.unit, false)
                                if (spinnerUqcPurchase.text.isNullOrBlank() && !match.officialUqc.isNullOrBlank())
                                    UqcMapper.codeToDisplay(match.officialUqc)?.let { spinnerUqcPurchase.setText(it, false) }
                                if (etHsnDescPurchase.text.isNullOrBlank() && !match.hsnDescription.isNullOrBlank())
                                    etHsnDescPurchase.setText(match.hsnDescription)
                                if (etCessRatePurchase.text.isNullOrBlank() && match.cessRate > 0)
                                    etCessRatePurchase.setText(match.cessRate.toString())
                                if (spinnerSupplyClassPurchase.text.isNullOrBlank() && !match.supplyClassification.isNullOrBlank())
                                    SupplyClassMapper.codeToDisplay(match.supplyClassification)?.let { spinnerSupplyClassPurchase.setText(it, false) }
                                if (etCategoryPurchase.text.isNullOrBlank() && !match.category.isNullOrBlank())
                                    etCategoryPurchase.setText(match.category, false)
                            } finally {
                                applyingAutofill = false
                            }
                        }
                    }"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
