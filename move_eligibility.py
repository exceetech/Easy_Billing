import re

def move_block(filepath):
    with open(filepath, "r") as f:
        content = f.read()

    # Find Eligibility block
    elig_pattern = re.compile(
        r'(\s*<!-- Eligibility -->\s*<TextView\s+[^>]+android:text="@string/xml_purchase_line_dialog_eligibility_label"[^>]+/>\s*<LinearLayout\s+android:id="@+id/tilEligibility"[^>]+>.*?</LinearLayout>)',
        re.DOTALL
    )
    match = elig_pattern.search(content)
    if not match:
        print(f"Eligibility not found in {filepath}")
        return
    
    elig_block = match.group(1)
    
    # Remove it from its current position
    content = content.replace(elig_block, "")
    
    # Find the target location: inside llTaxGstDetailsBody, right after its start tag
    target_pattern = r'(<LinearLayout\s+android:id="@+id/llTaxGstDetailsBody"[^>]*>)'
    
    replacement = r'\1' + elig_block
    content = re.sub(target_pattern, replacement, content, count=1)
    
    with open(filepath, "w") as f:
        f.write(content)
        
move_block("app/src/main/res/layout/dialog_purchase_line.xml")
move_block("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
