import re

with open("app/src/main/java/com/example/easy_billing/PurchaseDetailsActivity.kt", "r") as f:
    content = f.read()

old_code = """            row.findViewById<TextView>(R.id.tvCostAndGst).text = if (sameState) {
                val pct = (item.purchaseCgstPercentage + item.purchaseSgstPercentage)
                if (pct > 0) "GST ${pct.toInt()}%" else "0%"
            } else {
                if (item.purchaseIgstPercentage > 0) "IGST ${item.purchaseIgstPercentage.toInt()}%" else "0%"
            }"""
new_code = """            var taxString = if (sameState) {
                val pct = (item.purchaseCgstPercentage + item.purchaseSgstPercentage)
                if (pct > 0) "GST ${pct.toInt()}%" else "0%"
            } else {
                if (item.purchaseIgstPercentage > 0) "IGST ${item.purchaseIgstPercentage.toInt()}%" else "0%"
            }
            if (item.cessPercentage > 0.0) {
                val prettyCess = if (item.cessPercentage % 1.0 == 0.0) item.cessPercentage.toInt().toString() else "%.2f".format(item.cessPercentage).trimEnd('0').trimEnd('.')
                taxString += " + Cess $prettyCess%"
            }
            row.findViewById<TextView>(R.id.tvCostAndGst).text = taxString"""
content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/PurchaseDetailsActivity.kt", "w") as f:
    f.write(content)
