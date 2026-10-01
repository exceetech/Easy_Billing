import re

with open("app/src/main/java/com/example/easy_billing/util/UqcMapper.kt", "r") as f:
    content = f.read()

patch = """        // Packing
        "box"      to "BOX",
        "boxes"    to "BOX",
        "carton"   to "BOX",
        "cartons"  to "BOX",
        "ctn"      to "BOX",
        "pack"     to "PAC",
        "packs"    to "PAC",
        "packet"   to "PAC",
        "packets"  to "PAC",
        "sachet"   to "PAC",
        "sachets"  to "PAC",
        "pouch"    to "PAC",
        "pouches"  to "PAC",
        "bottle"   to "BTL",
        "bottles"  to "BTL",
        "btl"      to "BTL",
        "jar"      to "BTL",
        "jars"     to "BTL",
        "bag"      to "BAG",
        "bags"     to "BAG",
        "sack"     to "BAG",
        "sacks"    to "BAG",
        "bundle"   to "BDL",
        "bundles"  to "BDL",
        "bunch"    to "BDL",
        "bunches"  to "BDL",
        "roll"     to "ROL",
        "rolls"    to "ROL",
        "dozen"    to "DZN",
        "doz"      to "DZN",
        "set"      to "SET",
        "sets"     to "SET",
        "pair"     to "PRS",
        "pairs"    to "PRS",
        "can"      to "CAN",
        "cans"     to "CAN",
        "tin"      to "CAN",
        "tins"     to "CAN",
        "tube"     to "TUB",
        "tubes"    to "TUB",
        "drum"     to "DRM",
        "drums"    to "DRM",
        "strip"    to "NOS",
        "strips"   to "NOS",
        "sheet"    to "NOS",
        "sheets"   to "NOS",
        "tray"     to "NOS",
        "trays"    to "NOS",
        "plate"    to "NOS",
        "plates"   to "NOS",
        "cup"      to "NOS",
        "cups"     to "NOS",
        "glass"    to "NOS",
        "glasses"  to "NOS","""

# We need to replace the // Packing section in UNIT_TO_UQC map
pattern = r'// Packing.*?\"DRM\",'

content = re.sub(pattern, patch, content, flags=re.DOTALL)

with open("app/src/main/java/com/example/easy_billing/util/UqcMapper.kt", "w") as f:
    f.write(content)
