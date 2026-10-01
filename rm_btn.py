import re

def remove_btn(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Use a greedy match to eat up the entire tag until />
    pattern = r'<com\.google\.android\.material\.button\.MaterialButton\s+android:id="@+id/btnHsnHelp"[\s\S]*?/>'
    new_content = re.sub(pattern, '', content)

    with open(filepath, 'w') as f:
        f.write(new_content)
        
remove_btn("app/src/main/res/layout/dialog_purchase_line.xml")
remove_btn("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
