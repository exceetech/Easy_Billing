import re

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

# 1. Add import if needed
if "import com.example.easy_billing.util.SupplyClassMapper" not in content:
    content = content.replace("import com.example.easy_billing.util.UqcMapper", 
                              "import com.example.easy_billing.util.UqcMapper\nimport com.example.easy_billing.util.SupplyClassMapper")

# 2. Remove the raw array
content = re.sub(r'^\s*private val supplyClasses = listOf\("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST"\)\n', '', content, flags=re.MULTILINE)

# 3. Update the visual default
content = content.replace('spinnerSupplyClass.text = "TAXABLE"', 'spinnerSupplyClass.text = SupplyClassMapper.codeToDisplay("TAXABLE")')

# 4. Update the popup options
old_popup = """        spinnerSupplyClass.setOnClickListener {
            showSortStylePopup(spinnerSupplyClass, supplyClasses, spinnerSupplyClass.text.toString()) { picked ->
                spinnerSupplyClass.text = picked
            }
        }"""
new_popup = """        spinnerSupplyClass.setOnClickListener {
            showSortStylePopup(spinnerSupplyClass, SupplyClassMapper.ALL_DISPLAY, spinnerSupplyClass.text.toString()) { picked ->
                spinnerSupplyClass.text = picked
            }
        }"""
content = content.replace(old_popup, new_popup)

# 5. Update how it retrieves the value for saving/printing
old_save = """val supplyClassVal = spinnerSupplyClass.text.toString().trim().ifBlank { "TAXABLE" }"""
new_save = """val supplyClassVal = SupplyClassMapper.displayToCode(spinnerSupplyClass.text.toString()) ?: "TAXABLE\""""
content = content.replace(old_save, new_save)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)
