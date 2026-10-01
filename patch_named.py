import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old = """                    // Local purchase history fills anything the catalog didn't.
                    if (named.isEmpty()) {
                        history?.let { match ->"""
new = """                    // Local purchase history fills anything the catalog didn't.
                    history?.let { match ->"""

content = content.replace(old, new)
content = content.replace("                            } finally {\n                                applyingAutofill = false\n                            }\n                        }\n                    }", "                            } finally {\n                                applyingAutofill = false\n                            }\n                        }")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
