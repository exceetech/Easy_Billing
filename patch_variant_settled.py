import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

# 1. Change onVariantSettled definition
content = content.replace("val onVariantSettled = {", "val onVariantSettled: (Boolean) -> Unit = { force ->")

# 2. Change the condition
content = content.replace("} else if (vName != lastVariantName || pName != lastProductName) {", "} else if (force || vName != lastVariantName || pName != lastProductName) {")

# 3. Update the delayed call in onProductSettled
content = content.replace("onVariantSettled()\n                    }\n                }\n                }\n            }", "onVariantSettled(true)\n                    }\n                }\n                }\n            }")
# Wait, this might be tricky to replace exactly. Let's use regex.

content = re.sub(r'if \(etVariant\.text\?\.toString\(\)\?\.trim\(\)\?\.isNotEmpty\(\) == true\) \{\s*onVariantSettled\(\)\s*\}', 'if (etVariant.text?.toString()?.trim()?.isNotEmpty() == true) {\n                        onVariantSettled(true)\n                    }', content)

# 4. Update the calls at the end of the file
content = content.replace("etVariant.setOnItemClickListener { _, _, _, _ -> onVariantSettled() }", "etVariant.setOnItemClickListener { _, _, _, _ -> onVariantSettled(false) }")
content = content.replace("etVariant.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onVariantSettled() }", "etVariant.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onVariantSettled(false) }")

# 5. Update the call in etProductSearch.setOnItemClickListener
content = content.replace("if (!row.variant.isNullOrBlank()) onVariantSettled()", "if (!row.variant.isNullOrBlank()) onVariantSettled(false)")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
