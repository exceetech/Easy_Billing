import re

def fix_category(filepath):
    with open(filepath, "r") as f:
        content = f.read()

    # Find the exact category linear layout line
    content = content.replace('android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"\n                            android:orientation="vertical">\n                            <TextView\n                                android:layout_width="wrap_content" android:layout_height="wrap_content"\n                                android:layout_marginBottom="5dp"\n                                android:text="@string/xml_purchase_line_dialog_category_label"', 
    'android:layout_width="match_parent" android:layout_height="wrap_content"\n                            android:orientation="vertical">\n                            <TextView\n                                android:layout_width="wrap_content" android:layout_height="wrap_content"\n                                android:layout_marginBottom="5dp"\n                                android:text="@string/xml_purchase_line_dialog_category_label"')
    
    with open(filepath, "w") as f:
        f.write(content)

fix_category("app/src/main/res/layout/dialog_purchase_line.xml")
fix_category("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
