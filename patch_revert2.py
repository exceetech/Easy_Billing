with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace(
    '"Inputs" to "Stock / Raw Material (Inputs)",',
    '"Inputs" to "For Resale (Inputs)",'
)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)

with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    "<string name=\"xml_purchase_line_dialog_raw_material_label\">This is a raw material (skip selling price)</string>",
    "<string name=\"xml_purchase_line_dialog_raw_material_label\">It\\'s a raw material (not for direct sale)</string>"
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
