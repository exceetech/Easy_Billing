import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace("if (etCessRatePurchase.text.isNullOrBlank() && match.cessRate > 0)\n                        etCessRatePurchase.setText(trimNum(match.cessRate))", "if (etCessRatePurchase.text.isNullOrBlank())\n                        etCessRatePurchase.setText(trimNum(match.cessRate))")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
