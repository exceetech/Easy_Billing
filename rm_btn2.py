def remove_btn_lines(filepath):
    with open(filepath, 'r') as f:
        lines = f.readlines()
        
    start_idx = -1
    end_idx = -1
    for i, line in enumerate(lines):
        if 'android:id="@+id/btnHsnHelp"' in line:
            # Found the ID line, go backwards to find the opening tag
            for j in range(i, -1, -1):
                if '<com.google.android.material.button.MaterialButton' in lines[j]:
                    start_idx = j
                    break
            # Go forwards to find the closing tag
            for j in range(i, len(lines)):
                if '/>' in lines[j]:
                    end_idx = j
                    break
            break
            
    if start_idx != -1 and end_idx != -1:
        del lines[start_idx:end_idx+1]
        with open(filepath, 'w') as f:
            f.writelines(lines)
        print(f"Removed from {filepath}")
    else:
        print(f"Not found or failed in {filepath}")
        
remove_btn_lines("app/src/main/res/layout/dialog_purchase_line.xml")
remove_btn_lines("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml")
