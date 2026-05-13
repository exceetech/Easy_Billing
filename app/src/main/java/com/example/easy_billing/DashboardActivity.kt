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
import com.example.easy_billing.util.applyPremiumClickAnimation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.EditText
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext


class DashboardActivity : BaseActivity() {

    // ================= UI =================
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvProducts: RecyclerView
    private lateinit var rvCart: RecyclerView
    private lateinit var tvTotal: TextView
    private lateinit var tvCartBadge: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvWelcome: TextView

    private lateinit var tvDrawerNameBase: TextView
    private lateinit var cardNoticeBoard: MaterialCardView

    private var searchRunnable: Runnable? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ================= Adapters =================
    private lateinit var productAdapter: ProductAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var tvNoticeBoard: TextView
    private var aiAnimationJob: kotlinx.coroutines.Job? = null

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
        setupGreeting()

        // ✨ AI insight card animations — orb pulse + live-dot fade.
        startAiCardAnimations()

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

        // ✨ Premium indicators: Advanced Status Animation
        startLiveStatusAnimation()

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

        NetworkReceiver(this).startListening()
        checkSubscription()

        // Conflict-safe sync: pushes any locally-pending writes
        // first (so user edits win), then pulls fresh server state
        // for the read-mostly tables. No-op if offline.
        com.example.easy_billing.sync.SyncCoordinator
            .get(this)
            .flushPending()

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
                println("🔄 Syncing inventory (push logs -> pull master)")
                syncManager.syncInventory()
                syncManager.pullInventory()

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
        tvDrawerNameBase = findViewById(R.id.tvDrawerNameBase)
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
                val ownerName = profile.owner_name ?: "Store Owner"

                // 🔥 Split name for premium UI
                val parts = ownerName.trim().split(" ")
                
                if (parts.isNotEmpty()) {
                    tvDrawerNameBase.text = ownerName
                } else {
                    tvDrawerNameBase.text = "Store"
                }

