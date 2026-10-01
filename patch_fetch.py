import re

files = [
    "app/src/main/java/com/example/easy_billing/AddProductActivity.kt",
    "app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt"
]

old_block = """            withContext(Dispatchers.Main) {
                refreshVariantAdapter(key)
                productDefault?.let { fillStatutoryFrom(it, applyUnit = true) }
                if (::searchAdapter.isInitialized) refreshProductSearchPool()
            }"""

new_block = """            withContext(Dispatchers.Main) {
                refreshVariantAdapter(key)
                if (etVariant.text.toString().trim().isNotEmpty()) {
                    applyVariantAutofill()
                } else {
                    productDefault?.let { fillStatutoryFrom(it, applyUnit = true) }
                }
                if (::searchAdapter.isInitialized) refreshProductSearchPool()
            }"""

for file in files:
    with open(file, "r") as f:
        content = f.read()
    content = content.replace(old_block, new_block)
    with open(file, "w") as f:
        f.write(content)
