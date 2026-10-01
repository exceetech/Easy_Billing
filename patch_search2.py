import re

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

pattern = re.compile(r'val seenGlobal = HashSet<String>\(\).*?searchAdapter\.pool = rows', re.DOTALL)

new_block = """val seenGlobal = HashSet<String>()
            for (v in globalCatalogVariants) {
                val display = catalogDisplayNames[v.name.trim().lowercase()] ?: v.name.replaceFirstChar { it.uppercaseChar() }
                val key = "${display}|${v.brand.orEmpty()}|${v.variant_name.orEmpty()}".lowercase()
                if (!seenGlobal.add(key)) continue
                val parts = listOfNotNull(display, v.brand?.takeIf { it.isNotBlank() }, v.variant_name?.takeIf { it.isNotBlank() })
                rows.add(
                    SearchRow(
                        label = parts.joinToString(" • "),
                        tag = activity.getString(R.string.add_product_tag_global),
                        name = display,
                        brand = v.brand,
                        variant = v.variant_name
                    )
                )
            }
            searchAdapter.pool = rows"""

content = re.sub(pattern, new_block, content)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
