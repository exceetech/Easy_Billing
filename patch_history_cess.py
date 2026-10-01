import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace("if (etCessRatePurchase.text.isNullOrBlank() && match.cessRate > 0)\n                                    etCessRatePurchase.setText(match.cessRate.toString())", "if (etCessRatePurchase.text.isNullOrBlank())\n                                    etCessRatePurchase.setText(match.cessRate.toString())")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
