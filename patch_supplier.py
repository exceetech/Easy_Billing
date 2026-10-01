with open("app/src/main/res/layout/activity_purchase.xml", "r") as f:
    content = f.read()

content = content.replace('android:hint="@string/add_product_name_hint"', 'android:hint="@string/invoice_hint_supplier_name"')

with open("app/src/main/res/layout/activity_purchase.xml", "w") as f:
    f.write(content)

with open("app/src/main/res/values/strings.xml", "r") as f:
    strings = f.read()

strings = strings.replace('<string name="invoice_hint_supplier_name">Supplier name</string>', '<string name="invoice_hint_supplier_name">Enter supplier name</string>')

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(strings)
