package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.CartAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.RetrofitClient
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import androidx.core.content.edit
import com.example.easy_billing.db.ProductDao
import com.example.easy_billing.Product

class DashboardActivity : AppCompatActivity() {

    // ================= UI =================
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvProducts: RecyclerView
    private lateinit var rvCart: RecyclerView
    private lateinit var tvTotal: TextView
    private lateinit var tvCartBadge: TextView
    private lateinit var etSearch: TextInputEditText

    // ================= Adapters =================
    private lateinit var productAdapter: ProductAdapter
    private lateinit var cartAdapter: CartAdapter

    // ================= Data =================
    private val cartItems = mutableListOf<CartItem>()

    // ================= Activity Result =================
    private val invoiceLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                clearCart()
            }
        }

    // ==================================================
    // ================= ON CREATE ======================
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        initViews()
        setupHeader()
        setupRecyclerViews()
        setupDrawerButtons()
        setupSearch()
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    // ==================================================
    // ================= INITIALIZATION =================
    // ==================================================

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        rvProducts = findViewById(R.id.rvProducts)
        rvCart = findViewById(R.id.rvCart)
        tvTotal = findViewById(R.id.tvTotal)
        tvCartBadge = findViewById(R.id.tvCartBadge)
        etSearch = findViewById(R.id.etSearch)
    }

    private fun setupHeader() {
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)
        val btnCart = findViewById<ImageView>(R.id.btnCart)
        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        if (token != null) {
            lifecycleScope.launch {
                try {
                    val profile = RetrofitClient.api.getProfile("Bearer $token")
                    tvWelcome.text = "Welcome, ${profile.email} 👋"
                } catch (e: Exception) {

                    // Token invalid → logout automatically
                    getSharedPreferences("auth", MODE_PRIVATE)
                        .edit {
                            remove("TOKEN")
                        }

                    startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
                    finish()
                }
            }
        }

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnCart.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun setupRecyclerViews() {

        rvProducts.layoutManager = GridLayoutManager(this, 4)
        rvCart.layoutManager = LinearLayoutManager(this)

        // 🔥 Initialize productAdapter HERE
        productAdapter = ProductAdapter(
            onItemClick = { showQuantityDialog(it) },
            onItemLongClick = { showDeleteDialog(it) }
        )

        rvProducts.adapter = productAdapter

        cartAdapter = CartAdapter(
            cartItems,
            onQuantityChanged = { updateTotal() },
            onDelete = { item ->
                cartItems.remove(item)
                cartAdapter.notifyDataSetChanged()
                updateTotal()
            }
        )

        rvCart.adapter = cartAdapter
    }

    private fun setupDrawerButtons() {

        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            startActivity(Intent(this, AddProductsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<Button>(R.id.btnPreviousBills).setOnClickListener {
            startActivity(Intent(this, PreviousBillsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {

            getSharedPreferences("auth", MODE_PRIVATE)
                .edit {
                    remove("TOKEN")
                }

            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnGenerateBill).setOnClickListener {
            generateBill()
        }
    }

    private fun setupSearch() {
        etSearch.clearFocus() // Do NOT auto open keyboard

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                productAdapter.filter(s.toString())
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ==================================================
    // ================= DATA LOADING ===================
    // ==================================================

    private fun loadProducts() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@DashboardActivity)

            try {
                val backendProducts =
                    RetrofitClient.api.getMyProducts("Bearer $token")

                db.productDao().deleteAll()

                backendProducts.forEach {
                    db.productDao().insert(
                        Product(
                            id = it.id,
                            name = it.name,
                            price = it.price,
                            isCustom = false
                        )
                    )
                }

            } catch (e: Exception) {
                // Offline mode
            }

            val localProducts = db.productDao().getAll()

            productAdapter.updateData(localProducts)
        }
    }

    // ==================================================
    // ================= CART LOGIC =====================
    // ==================================================

    private fun addToCart(product: Product, qty: Int) {
        val existing = cartItems.find { it.product.id == product.id }

        if (existing != null) {
            existing.quantity += qty
        } else {
            cartItems.add(CartItem(product, qty))
        }

        cartAdapter.notifyDataSetChanged()
        updateTotal()
    }

    private fun updateTotal() {
        val total = cartItems.sumOf { it.subTotal() }
        tvTotal.text = "Total: ₹$total"

        val count = cartItems.sumOf { it.quantity }

        if (count <= 0) {
            tvCartBadge.visibility = View.GONE
        } else {
            tvCartBadge.visibility = View.VISIBLE
            tvCartBadge.text = if (count > 99) "99+" else count.toString()
        }
    }

    private fun clearCart() {
        cartItems.clear()
        cartAdapter.notifyDataSetChanged()
        updateTotal()
    }

    private fun generateBill() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, InvoiceActivity::class.java)
        intent.putExtra("CART_ITEMS", ArrayList(cartItems))
        invoiceLauncher.launch(intent)
    }

    // ==================================================
    // ================= DIALOGS ========================
    // ==================================================

    private fun showQuantityDialog(product: Product) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_quantity_pad, null)
        val tvQuantity = dialogView.findViewById<TextView>(R.id.tvQuantity)
        val gridPad = dialogView.findViewById<GridLayout>(R.id.gridPad)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddQty)

        var quantity = 0
        tvQuantity.text = ""

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        for (i in 0 until gridPad.childCount) {
            val btn = gridPad.getChildAt(i)
            if (btn !is Button) continue

            btn.setOnClickListener {
                when (btn.text.toString()) {
                    "C" -> quantity = 0
                    "⌫" -> quantity /= 10
                    else -> {
                        val digit = btn.text.toString().toInt()
                        quantity = (quantity * 10 + digit).coerceAtMost(999999)
                    }
                }
                tvQuantity.text = quantity.toString()
            }
        }

        btnAdd.setOnClickListener {
            if (quantity <= 0) {
                Toast.makeText(this, "Enter quantity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addToCart(product, quantity)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDeleteDialog(product: Product) {

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remove Product")
            .setMessage("Remove ${product.name}?")
            .setPositiveButton("Remove") { _, _ ->

                lifecycleScope.launch {

                    val db = AppDatabase.getDatabase(this@DashboardActivity)

                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    try {
                        // 1️⃣ Deactivate backend
                        RetrofitClient.api.deactivateProduct(
                            "Bearer $token",
                            product.id
                        )

                        // 2️⃣ Remove locally
                        db.productDao().deleteById(product.id)

                        // 3️⃣ Reload from Room
                        val updatedList = db.productDao().getAll()

                        runOnUiThread {
                            productAdapter.updateData(updatedList)
                        }

                    } catch (e: Exception) {
                        Toast.makeText(
                            this@DashboardActivity,
                            "Failed to remove product",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}