import re

with open("app/src/main/res/values/strings.xml", "r") as f:
    content = f.read()

replacements = {
    r'<string name="add_product_central_tax_hint">.*?</string>': '<string name="add_product_central_tax_hint">0</string>',
    r'<string name="add_product_state_tax_hint">.*?</string>': '<string name="add_product_state_tax_hint">0</string>',
    r'<string name="add_product_auto_filled_hint">.*?</string>': '<string name="add_product_auto_filled_hint">Auto</string>',
}

for pattern, replacement in replacements.items():
    content = re.sub(pattern, replacement, content)

with open("app/src/main/res/values/strings.xml", "w") as f:
    f.write(content)
