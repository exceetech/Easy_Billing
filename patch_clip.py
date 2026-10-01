with open("app/src/main/res/layout/activity_add_product.xml", "r") as f:
    content = f.read()

old_block = """                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="16dp">

                    <!-- ── Unified Name"""

new_block = """                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:clipToPadding="false"
                        android:clipChildren="false"
                        android:padding="16dp">

                    <!-- ── Unified Name"""

if old_block in content:
    content = content.replace(old_block, new_block)
    with open("app/src/main/res/layout/activity_add_product.xml", "w") as f:
        f.write(content)
else:
    print("AddProduct: Pattern not found!")

with open("app/src/main/res/layout/dialog_purchase_line.xml", "r") as f:
    content2 = f.read()

old_block2 = """                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <!-- Unified search"""

new_block2 = """                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:clipToPadding="false"
                    android:clipChildren="false"
                    android:padding="16dp">

                    <!-- Unified search"""

if old_block2 in content2:
    content2 = content2.replace(old_block2, new_block2)
    with open("app/src/main/res/layout/dialog_purchase_line.xml", "w") as f:
        f.write(content2)
else:
    print("PurchaseLine: Pattern not found!")
