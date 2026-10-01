import re

def change_bg(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # We need to replace the background and padding for the Raw material toggle bar
    old_block = """                    <!-- Raw material toggle bar -->
                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="horizontal" android:gravity="center_vertical"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_inv_field_light_grey"
                        android:paddingStart="14dp" android:paddingEnd="12dp" android:paddingVertical="10dp">"""
                        
    new_block = """                    <!-- Raw material toggle bar -->
                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="horizontal" android:gravity="center_vertical"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_inv_subcard_accent_amber"
                        android:paddingHorizontal="12dp" android:paddingVertical="11dp">"""

    content = content.replace(old_block, new_block)

    with open(filepath, 'w') as f:
        f.write(content)

change_bg("app/src/main/res/layout/dialog_purchase_line.xml")
change_bg("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
