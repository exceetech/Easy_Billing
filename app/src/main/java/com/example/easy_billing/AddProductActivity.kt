package com.example.easy_billing

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.GlobalProductRegisterRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VariantResponse
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.UqcMapper
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified "Add product" screen — the simplified replacement for the
 * old catalog-list + dialog flow (non-purchased products). One form
 * captures the full product master (incl. GSTR-1 fields under "More
 * details") plus optional opening stock. Opening stock is recorded as
 * a manual batch (no input-tax credit); for ITC the user is directed
 * to "Record a purchase".
 */
class AddProductActivity : BaseActivity() {

    private lateinit var db: AppDatabase

    // Local products keyed by lowercase name → used for autofill of a
    // previously-added product's HSN / tax / price / classification.
    private val localByName = HashMap<String, Product>()

    // Lowercased catalog product names — used only to gate the variant
    // fetch to products actually in the global catalog. The fetch itself
    // is by name (backend merges any duplicate rows), so no id map needed.
    private val catalogNames = HashSet<String>()
    private var variantCache: List<VariantResponse> = emptyList()
    // Guards against re-fetching variants for the same product on the
    // multiple triggers (name pick + focus-loss + cold-start).
    private var lastFetchedProduct: String? = null
    // True once the user deliberately picks a unit — autofill then leaves
    // the unit alone instead of overwriting the manual choice.
    private var unitUserSet = false

    private val units = listOf("piece", "kilogram", "litre", "gram", "millilitre")
    private val supplyClasses = listOf("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST")

    // Views
    private lateinit var etName: AutoCompleteTextView
    private lateinit var etCategory: AutoCompleteTextView
    private lateinit var etPrice: EditText
    private lateinit var etHsn: EditText
    private lateinit var etCgst: EditText
    private lateinit var etSgst: EditText
    private lateinit var etIgst: EditText
    private lateinit var switchTaxInclusive: SwitchMaterial
    private lateinit var switchOpeningStock: SwitchMaterial
    private lateinit var etQty: EditText
    private lateinit var etCost: EditText
    private lateinit var tvBadge: TextView

    // More-details views
    private lateinit var etVariant: AutoCompleteTextView
    private lateinit var etUnit: AutoCompleteTextView
    private lateinit var spinnerUqc: AutoCompleteTextView
    private lateinit var spinnerSupplyClass: AutoCompleteTextView
    private lateinit var etHsnDesc: EditText
    private lateinit var etCessRate: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_product)

        setupToolbar(R.id.toolbar)

        db = AppDatabase.getDatabase(this)

        etName = findViewById(R.id.etName)
        etCategory = findViewById(R.id.etCategory)
        etPrice = findViewById(R.id.etPrice)
        etHsn = findViewById(R.id.etHsn)
        etCgst = findViewById(R.id.etCgst)
        etSgst = findViewById(R.id.etSgst)
        etIgst = findViewById(R.id.etIgst)
        switchTaxInclusive = findViewById(R.id.switchTaxInclusive)
        switchOpeningStock = findViewById(R.id.switchOpeningStock)
        etQty = findViewById(R.id.etQty)
        etCost = findViewById(R.id.etCost)
        tvBadge = findViewById(R.id.tvCatalogBadge)

        etVariant = findViewById(R.id.etVariant)
        etUnit = findViewById(R.id.etUnit)
        spinnerUqc = findViewById(R.id.spinnerUqc)
        spinnerSupplyClass = findViewById(R.id.spinnerSupplyClass)
        etHsnDesc = findViewById(R.id.etHsnDesc)
        etCessRate = findViewById(R.id.etCessRate)

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }

        // ── Opening-stock reveal ──
        val groupOpeningStock = findViewById<View>(R.id.groupOpeningStock)
        switchOpeningStock.setOnCheckedChangeListener { _, checked ->
            groupOpeningStock.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── IGST auto = CGST + SGST (read-only) ──
        val recomputeIgst = {
            val total = (etCgst.text.toString().toDoubleOrNull() ?: 0.0) +
                    (etSgst.text.toString().toDoubleOrNull() ?: 0.0)
            etIgst.setText(if (total > 0) trimNum(total) + "%" else "0%")
        }
        etCgst.addTextChangedListener { recomputeIgst() }
        etSgst.addTextChangedListener { recomputeIgst() }

        // ── Dropdown adapters ──
        etUnit.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, units))
        etUnit.setText("piece", false)
        etUnit.setOnClickListener { etUnit.showDropDown() }
        etUnit.setOnItemClickListener { _, _, _, _ -> unitUserSet = true }

        spinnerUqc.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, UqcMapper.ALL_UQC_DISPLAY)
        )
        spinnerUqc.setOnClickListener { spinnerUqc.showDropDown() }

        spinnerSupplyClass.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, supplyClasses)
        )
        spinnerSupplyClass.setText("TAXABLE", false)
        spinnerSupplyClass.setOnClickListener { spinnerSupplyClass.showDropDown() }

        // ── Category dropdown ──
        lifecycleScope.launch {
            val shopIdStr = shopIdSync()
            val cats = com.example.easy_billing.util.ProductCategories.dropdownFor(
                this@AddProductActivity, shopIdStr
            )
            withContext(Dispatchers.Main) {
                etCategory.setAdapter(
                    ArrayAdapter(this@AddProductActivity, android.R.layout.simple_list_item_1, cats)
                )
                etCategory.setOnClickListener { etCategory.showDropDown() }
            }
        }

        // ── Name suggestions (local + backend catalog) + autofill map ──
        loadNameSuggestions()

        etName.setOnItemClickListener { _, _, _, _ ->
            tryAutofill()
            fetchGlobalVariants(etName.text.toString().trim())
        }
        // Also autofill when the user types a full name and moves on
        // (without tapping a suggestion).
        etName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                tryAutofill()
                fetchGlobalVariants(etName.text.toString().trim())
            }
        }
        etName.addTextChangedListener {
            if (!localByName.containsKey(etName.text.toString().trim().lowercase())) {
                tvBadge.text = "New to catalog"
            }
        }

        // ── Variant dropdown (from global catalog) ──
        etVariant.setOnClickListener { etVariant.showDropDown() }
        etVariant.setOnItemClickListener { _, _, _, _ -> applyVariantAutofill() }
        // Also autofill when a matching variant is typed and focus leaves
        // (without tapping a suggestion).
        etVariant.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyVariantAutofill()
        }

        findViewById<View>(R.id.btnSave).setOnClickListener { saveProduct() }
    }

    // ============================================================
    private fun tryAutofill() {
        val key = etName.text.toString().trim().lowercase()
        val p = localByName[key] ?: run { tvBadge.text = "New to catalog"; return }
        tvBadge.text = "In catalog"
        if (etPrice.text.isNullOrBlank()) etPrice.setText(trimNum(p.price))
        if (etHsn.text.isNullOrBlank()) p.hsnCode?.let { etHsn.setText(it) }
        if (etCgst.text.isNullOrBlank() && p.cgstPercentage > 0) etCgst.setText(trimNum(p.cgstPercentage))
        if (etSgst.text.isNullOrBlank() && p.sgstPercentage > 0) etSgst.setText(trimNum(p.sgstPercentage))
        switchTaxInclusive.isChecked = p.isTaxInclusive
        if (p.category.isNotBlank() && etCategory.text.isNullOrBlank()) etCategory.setText(p.category, false)
        // More-details
        if (etVariant.text.isNullOrBlank()) p.variant?.let { etVariant.setText(it) }
        if (!unitUserSet) p.unit?.let { etUnit.setText(it, false) }
        if (etHsnDesc.text.isNullOrBlank()) p.hsnDescription?.let { etHsnDesc.setText(it) }
        if (p.cessRate > 0 && etCessRate.text.isNullOrBlank()) etCessRate.setText(trimNum(p.cessRate))
        if (p.supplyClassification.isNotBlank()) spinnerSupplyClass.setText(p.supplyClassification, false)
    }

    /**
     * Fetches the global variants for the picked product (verified OR
     * this shop's own pending submissions) and populates the variant
     * dropdown. Read-only; never touches billing.
     */
    private fun fetchGlobalVariants(name: String) {
        val key = name.trim().lowercase()
        if (!catalogNames.contains(key)) { variantCache = emptyList(); return }
        // Skip if we already loaded this exact product (multiple triggers).
        if (key == lastFetchedProduct) return
        lastFetchedProduct = key
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            // Single by-name call — the backend merges variants across any
            // duplicate global_products rows sharing this name.
            val raw = try {
                RetrofitClient.api.getVariantsByName(token, name.trim())
            } catch (_: Exception) {
                lastFetchedProduct = null; return@launch  // allow retry if offline
            }
            // De-dupe by lowercased variant name (defensive against legacy dups).
            val seen = HashSet<String>()
            val variants = raw.filter { seen.add(it.variant_name.trim().lowercase()) }
            variantCache = variants
            val named = variants.filter { it.variant_name.isNotBlank() }
            // Product-level statutory holder: prefer the "" holder row, else
            // fall back to any variant (HSN/GST are product-level facts). Used
            // so variant-less products still autofill HSN/GST on name-pick.
            val productDefault = variants.firstOrNull { it.variant_name.isBlank() }
                ?: variants.firstOrNull()
            withContext(Dispatchers.Main) {
                etVariant.setAdapter(
                    ArrayAdapter(
                        this@AddProductActivity,
                        android.R.layout.simple_list_item_1,
                        named.map { it.variant_name }
                    )
                )
                productDefault?.let {
                    fillStatutoryFrom(it, applyUnit = it.variant_name.isBlank())
                }
            }
        }
    }

    /**
     * When a known variant is chosen, fill ONLY the statutory tax fields
     * and only when empty — never price or the tax-inclusive toggle, and
     * never overwriting something the user already typed.
     */
    private fun applyVariantAutofill() {
        val chosen = etVariant.text.toString().trim()
        val v = variantCache.firstOrNull { it.variant_name.equals(chosen, true) } ?: return
        fillStatutoryFrom(v, applyUnit = true)
    }

    /**
     * Fills the statutory fields (HSN, description, UQC, GST split, cess)
     * from a global variant, only into fields the user hasn't filled.
     * [applyUnit] controls whether the unit is also applied — true for a
     * picked variant or the product-level "" holder, false for an
     * arbitrary named variant used only as a product-level default.
     */
    private fun fillStatutoryFrom(v: VariantResponse, applyUnit: Boolean) {
        if (applyUnit && !unitUserSet && !v.unit.isNullOrBlank() && !v.unit.equals("unit", true))
            etUnit.setText(v.unit, false)
        if (etHsn.text.isNullOrBlank()) v.hsn_code?.let { etHsn.setText(it) }
        if (etHsnDesc.text.isNullOrBlank()) v.hsn_description?.let { etHsnDesc.setText(it) }
        if (spinnerUqc.text.isNullOrBlank() && !v.official_uqc.isNullOrBlank())
            UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.setText(it, false) }
        if (etCgst.text.isNullOrBlank() && v.cgst_percentage > 0) etCgst.setText(trimNum(v.cgst_percentage))
        if (etSgst.text.isNullOrBlank() && v.sgst_percentage > 0) etSgst.setText(trimNum(v.sgst_percentage))
        if (etCessRate.text.isNullOrBlank() && v.cess_rate > 0) etCessRate.setText(trimNum(v.cess_rate))
        // IGST recomputes from CGST+SGST via the text watcher.
    }

    // ============================================================
    private fun loadNameSuggestions() {
        lifecycleScope.launch {
            val names = LinkedHashSet<String>()
            try {
                val locals = ProductRepository.get(this@AddProductActivity).getAllForCurrentShop()
                for (p in locals) {
                    localByName[p.name.trim().lowercase()] = p
                    names.add(p.name)
                }
            } catch (_: Exception) { /* offline-safe */ }

            try {
                val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
                if (!token.isNullOrEmpty()) {
                    RetrofitClient.api.getCatalog(token).forEach {
                        names.add(it.name)
                        catalogNames.add(it.name.trim().lowercase())
                    }
                }
            } catch (_: Exception) { /* best-effort */ }

            withContext(Dispatchers.Main) {
                etName.setAdapter(
                    ArrayAdapter(
                        this@AddProductActivity,
                        android.R.layout.simple_list_item_1,
                        names.toList()
                    )
                )
                // If the user already picked/typed a name before the
                // catalog finished loading, retry the variant fetch now
                // that catalogNames is populated.
                val typed = etName.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) fetchGlobalVariants(typed)
            }
        }
    }

    // ============================================================
    private fun saveProduct() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("Enter product name"); etName.requestFocus(); return
        }
        val price = etPrice.text.toString().toDoubleOrNull()
        if (price == null || price <= 0) {
            toast("Enter a valid selling price"); etPrice.requestFocus(); return
        }

        val variant = normalizeVariant(etVariant.text.toString())
        val unit = normalizeUnit(etUnit.text.toString())
        val hsnCode = etHsn.text.toString().trim()
        val cgstPct = etCgst.text.toString().toDoubleOrNull() ?: 0.0
        val sgstPct = etSgst.text.toString().toDoubleOrNull() ?: 0.0
        val igstPct = cgstPct + sgstPct
        val isTaxInclusive = switchTaxInclusive.isChecked
        val categoryVal = etCategory.text.toString().trim()

        val officialUqcVal = UqcMapper.displayToCode(spinnerUqc.text.toString())
        val hsnDescVal = etHsnDesc.text.toString().trim().ifBlank { null }
        val cessRateVal = etCessRate.text.toString().toDoubleOrNull() ?: 0.0
        val supplyClassVal = spinnerSupplyClass.text.toString().trim().ifBlank { "TAXABLE" }

        val withStock = switchOpeningStock.isChecked
        val stockQty = etQty.text.toString().toDoubleOrNull() ?: 0.0
        val costPrice = etCost.text.toString().toDoubleOrNull() ?: 0.0
        if (withStock && stockQty <= 0) {
            toast("Enter opening stock quantity"); etQty.requestFocus(); return
        }

        lifecycleScope.launch {
            val storeInfo = db.storeInfoDao().get()
            val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()
            if (isGstEnabled && hsnCode.isBlank()) {
                withContext(Dispatchers.Main) {
                    toast("HSN Code is mandatory for GST billing")
                    etHsn.error = "Required"
                }
                return@launch
            }

            try {
                val shopId = shopIdSync()
                val validShopIds = ProductRepository.get(this@AddProductActivity).getValidShopIds()
                val existing = db.productDao().getByNameAndVariant(name, variant, validShopIds)
                if (existing != null) {
                    withContext(Dispatchers.Main) { confirmEditExistingVariant(existing) }
                    return@launch
                }

                val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)

                // Best-effort server registration.
                var serverId: Int? = null
                if (!token.isNullOrEmpty()) {
                    runCatching {
                        RetrofitClient.api.addProductToShop(
                            token,
                            AddProductRequest(
                                name = name,
                                variant_name = variant,
                                unit = unit,
                                price = price,
                                track_inventory = withStock,
                                initial_stock = if (withStock) stockQty else null,
                                cost_price = if (withStock) costPrice else null,
                                hsn_code = hsnCode.ifBlank { null },
                                default_gst_rate = igstPct,
                                cgst_percentage = cgstPct,
                                sgst_percentage = sgstPct,
                                igst_percentage = igstPct,
                                official_uqc = officialUqcVal,
                                hsn_description = hsnDescVal,
                                cess_rate = cessRateVal,
                                supply_classification = supplyClassVal,
                                category = categoryVal,
                                is_purchased = false,
                                is_tax_inclusive = isTaxInclusive
                            )
                        )
                    }.onSuccess { serverId = it.product_id }

                    // Feed the global variant queue (statutory fields only,
                    // never price). This is the single writer of unverified
                    // GlobalProductVariant rows. Needed here because the
                    // inline serverId above means SyncManager will skip this
                    // row and never register it.
                    runCatching {
                        RetrofitClient.api.registerGlobalProduct(
                            token,
                            GlobalProductRegisterRequest(
                                name = name,
                                variant = variant,
                                unit = unit,
                                hsn_code = hsnCode.ifBlank { null },
                                hsn_description = hsnDescVal,
                                official_uqc = officialUqcVal,
                                default_gst_rate = igstPct,
                                cgst_percentage = cgstPct,
                                sgst_percentage = sgstPct,
                                igst_percentage = igstPct,
                                cess_rate = cessRateVal
                            )
                        )
                    }
                }

                val newId = db.productDao().insert(
                    Product(
                        name = capitalizeFirst(name),
                        variant = variant,
                        unit = unit,
                        price = price,
                        trackInventory = withStock,
                        serverId = serverId,
                        isActive = true,
                        isCustom = true,
                        hsnCode = hsnCode.ifBlank { null },
                        defaultGstRate = igstPct,
                        cgstPercentage = cgstPct,
                        sgstPercentage = sgstPct,
                        igstPercentage = igstPct,
                        officialUqc = officialUqcVal,
                        hsnDescription = hsnDescVal,
                        cessRate = cessRateVal,
                        supplyClassification = supplyClassVal,
                        category = categoryVal,
                        shopId = shopId,
                        isTaxInclusive = isTaxInclusive,
                        isPurchased = false
                    )
                ).toInt()

                rememberCategoryIfNew(categoryVal, shopId)

                if (withStock) {
                    InventoryManager.addStock(db, newId, stockQty, costPrice)
                    runCatching { SyncManager(this@AddProductActivity).syncInventory() }
                }

                withContext(Dispatchers.Main) {
                    toast("Product added")
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Could not save: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * "Variant already exists. Edit instead?" — mirrors the old
     * AddProducts flow. Confirm → EditProductActivity; cancel stays.
     */
    private fun confirmEditExistingVariant(product: Product) {
        val label = product.variant?.takeIf { it.isNotBlank() } ?: product.name
        AlertDialog.Builder(this)
            .setTitle(R.string.variant_already_exists_title)
            .setMessage(getString(R.string.variant_already_exists_message, label))
            .setPositiveButton(R.string.action_edit) { d, _ ->
                d.dismiss()
                startActivity(
                    Intent(this, EditProductActivity::class.java)
                        .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
                )
                finish()
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    // ============================================================
    private fun normalizeUnit(unit: String?): String = when (unit?.lowercase()) {
        "piece" -> "piece"
        "kilogram", "kg" -> "kg"
        "litre", "liter", "l", "ltr" -> "litre"
        "gram", "g" -> "gram"
        "millilitre", "ml" -> "ml"
        else -> "piece"
    }

    private suspend fun shopIdSync(): String {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        return try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            prefs.getInt("SHOP_ID", 0).toString()
        }
    }

    private suspend fun rememberCategoryIfNew(category: String, shopId: String) {
        val name = category.trim()
        if (name.isEmpty()) return
        if (com.example.easy_billing.util.ProductCategories.PREDEFINED.any { it.equals(name, true) }) return
        if (name.equals(com.example.easy_billing.util.ProductCategories.UNCATEGORIZED, true)) return
        if (db.productCategoryDao().getByName(name, shopId) == null) {
            db.productCategoryDao().insertIgnore(
                com.example.easy_billing.db.ProductCategory(shopId = shopId, name = name)
            )
        }
    }

    private fun capitalizeFirst(value: String): String =
        value.trim().split(Regex("\\s+")).joinToString(" ") { w ->
            if (w.isEmpty()) w else w.first().uppercaseChar() + w.drop(1)
        }

    /**
     * Canonical variant normalization — MUST mirror the backend
     * `normalize_variant` (collapse whitespace, lowercase, capitalize the
     * first character) so local and global variant strings match exactly.
     */
    private fun normalizeVariant(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().split(Regex("\\s+")).joinToString(" ")
        if (cleaned.isEmpty()) return null
        return cleaned.lowercase().replaceFirstChar { it.uppercaseChar() }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
