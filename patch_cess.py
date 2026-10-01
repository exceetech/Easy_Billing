with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

old_code = """        if (etCessRate.text.isNullOrBlank() && v.cess_rate > 0) etCessRate.setText(trimNum(v.cess_rate))"""
new_code = """        if ((etCessRate.text.isNullOrBlank() || etCessRate.text.toString() == "0") && v.cess_rate > 0) etCessRate.setText(trimNum(v.cess_rate))"""
content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
