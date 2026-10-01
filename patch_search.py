with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content = f.read()

old_block = """            val seenGlobal = HashSet<String>()
            for (name in catalogNames) {
                val display = catalogDisplayNames[name] ?: name.replaceFirstChar { it.uppercaseChar() }
                val variantsForName = if (lastFetchedProduct == name) variantCache else emptyList()
                val namedVariants = CatalogAutofill.namedVariants(variantsForName)
                if (namedVariants.isEmpty()) {
                    val key = "$name||".lowercase()
                    if (seenGlobal.add(key)) {
                        rows.add(
                            SearchRow(
                                label = display,
                                tag = activity.getString(R.string.add_product_tag_global),
                                name = display,
                                brand = null,
                                variant = null
                            )
                        )
                    }
                } else {
                    for (v in namedVariants) {
                        val vKey = "$name|${v.brand.orEmpty()}|${v.variant_name.orEmpty()}".lowercase()
                        if (!seenGlobal.add(vKey)) continue
                        val parts = listOfNotNull(display, v.brand?.takeIf { it.isNotBlank() }, v.variant_name?.takeIf { it.isNotBlank() })
                        rows.add(
                            SearchRow(
                                label = parts.joinToString(" \\u2022 "),
                                tag = activity.getString(R.string.add_product_tag_global),
                                name = display,
                                brand = v.brand,
                                variant = v.variant_name
                            )
                        )
                    }
                }
            }"""

new_block = """            val seenGlobal = HashSet<String>()
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
            }"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content)
