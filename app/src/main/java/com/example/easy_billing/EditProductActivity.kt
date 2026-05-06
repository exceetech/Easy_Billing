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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-product edit screen.
 *
 * Strict separation from [AddProductsActivity]:
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
    private lateinit var tvName: TextView
    private lateinit var tvVariant: TextView
    private lateinit var badge: TextView

    // Editable fields
    private lateinit var etPrice: TextInputEditText
    private lateinit var etHsn: TextInputEditText
    private lateinit var etCgst: TextInputEditText
    private lateinit var etSgst: TextInputEditText
    private lateinit var etIgst: TextInputEditText
    private lateinit var btnHsnHelp: MaterialButton

    // Inventory section
    private lateinit var cardLockedStock: MaterialCardView
    private lateinit var tvCurrentStock: TextView
    private lateinit var groupEditableInventory: View
    private lateinit var switchTrack: SwitchMaterial
    private lateinit var tilStock: TextInputLayout
    private lateinit var etAddStock: TextInputEditText

    private lateinit var btnSave: MaterialButton

    /** Current stock at dialog-open time (used to gate the toggle). */
    private var currentStock: Double = 0.0
    private var hsnVerifyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_product)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = getString(R.string.edit_screen_title)

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
        tvName    = findViewById(R.id.tvName)
        tvVariant = findViewById(R.id.tvVariant)
        badge     = findViewById(R.id.badge)

        etPrice   = findViewById(R.id.etPrice)
        etHsn     = findViewById(R.id.etHsn)
        etCgst    = findViewById(R.id.etCgst)
        etSgst    = findViewById(R.id.etSgst)
        etIgst    = findViewById(R.id.etIgst)
        btnHsnHelp = findViewById(R.id.btnHsnHelp)

        cardLockedStock        = findViewById(R.id.cardLockedStock)
        tvCurrentStock         = findViewById(R.id.tvCurrentStock)
        groupEditableInventory = findViewById(R.id.groupEditableInventory)
        switchTrack            = findViewById(R.id.switchTrack)
        tilStock               = findViewById(R.id.tilStock)
        etAddStock             = findViewById(R.id.etAddStock)

        btnSave = findViewById(R.id.btnSave)
    }

    private fun wireButtons() {
        btnHsnHelp.setOnClickListener { HsnHelpLauncher.open(this) }

        btnSave.setOnClickListener { onSaveClicked() }

        // Debounced HSN backend verify — same UX as Add Product.
        val tilHsn = etHsn.parent.parent as? TextInputLayout
        etHsn.addTextChangedListener { editable ->
            tilHsn?.error = null
            tilHsn?.helperText = null
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
                    if (resp.valid) {
                        tilHsn?.helperText = resp.description
                            ?.takeIf { it.isNotBlank() } ?: "HSN verified"
                    } else {
                        tilHsn?.error = resp.message ?: "HSN not found in registry"
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

        if (product.isPurchased) {
            badge.text = getString(R.string.badge_purchased)
            badge.setBackgroundResource(R.drawable.bg_badge_chip)
        } else {
            badge.text = getString(R.string.badge_manual)
            badge.setBackgroundResource(R.drawable.bg_badge_chip)
        }

        // Pre-fill editable fields
        etPrice.setText(product.price.toString())
        etHsn.setText(product.hsnCode.orEmpty())
        if (product.cgstPercentage > 0) etCgst.setText(product.cgstPercentage.toString())
        if (product.sgstPercentage > 0) etSgst.setText(product.sgstPercentage.toString())
        if (product.igstPercentage > 0) etIgst.setText(product.igstPercentage.toString())

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

        if (product.isPurchased) {
            // Restricted save — sales fields only.
            viewModel.savePurchased(
                price = newPrice,
                hsn = hsn.takeIf { it.isNotBlank() },
                cgst = cgst, sgst = sgst, igst = igst
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
            addStockQuantity = addStock
        )
    }

    /* ---------------- Helpers ---------------- */

    private fun formatStock(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    companion object {
        const val EXTRA_PRODUCT_ID = "extra_product_id"
    }
}
