with open("app/src/main/java/com/example/easy_billing/util/CatalogAutofill.kt", "r") as f:
    content = f.read()

old_code = """    fun productLevelDefault(variants: List<VariantResponse>): VariantResponse? {
        if (namedVariants(variants).isNotEmpty()) return null
        return variants.firstOrNull { it.variant_name.isBlank() }
    }"""

new_code = """    fun productLevelDefault(variants: List<VariantResponse>): VariantResponse? {
        val named = namedVariants(variants)
        if (named.isEmpty()) return variants.firstOrNull { it.variant_name.isBlank() }
        if (named.size == 1) return named.first()
        return null
    }"""

content = content.replace(old_code, new_code)

with open("app/src/main/java/com/example/easy_billing/util/CatalogAutofill.kt", "w") as f:
    f.write(content)
