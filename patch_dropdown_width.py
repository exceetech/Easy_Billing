import re
import glob

files = [
    "app/src/main/java/com/example/easy_billing/AddProductActivity.kt",
    "app/src/main/java/com/example/easy_billing/PurchaseActivity.kt",
    "app/src/main/java/com/example/easy_billing/EditProductActivity.kt",
    "app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt"
]

for file in files:
    with open(file, "r") as f:
        content = f.read()

    # The instantiation is:
    # val popup = android.widget.PopupWindow(
    #     scroll, dp(200), height, true
    # )
    # OR 
    # val popup = android.widget.PopupWindow(scroll, dp(200), height, true).apply {
    
    # We will use regex to find PopupWindow(scroll, dp(200), height, true)
    # Note: there might be newlines.
    
    pattern = r'PopupWindow\(\s*scroll,\s*dp\(\d+\),\s*height,\s*true\s*\)'
    replacement = r'PopupWindow(scroll, anchor.width, height, true)'
    content = re.sub(pattern, replacement, content)

    # Some might not have newlines, just check for dp(200) inside PopupWindow
    
    with open(file, "w") as f:
        f.write(content)
