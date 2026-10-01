with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

old_add = "        etProductSearch.setAdapter(searchAdapter)"
new_add = """        val searchWrap = findViewById<android.view.View>(R.id.llSearchBarWrap)
        searchWrap.post {
            etProductSearch.dropDownWidth = searchWrap.width
        }
        etProductSearch.setAdapter(searchAdapter)"""
if old_add in content:
    content = content.replace(old_add, new_add)
    with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
        f.write(content)
else:
    print("AddProduct: setAdapter not found")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content2 = f.read()

new_pld = """        val searchWrap = view.findViewById<android.view.View>(R.id.llSearchBarWrapLine)
        searchWrap.post {
            etProductSearch.dropDownWidth = searchWrap.width
        }
        etProductSearch.setAdapter(searchAdapter)"""
if old_add in content2:
    content2 = content2.replace(old_add, new_pld)
    with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
        f.write(content2)
else:
    print("PurchaseLine: setAdapter not found")
