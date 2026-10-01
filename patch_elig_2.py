with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace(
    '"Ineligible" to "Not Eligible",',
    '"Ineligible" to "Cannot Claim GST (Blocked)",'
)

content = content.replace(
    '"None" to "None"',
    '"None" to "No GST to Claim (None)"'
)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
