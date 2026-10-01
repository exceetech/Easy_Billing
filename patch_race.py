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
                    }
                }
                }"""

new_block = """                    // Local purchase history fills anything the catalog didn't.
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
                    }
                    
                    // If a variant was already typed or tapped from search, the initial
                    // synchronous call to onVariantSettled() failed to find it because 
                    // variantCache was empty. Now that we have fetched it, run it again.
                    if (etVariant.text?.toString()?.trim()?.isNotEmpty() == true) {
                        onVariantSettled()
                    }
                }
                }"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
