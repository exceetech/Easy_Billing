package com.example.easy_billing

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.repository.ProductVerificationRepository
import com.example.easy_billing.util.AddProductDialogBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddProductsActivity : BaseActivity() {

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var db: AppDatabase
    private var catalogList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_products)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        db = AppDatabase.getDatabase(this)

        setupList()
        setupSearch()
        loadCatalogFromBackend()
    }

    // ================= LOAD CATALOG =================

    private fun loadCatalogFromBackend() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {
            try {
                val catalog = RetrofitClient.api.getCatalog("Bearer $token")
                catalogList = catalog.map { it.name }
                updateList(catalogList)
            } catch (e: Exception) {
                Toast.makeText(this@AddProductsActivity,
                    "Failed to load products", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= LIST =================

    private fun setupList() {
        val listItems = findViewById<ListView>(R.id.listItems)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )

        listItems.adapter = adapter

        listItems.setOnItemClickListener { _, _, position, _ ->

            val selected = adapter.getItem(position) ?: return@setOnItemClickListener

            if (selected == "Others") {
                showAddProductDialog(selected)
            } else {
                showVariantDialog(selected)
            }
        }
    }

    private fun showVariantDialog(productName: String) {

        lifecycleScope.launch {

            val products = db.productDao().getAll()

            val variants = products.filter { it.name == productName }

            val dialogView = layoutInflater.inflate(R.layout.dialog_variants, null)

            val tvTitle = dialogView.findViewById<TextView>(R.id.tvProductTitle)
            val listView = dialogView.findViewById<ListView>(R.id.listVariants)
            val btnAdd = dialogView.findViewById<Button>(R.id.btnAddVariant)

            tvTitle.text = productName

            // Show every existing variant with an "(Already added)"
            // affordance so the user understands they can't add a
            // duplicate from this screen.
            val displayList = variants.map {
                val variantText = it.variant?.takeIf { v -> v.isNotBlank() } ?: "(no variant)"
                val unit = it.unit ?: "piece"
                "$variantText ($unit) — ₹${it.price}  •  ${getString(R.string.action_already_added)}"
            }

            val adapter = ArrayAdapter(
                this@AddProductsActivity,
                android.R.layout.simple_list_item_1,
                displayList
            )

            listView.adapter = adapter

            val dialog = AlertDialog.Builder(this@AddProductsActivity)
                .setView(dialogView)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            // 🔥 CLICK EXISTING VARIANT → ASK BEFORE EDIT REDIRECT
            //
            // The Add Product screen never edits inline. Tapping a
            // row that already exists opens a confirmation dialog
            // and, on confirm, hands off to EditProductActivity.
            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedVariant = variants[position]
                dialog.dismiss()
                confirmEditExistingVariant(selectedVariant)
            }

            // 🔥 ADD NEW VARIANT — only entry point for new products
            //   from this dialog.
            btnAdd.setOnClickListener {
                dialog.dismiss()
                showAddProductDialog(productName)
            }
            dialog.show()
        }
    }

    private fun showUpdatePriceDialog(product: Product) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_product, null)

        val etPrice = dialogView.findViewById<EditText>(R.id.etDialogPrice)
        val etVariant = dialogView.findViewById<AutoCompleteTextView>(R.id.etVariantName)
        val etUnit = dialogView.findViewById<AutoCompleteTextView>(R.id.etUnit)
        
        val etHsnCode = dialogView.findViewById<EditText>(R.id.etHsnCode)
        val etGstRate = dialogView.findViewById<EditText>(R.id.etGstRate)

        val switchInventory = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchTrackInventory)
        val layoutInventory = dialogView.findViewById<LinearLayout>(R.id.layoutInventoryFields)
        val etStock = dialogView.findViewById<EditText>(R.id.etInitialStock)
        val etCost = dialogView.findViewById<EditText>(R.id.etCostPrice)

        val btnAdd = dialogView.findViewById<Button>(R.id.btnDialogAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)

        // 🔥 Prefill basic fields
        etVariant.setText(product.variant)
        etVariant.isEnabled = false
        etVariant.alpha = 0.5f

        etUnit.setText(product.unit, false)
        etUnit.isEnabled = false
        etUnit.alpha = 0.5f

        etPrice.setText(product.price.toString())
        
        etHsnCode.setText(product.hsnCode ?: "")
        etGstRate.setText(if (product.defaultGstRate > 0) product.defaultGstRate.toString() else "")

        // ===== Product-level tax inputs + HSN help + autofill =====
        val etCgst = dialogView.findViewById<EditText>(R.id.etCgst)
        val etSgst = dialogView.findViewById<EditText>(R.id.etSgst)
        val etIgst = dialogView.findViewById<EditText>(R.id.etIgst)
        if (product.cgstPercentage > 0) etCgst.setText(product.cgstPercentage.toString())
        if (product.sgstPercentage > 0) etSgst.setText(product.sgstPercentage.toString())
        if (product.igstPercentage > 0) etIgst.setText(product.igstPercentage.toString())

        AddProductDialogBinder.bind(
            dialogView = dialogView,
            scope = lifecycleScope,
            productRepo = ProductRepository.get(this),
            verificationRepo = ProductVerificationRepository.get(this),
            nameSource = { product.name }
        )

        // 🔥 Prefill inventory state
        switchInventory.isChecked = product.trackInventory
        layoutInventory.visibility = if (product.trackInventory) View.VISIBLE else View.GONE

        // Read the current stock once when the dialog opens. We use
        // this both to (a) gate the trackInventory toggle so the user
        // can't switch OFF while stock > 0, and (b) tell the save
        // handler to short-circuit if the rule is violated anyway.
        val currentStockHolder = doubleArrayOf(0.0)
        lifecycleScope.launch {
            currentStockHolder[0] = withContext(Dispatchers.IO) {
                db.inventoryDao().getInventory(product.id)?.currentStock ?: 0.0
            }
        }

        // ===== Purchase-based products: lock stock + inventory =====
        if (product.isPurchased) {
            switchInventory.isEnabled = false
            switchInventory.alpha = 0.5f
            etStock.isEnabled = false
            etStock.alpha = 0.5f
            etStock.hint = "Stock is managed through purchase entries"
            layoutInventory.visibility = View.VISIBLE
        }

        switchInventory.setOnCheckedChangeListener { _, isChecked ->
            // Strict: cannot turn OFF while stock > 0.
            if (product.trackInventory && !isChecked && currentStockHolder[0] > 0) {
                switchInventory.setOnCheckedChangeListener(null)
                switchInventory.isChecked = true  // revert silently
                switchInventory.setOnCheckedChangeListener { _, c2 ->
                    layoutInventory.visibility = if (c2) View.VISIBLE else View.GONE
                }
                toast("Cannot turn off inventory while stock > 0")
                return@setOnCheckedChangeListener
            }
            layoutInventory.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnAdd.text = "Update"

        btnAdd.setOnClickListener {

            val newPrice = etPrice.text.toString().toDoubleOrNull()
            if (newPrice == null || newPrice <= 0) {
                toast("Invalid price")
                return@setOnClickListener
            }

            val trackInventory = switchInventory.isChecked
            val stockQty = etStock.text.toString().toDoubleOrNull() ?: 0.0

            // Cost-price input was removed. Per the latest spec, when
            // there is no purchase invoice the average cost stays 0
            // — selling price is NOT a substitute for cost-of-goods.
            val costPrice = 0.0

            val hsnCode = etHsnCode.text.toString().trim()
            val gstRate = etGstRate.text.toString().toDoubleOrNull() ?: 0.0
            val cgstPct = etCgst.text.toString().toDoubleOrNull() ?: 0.0
            val sgstPct = etSgst.text.toString().toDoubleOrNull() ?: 0.0
            val igstPct = etIgst.text.toString().toDoubleOrNull() ?: 0.0

            lifecycleScope.launch {
                val storeInfo = db.storeInfoDao().get()
                val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()

                if (isGstEnabled && hsnCode.isBlank()) {
                    withContext(Dispatchers.Main) {
                        toast("HSN Code is mandatory for GST billing")
                        etHsnCode.error = "Required"
                    }
                    return@launch
                }

                // 🚫 Strict guard: if the user is switching OFF
                // trackInventory while stock still exists, refuse
                // before any writes. This prevents the previous bug
                // where the row was already updated in the DB before
                // the early return fired.
                val onHand = withContext(Dispatchers.IO) {
                    db.inventoryDao().getInventory(product.id)?.currentStock ?: 0.0
                }
                if (product.trackInventory && !trackInventory && onHand > 0) {
                    withContext(Dispatchers.Main) {
                        toast("Reduce stock to 0 before turning inventory off")
                    }
                    return@launch
                }

                // 🚫 Purchase-based products: stock fields locked.
                // Re-route through the restricted updater so cgst /
                // sgst / igst / price / hsn change but trackInventory
                // and stock stay untouched.
                if (product.isPurchased) {
                    com.example.easy_billing.repository.ProductRepository.get(
                        this@AddProductsActivity
                    ).updateSalesFieldsOnly(
                        productId = product.id,
                        price     = newPrice,
                        cgst      = cgstPct,
                        sgst      = sgstPct,
                        igst      = igstPct,
                        hsn       = hsnCode.ifBlank { null }
                    )
                    com.example.easy_billing.sync.SyncCoordinator
                        .get(this@AddProductsActivity).requestSync()
                    withContext(Dispatchers.Main) {
                        toast("Updated. Stock is managed through purchase entries.")
                        finish()
                    }
                    return@launch
                }

                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    val productId = product.id

                    // 🔥 CALL API (ONLY PRODUCT UPDATE)
                    val response = RetrofitClient.api.addProductToShop(
                        "Bearer $token",
                        AddProductRequest(
                            name = product.name,
                            variant_name = product.variant,
                            unit = normalizeUnit(product.unit),
                            price = newPrice,
                            track_inventory = trackInventory,
                            initial_stock = null,
                            cost_price = null,
                            hsn_code = hsnCode.ifBlank { null },
                            default_gst_rate = gstRate
                        )
                    )
                    // 🆕 Mirror to global catalogue (best-effort).
                    registerProductGlobally(
                        token = token ?: "",
                        name = product.name,
                        variant = product.variant,
                        hsn = hsnCode
                    )

                    val serverId = response.product_id

                    // 🔥 UPDATE LOCAL PRODUCT
                    db.productDao().update(
                        product.copy(
                            price = newPrice,
                            trackInventory = trackInventory,
                            serverId = serverId,
                            isActive = true,
                            hsnCode = hsnCode.ifBlank { null },
                            defaultGstRate = gstRate,
                            cgstPercentage = cgstPct,
                            sgstPercentage = sgstPct,
                            igstPercentage = igstPct
                        )
                    )

                    val inventory = db.inventoryDao().getInventoryIncludingInactive(productId)

                    // ================= STATE TRANSITIONS =================

                    // 🔴 ON → OFF
                    if (product.trackInventory && !trackInventory) {

                        if ((inventory?.currentStock ?: 0.0) > 0) {
                            toast("Reduce stock to 0 first")
                            return@launch
                        }

                        inventory?.let {
                            db.inventoryDao().update(it.copy(isActive = false))
                        }
                    }

                    // 🟢 OFF → ON (RESTORE)
                    if (!product.trackInventory && trackInventory) {

                        if (inventory != null) {
                            db.inventoryDao().update(inventory.copy(isActive = true))
                        }

                        if (stockQty > 0) {
                            InventoryManager.addStock(db, productId, stockQty, costPrice)
                        }
                        if (stockQty <= 0) {
                            toast("Enter stock quantity")
                            return@launch
                        }
                    }

                    // 🔵 ON → ON (ADD STOCK)
                    if (product.trackInventory && trackInventory && stockQty > 0) {

                        InventoryManager.addStock(db, productId, stockQty, costPrice)
                    }

                    // ⚪ OFF → OFF (DO NOTHING)

                    toast("Product updated")
                    dialog.dismiss()
                    finish()

                } catch (e: Exception) {
                    e.printStackTrace()
                    toast("Update failed")
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupSearch() {
        val etSearch = findViewById<EditText>(R.id.etSearch)

        etSearch.addTextChangedListener { text ->
            val query = text.toString().trim().lowercase()

            val filtered = if (query.isEmpty()) {
                catalogList
            } else {
                catalogList.filter {
                    it.lowercase().contains(query)
                }
            }

            updateList(filtered)
        }
    }

    private fun updateList(list: List<String>) {
        val displayList = list.toMutableList()

        if (!displayList.contains("Others")) {
            displayList.add("Others")
        }

        adapter.clear()
        adapter.addAll(displayList)
        adapter.notifyDataSetChanged()
    }

    // ================= ADD PRODUCT =================
    private fun showAddProductDialog(selectedItem: String) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_product, null)

        val etPrice = dialogView.findViewById<EditText>(R.id.etDialogPrice)
        val etCustomName = dialogView.findViewById<EditText>(R.id.etDialogCustomName)
        val etVariant = dialogView.findViewById<AutoCompleteTextView>(R.id.etVariantName)
        val etUnit = dialogView.findViewById<AutoCompleteTextView>(R.id.etUnit)

        val etHsnCode = dialogView.findViewById<EditText>(R.id.etHsnCode)
        val etGstRate = dialogView.findViewById<EditText>(R.id.etGstRate)

        val switchInventory = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchTrackInventory)
        val layoutInventory = dialogView.findViewById<LinearLayout>(R.id.layoutInventoryFields)
        val etStock = dialogView.findViewById<EditText>(R.id.etInitialStock)
        val etCost = dialogView.findViewById<EditText>(R.id.etCostPrice)

        val btnAdd = dialogView.findViewById<Button>(R.id.btnDialogAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)

        val layoutCustomName = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomName)

        layoutCustomName.visibility =
            if (selectedItem == "Others") View.VISIBLE else View.GONE

        val units = listOf("piece", "kilogram", "litre", "gram", "millilitre")
        etUnit.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, units))
        etUnit.setText("piece", false)

        switchInventory.setOnCheckedChangeListener { _, isChecked ->
            layoutInventory.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // ===== HSN help + product-level tax + autofill =====
        AddProductDialogBinder.bind(
            dialogView = dialogView,
            scope = lifecycleScope,
            productRepo = ProductRepository.get(this),
            verificationRepo = ProductVerificationRepository.get(this),
            nameSource = {
                if (selectedItem == "Others") etCustomName.text.toString().trim() else selectedItem
            }
        )

        // Trigger name-based autofill once the user lands on the dialog
        // for a known product (i.e. anything other than "Others").
        if (selectedItem != "Others") {

            bindGlobalData(selectedItem, dialogView)

            AddProductDialogBinder.triggerNameAutofill(
                dialogView,
                lifecycleScope,
                ProductRepository.get(this),
                ProductVerificationRepository.get(this),
                selectedItem
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnAdd.setOnClickListener {

            val price = etPrice.text.toString().toDoubleOrNull()
            if (price == null || price <= 0) {
                toast("Invalid price")
                return@setOnClickListener
            }

            val name = if (selectedItem == "Others") {
                etCustomName.text.toString().trim()
            } else selectedItem

            if (name.isEmpty()) {
                toast("Enter product name")
                return@setOnClickListener
            }

            val variantName = etVariant.text.toString()
                .trim()
                .split(" ")
                .joinToString(" ") {
                    if (it.isEmpty()) it else it.replaceFirstChar { c -> c.uppercase() }
                }
                .ifEmpty { null }
            val unit = normalizeUnit(etUnit.text.toString())
            val trackInventory = switchInventory.isChecked

            val stockQty = etStock.text.toString().toDoubleOrNull() ?: 0.0
            // Cost-price input was removed. Per the latest spec, when
            // there is no purchase invoice the average cost stays 0
            // — selling price is NOT a substitute for cost-of-goods.
            val costPrice = 0.0

            if (trackInventory && stockQty <= 0) {
                toast("Enter stock quantity")
                return@setOnClickListener
            }
            
            val hsnCode = etHsnCode.text.toString().trim()
            val gstRate = etGstRate.text.toString().toDoubleOrNull() ?: 0.0

            // Product-level CGST/SGST/IGST (new model — store-level
            // tax was removed in the GST refactor).
            val cgstPct = dialogView.findViewById<EditText>(R.id.etCgst)
                .text.toString().toDoubleOrNull() ?: 0.0
            val sgstPct = dialogView.findViewById<EditText>(R.id.etSgst)
                .text.toString().toDoubleOrNull() ?: 0.0
            val igstPct = dialogView.findViewById<EditText>(R.id.etIgst)
                .text.toString().toDoubleOrNull() ?: 0.0

            lifecycleScope.launch {
                val storeInfo = db.storeInfoDao().get()
                val isGstEnabled = storeInfo != null && storeInfo.gstin.isNotBlank()

                if (isGstEnabled && hsnCode.isBlank()) {
                    withContext(Dispatchers.Main) {
                        toast("HSN Code is mandatory for GST billing")
                        etHsnCode.error = "Required"
                    }
                    return@launch
                }

                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    // 🔥 LOCAL CHECK FIRST (FAST + IMPORTANT)
                    val localExisting = db.productDao()
                        .getByNameAndVariant(name, variantName)

                    // ================= NEW PRODUCT =================
                    if (localExisting == null) {

                        val response = RetrofitClient.api.addProductToShop(
                            "Bearer $token",
                            AddProductRequest(
                                name = name,
                                variant_name = variantName,
                                unit = unit,
                                price = price,
                                track_inventory = trackInventory,
                                initial_stock = if (trackInventory) stockQty else null,
                                cost_price = if (trackInventory) costPrice else null,
                                hsn_code = hsnCode.ifBlank { null },
                                default_gst_rate = gstRate
                            )
                        )
                        // 🆕 Mirror to global catalogue (best-effort).
                        registerProductGlobally(
                            token = token ?: "",
                            name = name,
                            variant = variantName,
                            hsn = hsnCode
                        )

                        val serverId = response.product_id

                        val newId = db.productDao().insert(
                            Product(
                                name = capitalizeFirst(name),
                                variant = variantName?.let { capitalizeFirst(it) },
                                unit = unit,
                                price = price,
                                trackInventory = trackInventory,
                                serverId = serverId,
                                isActive = true,
                                isCustom = (selectedItem == "Others"),
                                hsnCode = hsnCode.ifBlank { null },
                                defaultGstRate = gstRate,
                                cgstPercentage = cgstPct,
                                sgstPercentage = sgstPct,
                                igstPercentage = igstPct,
                                shopId = shopIdSync(db)
                            )
                        ).toInt()

                        if (trackInventory) {
                            InventoryManager.addStock(db, newId, stockQty, costPrice)
                        }

                        toast("Product added")
                        dialog.dismiss()
                        finish()
                        return@launch
                    }

                    // ================= EXISTING PRODUCT =================

                    runOnUiThread {

                        val customView = layoutInflater.inflate(R.layout.dialog_product_exists, null)

                        val customDialog = AlertDialog.Builder(this@AddProductsActivity)
                            .setView(customView)
                            .create()

                        customDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        val btnCancel = customView.findViewById<Button>(R.id.btnCancel)
                        val btnUpdate = customView.findViewById<Button>(R.id.btnUpdate)
                        val btnReplace = customView.findViewById<Button>(R.id.btnReplace)

                        val tvMessage = customView.findViewById<TextView>(R.id.tvMessage)
                        val tvDetails = customView.findViewById<TextView>(R.id.tvDetails)

                        lifecycleScope.launch {

                            val inventory = db.inventoryDao()
                                .getInventoryIncludingInactive(localExisting.id)

                            val stock = inventory?.currentStock ?: 0.0

                            val detailsText = buildString {
                                append("Selling Price: ₹${localExisting.price}\n")

                                if (!localExisting.variant.isNullOrEmpty()) {
                                    append("Variant: ${localExisting.variant}\n")
                                }

                                append("Unit: ${localExisting.unit}\n")

                                if (localExisting.trackInventory) {
                                    append("Stock: $stock")
                                } else {
                                    append("Inventory: OFF")
                                }
                            }

                            runOnUiThread {
                                tvMessage.text = "${localExisting.name} (${localExisting.variant}) already exists.\nDo you want to restore it?"
                                tvDetails.text = detailsText
                            }
                        }

                        // ================= UPDATE =================
                        btnUpdate.setOnClickListener {

                            lifecycleScope.launch {

                                val response = RetrofitClient.api.addProductToShop(
                                    "Bearer $token",
                                    AddProductRequest(
                                        name = name,
                                        variant_name = variantName,
                                        unit = unit,
                                        price = price,
                                        track_inventory = trackInventory,
                                        initial_stock = if (trackInventory) stockQty else null,
                                        cost_price = if (trackInventory) costPrice else null,
                                        hsn_code = hsnCode.ifBlank { null },
                                        default_gst_rate = gstRate
                                    )
                                )
                                // 🆕 Mirror to global catalogue (best-effort).
                                registerProductGlobally(
                                    token = token ?: "",
                                    name = name,
                                    variant = variantName,
                                    hsn = hsnCode
                                )

                                val serverId = response.product_id
                                val productId = localExisting.id

                                db.productDao().update(
                                    localExisting.copy(
                                        price = price,
                                        trackInventory = trackInventory,
                                        serverId = serverId,
                                        isActive = true,
                                        hsnCode = hsnCode.ifBlank { null },
                                        defaultGstRate = gstRate,
                                        cgstPercentage = cgstPct,
                                        sgstPercentage = sgstPct,
                                        igstPercentage = igstPct
                                    )
                                )

                                val inventory = db.inventoryDao().getInventoryIncludingInactive(productId)

                                // 🔥 ON → OFF
                                if (localExisting.trackInventory && !trackInventory) {
                                    if ((inventory?.currentStock ?: 0.0) > 0) {
                                        toast("Cannot disable inventory (stock exists)")
                                        return@launch
                                    }
                                    inventory?.let {
                                        db.inventoryDao().update(it.copy(isActive = false))
                                    }
                                }

                                // 🔥 OFF → ON
                                if (!localExisting.trackInventory && trackInventory) {
                                    if (inventory != null) {
                                        db.inventoryDao().update(inventory.copy(isActive = true))
                                    }
                                    if (stockQty > 0) {
                                        InventoryManager.addStock(db, productId, stockQty, costPrice)
                                    }
                                }

                                // 🔥 ON → ON
                                if (localExisting.trackInventory && trackInventory && stockQty > 0) {
                                    InventoryManager.addStock(db, productId, stockQty, costPrice)
                                }

                                toast("Product updated")
                                customDialog.dismiss()
                                dialog.dismiss()
                                finish()
                            }
                        }

                        // ================= REPLACE =================
                        btnReplace.setOnClickListener {

                            lifecycleScope.launch {

                                db.productDao().deactivate(localExisting.id)

                                val oldInventory = db.inventoryDao()
                                    .getInventoryIncludingInactive(localExisting.id)

                                oldInventory?.let {
                                    db.inventoryDao().update(it.copy(isActive = false))
                                }

                                val response = RetrofitClient.api.addProductToShop(
                                    "Bearer $token",
                                    AddProductRequest(
                                        name = name,
                                        variant_name = variantName,
                                        unit = unit,
                                        price = price,
                                        track_inventory = trackInventory,
                                        initial_stock = if (trackInventory) stockQty else null,
                                        cost_price = if (trackInventory) costPrice else null,
                                        hsn_code = hsnCode.ifBlank { null },
                                        default_gst_rate = gstRate
                                    )
                                )
                                // 🆕 Mirror to global catalogue (best-effort).
                                registerProductGlobally(
                                    token = token ?: "",
                                    name = name,
                                    variant = variantName,
                                    hsn = hsnCode
                                )

                                val serverId = response.product_id

                                val newId = db.productDao().insert(
                                    Product(
                                        name = capitalizeFirst(name),
                                        variant = variantName?.let { capitalizeFirst(it) },
                                        unit = unit,
                                        price = price,
                                        trackInventory = trackInventory,
                                        serverId = serverId,
                                        isActive = true,
                                        isCustom = (selectedItem == "Others"),
                                        hsnCode = hsnCode.ifBlank { null },
                                        defaultGstRate = gstRate,
                                        cgstPercentage = cgstPct,
                                        sgstPercentage = sgstPct,
                                        igstPercentage = igstPct,
                                        shopId = shopIdSync(db)
                                    )
                                ).toInt()

                                if (trackInventory) {
                                    InventoryManager.addStock(db, newId, stockQty, costPrice)
                                } else {
                                    val inv = db.inventoryDao().getInventoryIncludingInactive(newId)
                                    inv?.let {
                                        db.inventoryDao().update(it.copy(isActive = false))
                                    }
                                }

                                toast("Product replaced")
                                customDialog.dismiss()
                                dialog.dismiss()
                                finish()
                            }
                        }

                        // ================= CANCEL =================
                        btnCancel.setOnClickListener {
                            customDialog.dismiss()
                        }

                        customDialog.show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    toast("Failed")
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun normalizeUnit(unit: String?): String {
        return when (unit?.lowercase()) {
            "piece" -> "piece"
            "kilogram", "kg" -> "kg"
            "litre", "liter", "l", "ltr" -> "litre"
            "gram", "g" -> "gram"
            "millilitre", "ml" -> "ml"
            else -> "piece"
        }
    }


    /**
     * "Variant already exists. Edit instead?" dialog. Surfaced
     * whenever the Add Product flow detects a tap on a row that
     * is already in the catalogue. Confirm → [EditProductActivity];
     * cancel → user stays on the variant list.
     */
    private fun confirmEditExistingVariant(product: com.example.easy_billing.db.Product) {
        val message = getString(
            R.string.variant_already_exists_message,
            product.variant?.takeIf { it.isNotBlank() } ?: product.name
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.variant_already_exists_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_edit) { d, _ ->
                d.dismiss()
                startActivity(
                    android.content.Intent(this, EditProductActivity::class.java)
                        .putExtra(EditProductActivity.EXTRA_PRODUCT_ID, product.id)
                )
                finish()
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Best-effort: tells the backend to also add this product to its
     * **global** catalogue (so the same name/HSN/variant can be
     * verified by other shops via [com.example.easy_billing.network.ApiService.verifyProductName]
     * etc.). Failures are swallowed — the inline addProductToShop
     * call has already succeeded by the time we call this.
     */
    private suspend fun registerProductGlobally(
        token: String,
        name: String,
        variant: String?,
        hsn: String?
    ) {
        runCatching {
            RetrofitClient.api.registerGlobalProduct(
                "Bearer $token",
                com.example.easy_billing.network.GlobalProductRegisterRequest(
                    name = name,
                    variant = variant,
                    hsn_code = hsn?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /**
     * Best-effort current shop id — the GSTIN on `store_info` if
     * present, otherwise the SHOP_ID stored in auth prefs. Used
     * when inserting brand-new product rows so the dashboard tile
     * grid (which now filters by shop_id) doesn't lose them.
     */
    private suspend fun shopIdSync(db: AppDatabase): String {
        val gstin = db.storeInfoDao().get()?.gstin?.takeIf { it.isNotBlank() }
        if (gstin != null) return gstin
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        return try {
            prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
        } catch (e: ClassCastException) {
            prefs.getInt("SHOP_ID", 0).toString()
        }
    }

    /**
     * Capitalises the first letter of every word in [value]. Used
     * everywhere a product name or variant is persisted.
     */
    private fun capitalizeFirst(value: String): String =
        value.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.first().uppercaseChar() + word.drop(1)
        }


    private fun bindGlobalData(
        productName: String,
        dialogView: View
    ) {

        val etVariant = dialogView.findViewById<AutoCompleteTextView>(R.id.etVariantName)
        val etHsn = dialogView.findViewById<EditText>(R.id.etHsnCode)
        val etGst = dialogView.findViewById<EditText>(R.id.etGstRate)
        val etUnit = dialogView.findViewById<AutoCompleteTextView>(R.id.etUnit)

        lifecycleScope.launch {

            try {
                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null) ?: return@launch

                // ✅ Get catalog
                val catalog = RetrofitClient.api.getCatalog("Bearer $token")
                val product = catalog.find { it.name.equals(productName, true) }
                    ?: return@launch

                val productId = product.id

                // ✅ Fetch variants
                val variants = RetrofitClient.api.getVariants(
                    "Bearer $token",
                    productId
                )

                val variantNames = variants.map { it.variant_name }

                withContext(Dispatchers.Main) {

                    val adapter = ArrayAdapter(
                        this@AddProductsActivity,
                        android.R.layout.simple_list_item_1,
                        variantNames
                    )

                    etVariant.setAdapter(adapter)

                    // 🔥 Always show dropdown on click
                    etVariant.setOnClickListener {
                        etVariant.showDropDown()
                    }

                    // 🔥 USER TYPES → normalize text
                    etVariant.addTextChangedListener {
                        val normalized = it.toString()
                            .trim()
                            .split(" ")
                            .joinToString(" ") { word ->
                                if (word.isEmpty()) word
                                else word.replaceFirstChar { c -> c.uppercase() }
                            }

                        if (normalized != it.toString()) {
                            etVariant.setText(normalized)
                            etVariant.setSelection(normalized.length)
                        }
                    }

                    etVariant.setOnItemClickListener { _, _, position, _ ->

                        val selectedVariant = variants[position]

                        lifecycleScope.launch {
                            try {

                                val hsnResp = RetrofitClient.api.getHsn(
                                    "Bearer $token",
                                    productId
                                )

                                withContext(Dispatchers.Main) {

                                    etHsn.setText(hsnResp.hsn_code)

                                    etUnit.setText(selectedVariant.unit, false)

                                    val gst = when (hsnResp.hsn_code.length) {
                                        4 -> 5.0
                                        6 -> 12.0
                                        else -> 18.0
                                    }

                                    etGst.setText(gst.toString())
                                }

                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
