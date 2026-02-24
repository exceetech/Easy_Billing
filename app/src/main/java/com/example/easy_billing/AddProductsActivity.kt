package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.network.AddProductRequest
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch

class AddProductsActivity : AppCompatActivity() {

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var db: AppDatabase
    private var catalogList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_products)

        db = AppDatabase.getDatabase(this)

        setupList()
        setupSearch()
        loadCatalogFromBackend()
    }

    // ================= LOAD CATALOG FROM BACKEND =================

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

    // ================= LIST SETUP =================

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
            showAddProductDialog(selected)
        }
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
        val btnAdd = dialogView.findViewById<Button>(R.id.btnDialogAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)

        etCustomName.visibility =
            if (selectedItem == "Others") View.VISIBLE else View.GONE

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

            val name = if (selectedItem == "Others") {
                etCustomName.text.toString().trim()
            } else {
                selectedItem
            }

            if (name.isEmpty()) {
                toast("Enter product name")
                return@setOnClickListener
            }

            val price = priceText.toDouble()

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            lifecycleScope.launch {
                try {

                    // 🔹 Send to backend
                    RetrofitClient.api.addProductToShop(
                        "Bearer $token",
                        AddProductRequest(name, price)
                    )

                    // 🔹 Save locally for offline
                    db.productDao().insert(
                        Product(
                            name = name,
                            price = price,
                            isCustom = (selectedItem == "Others")
                        )
                    )

                    toast("Product added")
                    dialog.dismiss()
                    finish()

                } catch (e: Exception) {
                    toast("Already added or error")
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}