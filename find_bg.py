import re

with open("app/src/main/res/layout/activity_purchase.xml", "r") as f:
    content = f.read()

lines = content.splitlines()
for i, line in enumerate(lines):
    if "Add Item" in line or "add item" in line.lower():
        print("Found at line", i)
        for j in range(max(0, i-10), min(len(lines), i+10)):
            print(lines[j])
