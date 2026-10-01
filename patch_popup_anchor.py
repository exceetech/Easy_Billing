import re
import glob

files = [
    "app/src/main/java/com/example/easy_billing/AddProductActivity.kt",
    "app/src/main/java/com/example/easy_billing/PurchaseActivity.kt",
    "app/src/main/java/com/example/easy_billing/EditProductActivity.kt",
    "app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt"
]

old_sig = """    private fun showSortStylePopup(
        anchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {"""

new_sig = """    private fun showSortStylePopup(
        rawAnchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val anchor = if (rawAnchor.parent is android.widget.LinearLayout && 
            (rawAnchor.parent as android.view.View).background != null) {
            rawAnchor.parent as android.view.View
        } else rawAnchor"""

for file in files:
    with open(file, "r") as f:
        content = f.read()

    content = content.replace(old_sig, new_sig)

    with open(file, "w") as f:
        f.write(content)
