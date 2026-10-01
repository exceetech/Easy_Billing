import re

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

content = content.replace("if ((etCessRate.text.isNullOrBlank() || etCessRate.text.toString() == \"0\") && v.cess_rate > 0) etCessRate.setText(trimNum(v.cess_rate))", "if (etCessRate.text.isNullOrBlank() || etCessRate.text.toString() == \"0\") etCessRate.setText(trimNum(v.cess_rate))")

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
