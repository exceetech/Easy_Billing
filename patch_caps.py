with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    '<string name="xml_purchase_line_dialog_product_section">PRODUCT</string>',
    '<string name="xml_purchase_line_dialog_product_section">Product</string>'
)
content = content.replace(
    '<string name="xml_purchase_line_dialog_supplier_invoice_section">SUPPLIER INVOICE</string>',
    '<string name="xml_purchase_line_dialog_supplier_invoice_section">Supplier invoice</string>'
)
content = content.replace(
    '<string name="xml_purchase_line_dialog_tax_classification_section">TAX CLASSIFICATION</string>',
    '<string name="xml_purchase_line_dialog_tax_classification_section">Tax classification</string>'
)
content = content.replace(
    '<string name="xml_purchase_line_dialog_you_sell_at_section">YOU WILL SELL AT</string>',
    '<string name="xml_purchase_line_dialog_you_sell_at_section">You will sell at</string>'
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
