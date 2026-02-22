package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import kotlinx.coroutines.launch

class AddProductsActivity : AppCompatActivity() {

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_products)

        val etSearch = findViewById<EditText>(R.id.etSearch)
        val listItems = findViewById<ListView>(R.id.listItems)

        db = AppDatabase.getDatabase(this)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )
        listItems.adapter = adapter

        lifecycleScope.launch {
            insertDefaultProductsIfNeeded()
            loadProducts()
        }

        // 🔍 Search directly from DB
        etSearch.addTextChangedListener { text ->
            lifecycleScope.launch {
                val query = text.toString().trim()
                val results = if (query.isEmpty()) {
                    db.defaultProductDao().getAll()
                } else {
                    db.defaultProductDao().search(query)
                }

                val names = results.map { it.name }.toMutableList()

                if (names.isEmpty()) {
                    names.add("Others")
                }

                adapter.clear()
                adapter.addAll(names)
                adapter.notifyDataSetChanged()
            }
        }

        // 👆 Item click
        listItems.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position) ?: return@setOnItemClickListener
            showAddProductDialog(selected)
        }
    }

    // ✅ Insert only first time
    private suspend fun insertDefaultProductsIfNeeded() {
        val existing = db.defaultProductDao().getAll()
        if (existing.isEmpty()) {

            val defaultList = listOf(

                // Grains
                DefaultProduct(name = "Rice"),
                DefaultProduct(name = "Basmati Rice"),
                DefaultProduct(name = "Brown Rice"),
                DefaultProduct(name = "Idli Rice"),
                DefaultProduct(name = "Ponni Rice"),
                DefaultProduct(name = "Kolam Rice"),
                DefaultProduct(name = "Wheat Flour"),
                DefaultProduct(name = "Multigrain Atta"),
                DefaultProduct(name = "Maida"),
                DefaultProduct(name = "Rava"),
                DefaultProduct(name = "Fine Rava"),
                DefaultProduct(name = "Poha"),
                DefaultProduct(name = "Thin Poha"),
                DefaultProduct(name = "Thick Poha"),
                DefaultProduct(name = "Dalia"),
                DefaultProduct(name = "Semolina"),

                // Millets
                DefaultProduct(name = "Ragi"),
                DefaultProduct(name = "Jowar"),
                DefaultProduct(name = "Bajra"),
                DefaultProduct(name = "Foxtail Millet"),
                DefaultProduct(name = "Little Millet"),
                DefaultProduct(name = "Barnyard Millet"),
                DefaultProduct(name = "Kodo Millet"),
                DefaultProduct(name = "Quinoa"),

                // Pulses
                DefaultProduct(name = "Toor Dal"),
                DefaultProduct(name = "Moong Dal"),
                DefaultProduct(name = "Green Moong"),
                DefaultProduct(name = "Sprouted Moong"),
                DefaultProduct(name = "Chana Dal"),
                DefaultProduct(name = "Masoor Dal"),
                DefaultProduct(name = "Urad Dal"),
                DefaultProduct(name = "Split Urad Dal"),
                DefaultProduct(name = "Rajma"),
                DefaultProduct(name = "Chole"),
                DefaultProduct(name = "Black Chana"),
                DefaultProduct(name = "Kabuli Chana"),
                DefaultProduct(name = "White Peas"),
                DefaultProduct(name = "Soya Beans"),
                DefaultProduct(name = "Soya Chunks"),

                // Dairy
                DefaultProduct(name = "Milk"),
                DefaultProduct(name = "Skimmed Milk"),
                DefaultProduct(name = "Toned Milk"),
                DefaultProduct(name = "Full Cream Milk"),
                DefaultProduct(name = "Curd"),
                DefaultProduct(name = "Butter"),
                DefaultProduct(name = "Salted Butter"),
                DefaultProduct(name = "Unsalted Butter"),
                DefaultProduct(name = "Ghee"),
                DefaultProduct(name = "Desi Ghee"),
                DefaultProduct(name = "Paneer"),
                DefaultProduct(name = "Fresh Paneer"),
                DefaultProduct(name = "Cheese Slices"),
                DefaultProduct(name = "Cheese Cubes"),
                DefaultProduct(name = "Mozzarella Cheese"),
                DefaultProduct(name = "Fresh Cream"),
                DefaultProduct(name = "Buttermilk"),
                DefaultProduct(name = "Lassi"),
                DefaultProduct(name = "Condensed Milk"),
                DefaultProduct(name = "Eggs"),
                DefaultProduct(name = "Brown Eggs"),

                // Vegetables
                DefaultProduct(name = "Potato"),
                DefaultProduct(name = "Onion"),
                DefaultProduct(name = "Spring Onion"),
                DefaultProduct(name = "Tomato"),
                DefaultProduct(name = "Cherry Tomato"),
                DefaultProduct(name = "Carrot"),
                DefaultProduct(name = "Beetroot"),
                DefaultProduct(name = "Radish"),
                DefaultProduct(name = "Turnip"),
                DefaultProduct(name = "Cabbage"),
                DefaultProduct(name = "Cauliflower"),
                DefaultProduct(name = "Broccoli"),
                DefaultProduct(name = "Spinach"),
                DefaultProduct(name = "Fenugreek Leaves"),
                DefaultProduct(name = "Mustard Leaves"),
                DefaultProduct(name = "Coriander Leaves"),
                DefaultProduct(name = "Mint Leaves"),
                DefaultProduct(name = "Drumstick"),
                DefaultProduct(name = "Brinjal"),
                DefaultProduct(name = "Lady Finger"),
                DefaultProduct(name = "Bottle Gourd"),
                DefaultProduct(name = "Ridge Gourd"),
                DefaultProduct(name = "Snake Gourd"),
                DefaultProduct(name = "Bitter Gourd"),
                DefaultProduct(name = "Ash Gourd"),
                DefaultProduct(name = "Pumpkin"),
                DefaultProduct(name = "Green Chilli"),
                DefaultProduct(name = "Capsicum Green"),
                DefaultProduct(name = "Capsicum Yellow"),
                DefaultProduct(name = "Capsicum Red"),
                DefaultProduct(name = "Cucumber"),
                DefaultProduct(name = "Zucchini"),
                DefaultProduct(name = "Sweet Corn"),
                DefaultProduct(name = "Mushroom"),
                DefaultProduct(name = "Ginger"),
                DefaultProduct(name = "Garlic"),

                // Fruits
                DefaultProduct(name = "Apple"),
                DefaultProduct(name = "Green Apple"),
                DefaultProduct(name = "Banana"),
                DefaultProduct(name = "Robusta Banana"),
                DefaultProduct(name = "Orange"),
                DefaultProduct(name = "Mosambi"),
                DefaultProduct(name = "Mandarin"),
                DefaultProduct(name = "Mango"),
                DefaultProduct(name = "Alphonso Mango"),
                DefaultProduct(name = "Grapes"),
                DefaultProduct(name = "Black Grapes"),
                DefaultProduct(name = "Papaya"),
                DefaultProduct(name = "Pineapple"),
                DefaultProduct(name = "Watermelon"),
                DefaultProduct(name = "Muskmelon"),
                DefaultProduct(name = "Pomegranate"),
                DefaultProduct(name = "Guava"),
                DefaultProduct(name = "Pear"),
                DefaultProduct(name = "Kiwi"),
                DefaultProduct(name = "Strawberry"),
                DefaultProduct(name = "Blueberry"),
                DefaultProduct(name = "Chikoo"),

                // Dry Fruits
                DefaultProduct(name = "Almonds"),
                DefaultProduct(name = "Cashews"),
                DefaultProduct(name = "Pistachios"),
                DefaultProduct(name = "Walnuts"),
                DefaultProduct(name = "Raisins"),
                DefaultProduct(name = "Dates"),
                DefaultProduct(name = "Figs"),
                DefaultProduct(name = "Chia Seeds"),
                DefaultProduct(name = "Flax Seeds"),
                DefaultProduct(name = "Pumpkin Seeds"),
                DefaultProduct(name = "Sunflower Seeds"),
                DefaultProduct(name = "Sesame Seeds"),

                // Oils
                DefaultProduct(name = "Oil"),
                DefaultProduct(name = "Sunflower Oil"),
                DefaultProduct(name = "Mustard Oil"),
                DefaultProduct(name = "Groundnut Oil"),
                DefaultProduct(name = "Rice Bran Oil"),
                DefaultProduct(name = "Coconut Oil"),
                DefaultProduct(name = "Olive Oil"),
                DefaultProduct(name = "Butter Oil"),
                DefaultProduct(name = "Vanaspati"),

                // Continue same pattern for remaining categories...

                DefaultProduct(name = "Disposable Cups"),
                DefaultProduct(name = "Others")
            )

            db.defaultProductDao().insertAll(defaultList)
        }
    }

    private suspend fun loadProducts() {
        val products = db.defaultProductDao().getAll()
        val names = products.map { it.name }

        adapter.clear()
        adapter.addAll(names)
        adapter.notifyDataSetChanged()
    }

    // 🪟 Dialog
    private fun showAddProductDialog(selectedItem: String) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_product, null)
        val etPrice = dialogView.findViewById<EditText>(R.id.etDialogPrice)
        val etCustomName = dialogView.findViewById<EditText>(R.id.etDialogCustomName)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnDialogAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)

        if (selectedItem == "Others") {
            etCustomName.visibility = View.VISIBLE
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnAdd.setOnClickListener {

            val priceText = etPrice.text.toString()
            if (priceText.isEmpty()) {
                toast("Enter price")
                return@setOnClickListener
            }

            val name = if (selectedItem == "Others") {
                etCustomName.text.toString()
            } else {
                selectedItem
            }

            if (name.isNullOrEmpty()) {
                toast("Enter product name")
                return@setOnClickListener
            }

            val price = priceText.toDouble()

            lifecycleScope.launch {
                db.productDao().insert(
                    Product(
                        name = name,
                        price = price,
                        isCustom = (selectedItem == "Others")
                    )
                )

                runOnUiThread {
                    toast("Product added")
                    dialog.dismiss()
                    finish()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}