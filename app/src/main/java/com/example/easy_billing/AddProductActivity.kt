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
import com.example.easy_billing.util.CatalogAutofill
import com.example.easy_billing.util.UqcMapper
import com.google.android.material.materialswitch.MaterialSwitch
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

    // Local products grouped by lowercase name — a name can have several
    // saved variants, so this holds the *list* of this shop's own
    // products sharing that name. Nothing auto-fills from name alone; if
    // the user picks a variant that already exists here, they're prompted
    // to edit that existing product instead — see applyVariantAutofill().
    private val localVariantsByName = HashMap<String, MutableList<Product>>()

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
    private lateinit var etCategory: TextView
    private lateinit var etPrice: EditText
    private lateinit var etHsn: EditText
    private lateinit var etCgst: EditText
    private lateinit var etSgst: EditText
    private lateinit var etIgst: EditText
    private lateinit var switchTaxInclusive: MaterialSwitch
    private lateinit var switchOpeningStock: MaterialSwitch
    private lateinit var etQty: EditText
    private lateinit var etCost: EditText
    private lateinit var tvBadge: TextView

    // More-details views
    private lateinit var etVariant: AutoCompleteTextView
    // Fixed-choice fields — plain TextViews that open the same picker
    // popup as the Manage Products sort dropdown (showSortStylePopup).
    private lateinit var etUnit: TextView
    private lateinit var spinnerUqc: TextView
    private lateinit var spinnerSupplyClass: TextView
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

        // ── Fixed-choice pickers — same popup as the Manage Products sort
        //    dropdown (rounded white sheet, selected row highlighted with
        //    a green tick). Left blank (hint only) until the user picks
        //    one — normalizeUnit() still falls back to "piece" at save
        //    time if nothing was ever chosen. ──
        etUnit.setOnClickListener {
            // The field can hold a stored/catalog token ("kg"), while the
            // picker lists long forms ("kilogram") — map across so the
            // current unit actually shows as selected.
            showSortStylePopup(etUnit, units, unitDisplay(etUnit.text.toString())) { picked ->
                etUnit.text = picked
                unitUserSet = true
            }
        }

        spinnerUqc.setOnClickListener {
            showSortStylePopup(spinnerUqc, UqcMapper.ALL_UQC_DISPLAY, spinnerUqc.text.toString()) { picked ->
                spinnerUqc.text = picked
            }
        }

        // Left blank (hint "TAXABLE") until the user picks one — saveProduct()
        // still falls back to TAXABLE at save time if nothing was chosen.
        spinnerSupplyClass.setOnClickListener {
            showSortStylePopup(spinnerSupplyClass, supplyClasses, spinnerSupplyClass.text.toString()) { picked ->
                spinnerSupplyClass.text = picked
            }
        }

        // ── Category picker ──
        lifecycleScope.launch {
            val shopIdStr = shopIdSync()
            val cats = com.example.easy_billing.util.ProductCategories.dropdownFor(
                this@AddProductActivity, shopIdStr
            )
            withContext(Dispatchers.Main) {
                etCategory.setOnClickListener {
                    showSortStylePopup(etCategory, cats, etCategory.text.toString()) { picked ->
                        etCategory.text = picked
                    }
                }
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
            if (!localVariantsByName.containsKey(etName.text.toString().trim().lowercase())) {
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
        // Clearing the variant (backspace to empty, or the "Add another
        // variant" action) means there's no longer a specific variant
        // chosen — wipe any statutory fields that were auto-filled for the
        // previous selection so nothing stale gets saved by mistake.
        etVariant.addTextChangedListener {
            if (etVariant.text.isNullOrBlank()) resetAutofilledFields()
        }

        findViewById<View>(R.id.btnSave).setOnClickListener { saveProduct() }
    }

    // ============================================================
    /**
     * Runs when the product NAME is picked/typed. Deliberately touches NO
     * form field — only the catalog badge and the Variant dropdown's
     * contents. Every other field (category, unit, price, HSN, GST...)
     * fills in only once the user explicitly picks a variant — see
     * applyVariantAutofill().
     */
    private fun tryAutofill() {
        val key = etName.text.toString().trim().lowercase()
        val matches = localVariantsByName[key]
        if (matches.isNullOrEmpty()) {
            tvBadge.text = "New to catalog"
            etVariant.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_ep, emptyList<String>()))
            return
        }
        tvBadge.text = "In catalog"
        refreshVariantAdapter(key)
    }

    /**
     * Merges this shop's own saved variants for [nameKey] with the global
     * catalog's variants (if already fetched) into the Variant dropdown,
     * so tapping the field shows every variant that actually belongs to
     * this product name — not a guess.
     */
    private fun refreshVariantAdapter(nameKey: String) {
        val localNames = localVariantsByName[nameKey].orEmpty()
            .mapNotNull { it.variant?.trim()?.takeIf { v -> v.isNotBlank() } }
        val globalNames = variantCache.filter { it.variant_name.isNotBlank() }.map { it.variant_name }
        val merged = LinkedHashSet<String>().apply { addAll(localNames); addAll(globalNames) }
        etVariant.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_ep, merged.toList()))
    }

    /**
     * Fetches the global variants for the picked product (verified OR
     * this shop's own pending submissions) and populates the variant
     * dropdown. Read-only; never touches billing.
     */
    private fun fetchGlobalVariants(name: String) {
        val key = name.trim().lowercase()
        if (!catalogNames.contains(key)) {
            // Not in the catalog — drop the cache, and drop the key with it.
            // These two must always agree: leaving a stale key behind while
            // the cache is empty made the guard below short-circuit when the
            // user came back to the earlier product, so its variants never
            // reloaded and nothing auto-filled again.
            variantCache = emptyList()
            lastFetchedProduct = null
            return
        }
        // Skip if we already loaded this exact product (multiple triggers).
        // Safe because lastFetchedProduct is non-null only while variantCache
        // actually holds that product's variants.
        if (key == lastFetchedProduct) return
        lastFetchedProduct = key
        lifecycleScope.launch {
            // Single by-name call — the backend merges variants across any
            // duplicate global_products rows sharing this name.
            val variants = CatalogAutofill.fetchVariants(this@AddProductActivity, name)
            if (variants == null) {
                lastFetchedProduct = null; return@launch  // offline — allow retry
            }
            variantCache = variants
            // Only auto-apply the product-level holder row when there is
            // NOTHING to choose from — see CatalogAutofill.productLevelDefault.
            val productDefault = CatalogAutofill.productLevelDefault(variants)
            withContext(Dispatchers.Main) {
                refreshVariantAdapter(key)
                productDefault?.let { fillStatutoryFrom(it, applyUnit = true) }
            }
        }
    }

    /**
     * Runs only once the user has explicitly picked (or typed and moved
     * on from) a variant name. If that exact name+variant already exists
     * locally, surface the "already exists — edit instead?" prompt right
     * here instead of waiting until Save. Otherwise falls back to the
     * global catalog's variant data (statutory fields only) for a fresh
     * variant of this product.
     */
    private fun applyVariantAutofill() {
        val chosen = etVariant.text.toString().trim()
        if (chosen.isEmpty()) return
        val nameKey = etName.text.toString().trim().lowercase()
        val localMatch = localVariantsByName[nameKey]?.firstOrNull {
            it.variant?.trim()?.equals(chosen, true) == true
        }
        if (localMatch != null) {
            confirmEditExistingVariant(localMatch)
            return
        }
        val v = CatalogAutofill.variantNamed(variantCache, chosen) ?: return
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
            etUnit.text = v.unit
        if (etHsn.text.isNullOrBlank()) v.hsn_code?.let { etHsn.setText(it) }
        if (etHsnDesc.text.isNullOrBlank()) v.hsn_description?.let { etHsnDesc.setText(it) }
        if (spinnerUqc.text.isNullOrBlank() && !v.official_uqc.isNullOrBlank())
            UqcMapper.codeToDisplay(v.official_uqc)?.let { spinnerUqc.text = it }
        if (etCgst.text.isNullOrBlank() && v.cgst_percentage > 0) etCgst.setText(trimNum(v.cgst_percentage))
        if (etSgst.text.isNullOrBlank() && v.sgst_percentage > 0) etSgst.setText(trimNum(v.sgst_percentage))
        if (etCessRate.text.isNullOrBlank() && v.cess_rate > 0) etCessRate.setText(trimNum(v.cess_rate))
        // IGST recomputes from CGST+SGST via the text watcher.
    }

    /**
     * Wipes every field that [fillStatutoryFrom] may have auto-filled for a
     * variant, leaving only the product name/badge intact. Called whenever
     * the Variant field goes back to empty, since at that point there is no
     * longer a specific variant selection backing those values.
     */
    private fun resetAutofilledFields() {
        unitUserSet = false
        etUnit.setText("")
        etHsn.setText("")
        etHsnDesc.setText("")
        spinnerUqc.text = ""
        etCgst.setText("")
        etSgst.setText("")
        etCessRate.setText("")
        // etIgst recomputes to blank/0 automatically via its text watcher.
    }

    // ============================================================
    private fun loadNameSuggestions() {
        lifecycleScope.launch {
            val names = LinkedHashSet<String>()
            try {
                val locals = ProductRepository.get(this@AddProductActivity).getAllForCurrentShop()
                for (p in locals) {
                    localVariantsByName.getOrPut(p.name.trim().lowercase()) { mutableListOf() }.add(p)
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
                        R.layout.item_dropdown_ep,
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
                val repo = ProductRepository.get(this@AddProductActivity)

                // Via the repository, not the DAO directly: it capitalises the
                // name the same way the insert below does, so "basmati rice"
                // correctly matches the stored "Basmati Rice" instead of
                // slipping past and hitting the unique index.
                //
                // Exact match first, then branch on isActive. The query
                // deliberately matches hidden rows too (ProductRepository
                // .upsert depends on that — a purchase line naming a hidden
                // product must update it, not insert a second row into the
                // unique (shop_id, name, variant) slot). So the *caller* has
                // to tell the two cases apart; a second inactive-only query
                // after this one could never be reached.
                //
                // A case-different near-duplicate ("BASMATI RICE" vs the
                // stored "Basmati Rice") is a separate question, handled
                // below — it must not be resolved by this lookup, or an edit
                // could land on the wrong row.
                val existing = repo.getByNameAndVariant(name, variant)
                if (existing != null && existing.isActive) {
                    withContext(Dispatchers.Main) { confirmEditExistingVariant(existing) }
                    return@launch
                }

                // The typed values, applied over whichever saved row we
                // matched. Declared once: both the exact-match and the
                // case-different paths below hand it to the restore dialog,
                // and an earlier version of this only built it on one of
                // them — so "Restore with my details" quietly restored the
                // old values instead.
                fun restoredFrom(base: Product) = base.copy(
                    isActive = true,
                    price = price,
                    unit = unit,
                    // trackInventory is deliberately NOT taken from the
                    // opening-stock switch. A restore leaves stock alone, so
                    // flipping tracking off here would strand whatever the
                    // product still holds — untracked, unsellable and
                    // invisible in Manage Products. It is the same invariant
                    // Edit Product guards with "Reduce stock to 0 before
                    // turning inventory off"; change it there.
                    trackInventory = base.trackInventory,
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
                    isTaxInclusive = isTaxInclusive
                )

                // Hidden: the row still occupies the unique slot, so a fresh
                // insert would fail and that name would be unusable forever.
                // Offer to bring it back instead.
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        confirmRestoreDeactivated(existing, restoredFrom(existing))
                    }
                    return@launch
                }

                // No exact match — but the unique index is case-*sensitive*,
                // so "BASMATI RICE" would insert happily next to "Basmati
                // Rice" and leave two rows for one product. Catch it here and
                // send the user to the one that already exists.
                val clash = repo.findConflictIgnoringCase(name, variant)
                if (clash != null) {
                    withContext(Dispatchers.Main) {
                        if (clash.isActive) confirmEditExistingVariant(clash)
                        else confirmRestoreDeactivated(clash, restoredFrom(clash))
                    }
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
     * "This product was hidden — restore it?"
     *
     * Reached when the name + variant matches a deactivated product. The
     * unique (shop_id, name, variant) index means we cannot simply insert a
     * new row, so restoring is the only way that name becomes usable again;
     * without this the user would hit a raw constraint error with no way out.
     *
     * Opening stock is deliberately ignored here. Hiding is a soft delete,
     * so the product still holds whatever stock it had; adding the typed
     * quantity on top would silently inflate it, and treating it as a new
     * total would need a stock-adjustment entry to keep the ledger honest.
     * Stock goes in through "Record a purchase", which is the only path
     * that creates a proper batch — the dialog says so.
     *
     * @param hidden   the row as stored today
     * @param restored the same row with the values just typed applied
     */
    private fun confirmRestoreDeactivated(
        hidden: Product,
        restored: Product
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_variant_restore, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val label = hidden.variant?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "${hidden.name} ($it)" } ?: hidden.name
        val stockNote = if (switchOpeningStock.isChecked)
            "\n\nIts existing stock is kept as-is — add more from \"Record a purchase\"."
        else ""
        view.findViewById<TextView>(R.id.tvRestoreMessage).text =
            "$label was hidden earlier. Restore it to use this name again.$stockNote"

        // The whole write path for a restore. Local to this dialog so both
        // buttons go through exactly the same steps and can't drift apart.
        fun restore(product: Product) {
            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    db.productDao().update(product)
                    db.productDao().activate(product.id)
                    rememberCategoryIfNew(product.category, shopIdSync())
                    pushRestoredProduct(product)
                    withContext(Dispatchers.Main) {
                        toast("Product restored")
                        finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toast("Could not restore: ${e.message ?: "unknown error"}")
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btnRestoreUpdate).setOnClickListener { restore(restored) }
        // Keeps every saved value; only flips it back to active.
        view.findViewById<View>(R.id.btnRestoreKeep).setOnClickListener {
            restore(hidden.copy(isActive = true))
        }
        view.findViewById<View>(R.id.btnRestoreCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * "Variant already exists. Edit instead?" — mirrors the old
     * AddProducts flow. Confirm → EditProductActivity; the other option
     * stays on this screen but clears the Variant box (rather than just
     * dismissing) so the user can type a genuinely new variant name
     * instead of re-triggering this same prompt.
     */
    private fun confirmEditExistingVariant(product: Product) {
        val variantName = product.variant?.trim()
        val view = layoutInflater.inflate(R.layout.dialog_variant_exists, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvVariantExistsMessage).text = if (!variantName.isNullOrBlank())
            getString(R.string.variant_already_exists_message, product.name, variantName)
        else
            getString(R.string.variant_already_exists_message_no_variant, product.name)

        var editChosen = false
        view.findViewById<View>(R.id.btnEditExisting).setOnClickListener {
            editChosen = true
            dialog.dismiss()
            startActivity(
                Intent(this, EditProductActivity::class.java)
                    .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
            )
            finish()
        }
        view.findViewById<View>(R.id.btnAddAnotherVariant).setOnClickListener {
            dialog.dismiss()
        }
        // Dismissing any other way — tapping outside, back press — leaves a
        // stale variant name sitting in the field with no real selection
        // behind it, so treat it the same as "Add another variant".
        dialog.setOnDismissListener {
            if (!editChosen) {
                etVariant.setText("")
                etVariant.requestFocus()
            }
        }
        dialog.show()
    }

    /**
     * Mirrors a restore to the backend.
     *
     * `activate()` leaves the row pending so SyncManager pushes the
     * *isActive* flip, but the price / GST / HSN the user just typed would
     * otherwise stay local. Every other edit path in the app pushes inline
     * right after its local write (see
     * ProductRepository.updateSalesFieldsOnly) — this keeps that contract.
     *
     * Fire-and-forget: the local row is authoritative and the purchase is
     * already saved, so a network failure must not surface as an error.
     */
    private suspend fun pushRestoredProduct(product: Product) {
        val serverId = product.serverId ?: return
        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        runCatching {
            RetrofitClient.api.updateShopProduct(
                token = "Bearer $token",
                serverId = serverId,
                request = AddProductRequest(
                    name = product.name,
                    variant_name = product.variant?.ifBlank { null },
                    unit = product.unit ?: "piece",
                    price = product.price,
                    track_inventory = product.trackInventory,
                    initial_stock = null,   // stock is never touched by a restore
                    cost_price = null,
                    hsn_code = product.hsnCode?.takeIf { it.isNotBlank() },
                    default_gst_rate = product.defaultGstRate,
                    cgst_percentage = product.cgstPercentage,
                    sgst_percentage = product.sgstPercentage,
                    igst_percentage = product.igstPercentage,
                    official_uqc = product.officialUqc,
                    hsn_description = product.hsnDescription,
                    cess_rate = product.cessRate,
                    supply_classification = product.supplyClassification,
                    category = product.category,
                    is_purchased = product.isPurchased,
                    is_tax_inclusive = product.isTaxInclusive
                )
            )
        }
    }

    /* ---------------- Picker popup — same visual as
       ManageProductsActivity.showSortPopup() / EditProductActivity. ---------------- */

    private fun showSortStylePopup(
        anchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val green = android.graphics.Color.parseColor("#0F6E56")
        val ink = android.graphics.Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val currentIndex = options.indexOf(current).coerceAtLeast(-1)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        // Fit the sheet to the room actually available, and flip it above the
        // field when there isn't enough space below — this form is long, so a
        // picker near the bottom would otherwise be clipped by the screen
        // edge. Same treatment as PurchaseActivity.showSortStylePopup().
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val windowH = anchor.rootView.height
        val gap = dp(6)
        val margin = dp(12)
        val spaceBelow = windowH - (loc[1] + anchor.height) - gap - margin
        val spaceAbove = loc[1] - gap - margin
        val wanted = minOf(options.size * dp(44) + dp(10), dp(320))
        val showAbove = spaceBelow < wanted && spaceAbove > spaceBelow
        val available = (if (showAbove) spaceAbove else spaceBelow).coerceAtLeast(dp(88))
        val height = minOf(wanted, available)

        val popup = android.widget.PopupWindow(
            scroll, dp(200), height, true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEachIndexed { i, label ->
            val isSel = i == currentIndex
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) green else ink)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(tv)
            if (isSel) {
                row.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(green)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                onPick(label)
                popup.dismiss()
            }
            container.addView(row)
        }

        if (showAbove) {
            // showAsDropDown anchors below, so offset back up by the anchor's
            // own height plus the sheet's.
            popup.showAsDropDown(anchor, 0, -(anchor.height + height + gap))
        } else {
            popup.showAsDropDown(anchor, 0, gap)
        }
    }

    // ============================================================
    /** Inverse of [normalizeUnit] — storage token to picker label. */
    private fun unitDisplay(unit: String?): String = when (unit?.trim()?.lowercase()) {
        "kg", "kilogram" -> "kilogram"
        "ml", "millilitre" -> "millilitre"
        "l", "ltr", "liter", "litre" -> "litre"
        "g", "gram" -> "gram"
        "piece" -> "piece"
        else -> unit.orEmpty()
    }

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
