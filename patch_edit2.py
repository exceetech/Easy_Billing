with open("app/src/main/java/com/example/easy_billing/EditProductActivity.kt", "r") as f:
    content = f.read()

old_code = """        val popup = android.widget.PopupWindow(
            scroll, dp(200),
            minOf(options.size * dp(44) + dp(10), dp(320)),
            true
        ).apply {"""

new_code = """        val popup = android.widget.PopupWindow(
            scroll, anchor.width,
            minOf(options.size * dp(44) + dp(10), dp(320)),
            true
        ).apply {"""

content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/EditProductActivity.kt", "w") as f:
    f.write(content)
