import re

with open("app/src/main/java/com/example/easy_billing/EditProductActivity.kt", "r") as f:
    content = f.read()

pattern = r'PopupWindow\(\s*scroll,\s*dp\(\d+\),\s*minOf[^,]+,\s*true\s*\)'
replacement = r'PopupWindow(scroll, anchor.width, minOf(options.size * dp(44) + dp(10), dp(320)), true)'
content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/easy_billing/EditProductActivity.kt", "w") as f:
    f.write(content)
