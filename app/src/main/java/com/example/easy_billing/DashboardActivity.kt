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
import com.example.easy_billing.util.ProductCategories
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
    private lateinit var tvDrawerSales: TextView
    private lateinit var tvDrawerBills: TextView

    private lateinit var vpAiInsights: androidx.viewpager2.widget.ViewPager2
    private lateinit var fabAiInsights: View
    private lateinit var aiInsightsAdapter: AiInsightsAdapter

    private var searchRunnable: Runnable? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ================= Adapters =================
    private lateinit var productAdapter: ProductAdapter
    private lateinit var cartAdapter: CartAdapter
    private var aiAnimationJob: kotlinx.coroutines.Job? = null

    private val noticeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var currentLanguage = "en"

    private var currentSort = SortType.A_TO_Z

    // ── Sort state: canonical product list + precomputed sort keys ──
    // All maps are built ONCE per data load so a sort tap is a pure
    // in-memory reorder (no DB / network work), keeping it instant.
    private var allProducts: List<Product> = emptyList()
    private var stockMap: Map<Int, Double> = emptyMap()
    private var avgCostMap: Map<Int, Double> = emptyMap()
    private var soldQtyMap: Map<Int, Double> = emptyMap()
    private var revenueMap: Map<Int, Double> = emptyMap()
    private var profitMap: Map<Int, Double> = emptyMap()

    // ── Filter state (combinable; AND across groups, OR within a group) ──
    private var filterCategories: Set<String> = emptySet()
    private var filterStock: Set<StockStatus> = emptySet()
    private var filterPurchased: Boolean = false
    private var filterManual: Boolean = false
    private var filterPriceMin: Double? = null
    private var filterPriceMax: Double? = null

    private val lowStockThreshold = 5.0

    enum class StockStatus { IN_STOCK, LOW, OUT }

    // ── View mode (Grid tiles / List / Categorized) ──
    enum class ViewMode { GRID, LIST, CATEGORIZED }
    private var viewMode = ViewMode.GRID
    private val GRID_SPAN = 5
    private lateinit var productGridManager: GridLayoutManager

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
                            authToken,
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

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {

            if (token != null) {

                // Show local data immediately.
                loadProducts()

                // Single coordinated push + pull. Routing through SyncCoordinator
                // (instead of ad-hoc SyncManager calls) keeps this serialized with
                // write-triggered syncs via the single-flight lock, so non-bill rows
                // can't be double-pushed by a concurrent pass (Sync audit S1/S4).
                com.example.easy_billing.sync.SyncCoordinator
                    .get(this@DashboardActivity)
                    .flushPending()
                    .join()

                // Refresh UI from the freshly-synced local DB.
                loadProducts()        // 🔥 refresh stock in tiles
                loadStoreFromRoom()
                loadDrawerStats()

                // Surface sync health — only nag on genuine problems (failed
                // uploads / product-blocked records), never routine pending rows.
                val syncStatus = com.example.easy_billing.sync.SyncState.current
                if (syncStatus.hasProblems) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@DashboardActivity,
                            syncStatus.summary(),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

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
        tvDrawerNameBase = findViewById(R.id.tvDrawerNameBase)
        tvDrawerSales = findViewById(R.id.tvDrawerSales)
        tvDrawerBills = findViewById(R.id.tvDrawerBills)

        vpAiInsights = findViewById(R.id.vpAiInsights)
        fabAiInsights = findViewById(R.id.fabAiInsights)
        
        fabAiInsights.setOnClickListener {
            fabAiInsights.visibility = View.GONE
            vpAiInsights.visibility = View.VISIBLE
        }

        switchTranslate = findViewById(R.id.switchTranslate)

        // Drawer footer: app version
        try {
            val ver = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvDrawerVersion)?.text = "ExPOS · v$ver"
        } catch (_: Exception) {}
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

            val sortOptions = listOf(
                "Name: A → Z", "Name: Z → A",
                "Price: Low → High", "Price: High → Low",
                "Stock: Low → High", "Stock: High → Low",
                "Stock Value: High → Low", "Category",
                "Best Selling", "Top Revenue", "Most Profitable"
            )
            val sortTypes = listOf(
                SortType.A_TO_Z, SortType.Z_TO_A,
                SortType.PRICE_LOW_HIGH, SortType.PRICE_HIGH_LOW,
                SortType.STOCK_LOW_HIGH, SortType.STOCK_HIGH_LOW,
                SortType.STOCK_VALUE_HIGH_LOW, SortType.CATEGORY,
                SortType.BEST_SELLING, SortType.TOP_REVENUE, SortType.TOP_PROFIT
            )
            val current = sortTypes.indexOf(currentSort).coerceAtLeast(0)

            showOptionPopover(btnSort, sortOptions, current) { idx ->
                currentSort = sortTypes[idx]
                applySort()   // instant in-memory reorder
            }
        }

        findViewById<LinearLayout>(R.id.btnFilterContainer).setOnClickListener {
            showFilterSheet()
        }

        val btnView = findViewById<LinearLayout>(R.id.btnViewContainer)
        btnView.setOnClickListener {
            val options = listOf("Grid (tiles)", "List", "Categorized")
            val current = when (viewMode) {
                ViewMode.GRID -> 0
                ViewMode.LIST -> 1
                ViewMode.CATEGORIZED -> 2
            }
            showOptionPopover(btnView, options, current) { idx ->
                val newMode = when (idx) {
                    0 -> ViewMode.GRID
                    1 -> ViewMode.LIST
                    2 -> ViewMode.CATEGORIZED
                    else -> viewMode
                }
                if (newMode != viewMode) {
                    viewMode = newMode
                    updateViewModeIcon()
                    applySort()   // re-render in the new mode (instant)
                }
            }
        }

        btnCart.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun updateViewModeIcon() {
        val iv = findViewById<android.widget.ImageView>(R.id.ivViewMode)
        iv.setImageResource(
            when (viewMode) {
                ViewMode.GRID -> R.drawable.ic_hdr_view_grid
                ViewMode.LIST -> R.drawable.ic_hdr_view_list
                ViewMode.CATEGORIZED -> R.drawable.ic_hdr_view_categorized
            }
        )
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

    private fun loadDrawerStats() {
        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null) ?: return
        lifecycleScope.launch {
            try {
                val data = RetrofitClient.api.getOverview(token, "today", null, null)
                if (::tvDrawerSales.isInitialized) {
                    tvDrawerSales.text = CurrencyHelper.format(this@DashboardActivity, data.total_revenue)
                }
                if (::tvDrawerBills.isInitialized) {
                    tvDrawerBills.text = data.total_bills.toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadShopName() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch

            try {

                val profile = RetrofitClient.api.getProfile(token)

                val shopName = profile.shop_name
                val ownerName = profile.owner_name ?: "Owner"

                // 🔥 Split name for premium UI
                val parts = ownerName.trim().split(" ")
                
                if (parts.isNotEmpty()) {
                    tvDrawerNameBase.text = ownerName
                } else {
                    tvDrawerNameBase.text = "Store"
                }

                // Logout button email subtitle
                findViewById<TextView>(R.id.tvLogoutEmail)?.text = profile.email

                // ✅ Welcome text
                tvWelcome.text = shopName

            } catch (e: Exception) {

                tvWelcome.text = "Dashboard"

                // 🔥 fallback
                tvDrawerNameBase.text = "Store"
            }
        }
    }

    private fun setupRecyclerViews() {

        val gridManager = GridLayoutManager(this, GRID_SPAN)
        productGridManager = gridManager
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

        // Category headers span the full row; product tiles take 1 cell.
        gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (productAdapter.isHeader(position)) gridManager.spanCount else 1
        }

        rvProducts.layoutManager = gridManager
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

        findViewById<View>(R.id.btnPurchaseHistory).setOnClickListener {
            startActivity(Intent(this, PurchaseHistoryActivity::class.java))
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.btnImportServices).setOnClickListener {
            startActivity(Intent(this, com.example.easy_billing.ui.ImportServicesActivity::class.java))
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
            com.example.easy_billing.ui.ThemedDropdown.showConfirm(
                context = this,
                title = "Sign out?",
                message = "You'll need to log in again to access your store.",
                confirmLabel = "Sign out"
            ) {
                getSharedPreferences("auth", MODE_PRIVATE)
                    .edit {
                        remove("TOKEN")
                    }

                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
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
            if (!token.isNullOrEmpty()) {
                val backendProducts =
                    RetrofitClient.api.getMyProducts(token)

                val existingProducts = db.productDao().getAllWithInactive()

                // Key by serverId — Room local `id` and backend `serverId`
                // are independent numbers. The old code keyed by `id` but
                // looked up by server id, so existing was always null and
                // new rows were inserted with id = server_id (corrupting
                // Room's auto-increment and the shopId filter).
                val existingByServerId = existingProducts
                    .filter { it.serverId != null }
                    .associateBy { it.serverId!! }

                backendProducts.forEach { bp ->

                    var existing = existingByServerId[bp.id]
                    
                    if (existing == null) {
                        // Fallback check by name and variant to prevent breaking UNIQUE constraint
                        // if the product was created locally but sync failed
                        val validShopIds = listOf(currentShopId, "")
                        existing = existingProducts.firstOrNull { 
                            it.name.equals(bp.name, ignoreCase = true) && 
                            (it.variant ?: "") == (bp.variant ?: "") &&
                            validShopIds.contains(it.shopId)
                        }
                    }

                    if (existing != null) {
                        // Update existing — preserve Room local id, merge all
                        // backend fields including every GSTR-1 column.
                        // Always write currentShopId so the row is found by
                        // getAllForCurrentShop() regardless of what format
                        // the shopId was stored in previously.
                        db.productDao().update(
                            existing.copy(
                                serverId       = bp.id,
                                name           = bp.name,
                                variant        = bp.variant ?: existing.variant.orEmpty(),
                                unit           = bp.unit ?: existing.unit,
                                price          = bp.price,
                                isActive       = bp.is_active,
                                isPurchased    = bp.is_purchased,
                                shopId         = currentShopId,
                                hsnCode        = bp.hsn_code ?: existing.hsnCode,
                                defaultGstRate = bp.default_gst_rate ?: existing.defaultGstRate,
                                cgstPercentage = bp.cgst_percentage,
                                sgstPercentage = bp.sgst_percentage,
                                igstPercentage = bp.igst_percentage,
                                officialUqc    = bp.official_uqc ?: existing.officialUqc,
                                hsnDescription = bp.hsn_description ?: existing.hsnDescription,
                                cessRate       = bp.cess_rate,
                                category       = bp.category.ifBlank { existing.category }
                            )
                        )
                    } else {
                        // New product from backend — id = 0 so Room
                        // auto-generates the local primary key.
                        db.productDao().upsert(
                            Product(
                                id             = 0,
                                serverId       = bp.id,
                                name           = bp.name,
                                variant        = bp.variant ?: "",
                                unit           = bp.unit,
                                price          = bp.price,
                                trackInventory = true,
                                isCustom       = false,
                                isActive       = bp.is_active,
                                isPurchased    = bp.is_purchased,
                                shopId         = currentShopId,
                                hsnCode        = bp.hsn_code,
                                defaultGstRate = bp.default_gst_rate ?: 0.0,
                                cgstPercentage = bp.cgst_percentage,
                                sgstPercentage = bp.sgst_percentage,
                                igstPercentage = bp.igst_percentage,
                                officialUqc    = bp.official_uqc,
                                hsnDescription = bp.hsn_description,
                                cessRate       = bp.cess_rate,
                                category       = bp.category
                            )
                        )
                    }
                }
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

        val activeInv = inventoryList.filter { it.isActive }
        val inventoryMap = activeInv.associate { it.productId to it.currentStock }
        val costMap       = activeInv.associate { it.productId to it.averageCost }

        // Precompute sales aggregates in ONE GROUP BY query so the sales
        // sorts (best-selling / revenue / profit) are instant on tap.
        val agg = db.billItemDao().getSalesAggByProduct()
        val soldQty  = agg.associate { it.productId to it.qty }
        val revenue  = agg.associate { it.productId to it.revenue }
        val profit   = agg.associate { it.productId to it.profit }

        // Cache everything for the in-memory sorter.
        allProducts = localProducts
        stockMap    = inventoryMap
        avgCostMap  = costMap
        soldQtyMap  = soldQty
        revenueMap  = revenue
        profitMap   = profit

        withContext(Dispatchers.Main) {
            productAdapter.setInventoryMap(inventoryMap)
        }
        // Apply the current sort (uses the freshly cached maps).
        applySort()
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

        // Cart-header subtotal KPI (mirrors the live order total)
        findViewById<TextView>(R.id.tvCartHeaderSubtotal)?.text = CurrencyHelper.format(this, total)
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
        val tvProductName = dialogView.findViewById<TextView>(R.id.tvProductName)
        val tvUnitPrice = dialogView.findViewById<TextView>(R.id.tvUnitPrice)
        val tvLineTotal = dialogView.findViewById<TextView>(R.id.tvLineTotal)
        val btnCloseQty = dialogView.findViewById<View>(R.id.btnCloseQty)

        tvProductName.text = if (!product.variant.isNullOrBlank())
            "${product.name} · ${product.variant}"
        else product.name
        tvUnitPrice.text = CurrencyHelper.format(this, product.price)

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
            val q = quantityStr.toDoubleOrNull() ?: 0.0
            tvLineTotal.text = CurrencyHelper.format(this, q * product.price)
        }
        updateDisplay()

        btnCloseQty.setOnClickListener { dialog.dismiss() }

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
                                if (!token.isNullOrEmpty()) {
                                    RetrofitClient.api.deactivateProduct(
                                        token,
                                        sid
                                    )
                                }
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

                            // Refresh the canonical list and re-render through
                            // the active sort / filter / view-mode pipeline.
                            allProducts = updatedList
                            applySort()

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
        val isReset = prefs.getBoolean("ai_reset", false)
        if (isReset) {
            vpAiInsights.visibility = View.GONE
            return
        }

        val token = getSharedPreferences("auth", MODE_PRIVATE)
            .getString("TOKEN", null)

        lifecycleScope.launch {
            try {
                if (token.isNullOrEmpty()) {
                    vpAiInsights.visibility = View.GONE
                    return@launch
                }

                // Auth header is attached by AuthInterceptor, not passed here.
                val response = RetrofitClient.api.getAiReport()

                // The endpoint now returns the full insight list (the AI Insights screen
                // shows all of them grouped); the dashboard noticeboard keeps its top 3.
                val activeInsights = response.insights.take(3)

                if (activeInsights.isEmpty()) {
                    vpAiInsights.visibility = View.GONE
                    fabAiInsights.visibility = View.GONE
                    return@launch
                }

                // Show by default if we have insights and not manually minimized
                if (fabAiInsights.visibility != View.VISIBLE) {
                    vpAiInsights.visibility = View.VISIBLE
                }
                
                aiInsightsAdapter = AiInsightsAdapter(this@DashboardActivity, activeInsights) {
                    // Handle Minimize
                    vpAiInsights.visibility = View.GONE
                    fabAiInsights.visibility = View.VISIBLE
                }
                vpAiInsights.adapter = aiInsightsAdapter

                // Auto-scroll logic
                aiAnimationJob?.cancel()
                if (activeInsights.size > 1) {
                    aiAnimationJob = lifecycleScope.launch {
                        while (isActive) {
                            kotlinx.coroutines.delay(5000)
                            val nextItem = (vpAiInsights.currentItem + 1) % activeInsights.size
                            vpAiInsights.setCurrentItem(nextItem, true)
                        }
                    }
                }

            } catch (e: Exception) {
                vpAiInsights.visibility = View.GONE
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

                val res = RetrofitClient.api.getSubscription(token)

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
        PRICE_LOW_HIGH,
        PRICE_HIGH_LOW,
        STOCK_LOW_HIGH,
        STOCK_HIGH_LOW,
        STOCK_VALUE_HIGH_LOW,
        CATEGORY,
        BEST_SELLING,
        TOP_REVENUE,
        TOP_PROFIT
    }

    /**
     * Pure in-memory reorder of [allProducts] using the precomputed sort
     * keys. No DB/network — runs on Default just to keep the main thread
     * free for very large catalogues, then submits via DiffUtil so the
     * grid animates smoothly. Safe to call from any sort tap.
     */
    private fun applySort() {
        val source = allProducts
        if (source.isEmpty()) return
        lifecycleScope.launch(Dispatchers.Default) {
            fun stock(p: Product) = stockMap[p.id] ?: 0.0
            fun value(p: Product) = stock(p) * (avgCostMap[p.id] ?: 0.0)
            fun sold(p: Product) = soldQtyMap[p.id] ?: 0.0
            fun revenue(p: Product) = revenueMap[p.id] ?: 0.0
            fun profit(p: Product) = profitMap[p.id] ?: 0.0

            // ── Filter first (AND across groups, OR within a group) ──
            val filtered = source.filter { p ->
                // Category
                if (filterCategories.isNotEmpty()) {
                    val cat = p.category.ifBlank { ProductCategories.UNCATEGORIZED }
                    if (cat !in filterCategories) return@filter false
                }
                // Stock status
                if (filterStock.isNotEmpty()) {
                    val s = stock(p)
                    val status = when {
                        s <= 0.0 -> StockStatus.OUT
                        s <= lowStockThreshold -> StockStatus.LOW
                        else -> StockStatus.IN_STOCK
                    }
                    if (status !in filterStock) return@filter false
                }
                // Product type (OR within the group; if neither chosen, no constraint)
                if (filterPurchased || filterManual) {
                    val matches = (filterPurchased && p.isPurchased) ||
                        (filterManual && !p.isPurchased)
                    if (!matches) return@filter false
                }
                // Price range
                filterPriceMin?.let { if (p.price < it) return@filter false }
                filterPriceMax?.let { if (p.price > it) return@filter false }
                true
            }

            val sorted = when (currentSort) {
                SortType.A_TO_Z -> filtered.sortedBy { it.name.lowercase() }
                SortType.Z_TO_A -> filtered.sortedByDescending { it.name.lowercase() }
                SortType.PRICE_LOW_HIGH -> filtered.sortedBy { it.price }
                SortType.PRICE_HIGH_LOW -> filtered.sortedByDescending { it.price }
                // Out-of-stock first is the useful "low" ordering for restock.
                SortType.STOCK_LOW_HIGH -> filtered.sortedBy { stock(it) }
                SortType.STOCK_HIGH_LOW -> filtered.sortedByDescending { stock(it) }
                SortType.STOCK_VALUE_HIGH_LOW -> filtered.sortedByDescending { value(it) }
                // Group by category; blank categories sink to the bottom,
                // names break ties for a stable, readable order.
                SortType.CATEGORY -> filtered.sortedWith(
                    compareBy(
                        { it.category.ifBlank { "￿" }.lowercase() },
                        { it.name.lowercase() }
                    )
                )
                SortType.BEST_SELLING -> filtered.sortedWith(
                    compareByDescending<Product> { sold(it) }.thenBy { it.name.lowercase() }
                )
                SortType.TOP_REVENUE -> filtered.sortedWith(
                    compareByDescending<Product> { revenue(it) }.thenBy { it.name.lowercase() }
                )
                SortType.TOP_PROFIT -> filtered.sortedWith(
                    compareByDescending<Product> { profit(it) }.thenBy { it.name.lowercase() }
                )
            }
            withContext(Dispatchers.Main) {
                // Apply the current view mode: list = 1 column, grid &
                // categorized = full span (categorized adds section headers).
                productGridManager.spanCount = if (viewMode == ViewMode.LIST) 1 else GRID_SPAN
                productGridManager.spanSizeLookup.invalidateSpanIndexCache()
                // Column header only makes sense in List view.
                findViewById<LinearLayout>(R.id.llListHeader).visibility =
                    if (viewMode == ViewMode.LIST) View.VISIBLE else View.GONE
                productAdapter.setProducts(
                    sorted,
                    grouped = viewMode == ViewMode.CATEGORIZED,
                    asList = viewMode == ViewMode.LIST
                )
            }
        }
    }

    /** Number of active filter groups — drives the badge on the button. */
    private fun activeFilterCount(): Int {
        var n = 0
        if (filterCategories.isNotEmpty()) n++
        if (filterStock.isNotEmpty()) n++
        if (filterPurchased || filterManual) n++
        if (filterPriceMin != null || filterPriceMax != null) n++
        return n
    }

    private fun updateFilterBadge() {
        val badge = findViewById<TextView>(R.id.tvFilterBadge)
        val count = activeFilterCount()
        if (count > 0) {
            badge.text = count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    /**
     * Combinable filter sheet. Selections are applied to [allProducts]
     * via [applySort] — the same instant in-memory pipeline as sorting —
     * so filter → sort → search compose cleanly.
     */
    /** Lifted-button + unfold popover for single-select menus (View / Sort), matching the filter. */
    private fun showOptionPopover(anchor: View, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val emerald = android.graphics.Color.parseColor("#0F6E56")
        val ink = android.graphics.Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)

        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_filter_popover)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val rows = mutableListOf<View>()
        options.forEachIndexed { i, opt ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_imp_card)
                setPadding(dp(13), dp(12), dp(13), dp(12))
                isClickable = true
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i < options.size - 1) bottomMargin = dp(7) }
            }
            val tv = TextView(this).apply {
                text = opt
                textSize = 14f
                typeface = medium
                setTextColor(if (i == selectedIndex) emerald else ink)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            if (i == selectedIndex) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(emerald)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(18), dp(18))
                })
            }
            list.addView(row)
            rows.add(row)
        }

        val tall = options.size > 7
        val content: View = if (tall) androidx.core.widget.NestedScrollView(this).apply { addView(list) } else list

        val popup = android.widget.PopupWindow(
            content,
            dp(230),
            if (tall) dp(360) else android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.elevation = 16f * d
        popup.isOutsideTouchable = true
        popup.animationStyle = R.style.FilterUnfoldAnim

        rows.forEachIndexed { i, row -> row.setOnClickListener { onSelect(i); popup.dismiss() } }

        anchor.setBackgroundResource(R.drawable.bg_hdr_tile_active)
        anchor.animate().translationZ(8f * d).setDuration(150).start()
        popup.setOnDismissListener {
            anchor.setBackgroundResource(R.drawable.bg_hdr_tile)
            anchor.animate().translationZ(0f).setDuration(150).start()
        }
        popup.showAsDropDown(anchor, 0, dp(3), android.view.Gravity.END)
    }

    private fun showFilterSheet() {
        val view = layoutInflater.inflate(R.layout.dialog_product_filter, null)
        val popDensity = resources.displayMetrics.density
        val popup = android.widget.PopupWindow(
            view,
            (300 * popDensity).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        popup.elevation = 16f * popDensity
        popup.isOutsideTouchable = true
        popup.inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
        popup.animationStyle = R.style.FilterUnfoldAnim

        val hdrCategory = view.findViewById<View>(R.id.hdrCategory)
        val contentCategory = view.findViewById<View>(R.id.contentCategory)
        val ivCatChevron = view.findViewById<ImageView>(R.id.ivCatChevron)
        val tvCategorySummary = view.findViewById<TextView>(R.id.tvCategorySummary)
        val categoryList = view.findViewById<LinearLayout>(R.id.categoryList)
        val btnCatSelectAll = view.findViewById<TextView>(R.id.btnCatSelectAll)

        val hdrStock = view.findViewById<View>(R.id.hdrStock)
        val contentStock = view.findViewById<View>(R.id.contentStock)
        val ivStockChevron = view.findViewById<ImageView>(R.id.ivStockChevron)
        val tvStockSummary = view.findViewById<TextView>(R.id.tvStockSummary)
        val stockList = view.findViewById<LinearLayout>(R.id.stockList)

        val hdrType = view.findViewById<View>(R.id.hdrType)
        val contentType = view.findViewById<View>(R.id.contentType)
        val ivTypeChevron = view.findViewById<ImageView>(R.id.ivTypeChevron)
        val tvTypeSummary = view.findViewById<TextView>(R.id.tvTypeSummary)
        val typeList = view.findViewById<LinearLayout>(R.id.typeList)

        val hdrPrice = view.findViewById<View>(R.id.hdrPrice)
        val contentPrice = view.findViewById<View>(R.id.contentPrice)
        val ivPriceChevron = view.findViewById<ImageView>(R.id.ivPriceChevron)
        val tvPriceSummary = view.findViewById<TextView>(R.id.tvPriceSummary)
        val etPriceMin = view.findViewById<EditText>(R.id.etPriceMin)
        val etPriceMax = view.findViewById<EditText>(R.id.etPriceMax)

        val btnClear = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearFilters)
        val btnApply = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplyFilters)

        // Working copies of current filter state
        val workingCategories = filterCategories.toMutableSet()
        val workingStock = filterStock.toMutableSet()
        var workingPurchased = filterPurchased
        var workingManual = filterManual

        filterPriceMin?.let { etPriceMin.setText(if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) }
        filterPriceMax?.let { etPriceMax.setText(if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) }

        // Reusable check-row builder; returns a setter for "select all"
        fun addCheckRow(
            container: LinearLayout, label: String, count: Int?, initial: Boolean, onChange: (Boolean) -> Unit
        ): (Boolean) -> Unit {
            val row = layoutInflater.inflate(R.layout.item_filter_check, container, false)
            val box = row.findViewById<View>(R.id.boxFrame)
            val check = row.findViewById<ImageView>(R.id.ivCheck)
            val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
            val tvCount = row.findViewById<TextView>(R.id.tvCount)
            tvLabel.text = label
            if (count != null) tvCount.text = count.toString() else tvCount.visibility = View.GONE
            var checked = initial
            fun render() {
                box.setBackgroundResource(if (checked) R.drawable.bg_check_on else R.drawable.bg_check_off)
                check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            }
            render()
            row.setOnClickListener {
                checked = !checked
                render()
                onChange(checked)
            }
            container.addView(row)
            return { v: Boolean ->
                if (checked != v) {
                    checked = v
                    render()
                    onChange(checked)
                }
            }
        }

        val allCats = allProducts
            .map { it.category.ifBlank { ProductCategories.UNCATEGORIZED } }
            .distinct().sortedBy { it.lowercase() }
        val counts = allProducts
            .groupingBy { it.category.ifBlank { ProductCategories.UNCATEGORIZED } }.eachCount()

        fun updateCategorySummary() {
            tvCategorySummary.text = when {
                workingCategories.isEmpty() -> "All categories"
                workingCategories.size >= allCats.size -> "All selected"
                else -> "${workingCategories.size} of ${allCats.size} selected"
            }
        }
        fun updateStockSummary() {
            tvStockSummary.text = if (workingStock.isEmpty()) "Any" else workingStock.joinToString(", ") {
                when (it) {
                    StockStatus.IN_STOCK -> "In stock"
                    StockStatus.LOW -> "Low"
                    StockStatus.OUT -> "Out"
                }
            }
        }
        fun updateTypeSummary() {
            val s = mutableListOf<String>()
            if (workingPurchased) s.add("Purchased")
            if (workingManual) s.add("Manual")
            tvTypeSummary.text = if (s.isEmpty()) "Any" else s.joinToString(", ")
        }
        fun updatePriceSummary() {
            val mn = etPriceMin.text?.toString()?.trim()
            val mx = etPriceMax.text?.toString()?.trim()
            tvPriceSummary.text = when {
                mn.isNullOrEmpty() && mx.isNullOrEmpty() -> "Any"
                mn.isNullOrEmpty() -> "Up to ₹$mx"
                mx.isNullOrEmpty() -> "From ₹$mn"
                else -> "₹$mn – ₹$mx"
            }
        }

        val catSetters = mutableListOf<(Boolean) -> Unit>()
        for (cat in allCats) {
            catSetters.add(addCheckRow(categoryList, cat, counts[cat], cat in workingCategories) { on ->
                if (on) workingCategories.add(cat) else workingCategories.remove(cat)
                updateCategorySummary()
            })
        }
        btnCatSelectAll.setOnClickListener {
            val turnOn = workingCategories.size < allCats.size
            catSetters.forEach { it(turnOn) }
        }

        val stockSetters = mutableListOf<(Boolean) -> Unit>()
        listOf(
            "In stock" to StockStatus.IN_STOCK,
            "Low stock" to StockStatus.LOW,
            "Out of stock" to StockStatus.OUT
        ).forEach { (label, st) ->
            stockSetters.add(addCheckRow(stockList, label, null, st in workingStock) { on ->
                if (on) workingStock.add(st) else workingStock.remove(st)
                updateStockSummary()
            })
        }

        val typeSetters = mutableListOf<(Boolean) -> Unit>()
        typeSetters.add(addCheckRow(typeList, "Purchased", null, workingPurchased) { workingPurchased = it; updateTypeSummary() })
        typeSetters.add(addCheckRow(typeList, "Manual", null, workingManual) { workingManual = it; updateTypeSummary() })

        updateCategorySummary(); updateStockSummary(); updateTypeSummary(); updatePriceSummary()

        val priceWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updatePriceSummary() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etPriceMin.addTextChangedListener(priceWatcher)
        etPriceMax.addTextChangedListener(priceWatcher)

        fun toggle(content: View, chevron: ImageView) {
            val show = content.visibility != View.VISIBLE
            content.visibility = if (show) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (show) 180f else 0f).setDuration(150).start()
        }
        ivCatChevron.rotation = 180f
        hdrCategory.setOnClickListener { toggle(contentCategory, ivCatChevron) }
        hdrStock.setOnClickListener { toggle(contentStock, ivStockChevron) }
        hdrType.setOnClickListener { toggle(contentType, ivTypeChevron) }
        hdrPrice.setOnClickListener { toggle(contentPrice, ivPriceChevron) }

        btnClear.setOnClickListener {
            catSetters.forEach { it(false) }
            stockSetters.forEach { it(false) }
            typeSetters.forEach { it(false) }
            etPriceMin.text?.clear()
            etPriceMax.text?.clear()
            updatePriceSummary()
        }

        btnApply.setOnClickListener {
            filterCategories = workingCategories.toSet()
            filterStock = workingStock.toSet()
            filterPurchased = workingPurchased
            filterManual = workingManual
            filterPriceMin = etPriceMin.text?.toString()?.trim()?.toDoubleOrNull()
            filterPriceMax = etPriceMax.text?.toString()?.trim()?.toDoubleOrNull()
            updateFilterBadge()
            applySort()
            popup.dismiss()
        }

        val filterAnchor = findViewById<View>(R.id.btnFilterContainer)

        // Pin & lift the button; panel unfolds from beneath it
        filterAnchor.setBackgroundResource(R.drawable.bg_hdr_tile_active)
        filterAnchor.animate().translationZ(8f * popDensity).setDuration(150).start()
        popup.setOnDismissListener {
            filterAnchor.setBackgroundResource(R.drawable.bg_hdr_tile)
            filterAnchor.animate().translationZ(0f).setDuration(150).start()
        }

        popup.showAsDropDown(filterAnchor, 0, (3 * popDensity).toInt(), android.view.Gravity.END)
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

        // Ring removed — dot-only live indicator
        ring.visibility = View.GONE

        // 2. Soft blink for the dot — steady live indicator, no size breathing
        dot.scaleX = 1f
        dot.scaleY = 1f
        android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun setupGreeting() {
        val tvGreeting = findViewById<TextView>(R.id.tvGreeting) ?: return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
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
