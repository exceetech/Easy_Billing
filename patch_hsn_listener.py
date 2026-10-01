import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_block = """        etHsn.addTextChangedListener { editable ->
            if (applyingAutofill) return@addTextChangedListener
            val hsn = editable?.toString()?.trim().orEmpty()

            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")

            if (hsn.length < 4) return@addTextChangedListener
            activity.lifecycleScope.launch {
                val match = withContext(Dispatchers.IO) {
                    productRepo.autoFillFromHistory(hsn = hsn)
                } ?: return@launch   // no history for this HSN — leave the rates to the user
                withContext(Dispatchers.Main) {
                    if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                    if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                    if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                }
            }
        }"""

new_block = """        etHsn.addTextChangedListener { editable ->
            if (applyingAutofill) return@addTextChangedListener
            val hsn = editable?.toString()?.trim().orEmpty()

            etSCgst.setText("")
            etSSgst.setText("")
            etSIgst.setText("")

            if (hsn.length < 4) return@addTextChangedListener
            activity.lifecycleScope.launch {
                val match = withContext(Dispatchers.IO) {
                    productRepo.autoFillFromHistory(hsn = hsn)
                } ?: return@launch   // no history for this HSN — leave the rates to the user
                withContext(Dispatchers.Main) {
                    if (etSCgst.text.isNullOrBlank()) etSCgst.setText(match.cgstPercentage.toString())
                    if (etSSgst.text.isNullOrBlank()) etSSgst.setText(match.sgstPercentage.toString())
                    if (etSIgst.text.isNullOrBlank()) etSIgst.setText(match.igstPercentage.toString())
                    
                    if (etHsnDescPurchase.text.isNullOrBlank() && !match.hsnDescription.isNullOrBlank())
                        etHsnDescPurchase.setText(match.hsnDescription)
                    if (etCessRatePurchase.text.isNullOrBlank() && match.cessRate > 0)
                        etCessRatePurchase.setText(match.cessRate.toString())
                    if (spinnerUqcPurchase.text.isNullOrBlank() && !match.officialUqc.isNullOrBlank())
                        UqcMapper.codeToDisplay(match.officialUqc)?.let { spinnerUqcPurchase.setText(it, false) }
                    if (spinnerSupplyClassPurchase.text.isNullOrBlank() && !match.supplyClassification.isNullOrBlank())
                        SupplyClassMapper.codeToDisplay(match.supplyClassification)?.let { spinnerSupplyClassPurchase.setText(it, false) }
                }
            }
        }"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
