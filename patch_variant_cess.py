import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace("etHsnDescPurchase.setText(match.hsnDescription ?: \"\")\n                                etCessRatePurchase.setText(match.cessRate.toString())", "etHsnDescPurchase.setText(match.hsnDescription ?: \"\")\n                                etCessRatePurchase.setText(match.cessRate.toString())")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
