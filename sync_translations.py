import zipfile
import xml.etree.ElementTree as ET
import json
import os

EXCEL_FILE = 'app_strings_translations.xlsx'
LANG_FOLDERS = {
    'hi': 'values-hi',
    'kn': 'values-kn',
    'ml': 'values-ml',
    'ta': 'values-ta',
    'te': 'values-te'
}

def strip_ns(tag):
    return tag.split('}', 1)[1] if '}' in tag else tag

def extract_translations_from_excel(filename):
    translations = {}
    print(f"Reading {filename}...")
    try:
        with zipfile.ZipFile(filename, 'r') as z:
            sheets = [n for n in z.namelist() if n.startswith('xl/worksheets/sheet')]
            if not sheets:
                print("Error: No worksheets found in the Excel file.")
                return translations
            
            with z.open(sheets[0]) as f:
                tree = ET.parse(f)
                root = tree.getroot()
                
                for row in root.iter():
                    if strip_ns(row.tag) == 'row':
                        row_data = {}
                        for c in row:
                            if strip_ns(c.tag) == 'c':
                                r_attr = c.get('r')
                                if not r_attr: continue
                                col_letter = "".join(filter(str.isalpha, r_attr))
                                
                                val = ""
                                if c.get('t') == 'inlineStr':
                                    for is_elem in c:
                                        if strip_ns(is_elem.tag) == 'is':
                                            for t_elem in is_elem:
                                                if strip_ns(t_elem.tag) == 't' and t_elem.text:
                                                    val += t_elem.text
                                else:
                                    for child in c:
                                        if strip_ns(child.tag) == 'v' and child.text:
                                            val = child.text
                                            break
                                row_data[col_letter] = val
                        
                        if 'A' in row_data and row_data['A'] and row_data['A'] != 'key':
                            key = row_data['A']
                            translations[key] = {
                                'en': row_data.get('C', ''),
                                'hi': row_data.get('D', ''),
                                'kn': row_data.get('E', ''),
                                'ml': row_data.get('F', ''),
                                'ta': row_data.get('G', ''),
                                'te': row_data.get('H', '')
                            }
    except Exception as e:
        print(f"Failed to read Excel file: {e}")
    return translations

def update_xml_files(translations):
    # Update regional languages
    for lang, folder in LANG_FOLDERS.items():
        file_path = f"app/src/main/res/{folder}/strings.xml"
        if not os.path.exists(file_path):
            continue
            
        try:
            tree = ET.parse(file_path)
            root = tree.getroot()
            changed = False
            
            for string_elem in root.findall('string'):
                name = string_elem.get('name')
                if name in translations:
                    new_val = translations[name].get(lang, '')
                    if new_val and new_val != string_elem.text:
                        print(f"[{lang}] Updating '{name}': '{string_elem.text}' -> '{new_val}'")
                        string_elem.text = new_val
                        changed = True
            
            if changed:
                tree.write(file_path, encoding='utf-8', xml_declaration=True)
                print(f"✅ Saved updates to {file_path}")
        except Exception as e:
            print(f"Error processing {file_path}: {e}")

    # Update English (Default)
    file_path = "app/src/main/res/values/strings.xml"
    if os.path.exists(file_path):
        try:
            tree = ET.parse(file_path)
            root = tree.getroot()
            changed = False
            for string_elem in root.findall('string'):
                name = string_elem.get('name')
                if name in translations:
                    new_val = translations[name].get('en', '')
                    if new_val and new_val != string_elem.text:
                        print(f"[en] Updating '{name}': '{string_elem.text}' -> '{new_val}'")
                        string_elem.text = new_val
                        changed = True
            if changed:
                tree.write(file_path, encoding='utf-8', xml_declaration=True)
                print(f"✅ Saved updates to {file_path}")
        except Exception as e:
            print(f"Error processing {file_path}: {e}")

if __name__ == "__main__":
    print("Starting translation sync...")
    translations = extract_translations_from_excel(EXCEL_FILE)
    if translations:
        print(f"Found {len(translations)} strings in the spreadsheet. Updating XML files...")
        update_xml_files(translations)
        print("Sync complete!")
    else:
        print("No translations found or failed to read spreadsheet.")
