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
import kotlinx.coroutines.launch

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

            val displayList = variants.map {

                val variantText = it.variant?.takeIf { v -> v.isNotBlank() } ?: ""

                if (variantText.isEmpty()) {
                    "(${it.unit ?: "piece"}) - ₹${it.price}"
                } else {
                    "$variantText (${it.unit}) - ₹${it.price}"
                }
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

            // 🔥 CLICK VARIANT → UPDATE PRICE
            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedVariant = variants[position]
                dialog.dismiss()
                showUpdatePriceDialog(selectedVariant)
            }

            // 🔥 ADD NEW VARIANT
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
        val etVariant = dialogView.findViewById<EditText>(R.id.etVariantName)
        val etUnit = dialogView.findViewById<AutoCompleteTextView>(R.id.etUnit)

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

        // 🔥 Prefill inventory state
        switchInventory.isChecked = product.trackInventory
        layoutInventory.visibility = if (product.trackInventory) View.VISIBLE else View.GONE

        switchInventory.setOnCheckedChangeListener { _, isChecked ->
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
            val costPrice = etCost.text.toString().toDoubleOrNull() ?: 0.0

            if (trackInventory && stockQty > 0 && costPrice <= 0) {
                toast("Enter valid cost price")
                return@setOnClickListener
            }

            lifecycleScope.launch {

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
                            cost_price = null
                        )
                    )

                    val serverId = response.product_id

                    // 🔥 UPDATE LOCAL PRODUCT
                    db.productDao().update(
                        product.copy(
                            price = newPrice,
                            trackInventory = trackInventory,
                            serverId = serverId,
                            isActive = true
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
                            toast("Enter stock \u0026 cost price")
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
        val etVariant = dialogView.findViewById<EditText>(R.id.etVariantName)
        val etUnit = dialogView.findViewById<AutoCompleteTextView>(R.id.etUnit)

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

            val variantName = etVariant.text.toString().trim().ifEmpty { null }
            val unit = normalizeUnit(etUnit.text.toString())
            val trackInventory = switchInventory.isChecked

            val stockQty = etStock.text.toString().toDoubleOrNull() ?: 0.0
            val costPrice = etCost.text.toString().toDoubleOrNull() ?: 0.0

            if (trackInventory && (stockQty <= 0 || costPrice <= 0)) {
                toast("Enter stock \u0026 cost price")
                return@setOnClickListener
            }

            lifecycleScope.launch {

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
                                cost_price = if (trackInventory) costPrice else null
                            )
                        )

                        val serverId = response.product_id

                        val newId = db.productDao().insert(
                            Product(
                                name = name,
                                variant = variantName,
                                unit = unit,
                                price = price,
                                trackInventory = trackInventory,
                                serverId = serverId,
                                isActive = true,
                                isCustom = (selectedItem == "Others")
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
                                        cost_price = if (trackInventory) costPrice else null
                                    )
                                )

                                val serverId = response.product_id
                                val productId = localExisting.id

                                db.productDao().update(
                                    localExisting.copy(
                                        price = price,
                                        trackInventory = trackInventory,
                                        serverId = serverId,
                                        isActive = true
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
                                        cost_price = if (trackInventory) costPrice else null
                                    )
                                )

                                val serverId = response.product_id

                                val newId = db.productDao().insert(
                                    Product(
                                        name = name,
                                        variant = variantName,
                                        unit = unit,
                                        price = price,
                                        trackInventory = trackInventory,
                                        serverId = serverId,
                                        isActive = true,
                                        isCustom = (selectedItem == "Others")
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
