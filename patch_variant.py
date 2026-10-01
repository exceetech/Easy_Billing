with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    '<string name="xml_purchase_line_dialog_variant_label">Variant</string>',
    '<string name="xml_purchase_line_dialog_variant_label">Type</string>'
)
content = content.replace(
    '<string name="xml_purchase_line_dialog_variant_hint">Size or pack type</string>',
    '<string name="xml_purchase_line_dialog_variant_hint">Enter product type</string>'
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
