def move_lines(filepath):
    with open(filepath, 'r') as f:
        lines = f.readlines()
        
    start_idx = -1
    end_idx = -1
    for i, line in enumerate(lines):
        if "<!-- Eligibility -->" in line:
            start_idx = i
        # The block ends with `</LinearLayout>` at line 382.
        # Let's find the `</LinearLayout>` right before "<!-- Raw material toggle bar -->"
        if "<!-- Raw material toggle bar -->" in line and start_idx != -1:
            # We assume it ends at i-2 (skipping the empty line)
            end_idx = i - 1
            while lines[end_idx].strip() == "":
                end_idx -= 1
            break
            
    if start_idx != -1 and end_idx != -1:
        elig_block = lines[start_idx:end_idx+1]
        
        # Remove from old place
        del lines[start_idx:end_idx+1]
        
        # Find llTaxGstDetailsBody
        dest_idx = -1
        for i, line in enumerate(lines):
            if 'android:id="@+id/llTaxGstDetailsBody"' in line:
                dest_idx = i
                break
                
        # the `<LinearLayout` declaration spans multiple lines, find `>` 
        while ">" not in lines[dest_idx]:
            dest_idx += 1
            
        # Insert after `>`
        for j, block_line in enumerate(elig_block):
            lines.insert(dest_idx + 1 + j, block_line)
            
        with open(filepath, 'w') as f:
            f.writelines(lines)
        print(f"Moved in {filepath}")
    else:
        print(f"Failed in {filepath}")
        
move_lines("app/src/main/res/layout/dialog_purchase_line.xml")
move_lines("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
