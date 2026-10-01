with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    '<string name="xml_purchase_line_dialog_eligibility_label">This is for the shop to sell</string>',
    '<string name="xml_purchase_line_dialog_eligibility_label">ITC Eligibility</string>'
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
