with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    '<string name="purchase_invoice_number_hint">Bill No (ex: INV-01)</string>',
    '<string name="purchase_invoice_number_hint">Bill Number</string>'
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
