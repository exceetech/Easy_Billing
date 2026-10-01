with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "r") as f:
    content = f.read()

# Add globalCatalogVariants
content = content.replace(
    "private val catalogDisplayNames = HashMap<String, String>()",
    "private val catalogDisplayNames = HashMap<String, String>()\n    private val globalCatalogVariants = mutableListOf<com.example.easy_billing.network.GlobalProductResponse>()"
)

# Update loadNameSuggestions
load_code = """                    RetrofitClient.api.getCatalog(token).forEach {
                        names.add(it.name)
                        val key = it.name.trim().lowercase()
                        catalogNames.add(key)
                        catalogDisplayNames[key] = it.name
                    }"""
new_load = """                    RetrofitClient.api.getCatalog(token).forEach {
                        names.add(it.name)
                        val key = it.name.trim().lowercase()
                        catalogNames.add(key)
                        catalogDisplayNames[key] = it.name
                        globalCatalogVariants.add(it)
                    }"""
content = content.replace(load_code, new_load)

# Update refreshProductSearchPool
pool_code = """        val seenGlobal = HashSet<String>()
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
                            tag = getString(R.string.add_product_tag_global),
                            name = display,
                            brand = null,
                            variant = null
                        )
                    )
                }
            } else {
                for (v in namedVariants) {
                    val key = "$name|${v.brand.orEmpty()}|${v.variant_name}".lowercase()
                    if (!seenGlobal.add(key)) continue
                    val parts = listOfNotNull(display, v.brand?.takeIf { it.isNotBlank() }, v.variant_name.takeIf { it.isNotBlank() })
                    rows.add(
                        SearchRow(
                            label = parts.joinToString(" • "),
                            tag = getString(R.string.add_product_tag_global),
                            name = display,
                            brand = v.brand,
                            variant = v.variant_name
                        )
                    )
                }
            }
        }"""
new_pool_code = """        val seenGlobal = HashSet<String>()
        for (v in globalCatalogVariants) {
            val display = catalogDisplayNames[v.name.trim().lowercase()] ?: v.name.replaceFirstChar { it.uppercaseChar() }
            val key = "${display}|${v.brand.orEmpty()}|${v.variant_name.orEmpty()}".lowercase()
            if (!seenGlobal.add(key)) continue
            val parts = listOfNotNull(display, v.brand?.takeIf { it.isNotBlank() }, v.variant_name?.takeIf { it.isNotBlank() })
            rows.add(
                SearchRow(
                    label = parts.joinToString(" • "),
                    tag = getString(R.string.add_product_tag_global),
                    name = display,
                    brand = v.brand,
                    variant = v.variant_name
                )
            )
        }"""
content = content.replace(pool_code, new_pool_code)

with open("app/src/main/java/com/example/easy_billing/AddProductActivity.kt", "w") as f:
    f.write(content)


# DO THE SAME FOR PurchaseLineDialog.kt
with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "r") as f:
    content2 = f.read()

content2 = content2.replace(
    "private val catalogDisplayNames = HashMap<String, String>()",
    "private val catalogDisplayNames = HashMap<String, String>()\n    private val globalCatalogVariants = mutableListOf<com.example.easy_billing.network.GlobalProductResponse>()"
)

content2 = content2.replace(load_code, new_load)
content2 = content2.replace(pool_code.replace("getString(R.string.add_product_tag_global)", "\"Global\""), new_pool_code.replace("getString(R.string.add_product_tag_global)", "\"Global\""))

with open("app/src/main/java/com/example/easy_billing/PurchaseLineDialog.kt", "w") as f:
    f.write(content2)

