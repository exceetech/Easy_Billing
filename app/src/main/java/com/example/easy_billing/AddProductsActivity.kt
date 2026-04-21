package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.compose.ui.text.capitalize
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.InventoryManager
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

                if (variantText.isNullOrEmpty()) {
                    "(${it.unit?: "piece"}) - ₹${it.price}"
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

            val dialog = android.app.AlertDialog.Builder(this@AddProductsActivity)
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

        val btnAdd = dialogView.findViewById<Button>(R.id.btnDialogAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)

        // 🔥 pre-fill
        etVariant.setText(product.variant)
        etVariant.isEnabled = false
        etVariant.isFocusable = false
        etVariant.isClickable = false
        etVariant.alpha = 0.5f

        etUnit.setText(product.unit, false)
        etUnit.isEnabled = false
        etUnit.isFocusable = false
        etUnit.isClickable = false
        etUnit.alpha = 0.5f

        etPrice.setText(product.price.toString())

        val dialog = android.app.AlertDialog.Builder(this)
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

            lifecycleScope.launch {
                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    RetrofitClient.api.addProductToShop(
                        "Bearer $token",
                        AddProductRequest(
                            name = product.name,
                            variant_name = product.variant,
                            unit = normalizeUnit(product.unit),
                            price = newPrice
                        )
                    )

                    // 🔥 LOCAL SYNC
                    db.productDao().deleteById(product.id)

                    db.productDao().insert(
                        product.copy(price = newPrice)
                    )

                    toast("Price updated")
                    dialog.dismiss()
                    finish()

                } catch (e: Exception) {
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

        // ================= UNIT SETUP =================
        val units = listOf("piece", "kilogram", "litre", "gram", "millilitre")
        val unitAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            units
        )
        etUnit.setAdapter(unitAdapter)
        etUnit.setText("piece", false)

        // ================= INVENTORY TOGGLE =================
        switchInventory.setOnCheckedChangeListener { _, isChecked ->
            layoutInventory.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnAdd.setOnClickListener {

            val priceText = etPrice.text.toString().trim()

            if (priceText.isEmpty()) {
                toast("Enter price")
                return@setOnClickListener
            }

            val price = priceText.toDoubleOrNull()
            if (price == null || price <= 0) {
                toast("Invalid price")
                return@setOnClickListener
            }

            if (price > 1_000_000) {
                toast("Price too high")
                return@setOnClickListener
            }

            val name = if (selectedItem == "Others") {
                etCustomName.text.toString().trim()
            } else selectedItem

            if (name.isEmpty()) {
                toast("Enter product name")
                return@setOnClickListener
            }

            val variantInput = etVariant.text.toString().trim()
            val variantName = if (variantInput.isEmpty()) null else variantInput

            val unit = normalizeUnit(etUnit.text.toString())

            val trackInventory = switchInventory.isChecked

            val stockQty = etStock.text.toString().toDoubleOrNull() ?: 0.0
            val costPrice = etCost.text.toString().toDoubleOrNull() ?: 0.0

            // 🔥 VALIDATION FOR INVENTORY
            if (trackInventory) {
                if (stockQty <= 0 || costPrice <= 0) {
                    toast("Enter stock and cost price")
                    return@setOnClickListener
                }
            }

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            lifecycleScope.launch {
                try {

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    RetrofitClient.api.addProductToShop(
                        "Bearer $token",
                        AddProductRequest(
                            name = name,
                            variant_name = variantName,
                            unit = unit,
                            price = price
                        )
                    )

                    // 🔥 STEP 1: CHECK EXISTING PRODUCT
                    val existingProduct = db.productDao()
                        .getByNameAndVariant(name, variantName)

                    val productId: Int

                    if (existingProduct != null) {

                        // 🔥 REUSE PRODUCT
                        productId = existingProduct.id

                        // 🔥 UPDATE PRICE (optional)
                        db.productDao().deleteById(existingProduct.id)

                        db.productDao().insert(
                            existingProduct.copy(
                                price = price,
                                trackInventory = trackInventory
                            )
                        )

                    } else {

                        // 🔥 CREATE NEW PRODUCT
                        val newId = db.productDao().insert(
                            Product(
                                name = name,
                                variant = variantName,
                                unit = unit,
                                price = price,
                                trackInventory = trackInventory,
                                isCustom = (selectedItem == "Others")
                            )
                        )

                        productId = newId.toInt()
                    }

                    // ================= ADD STOCK =================
                    if (trackInventory) {
                        InventoryManager.addStock(
                            db = db,
                            productId = productId,
                            quantity = stockQty,
                            costPrice = costPrice
                        )
                    }

                    toast("Product saved successfully")
                    dialog.dismiss()
                    finish()

                } catch (e: Exception) {
                    e.printStackTrace()
                    toast("Failed to add product")
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

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