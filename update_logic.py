with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_listener = """        switchRawMaterial.setOnCheckedChangeListener { _, _ ->
            updateAssetLineUi(getSelectedEligibility(chipGroupEligibility))
            recompute()
        }"""

new_listener = """        switchRawMaterial.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                setSelectedEligibility(chipGroupEligibility, "Capital goods")
            } else {
                setSelectedEligibility(chipGroupEligibility, "Inputs")
            }
            updateAssetLineUi(getSelectedEligibility(chipGroupEligibility))
            recompute()
        }"""

content = content.replace(old_listener, new_listener)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
