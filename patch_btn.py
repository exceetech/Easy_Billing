import re

def remove_btn_xml(filepath):
    with open(filepath, "r") as f:
        content = f.read()

    btn_pattern = re.compile(
        r'<com\.google\.android\.material\.button\.MaterialButton\s+android:id="@+id/btnHsnHelp"[^>]+/>',
        re.DOTALL
    )
    content = re.sub(btn_pattern, '', content)
    
    with open(filepath, "w") as f:
        f.write(content)

remove_btn_xml("app/src/main/res/layout/dialog_purchase_line.xml")
remove_btn_xml("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

content = content.replace(
    'val btnHelp = view.findViewById<MaterialButton>(R.id.btnHsnHelp)',
    '// btnHelp removed'
)
content = content.replace(
    'btnHelp.setOnClickListener { HsnHelpLauncher.open(activity) }',
    '// btnHelp.setOnClickListener removed'
)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
