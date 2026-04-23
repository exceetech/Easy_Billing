package com.example.easy_billing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.CartAdapter
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.SaveTokenRequest
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.DeviceUtils
import com.example.easy_billing.util.NetworkReceiver
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class DashboardActivity : BaseActivity() {

    // ================= UI =================
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvProducts: RecyclerView
    private lateinit var rvCart: RecyclerView
    private lateinit var tvTotal: TextView
    private lateinit var tvCartBadge: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvWelcome: TextView
    private lateinit var cardNoticeBoard: MaterialCardView

    private var searchRunnable: Runnable? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ================= Adapters =================
    private lateinit var productAdapter: ProductAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var tvNoticeBoard: TextView

    private val noticeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var currentLanguage = "en"

    private var currentSort = SortType.A_TO_Z

    private lateinit var switchTranslate: SwitchMaterial

    private val noticeRunnable = object : Runnable {
        override fun run() {
            loadAiNoticeBoard()
            noticeHandler.postDelayed(this, 5 * 60 * 1000) // 5 minutes
        }
    }
    // ================= Data =================
    private val cartItems = mutableListOf<CartItem>()


    // ================= Activity Result =================
    private val invoiceLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                clearCart()

                lifecycleScope.launch {
                    val syncManager = SyncManager(this@DashboardActivity)
                    syncManager.syncBills()
                    syncManager.pullAccountsFromServer()
                    syncManager.syncAccounts()
                    syncManager.syncCredit()

                    // 🔥 ADD THIS
                    loadProducts()
                }
            }
        }

    // ==================================================
    // ================= ON CREATE ======================
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        NetworkReceiver(this).startListening()
        validateLocalDevice()

        // ✅ Initialize views FIRST
        initViews()

        checkSubscription()

        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )

        etSearch.clearFocus()
        setupOutsideTouch()

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        // ✅ Language
        currentLanguage = prefs.getString("app_language", "en") ?: "en"

        // ✅ Translation toggle
        switchTranslate.isChecked =
            prefs.getBoolean("translation_enabled", true)

        switchTranslate.setOnCheckedChangeListener { _, isChecked ->

            prefs.edit { putBoolean("translation_enabled", isChecked) }

            Toast.makeText(
                this,
                if (isChecked) "Translation ON" else "Translation OFF",
                Toast.LENGTH_SHORT
            ).show()

            setupRecyclerViews()

            lifecycleScope.launch {
                loadProducts() // ✅ safe coroutine call
            }
        }

        // ✅ Notification permission
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        setupHeader()
        setupRecyclerViews()
        setupDrawerButtons()
        setupSearch()
        updateTotal()

        getFcmToken()
        createNotificationChannel()

        loadAiNoticeBoard()
        noticeHandler.postDelayed(noticeRunnable, 5 * 60 * 1000)

    }

    override fun onDestroy() {
        super.onDestroy()
        noticeHandler.removeCallbacks(noticeRunnable)
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "easy_billing_channel",
                "Easy Billing Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getFcmToken() {

        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->

                if (!task.isSuccessful) {
                    println("FCM TOKEN FAILED")
                    return@addOnCompleteListener
                }

                val fcmToken = task.result

                println("FCM TOKEN FROM FIREBASE: $fcmToken")

                lifecycleScope.launch {

                    val authToken = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)

                    if (authToken == null) {
                        println("AUTH TOKEN NULL")
                        return@launch
                    }

                    try {

                        val response = RetrofitClient.api.saveFcmToken(
                            "Bearer $authToken",
                            SaveTokenRequest(fcmToken)
                        )

                        if (response.isSuccessful) {

                            println("FCM TOKEN SAVED SUCCESSFULLY")

                        } else {

                            println("BACKEND ERROR: ${response.code()}")

                        }

                    } catch (e: Exception) {

                        println("FAILED TO SEND TOKEN: ${e.message}")

                    }
                }
            }
    }

    override fun onResume() {
        super.onResume()

        checkSubscription()

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@DashboardActivity)
            val syncManager = SyncManager(this@DashboardActivity)

            if (token != null) {

                // ================= STEP 1: PRODUCTS =================
                loadProducts()

                // ================= STEP 2: BASIC =================
                syncManager.syncStoreInfo()
                syncManager.syncBillingSettings()

                // ================= STEP 3: ACCOUNTS =================
                syncManager.syncAccounts()
                syncManager.pullAccountsFromServer()

                // ================= STEP 4: INVENTORY =================
                val isInventoryEmpty = db.inventoryDao().getAll().isEmpty()

                if (isInventoryEmpty) {

                    println("🆕 Fresh install → pulling inventory")
                    syncManager.pullInventory()

                } else {

                    println("🔄 Existing user → syncing logs")
                    syncManager.syncInventory()
                }

                // ================= STEP 5: BILLS =================
                syncManager.syncBills()

                // ================= STEP 6: CREDIT =================
                syncManager.syncCredit()

                // ================= STEP 7: FINAL UI =================
                loadProducts()        // 🔥 refresh stock in tiles
                loadStoreFromRoom()

            } else {
                loadProducts()
            }
        }
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
        tvWelcome = findViewById(R.id.tvWelcome)
        tvNoticeBoard = findViewById(R.id.tvNoticeBoard)
        cardNoticeBoard = findViewById(R.id.cardNoticeBoard)

        switchTranslate = findViewById(R.id.switchTranslate)
    }

    private fun setupHeader() {
        val btnMenu = findViewById<LinearLayout>(R.id.btnMenuContainer)
        val btnSort = findViewById<LinearLayout>(R.id.btnSortContainer)
        val btnCart = findViewById<LinearLayout>(R.id.btnCartContainer)

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        if (token != null) {
            lifecycleScope.launch {
                try {
                    loadShopName()
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

        btnSort.setOnClickListener {

            val popup = PopupMenu(this, btnSort, Gravity.END)
            popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)

            popup.setOnMenuItemClickListener { item ->

                when (item.itemId) {

                    R.id.sort_az -> {
                        sortProducts("AZ")
                        true
                    }

                    R.id.sort_za -> {
                        sortProducts("ZA")
                        true
                    }

                    else -> false
                }
            }

            popup.show()
        }

        btnCart.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    fun typeWriter(textView: TextView, message: String, delay: Long = 40) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var index = 0

        handler.post(object : Runnable {
            override fun run() {
                if (index <= message.length) {
                    textView.text = message.substring(0, index)
                    index++
                    handler.postDelayed(this, delay)
                }
            }
        })
    }

    private fun loadShopName() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val profile = RetrofitClient.api.getProfile("Bearer $token")

                val shopName = profile.shop_name

                typeWriter(
                    tvWelcome,
                    "Welcome to $shopName Dashboard 👋"
                )

            } catch (e: Exception) {

                typeWriter(tvWelcome, "Welcome to Dashboard 👋")

            }
        }
    }
    private fun setupRecyclerViews() {

        rvProducts.layoutManager = GridLayoutManager(this, 5)
        rvCart.layoutManager = LinearLayoutManager(this)

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val translationEnabled =
            prefs.getBoolean("translation_enabled", true)

        // Initialize productAdapter HERE
        productAdapter = ProductAdapter(
            language = currentLanguage,
            translationEnabled = translationEnabled,
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

        findViewById<MaterialButton>(R.id.btnAdmin).setOnClickListener {
            startActivity(Intent(this, AddProductsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {

            startActivity(
                Intent(this, SettingsActivity::class.java)
            )

            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnReports).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnPreviousBills).setOnClickListener {
            startActivity(Intent(this, BillHistoryActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnCreditAccounts).setOnClickListener {
            startActivity(Intent(this, CreditAccountsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnInventory).setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnProfit).setOnClickListener {
            startActivity(Intent(this, ProfitActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnSubscription).setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<MaterialButton>(R.id.btnAiInsights).setOnClickListener {

            startActivity(
                Intent(this, AiDashboardActivity::class.java)
            )

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

        findViewById<MaterialButton>(R.id.btnGenerateBill).setOnClickListener {
            generateBill()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        if (currentFocus == etSearch) {

            val outRect = android.graphics.Rect()
            etSearch.getGlobalVisibleRect(outRect)

            if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {

                // ✅ Clear text
                etSearch.text?.clear()

                // ✅ Remove focus
                etSearch.clearFocus()

                // ✅ Hide keyboard
                val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager

                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

                // ✅ Reset list (IMPORTANT for POS)
                productAdapter.filter("")
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun setupSearch() {

        etSearch.clearFocus()

        etSearch.addTextChangedListener(object : android.text.TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                // Cancel previous search (debounce)
                searchRunnable?.let { searchHandler.removeCallbacks(it) }

                searchRunnable = Runnable {

                    val query = s?.toString()
                        ?.trim()
                        ?.take(50) // limit length (security + performance)
                        ?: ""

                    if (query.isEmpty()) {
                        productAdapter.filter("")
                    } else {
                        productAdapter.filter(query)
                    }
                }

                // Delay search by 300ms
                searchHandler.postDelayed(searchRunnable!!, 300)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ==================================================
    // ================= DATA LOADING ===================
    // ==================================================

    private suspend fun loadProducts() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        val db = AppDatabase.getDatabase(this@DashboardActivity)

        try {
            val backendProducts =
                RetrofitClient.api.getMyProducts("Bearer $token")

            val existingProducts = db.productDao().getAll()
            val existingMap = existingProducts.associateBy { it.id }

            backendProducts.forEach {

                val existing = existingMap[it.id]

                db.productDao().insert(
                    Product(
                        id = it.id,
                        serverId = it.id,   // ✅ correct
                        name = it.name,
                        variant = it.variant ?: "",
                        unit = it.unit,
                        price = it.price,
                        trackInventory = existing?.trackInventory ?: true,
                        isCustom = false
                    )
                )
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@DashboardActivity,
                    "Failed to load products",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 🔥 UI update
        val localProducts = db.productDao().getAll()
        val inventoryList = db.inventoryDao().getAll()

        val inventoryMap = inventoryList
            .filter { it.isActive }
            .associate {
                it.productId to it.currentStock
            }

        val sortedList = when (currentSort) {
            SortType.A_TO_Z -> localProducts.sortedBy { it.name.lowercase() }
            SortType.Z_TO_A -> localProducts.sortedByDescending { it.name.lowercase() }
        }

        withContext(Dispatchers.Main) {
            productAdapter.updateData(sortedList)
            productAdapter.setInventoryMap(inventoryMap)
        }
    }

    // ==================================================
    // ================= CART LOGIC =====================
    // ==================================================

    private fun addToCart(product: Product, qty: Double) {

        val MAX_QTY = 10000.0

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@DashboardActivity)
            val inventory = db.inventoryDao().getInventory(product.id)

            // ================= 🔥 INVENTORY CHECK =================

            if (product.trackInventory) {

                if (inventory == null || inventory.currentStock <= 0) {
                    Toast.makeText(this@DashboardActivity, "Out of stock", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }

            val existing = cartItems.find { it.product.id == product.id }

            val currentQty = existing?.quantity ?: 0.0
            val newQty = currentQty + qty

            // ================= 🔥 MAX LIMIT =================

            if (newQty > MAX_QTY) {

                Toast.makeText(this@DashboardActivity, "Max quantity limit reached", Toast.LENGTH_SHORT).show()

                if (existing != null) {
                    existing.quantity = MAX_QTY
                    cartAdapter.notifyDataSetChanged()
                    updateTotal()
                }

                return@launch
            }

            // ================= 🔥 STOCK LIMIT CHECK =================

            if (product.trackInventory && inventory != null && newQty > inventory.currentStock) {

                val allowedQty = inventory.currentStock

                runOnUiThread {

                    val view = layoutInflater.inflate(R.layout.dialog_limited_stock, null)

                    val dialog = AlertDialog.Builder(this@DashboardActivity)
                        .setView(view)
                        .create()

                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                    val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
                    val btnCancel = view.findViewById<Button>(R.id.btnCancel)
                    val btnAdd = view.findViewById<Button>(R.id.btnAddAvailable)

                    // 🔥 Dynamic text
                    tvMessage.text = "Only $allowedQty available.\nDo you want to add available quantity?"
                    btnAdd.text = "Add $allowedQty"

                    // ❌ Cancel
                    btnCancel.setOnClickListener {
                        dialog.dismiss()
                    }

                    // ✅ Add Available
                    btnAdd.setOnClickListener {

                        if (existing != null) {
                            existing.quantity = allowedQty
                        } else {
                            cartItems.add(
                                CartItem(
                                    product = product,
                                    quantity = allowedQty
                                )
                            )
                        }

                        cartAdapter.notifyDataSetChanged()
                        updateTotal()

                        dialog.dismiss()
                    }

                    dialog.show()
                }

                return@launch
            }

            // ================= 🔥 NORMAL FLOW =================

            if (existing != null) {
                existing.quantity = newQty
            } else {
                cartItems.add(
                    CartItem(
                        product = product,
                        quantity = qty
                    )
                )
            }

            cartAdapter.notifyDataSetChanged()
            updateTotal()
        }
    }

    private fun updateTotal() {

        val total = cartItems.sumOf { it.subTotal() }

        // ✅ Dynamic currency
        tvTotal.text = "Total: ${CurrencyHelper.format(this, total)}"

        val count = cartItems.size

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

        // 🚀 ONLY PASS DATA → DO NOT SAVE HERE
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
        val btnAdd = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddQty)
        val btnBackspace = dialogView.findViewById<ImageButton>(R.id.btnBackspace)

        var quantityStr = ""

        val isDecimalAllowed = when (product.unit?.lowercase()) {
            "kilogram", "kg", "litre", "l" -> true
            else -> false
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.55).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 🔥 FAST DISPLAY (no lag)
        fun updateDisplay() {
            tvQuantity.text = if (quantityStr.isEmpty()) "0" else quantityStr
        }

        // 🔥 BUTTON PRESS ANIMATION
        fun View.pressAnim() {
            this.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60)
                .withEndAction {
                    this.animate().scaleX(1f).scaleY(1f).setDuration(60)
                }
        }

        // 🔥 HAPTIC FEEDBACK
        fun View.vibrate() {
            this.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // 🔥 KEYPAD LOOP
        for (i in 0 until gridPad.childCount) {

            val btn = gridPad.getChildAt(i)
            if (btn !is com.google.android.material.button.MaterialButton) continue

            val key = btn.text.toString()

            // 🔥 HIDE DOT FOR NON-DECIMAL ITEMS
            if (key == "." && !isDecimalAllowed) {
                btn.visibility = View.GONE
                continue
            }

            btn.setOnClickListener {

                btn.pressAnim()
                btn.vibrate()

                when {

                    key == "C" -> {
                        quantityStr = ""
                    }

                    key == "." -> {

                        if (quantityStr.contains(".")) return@setOnClickListener

                        quantityStr = if (quantityStr.isEmpty()) "0." else "$quantityStr."
                    }

                    key.all { it.isDigit() } -> {

                        // prevent too long input
                        if (quantityStr.length >= 7) return@setOnClickListener

                        // limit decimal precision
                        if (quantityStr.contains(".")) {
                            val parts = quantityStr.split(".")
                            if (parts.size == 2 && parts[1].length >= 3) return@setOnClickListener
                        }

                        quantityStr += key
                    }
                }

                updateDisplay()
            }
        }

        // 🔥 BACKSPACE
        btnBackspace.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

            if (quantityStr.isNotEmpty()) {
                quantityStr = quantityStr.dropLast(1)
                updateDisplay()
            }
        }

        btnBackspace.setOnLongClickListener {
            quantityStr = ""
            updateDisplay()
            true
        }

        // 🔥 ADD BUTTON
        btnAdd.setOnClickListener {

            val quantity = quantityStr.toDoubleOrNull()

            if (quantity == null || quantity <= 0) {
                Toast.makeText(this, "Enter valid quantity", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addToCart(product, quantity)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDeleteDialog(product: Product) {

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@DashboardActivity)

            val stockQty = InventoryManager.getTotalStock(
                db = db,
                productId = product.id
            )

            val view = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)

            val dialog = AlertDialog.Builder(this@DashboardActivity)
                .setView(view)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
            val btnDelete = view.findViewById<Button>(R.id.btnDelete)
            val btnCancel = view.findViewById<Button>(R.id.btnCancel)

            // ================= BLOCK DELETE =================
            if (product.trackInventory && stockQty > 0.0) {

                tvMessage.text =
                    "⚠️ Cannot remove ${product.name}\n\nStock available: $stockQty\n\nReduce stock to 0 first."

                btnDelete.text = "OK"

                btnDelete.setOnClickListener {
                    dialog.dismiss()
                }

            } else {

                // ================= ALLOW DEACTIVATE =================
                tvMessage.text = "Remove ${product.name}?"

                btnDelete.text = "Remove"

                btnDelete.setOnClickListener {

                    dialog.dismiss()

                    lifecycleScope.launch {

                        val token = getSharedPreferences("auth", MODE_PRIVATE)
                            .getString("TOKEN", null)

                        try {

                            // 🔥 BACKEND
                            product.serverId?.let { sid ->
                                RetrofitClient.api.deactivateProduct(
                                    "Bearer $token",
                                    sid
                                )
                            }

                            // 🔥 LOCAL SOFT DELETE
                            db.productDao().deactivate(product.id)

                            // 🔥 ALSO DEACTIVATE INVENTORY
                            val inventory = db.inventoryDao().getInventory(product.id)
                            if (inventory != null) {
                                db.inventoryDao().update(
                                    inventory.copy(isActive = false)
                                )
                            }

                            val updatedList = db.productDao().getAll()

                            withContext(Dispatchers.Main) {
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
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun loadAiNoticeBoard() {

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        // 🔥 STEP 1: CHECK RESET FLAG
        val isReset = prefs.getBoolean("ai_reset", false)

        if (isReset) {
            tvNoticeBoard.text = "Your AI insights will appear after your first sale 🚀"
            return
        }

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            try {

                if (token.isNullOrEmpty()) {
                    tvNoticeBoard.text = "Session expired"
                    return@launch
                }

                val response = RetrofitClient.api.getAiReport("Bearer $token")

                // 🔥 STEP 2: EMPTY DATA CHECK
                if (response.report_data.isEmpty()) {
                    tvNoticeBoard.text = "Start selling to see insights 📊"
                    return@launch
                }

                var text = response.ai_report

                text = text.replace("&lt;", "<")
                text = text.replace("&gt;", ">")

                val noticeText = text.replace(
                    "\n",
                    "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                )

                tvNoticeBoard.text = Html.fromHtml(
                    noticeText,
                    Html.FROM_HTML_MODE_LEGACY
                )

                tvNoticeBoard.isSelected = true

                cardNoticeBoard.scaleX = 0.9f
                cardNoticeBoard.scaleY = 0.9f
                cardNoticeBoard.alpha = 0f

                cardNoticeBoard.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(400)

            } catch (e: Exception) {

                tvNoticeBoard.text = "AI insights unavailable"
            }
        }
    }

    private fun setupOutsideTouch() {

        val root = findViewById<View>(android.R.id.content)

        root.setOnTouchListener { _, _ ->

            // Clear text
            etSearch.text?.clear()

            // Remove focus
            etSearch.clearFocus()

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

            false
        }
    }

    // ================= SUBSCRIPTION CHECK =================

    private fun checkSubscription() {

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        // 🔴 If no token → logout
        if (token.isNullOrEmpty()) {
            redirectToLogin()
            return
        }

        lifecycleScope.launch {

            try {

                val res = RetrofitClient.api.getSubscription("Bearer $token")

                // 🔴 If not active → block user
                if (res.status != "active") {
                    showSubscriptionDialog()
                }

            } catch (e: retrofit2.HttpException) {

                // 🔴 Backend blocked (403)
                if (e.code() == 403) {
                    showSubscriptionDialog()
                }

            } catch (e: Exception) {

                Toast.makeText(
                    this@DashboardActivity,
                    "Unable to verify subscription",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showSubscriptionDialog() {

        AlertDialog.Builder(this)
            .setTitle("Subscription Required")
            .setMessage("Your subscription has expired. Please renew to continue using the app.")
            .setCancelable(false)
            .setPositiveButton("Renew") { _, _ ->

                startActivity(
                    Intent(this@DashboardActivity, SubscriptionActivity::class.java)
                )

                finish()
            }
            .show()
    }

    private fun redirectToLogin() {

        getSharedPreferences("auth", MODE_PRIVATE)
            .edit {
                remove("TOKEN")
            }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)
        finish()
    }

    private fun validateLocalDevice() {

        val prefs = getSharedPreferences("auth", MODE_PRIVATE)

        val savedDevice = prefs.getString("DEVICE_ID", null)
        val currentDevice = DeviceUtils.getDeviceId(this)

        if (savedDevice != currentDevice) {

            Toast.makeText(
                this,
                "Unauthorized device",
                Toast.LENGTH_LONG
            ).show()

            finishAffinity()
        }
    }

    private suspend fun loadStoreFromRoom() {

        val db = AppDatabase.getDatabase(this)
        val store = db.storeInfoDao().get()

        runOnUiThread {
            // wherever you show store name
            findViewById<TextView>(R.id.tvStoreName)?.text =
                store?.name ?: "My Store"
        }
    }

    enum class SortType {
        A_TO_Z,
        Z_TO_A,
    }

    private fun sortProducts(type: String) {

        lifecycleScope.launch(Dispatchers.Default) {

            val list = productAdapter.getCurrentList().toMutableList()

            val sorted = when (type) {

                "AZ" -> list.sortedBy { it.name.lowercase() }

                "ZA" -> list.sortedByDescending { it.name.lowercase() }

                else -> list
            }

            withContext(Dispatchers.Main) {
                productAdapter.submitList(sorted)
            }
        }
    }

}