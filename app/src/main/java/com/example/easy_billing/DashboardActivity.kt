package com.example.easy_billing

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.CartAdapter
import com.example.easy_billing.adapter.ProductAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.model.CartItem
import kotlinx.coroutines.launch
import android.widget.GridLayout
import android.view.View
import android.widget.ImageView
import androidx.core.view.GravityCompat

class DashboardActivity : AppCompatActivity() {

    private lateinit var rvProducts: RecyclerView
    private lateinit var rvCart: RecyclerView
    private lateinit var tvTotal: TextView

    private val cartItems = mutableListOf<CartItem>()
    private lateinit var cartAdapter: CartAdapter

    private val invoiceLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {

                // ✅ Clear cart when Invoice closes
                cartItems.clear()
                cartAdapter.notifyDataSetChanged()
                updateTotal()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val btnCart = findViewById<ImageView>(R.id.btnCart)

        btnCart.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        val btnAdmin = findViewById<Button>(R.id.btnAdmin)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnGenerateBill = findViewById<Button>(R.id.btnGenerateBill)
        val btnPreviousBills = findViewById<Button>(R.id.btnPreviousBills)
        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        val userName = intent.getStringExtra("USER_NAME")
        tvWelcome.text = "Welcome, $userName 👋"

        rvProducts = findViewById(R.id.rvProducts)
        rvProducts.layoutManager = GridLayoutManager(this, 3)

        rvCart = findViewById(R.id.rvCart)
        rvCart.layoutManager = LinearLayoutManager(this)

        tvTotal = findViewById(R.id.tvTotal)

        cartAdapter = CartAdapter(
            cartItems,
            onQuantityChanged = {
                updateTotal()
            },
            onDelete = { item ->
                cartItems.remove(item)
                cartAdapter.notifyDataSetChanged()
                updateTotal()
            }
        )
        rvCart.adapter = cartAdapter

        btnAdmin.setOnClickListener {
            startActivity(Intent(this, AddProductsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        btnPreviousBills.setOnClickListener {
            startActivity(Intent(this, PreviousBillsActivity::class.java))
            drawerLayout.closeDrawers()   // 👈 closes drawer automatically
        }

        btnLogout.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        btnGenerateBill.setOnClickListener {

            if (cartItems.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, InvoiceActivity::class.java)
            intent.putExtra("CART_ITEMS", ArrayList(cartItems))  // ✅ pass cart
            invoiceLauncher.launch(intent)
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawers()
        }
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    // 🔹 Load products from Room
    private fun loadProducts() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@DashboardActivity)
            val products = db.productDao().getAllProducts()

            runOnUiThread {
                rvProducts.adapter = ProductAdapter(
                    products,
                    onItemClick = { product ->
                        showQuantityDialog(product)
                    },
                    onItemLongClick = { product ->
                        showDeleteDialog(product)
                    }
                )
            }
        }
    }

    // 🔹 Calculator-style quantity dialog
    private fun showQuantityDialog(product: Product) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_quantity_pad, null)
        val tvQuantity = dialogView.findViewById<TextView>(R.id.tvQuantity)
        val gridPad = dialogView.findViewById<GridLayout>(R.id.gridPad)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddQty)

        var quantity = 0
        tvQuantity.text = ""

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
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

    // 🔹 Add product to cart
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

    // 🔹 Update total price
    private fun updateTotal() {
        val total = cartItems.sumOf { it.subTotal() }
        tvTotal.text = "Total: ₹$total"
    }

    // 🔹 Delete product (long press)
    private fun showDeleteDialog(product: Product) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Product")
            .setMessage("Remove ${product.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(this@DashboardActivity)
                    db.productDao().deleteById(product.id)
                    loadProducts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}