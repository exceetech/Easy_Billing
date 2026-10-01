with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

content = content.replace(
    '<string name="xml_purchase_line_dialog_eligibility_label">ITC Eligibility</string>',
    '<string name="xml_purchase_line_dialog_eligibility_label">Item Purpose</string>'
)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
