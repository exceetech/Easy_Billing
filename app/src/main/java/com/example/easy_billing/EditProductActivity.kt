package com.example.easy_billing

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.repository.ProductVerificationRepository
import com.example.easy_billing.util.HsnHelpLauncher
import com.example.easy_billing.viewmodel.EditProductViewModel
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.example.easy_billing.util.UqcMapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-product edit screen.
 *
 * Strict separation from [AddProductActivity]:
 *   • Add screen creates new entries only and never edits.
 *   • This screen edits an existing entry only and never creates.
 *
 * Behaviour for purchased products (`isPurchased = true`):
 *   • The "Track inventory" switch and the "Add stock" input are
 *     hidden and replaced by a read-only "Current stock" card.
 *   • Save routes through [ProductRepository.updateSalesFieldsOnly]
 *     so trackInventory + stock can never be changed.
 *
 * Behaviour for manual products:
 *   • Full editing — price, GST, track-inventory, add-stock.
 *   • Track-inventory cannot be turned OFF if stock > 0 (the
 *     toggle reverts and a toast is shown).
 *   • Adding stock uses cost-price = 0 (per the project spec —
 *     manual products don't carry a cost basis).
 */
class EditProductActivity : BaseActivity() {

    private val viewModel: EditProductViewModel by viewModels()

    // Header
    private lateinit var tvAvatar: TextView
    private lateinit var tvName: TextView
    private lateinit var tvVariant: TextView
    private lateinit var badge: TextView
    private lateinit var tvHsnStatus: TextView
    private lateinit var tvHeroPrice: TextView
    private lateinit var tvHeroGst: TextView
    private lateinit var tvHeroCategory: TextView

    // Editable fields
    private lateinit var etPrice: TextInputEditText
    private lateinit var etHsn: TextInputEditText
    private lateinit var etCgst: TextInputEditText
    private lateinit var etSgst: TextInputEditText
    private lateinit var etIgst: TextInputEditText
    private lateinit var switchTaxInclusive: MaterialSwitch
    private lateinit var btnHsnHelp: MaterialButton

    // GSTR-1 product master fields (v23)
    // UQC / Supply classification are fixed-choice fields, so they open
    // the same custom picker popup as the Manage Products sort dropdown
    // (bg_pos_dropdown container, bg_pos_row_selected highlight) rather
    // than a native AutoCompleteTextView dropdown.
    private lateinit var spinnerOfficialUqc: TextView
    private lateinit var etHsnDescription: TextInputEditText
    private lateinit var etCessRate: TextInputEditText
    private lateinit var spinnerSupplyClassification: TextView
    private lateinit var etCategory: AutoCompleteTextView

    // Inventory section
    private lateinit var cardLockedStock: View
    private lateinit var tvCurrentStock: TextView
    private lateinit var groupEditableInventory: View
    private lateinit var switchTrack: MaterialSwitch
    private lateinit var tilStock: View
    private lateinit var etAddStock: TextInputEditText

    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton

    /** Current stock at dialog-open time (used to gate the toggle). */
    private var currentStock: Double = 0.0
    private var hsnVerifyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_product)

        setupToolbar(R.id.toolbar)

        bindViews()
        wireButtons()
        observe()

        val productId = intent.getIntExtra(EXTRA_PRODUCT_ID, -1)
        if (productId <= 0) {
            Toast.makeText(this, "Missing product id", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        viewModel.load(productId)
    }

    /* ---------------- Bind ---------------- */

    private fun bindViews() {
        tvAvatar  = findViewById(R.id.tvAvatar)
        tvName    = findViewById(R.id.tvName)
        tvVariant = findViewById(R.id.tvVariant)
        badge     = findViewById(R.id.badge)
        tvHsnStatus = findViewById(R.id.tvHsnStatus)
        tvHeroPrice    = findViewById(R.id.tvHeroPrice)
        tvHeroGst      = findViewById(R.id.tvHeroGst)
        tvHeroCategory = findViewById(R.id.tvHeroCategory)

        etPrice   = findViewById(R.id.etPrice)
        etHsn     = findViewById(R.id.etHsn)
        etCgst    = findViewById(R.id.etCgst)
        etSgst    = findViewById(R.id.etSgst)
        etIgst    = findViewById(R.id.etIgst)
        switchTaxInclusive = findViewById(R.id.switchTaxInclusive)
        btnHsnHelp = findViewById(R.id.btnHsnHelp)

        spinnerOfficialUqc = findViewById(R.id.spinnerOfficialUqc)
        etHsnDescription   = findViewById(R.id.etHsnDescription)
        etCessRate         = findViewById(R.id.etCessRate)
        spinnerSupplyClassification = findViewById(R.id.spinnerSupplyClassification)

        // Exact same popup as ManageProductsActivity.showSortPopup().
        spinnerOfficialUqc.setOnClickListener {
            showSortStylePopup(spinnerOfficialUqc, UqcMapper.ALL_UQC_DISPLAY, spinnerOfficialUqc.text.toString()) { picked ->
                spinnerOfficialUqc.text = picked
            }
        }
        spinnerSupplyClassification.setOnClickListener {
            val options = listOf("TAXABLE", "NIL_RATED", "EXEMPT", "NON_GST")
            showSortStylePopup(spinnerSupplyClassification, options, spinnerSupplyClassification.text.toString()) { picked ->
                spinnerSupplyClassification.text = picked
            }
        }

        etCategory = findViewById(R.id.etCategory)
        lifecycleScope.launch {
            val prefs = getSharedPreferences("auth", MODE_PRIVATE)
            val shopIdStr = try {
                prefs.getString("SHOP_ID", null) ?: prefs.getInt("SHOP_ID", 0).toString()
            } catch (e: ClassCastException) { prefs.getInt("SHOP_ID", 0).toString() }
            val cats = com.example.easy_billing.util.ProductCategories.dropdownFor(
                this@EditProductActivity, shopIdStr
            )
            etCategory.setAdapter(
                ArrayAdapter(this@EditProductActivity, R.layout.item_dropdown_ep, cats)
            )
        }
        etCategory.setOnClickListener { etCategory.showDropDown() }

        cardLockedStock        = findViewById(R.id.cardLockedStock)
        tvCurrentStock         = findViewById(R.id.tvCurrentStock)
        groupEditableInventory = findViewById(R.id.groupEditableInventory)
        switchTrack            = findViewById(R.id.switchTrack)
        tilStock               = findViewById(R.id.tilStock)
        etAddStock             = findViewById(R.id.etAddStock)

        btnSave   = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)
    }

    private fun wireButtons() {
        btnHsnHelp.setOnClickListener { HsnHelpLauncher.open(this) }

        // IGST is derived, never typed — exactly as in Add Product. Letting
        // the three rates be edited independently allowed CGST+SGST and IGST
        // to disagree, so the same product charged one rate on an intra-state
        // bill and a different one inter-state.
        //
        // Guarded on split > 0 so a legacy IGST-only row (no CGST/SGST
        // recorded) keeps its rate instead of being silently zeroed.
        val recomputeIgst = {
            val split = (etCgst.text?.toString()?.toDoubleOrNull() ?: 0.0) +
                    (etSgst.text?.toString()?.toDoubleOrNull() ?: 0.0)
            if (split > 0) etIgst.setText(formatRate(split))
        }
        etCgst.addTextChangedListener { recomputeIgst() }
        etSgst.addTextChangedListener { recomputeIgst() }

        btnSave.setOnClickListener { onSaveClicked() }
        btnCancel.setOnClickListener { finish() }

        // Debounced HSN backend verify — same UX as Add Product, shown
        // via the small status line under the field (tvHsnStatus).
        etHsn.addTextChangedListener { editable ->
            tvHsnStatus.visibility = View.GONE
            val hsn = editable?.toString()?.trim().orEmpty()
            if (hsn.length < 4) return@addTextChangedListener
            hsnVerifyJob?.cancel()
            hsnVerifyJob = lifecycleScope.launch {
                delay(600)
                val result = withContext(Dispatchers.IO) {
                    ProductVerificationRepository.get(this@EditProductActivity)
                        .verifyHsn(hsn)
                }
                result.onSuccess { resp ->
                    tvHsnStatus.visibility = View.VISIBLE
                    if (resp.valid) {
                        tvHsnStatus.setTextColor(0xFF0F6E56.toInt())
                        tvHsnStatus.text = resp.description
                            ?.takeIf { it.isNotBlank() } ?: "HSN verified"
                    } else {
                        tvHsnStatus.setTextColor(0xFFA32D2D.toInt())
                        tvHsnStatus.text = resp.message ?: "HSN not found in registry"
                    }
                }
            }
        }

        // Strict toggle: cannot turn OFF if stock exists.
        switchTrack.setOnCheckedChangeListener { _, isChecked ->
            val product = viewModel.product.value ?: return@setOnCheckedChangeListener
            
            var actualState = isChecked
            if (product.trackInventory && !isChecked && currentStock > 0) {
                switchTrack.setOnCheckedChangeListener(null)
                switchTrack.isChecked = true
                actualState = true
                wireToggleListener()
                Toast.makeText(
                    this,
                    "Cannot turn off inventory while stock > 0",
                    Toast.LENGTH_SHORT
                ).show()
            }
            tilStock.visibility = if (actualState) View.VISIBLE else View.GONE
        }
    }

    private fun wireToggleListener() {
        switchTrack.setOnCheckedChangeListener { _, isChecked ->
            val product = viewModel.product.value ?: return@setOnCheckedChangeListener
            
            var actualState = isChecked
            if (product.trackInventory && !isChecked && currentStock > 0) {
                switchTrack.setOnCheckedChangeListener(null)
                switchTrack.isChecked = true
                actualState = true
                wireToggleListener()
                Toast.makeText(
                    this,
                    "Cannot turn off inventory while stock > 0",
                    Toast.LENGTH_SHORT
                ).show()
            }
            tilStock.visibility = if (actualState) View.VISIBLE else View.GONE
        }
    }

    /* ---------------- Observe ---------------- */

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.product.collect { product ->
                        product?.let { renderProduct(it) }
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        btnSave.isEnabled = !state.saving
                        state.error?.let {
                            Toast.makeText(this@EditProductActivity, it, Toast.LENGTH_LONG).show()
                            viewModel.clearTransient()
                        }
                        state.savedAt?.let {
                            Toast.makeText(
                                this@EditProductActivity,
                                R.string.edit_save_success,
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun renderProduct(product: Product) {
        tvName.text = product.name
        tvVariant.text = product.variant
            ?.takeIf { it.isNotBlank() }
            ?.let { "Variant: $it" } ?: "No variant"
        tvAvatar.text = avatarInitials(product.name)

        if (product.isPurchased) {
            badge.text = getString(R.string.badge_purchased)
            badge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF1D9E75.toInt())
        } else {
            badge.text = getString(R.string.badge_manual)
            badge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFB8895A.toInt())
        }

        tvHeroPrice.text = "₹%.2f".format(product.price)
        val gstTotal = if (product.igstPercentage > 0) product.igstPercentage
            else product.cgstPercentage + product.sgstPercentage
        tvHeroGst.text = if (gstTotal > 0) "${formatRate(gstTotal)}%" else "—"
        tvHeroCategory.text = product.category.takeIf { it.isNotBlank() } ?: "—"

        // Pre-fill editable fields — always populate every field so the
        // user sees the current value and can edit it directly.
        etPrice.setText(product.price.toString())
        etHsn.setText(product.hsnCode.orEmpty())
        etCgst.setText(formatRate(product.cgstPercentage))
        etSgst.setText(formatRate(product.sgstPercentage))
        // Set last, and derived: the watchers above have already written a
        // value off the two setText calls. A row with no CGST/SGST is an
        // IGST-only product and keeps what was stored.
        val split = product.cgstPercentage + product.sgstPercentage
        etIgst.setText(formatRate(if (split > 0) split else product.igstPercentage))
        switchTaxInclusive.isChecked = product.isTaxInclusive
        // GSTR-1 product master (v23)
        spinnerOfficialUqc.text = UqcMapper.codeToDisplay(product.officialUqc) ?: ""
        etHsnDescription.setText(product.hsnDescription ?: "")
        etCessRate.setText(formatRate(product.cessRate))
        spinnerSupplyClassification.text = product.supplyClassification
        etCategory.setText(product.category, false)

        // Inventory section toggles by isPurchased.
        if (product.isPurchased) {
            cardLockedStock.visibility = View.VISIBLE
            groupEditableInventory.visibility = View.GONE
        } else {
            cardLockedStock.visibility = View.GONE
            groupEditableInventory.visibility = View.VISIBLE
            switchTrack.setOnCheckedChangeListener(null)
            switchTrack.isChecked = product.trackInventory
            tilStock.visibility = if (product.trackInventory) View.VISIBLE else View.GONE
            wireToggleListener()
        }

        // Pull current stock for the gate + the read-only display.
        lifecycleScope.launch {
            currentStock = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@EditProductActivity)
                    .inventoryDao().getInventory(product.id)?.currentStock ?: 0.0
            }
            tvCurrentStock.text = "${formatStock(currentStock)} ${product.unit ?: "piece"}"
        }
    }

    /* ---------------- Save ---------------- */

    private fun onSaveClicked() {
        val product = viewModel.product.value ?: return

        val newPrice = etPrice.text?.toString()?.toDoubleOrNull()
        if (newPrice == null || newPrice <= 0) {
            Toast.makeText(this, "Enter a valid selling price", Toast.LENGTH_SHORT).show()
            return
        }
        val hsn = etHsn.text?.toString()?.trim().orEmpty()
        val cgst = etCgst.text?.toString()?.toDoubleOrNull() ?: 0.0
        val sgst = etSgst.text?.toString()?.toDoubleOrNull() ?: 0.0
        val igst = etIgst.text?.toString()?.toDoubleOrNull() ?: 0.0
        // GSTR-1 product master (v23)
        val officialUqcVal  = UqcMapper.displayToCode(spinnerOfficialUqc.text?.toString())
        val hsnDescVal      = etHsnDescription.text?.toString()?.trim()?.ifBlank { null }
        val cessRateVal     = etCessRate.text?.toString()?.toDoubleOrNull() ?: 0.0
        val supplyClassVal  = spinnerSupplyClassification.text?.toString()?.trim()?.ifBlank { "TAXABLE" } ?: "TAXABLE"

        // GST billing needs an HSN on every product. Add Product blocks this
        // at creation; without the same gate here a product could be created
        // valid and then quietly saved back with the code stripped out.
        if (hsn.isBlank()) {
            lifecycleScope.launch {
                val gstEnabled = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@EditProductActivity)
                        .storeInfoDao().get()?.gstin?.isNotBlank() == true
                }
                if (gstEnabled) {
                    etHsn.error = "Required"
                    Toast.makeText(
                        this@EditProductActivity,
                        "HSN Code is mandatory for GST billing",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    commitSave(product, newPrice, hsn, cgst, sgst, igst,
                        officialUqcVal, hsnDescVal, cessRateVal, supplyClassVal)
                }
            }
            return
        }

        commitSave(product, newPrice, hsn, cgst, sgst, igst,
            officialUqcVal, hsnDescVal, cessRateVal, supplyClassVal)
    }

    /** The save itself, once validation has passed. */
    private fun commitSave(
        product: Product,
        newPrice: Double,
        hsn: String,
        cgst: Double,
        sgst: Double,
        igst: Double,
        officialUqcVal: String?,
        hsnDescVal: String?,
        cessRateVal: Double,
        supplyClassVal: String
    ) {
        if (product.isPurchased) {
            // Restricted save — sales fields only.
            viewModel.savePurchased(
                price = newPrice,
                hsn = hsn.takeIf { it.isNotBlank() },
                cgst = cgst, sgst = sgst, igst = igst,
                officialUqc = officialUqcVal,
                hsnDescription = hsnDescVal,
                cessRate = cessRateVal,
                supplyClassification = supplyClassVal,
                category = etCategory.text?.toString()?.trim().orEmpty(),
                isTaxInclusive = switchTaxInclusive.isChecked
            )
            return
        }

        // Manual product — full save with optional add-stock.
        val track = switchTrack.isChecked
        val addStock = etAddStock.text?.toString()?.toDoubleOrNull() ?: 0.0
        if (product.trackInventory && !track && currentStock > 0) {
            Toast.makeText(
                this, "Reduce stock to 0 before turning inventory off",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        viewModel.saveManual(
            price = newPrice,
            hsn = hsn.takeIf { it.isNotBlank() },
            cgst = cgst, sgst = sgst, igst = igst,
            trackInventory = track,
            addStockQuantity = addStock,
            officialUqc = officialUqcVal,
            hsnDescription = hsnDescVal,
            cessRate = cessRateVal,
            supplyClassification = supplyClassVal,
            category = etCategory.text?.toString()?.trim().orEmpty(),
            isTaxInclusive = switchTaxInclusive.isChecked
        )
    }

    /* ---------------- Picker popup — same visual as
       ManageProductsActivity.showSortPopup() ---------------- */

    private fun showSortStylePopup(
        anchor: View,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val green = android.graphics.Color.parseColor("#0F6E56")
        val ink = android.graphics.Color.parseColor("#1A1A18")
        val medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.googlesans_medium)
        val currentIndex = options.indexOf(current).coerceAtLeast(-1)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        val popup = android.widget.PopupWindow(
            scroll, dp(200),
            minOf(options.size * dp(44) + dp(10), dp(320)),
            true
        ).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEachIndexed { i, label ->
            val isSel = i == currentIndex
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                )
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                typeface = medium
                setTextColor(if (isSel) green else ink)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(tv)
            if (isSel) {
                row.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(green)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(16), dp(16))
                })
            }
            row.setOnClickListener {
                onPick(label)
                popup.dismiss()
            }
            container.addView(row)
        }

        popup.showAsDropDown(anchor, 0, dp(6))
    }

    /* ---------------- Helpers ---------------- */

    /** First letters of up to 2 words, uppercased; falls back to first 2 chars. */
    private fun avatarInitials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "—"
        }
    }

    private fun formatStock(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    /* ---------------- Helpers ---------------- */

    /**
     * Format a tax/rate Double for display in an EditText:
     *   0.0  → ""    (empty so hint text is visible — no rate set)
     *   5.0  → "5"   (strip unnecessary decimal)
     *   2.5  → "2.5" (keep decimal when meaningful)
     */
    private fun formatRate(value: Double): String = when {
        value <= 0.0                  -> ""
        value % 1.0 == 0.0           -> value.toInt().toString()
        else                          -> value.toString()
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "extra_product_id"
    }
}