                // ✅ Welcome text
                typeWriter(
                    tvWelcome,
                    "Welcome to $shopName Dashboard 👋"
                )

            } catch (e: Exception) {

                typeWriter(tvWelcome, "Welcome to Dashboard 👋")

                // 🔥 fallback
                tvDrawerNameBase.text = "Store"
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

    /**
     * "Add/Edit Product" drawer entry now routes through a 2-option
     * chooser:
     *   • Add Purchased Product   → [PurchaseActivity]
     *   • Add Non-Purchased       → [AddProductsActivity] (existing
     *                                manual-product flow)
     */
    private fun showAddEditProductChooser() {
        val view = layoutInflater.inflate(R.layout.dialog_add_product_chooser, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<MaterialButton>(R.id.btnPurchasedProduct).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, PurchaseActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnNonPurchasedProduct).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddProductsActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnManageProducts).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ManageProductsActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnChooserCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setupDrawerButtons() {

        findViewById<View>(R.id.btnAdmin).setOnClickListener {
            showAddEditProductChooser()
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {

            startActivity(
                Intent(this, SettingsActivity::class.java)
            )

            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnReports).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnPreviousBills).setOnClickListener {
            startActivity(Intent(this, BillHistoryActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnGstReports).setOnClickListener {
            startActivity(Intent(this, GstReportsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnCreditAccounts).setOnClickListener {
            startActivity(Intent(this, CreditAccountsActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnInventory).setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnPurchase).setOnClickListener {
            startActivity(Intent(this, PurchaseActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnProfit).setOnClickListener {
            startActivity(Intent(this, ProfitActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnSubscription).setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnAiInsights).setOnClickListener {

            startActivity(
                Intent(this, AiDashboardActivity::class.java)
            )

            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnLogout).setOnClickListener {

            getSharedPreferences("auth", MODE_PRIVATE)
                .edit {
                    remove("TOKEN")
                }

            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        findViewById<View>(R.id.btnGenerateBill).apply {
            applyPremiumClickAnimation()
            setOnClickListener {
                generateBill()
            }
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
        val productRepo = com.example.easy_billing.repository.ProductRepository.get(this)
        val currentShopId = productRepo.currentShopId()

        try {
            val backendProducts =
                RetrofitClient.api.getMyProducts("Bearer $token")

            val existingProducts = db.productDao().getAllWithInactive()
            val existingMap = existingProducts.associateBy { it.id }

            backendProducts.forEach {

                val existing = existingMap[it.id]

                // Preserve all locally-tracked fields when merging
                // backend changes — otherwise hsnCode / cgst / sgst /
                // igst / isPurchased / shopId all get clobbered to
                // defaults on every dashboard refresh.
                val merged = (existing ?: Product(
                    id = it.id,
                    serverId = it.id,
                    name = it.name,
                    variant = it.variant ?: "",
                    unit = it.unit,
                    price = it.price,
                    trackInventory = true,
                    isCustom = false,
                    shopId = currentShopId
                )).copy(
                    id = it.id,
                    serverId = it.id,
                    name = it.name,
                    variant = it.variant ?: existing?.variant.orEmpty(),
                    unit = it.unit ?: existing?.unit,
                    price = it.price,
                    isActive = true,
                    shopId = existing?.shopId?.takeIf { sid -> sid.isNotBlank() }
                        ?: currentShopId
                )
                db.productDao().insert(merged)
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

        // 🔥 UI update — shop-scoped so cross-shop ghost rows from
        // a previous login on the same device don't bleed into the
        // tile grid.
        val localProducts = productRepo.getAllForCurrentShop()
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

            // ================= 🔥 INVENTORY LOGIC ONLY IF ENABLED =================

            if (product.trackInventory) {

                val inventory = db.inventoryDao().getInventory(product.id)

                if (inventory != null) {
                    // ❌ OUT OF STOCK
                    if (inventory.currentStock <= 0) {
                        Toast.makeText(this@DashboardActivity, "Out of stock", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // ❌ LIMIT EXCEEDED
                    if (newQty > inventory.currentStock) {

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

                            tvMessage.text = "Only $allowedQty available.\nDo you want to add available quantity?"
                            btnAdd.text = "Add $allowedQty"

                            btnCancel.setOnClickListener {
                                dialog.dismiss()
                            }

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
                }
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

        // ✅ Total (taxes included, no extra label prefix)
        tvTotal.text = CurrencyHelper.format(this, total)

        // Badge — clean number, max 99+
        val count = cartItems.size
        val badgeText = if (count > 99) "99+" else count.toString()
        tvCartBadge.text = badgeText

        // Also update the cart drawer's neon badge
        findViewById<TextView>(R.id.tvCartDrawerBadge)?.text = badgeText
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

                            val updatedList = com.example.easy_billing.repository
                                .ProductRepository.get(this@DashboardActivity)
                                .getAllForCurrentShop()

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

                val rawText = response.ai_report
                // Robust split: handle dots, newlines, and bullet points
                val insightsList = rawText.split(Regex("[●\n*]+")).map { it.trim() }.filter { it.isNotBlank() }

                if (insightsList.isEmpty()) {
                    tvNoticeBoard.text = "Fetching intelligence..."
                    return@launch
                }

                aiAnimationJob?.cancel()
                aiAnimationJob = lifecycleScope.launch {
                    var currentIndex = 0
                    while (isActive) {
                        val insight = insightsList[currentIndex]
                        
                        var cleanText = insight.trim()
                            .replace(Regex("</?.*?>"), "")
                            .replace(Regex("\\{.*?:.*?\\}"), "")
                            .replace(Regex("[\\[\\]*`]+"), "")
                            .replace("Rs.", "₹") // Transform for premium look
                            .replace("RS", "₹")
                            .trim()
                            .uppercase()

                        if (cleanText.isEmpty()) {
                            currentIndex = (currentIndex + 1) % insightsList.size
                            continue
                        }

                        // ✦ PREMIUM JEWELED HIGHLIGHT PALETTE
                        val (primaryText, accentBg, highlightText) = when {
                            cleanText.contains("PROFIT") || cleanText.contains("INCREASE") || cleanText.contains("GROWTH") -> 
                                Triple("#1E293B", "#ECFDF5", "#059669")
                            cleanText.contains("ALERT") || cleanText.contains("DECREASE") || cleanText.contains("LOW") || cleanText.contains("STOCK") -> 
                                Triple("#1E293B", "#FEF2F2", "#DC2626")
                            cleanText.contains("TOP") || cleanText.contains("TRENDING") || cleanText.contains("PREMIUM") -> 
                                Triple("#1E293B", "#FFFBEB", "#D97706")
                            else -> 
                                Triple("#1E293B", "#EEF2FF", "#4F46E5")
                        }

                        val spannable = android.text.SpannableStringBuilder()
                        
                        // 1. Structural Anchor
                        spannable.append("◢ ")
                        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(highlightText)), 0, 2, 0)
                        
                        // 2. The Body (Executive Serif)
                        spannable.append(cleanText.lowercase().replaceFirstChar { it.uppercase() })
                        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(primaryText)), 2, spannable.length, 0)
                        
                        // 3. THE JEWELED CAPSULES (Supports ₹, %, and numbers)
                        val highlightRegex = Regex("(\\d+%|₹\\s?\\d+[\\d,.]*)")
                        highlightRegex.findAll(spannable).forEach { match ->
                            val start = match.range.first
                            val end = match.range.last + 1
                            
                            spannable.setSpan(
                                RoundedBackgroundSpan(
                                    android.graphics.Color.parseColor(accentBg), 
                                    android.graphics.Color.parseColor(highlightText),
                                    6f // Slightly rounder for 'Premium' feel
                                ), 
                                start, end, 0
                            )
                            spannable.setSpan(android.text.style.TypefaceSpan("monospace"), start, end, 0)
                            spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0)
                        }

                        withContext(Dispatchers.Main) {
                            // ✦ SUBTLE METALLIC SHIMMER
                            val paint = tvNoticeBoard.paint
                            val width = paint.measureText(spannable.toString())
                            
                            val flowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f)
                            flowAnimator.duration = 6000 
                            flowAnimator.repeatCount = android.animation.ValueAnimator.INFINITE
                            flowAnimator.addUpdateListener { animator ->
                                val progress = animator.animatedValue as Float
                                val shader = android.graphics.LinearGradient(
                                    0f, 0f, width * progress, 0f,
                                    intArrayOf(
                                        android.graphics.Color.parseColor(primaryText), 
                                        android.graphics.Color.parseColor("#94A3B8"), // Muted Glint
                                        android.graphics.Color.parseColor(primaryText)
                                    ), 
                                    floatArrayOf(0.45f, 0.5f, 0.55f),
                                    android.graphics.Shader.TileMode.MIRROR
                                )
                                tvNoticeBoard.paint.shader = shader
                                tvNoticeBoard.invalidate()
                            }
                            flowAnimator.start()

                            tvNoticeBoard.text = spannable
                            tvNoticeBoard.alpha = 0f
                            tvNoticeBoard.translationX = 60f
                            tvNoticeBoard.letterSpacing = 0.05f // Natural spacing for Serif
                            
                            tvNoticeBoard.animate()
                                .alpha(1f)
                                .translationX(0f)
                                .setDuration(1200)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                                
                            lifecycleScope.launch {
                                try {
                                    kotlinx.coroutines.delay(6000)
                                    flowAnimator.cancel()
                                } catch (e: Exception) {}
                            }
                        }
                        kotlinx.coroutines.delay(6500) 

                        withContext(Dispatchers.Main) {
                            tvNoticeBoard.animate()
                                .alpha(0f)
                                .translationX(-80f)
                                .setDuration(500)
                                .start()
                        }
                        kotlinx.coroutines.delay(600) 

                        currentIndex = (currentIndex + 1) % insightsList.size
                    }
                }

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

    /**
     * Slim ticker has only one moving piece now — the phosphor
     * "live" dot fades 1.0 ↔ 0.35 every 1.2 s. The classic live
     * indicator heartbeat, nothing more.
     */
    private fun startAiCardAnimations() {
        // Redundant - Badges removed from layout
    }

    /** Reusable infinite-reverse pulse driver for the AI card. */
    private fun animatePulse(
        viewId: Int,
        durationMs: Long,
        from: Float,
        to: Float,
        alphaTo: Float? = null
    ) {
        val view = findViewById<android.view.View>(viewId) ?: return
        val animators = mutableListOf<android.animation.ObjectAnimator>(
            android.animation.ObjectAnimator.ofFloat(view, "scaleX", from, to),
            android.animation.ObjectAnimator.ofFloat(view, "scaleY", from, to)
        )
        if (alphaTo != null) {
            animators += android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, alphaTo)
        }
        animators.forEach {
            it.duration = durationMs
            it.repeatCount = android.animation.ObjectAnimator.INFINITE
            it.repeatMode  = android.animation.ObjectAnimator.REVERSE
            it.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            it.start()
        }
    }

    /**
     * Replicates the "Secure Login" breathing animation from MainActivity
     * for premium text indicators.
     */
    private fun applyBreathingAnimation(viewId: Int, glowColorHex: String) {
        val tv = findViewById<TextView>(viewId) ?: return

        // Smooth alpha breathing
        val alphaAnim = android.animation.ObjectAnimator.ofFloat(tv, View.ALPHA, 0.5f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        // Slight premium scale effect
        val scaleX = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_X, 0.98f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        val scaleY = android.animation.ObjectAnimator.ofFloat(tv, View.SCALE_Y, 0.98f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }

        // Subtle glow (matches the emerald green indicator line)
        val glowAnim = android.animation.ValueAnimator.ofFloat(0.2f, 0.8f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE

            addUpdateListener {
                val alpha = it.animatedValue as Float
                tv.setShadowLayer(
                    8f * alpha,
                    0f,
                    0f,
                    android.graphics.Color.parseColor(glowColorHex)
                )
            }
        }

        android.animation.AnimatorSet().apply {
            playTogether(alphaAnim, scaleX, scaleY, glowAnim)
            start()
        }
    }

    private fun startLiveStatusAnimation() {
        // --- 1. Right Drawer (Cart) Indicator ---
        animateCyberTag(R.id.viewLiveRing, R.id.viewLiveDot)

        // --- 2. Left Drawer (Nav) Indicator ---
        animateCyberTag(R.id.viewNavRing, R.id.viewNavDot)
    }

    private fun animateCyberTag(ringId: Int, dotId: Int) {
        val ring = findViewById<View>(ringId) ?: return
        val dot = findViewById<View>(dotId) ?: return

        // 1. Sonar Pulse for the ring (Expanding & Fading)
        val scaleX = android.animation.ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 2.5f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 2.5f)
        val alpha = android.animation.ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0f)

        android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 2000
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            android.animation.ValueAnimator.INFINITE.let {
                scaleX.repeatCount = it
                scaleY.repeatCount = it
                alpha.repeatCount = it
            }
            start()
        }

        // 2. Core Breathing for the dot
        val dotScaleX = android.animation.ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.4f)
        val dotScaleY = android.animation.ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.4f)

        android.animation.AnimatorSet().apply {
            playTogether(dotScaleX, dotScaleY)
            duration = 1000
            android.animation.ValueAnimator.INFINITE.let {
                dotScaleX.repeatCount = it
                dotScaleY.repeatCount = it
                dotScaleX.repeatMode = android.animation.ValueAnimator.REVERSE
                dotScaleY.repeatMode = android.animation.ValueAnimator.REVERSE
            }
            start()
        }
    }

    private fun setupGreeting() {
        val tvGreeting = findViewById<TextView>(R.id.tvGreeting) ?: return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..11 -> "Good Morning ☀️"
            in 12..16 -> "Good Afternoon 🌤️"
            in 17..20 -> "Good Evening 🌙"
            else -> "Good Night 🌠"
        }
        tvGreeting.text = greeting
    }

    /** Custom Span for creating high-end 'Glass Capsule' backgrounds for numbers. */
    class RoundedBackgroundSpan(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val cornerRadius: Float
    ) : android.text.style.ReplacementSpan() {

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            // ✦ CRITICAL FIX: Store and clear shader to avoid 'Black Box' interference
            val originalShader = paint.shader
            paint.shader = null 

            val rect = android.graphics.RectF(
                x, 
                top.toFloat() + 2f, 
                x + paint.measureText(text, start, end) + 20f, 
                bottom.toFloat() - 2f
            )
            paint.color = backgroundColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            
            paint.color = textColor
            paint.isFakeBoldText = true // Ensure readability in capsule
            canvas.drawText(text, start, end, x + 10f, y.toFloat(), paint)

            // Restore shader for subsequent text rendering
            paint.shader = originalShader
        }

        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int {
            return (paint.measureText(text, start, end) + 20f).toInt()
        }
    }
}
