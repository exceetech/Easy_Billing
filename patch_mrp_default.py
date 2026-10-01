import re

def patch_file(filepath):
    with open(filepath, "r") as f:
        content = f.read()
    
    old_switch = """                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/switchTaxInclusive"
                            style="@style/EpSwitch"
                            android:checked="true"/>"""
    new_switch = """                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/switchTaxInclusive"
                            style="@style/EpSwitch"
                            android:checked="false"/>"""
                            
    content = content.replace(old_switch, new_switch)
    
    with open(filepath, "w") as f:
        f.write(content)

patch_file("app/src/main/res/layout/activity_add_product.xml")
patch_file("app/src/main/res/layout-sw600dp/activity_add_product.xml")
