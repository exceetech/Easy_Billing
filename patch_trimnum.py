import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace("etSelling.setText(match.price.toString())", "etSelling.setText(trimNum(match.price))")
content = content.replace("etSCgst.setText(match.cgstPercentage.toString())", "etSCgst.setText(trimNum(match.cgstPercentage))")
content = content.replace("etSSgst.setText(match.sgstPercentage.toString())", "etSSgst.setText(trimNum(match.sgstPercentage))")
content = content.replace("etSIgst.setText(match.igstPercentage.toString())", "etSIgst.setText(trimNum(match.igstPercentage))")
content = content.replace("etCessRatePurchase.setText(match.cessRate.toString())", "etCessRatePurchase.setText(trimNum(match.cessRate))")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
