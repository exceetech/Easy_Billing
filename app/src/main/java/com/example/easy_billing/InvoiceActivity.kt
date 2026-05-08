package com.example.easy_billing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import android.transition.AutoTransition
import android.transition.TransitionManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.easy_billing.adapter.InvoiceAdapter
import com.example.easy_billing.db.*
import com.example.easy_billing.model.CartItem
import com.example.easy_billing.network.BillItemRequest
import com.example.easy_billing.network.CreateBillRequest
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreateSaleRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.SaleItemDto
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GstBillingCalculator
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.util.InvoicePdfGenerator
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * GST-aware invoice screen.
 *
 *   • Invoice-type selector (B2B / B2C) lives in-screen — no
 *     pre-entry popup.
 *   • Customer fields are optional for B2C and mandatory for
 *     B2B (Customer Name, Business Name, Phone, GST, State).
 *   • Tax math goes through [GstBillingCalculator] which honours
 *     both the Composition and Normal GST schemes and resolves
 *     intra- vs inter-state purely from the seller / buyer state
 *     codes.
 *   • The legacy `bills` / `bill_items` tables are still written
 *     so existing bill history, reports, inventory and sync
 *     logic continue to operate untouched. The new GST-aware
 *     rows are added in `gst_sales_invoice_table` /
 *     `gst_sales_items_table`.
 */
class InvoiceActivity : AppCompatActivity() {

    // ---- Header / scheme ----
    private lateinit var tvStoreName: TextView
    private lateinit var tvBillInfo: TextView
    private lateinit var tvSchemeBadge: TextView

    // ---- Type selector ----
    private lateinit var cgInvoiceType: ChipGroup
    private lateinit var chipB2C: Chip
    private lateinit var chipB2B: Chip
    private lateinit var tvInvoiceTypeHint: TextView

    // ---- Customer card ----
    private lateinit var cardCustomer: View
    private lateinit var tvCustomerRequirement: TextView
    private lateinit var etCustomerName: EditText
    private lateinit var tilBusinessName: TextInputLayout
    private lateinit var etBusinessName: EditText
    private lateinit var etCustomerPhone: EditText
    private lateinit var tilCustomerGst: TextInputLayout
    private lateinit var etCustomerGst: EditText
    private lateinit var tilCustomerState: TextInputLayout
    private lateinit var etCustomerState: AutoCompleteTextView

    // ---- GST summary ----
    private lateinit var cardGstSummary: View
    private lateinit var tvSupplyTypeBadge: TextView
    private lateinit var tvSchemeLine: TextView
    private lateinit var tvSupplyExplain: TextView

    // ---- Tax breakdown ----
    private lateinit var cardTaxBreakdown: View
    private lateinit var rowCgst: View
    private lateinit var rowSgst: View
    private lateinit var rowIgst: View
    private lateinit var tvCgstAmount: TextView
    private lateinit var tvSgstAmount: TextView
    private lateinit var tvIgstAmount: TextView
    private lateinit var tvTotalTax: TextView
    private lateinit var tvCompositionNote: TextView

    // ---- Totals ----
    private lateinit var tvSubtotal: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvGstPercent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var etDiscount: EditText
    private lateinit var rgPaymentMethod: RadioGroup
    private lateinit var btnConfirm: Button
    private lateinit var btnPrint: Button
    private lateinit var btnClose: Button

    // ---- State ----
    private lateinit var items: List<CartItem>
    private var savedBillId: Int = -1
    private var isBillSaved = false
    private var isUpdating = false
    private var billNumber: String = " "

    private var defaultGstFallback: Double = 0.0
    private var gstScheme: String = GstBillingCalculator.SCHEME_NORMAL
    private var sellerStateCode: String = ""
    private var sellerGstin: String = ""

    private var invoiceType: String = "B2C"

    // Re-used after each calculate() — drives both the persistence
    // step and the bill-summary widgets at the bottom of the screen.
    @Volatile
    private var lastBreakdown: GstBillingCalculator.BillBreakdown? = null

    /** Adapter is rebuilt only once; mode flips go through [InvoiceAdapter.updateMode]. */
    private lateinit var invoiceAdapter: InvoiceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)

        bindViews()

        @Suppress("UNCHECKED_CAST")
        items = intent.getSerializableExtra("CART_ITEMS") as? List<CartItem> ?: emptyList()

        val rvItems = findViewById<RecyclerView>(R.id.rvInvoiceItems)
        rvItems.layoutManager = LinearLayoutManager(this)
        invoiceAdapter = InvoiceAdapter(
            items       = items,
            supplyType  = InvoiceAdapter.SUPPLY_INTRASTATE,
            gstScheme   = gstScheme
        )
        rvItems.adapter = invoiceAdapter
        // Per-row "fall in" animation when the screen first appears.
        rvItems.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            this, R.anim.layout_animation_fall_down
        )
        rvItems.scheduleLayoutAnimation()

        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        tvBillInfo.text = "Date: $date"

        // Premium polish — staggered card reveal + press scaling on
        // anything tappable. Set up *once* per onCreate.
        playEntryAnimations()
        attachPressFeedback(btnConfirm, scaleTo = 0.97f)
        attachPressFeedback(btnPrint,   scaleTo = 0.96f)
        attachPressFeedback(btnClose,   scaleTo = 0.96f)
        attachPressFeedback(chipB2C,    scaleTo = 0.95f)
        attachPressFeedback(chipB2B,    scaleTo = 0.95f)

        wireInvoiceTypeSelector()
        applyInvoiceTypeUi("B2C")

        etDiscount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalculate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Re-run totals when the buyer state changes (intra ↔ inter).
        etCustomerState.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalculate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etCustomerGst.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalculate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadStoreInfo()
        loadBillingSettings()
        recalculate()

        btnConfirm.setOnClickListener {
            if (!validateB2BFields()) return@setOnClickListener
            if (getPaymentMethod() == "CREDIT") handleCreditFlow() else saveBill()
        }
        btnPrint.setOnClickListener { generatePdfAndPrint() }
        btnClose.setOnClickListener {
            setResult(if (isBillSaved) RESULT_OK else RESULT_CANCELED)
            finish()
        }
        btnPrint.isEnabled = false
    }

    // ================= BIND =================

    private fun bindViews() {
        tvStoreName       = findViewById(R.id.tvStoreName)
        tvBillInfo        = findViewById(R.id.tvBillInfo)
        tvSchemeBadge     = findViewById(R.id.tvSchemeBadge)

        cgInvoiceType     = findViewById(R.id.cgInvoiceType)
        chipB2C           = findViewById(R.id.chipB2C)
        chipB2B           = findViewById(R.id.chipB2B)
        tvInvoiceTypeHint = findViewById(R.id.tvInvoiceTypeHint)

        cardCustomer          = findViewById(R.id.cardCustomer)
        tvCustomerRequirement = findViewById(R.id.tvCustomerRequirement)
        etCustomerName        = findViewById(R.id.etCustomerName)
        tilBusinessName       = findViewById(R.id.tilBusinessName)
        etBusinessName        = findViewById(R.id.etBusinessName)
        etCustomerPhone       = findViewById(R.id.etCustomerPhone)
        tilCustomerGst        = findViewById(R.id.tilCustomerGst)
        etCustomerGst         = findViewById(R.id.etCustomerGst)
        tilCustomerState      = findViewById(R.id.tilCustomerState)
        etCustomerState       = findViewById(R.id.etCustomerState)

        cardGstSummary    = findViewById(R.id.cardGstSummary)
        tvSupplyTypeBadge = findViewById(R.id.tvSupplyTypeBadge)
        tvSchemeLine      = findViewById(R.id.tvSchemeLine)
        tvSupplyExplain   = findViewById(R.id.tvSupplyExplain)

        cardTaxBreakdown  = findViewById(R.id.cardTaxBreakdown)
        rowCgst           = findViewById(R.id.rowCgst)
        rowSgst           = findViewById(R.id.rowSgst)
        rowIgst           = findViewById(R.id.rowIgst)
        tvCgstAmount      = findViewById(R.id.tvCgstAmount)
        tvSgstAmount      = findViewById(R.id.tvSgstAmount)
        tvIgstAmount      = findViewById(R.id.tvIgstAmount)
        tvTotalTax        = findViewById(R.id.tvTotalTax)
        tvCompositionNote = findViewById(R.id.tvCompositionNote)

        tvSubtotal     = findViewById(R.id.tvSubtotal)
        tvGst          = findViewById(R.id.tvGst)
        tvGstPercent   = findViewById(R.id.tvGstPercent)
        tvTotal        = findViewById(R.id.tvTotal)
        etDiscount     = findViewById(R.id.etDiscount)
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod)
        btnConfirm     = findViewById(R.id.btnConfirm)
        btnPrint       = findViewById(R.id.btnPrint)
        btnClose       = findViewById(R.id.btnClose)

        setupStateDropdown()
    }

    /**
     * Wires the customer-state field as an exposed dropdown showing
     * every Indian state (mirrors the pattern in PurchaseActivity).
     * Tapping the field opens the list; tapping a row populates the
     * input — and our existing TextWatcher on it triggers
     * [recalculate] so the intra/inter supply type updates instantly.
     */
    private fun setupStateDropdown() {
        val states = GstEngine.INDIA_STATES.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, states)
        etCustomerState.setAdapter(adapter)
        etCustomerState.setOnClickListener { etCustomerState.showDropDown() }
    }

    private fun wireInvoiceTypeSelector() {
        cgInvoiceType.setOnCheckedStateChangeListener { _, checkedIds ->
            invoiceType = if (checkedIds.contains(R.id.chipB2B)) "B2B" else "B2C"
            applyInvoiceTypeUi(invoiceType)
            recalculate()
        }
    }

    private fun applyInvoiceTypeUi(type: String) {
        val isB2B = type == "B2B"

        // Animate the field reveal so the new rows slide/fade in
        // instead of snapping to visible.
        (cardCustomer as? ViewGroup)?.let { parent ->
            TransitionManager.beginDelayedTransition(
                parent,
                AutoTransition().setDuration(260L)
            )
        }

        tilBusinessName.visibility  = if (isB2B) View.VISIBLE else View.GONE
        tilCustomerGst.visibility   = if (isB2B) View.VISIBLE else View.GONE
        tilCustomerState.visibility = if (isB2B) View.VISIBLE else View.GONE

        tvCustomerRequirement.text = if (isB2B) "Required" else "Optional"
        tvCustomerRequirement.setBackgroundResource(
            if (isB2B) R.drawable.bg_pill_red else R.drawable.bg_pill_green
        )
        tvCustomerRequirement.setTextColor(
            if (isB2B) 0xFFB91C1C.toInt() else 0xFF16A34A.toInt()
        )

        tvInvoiceTypeHint.text = if (isB2B)
            "B2B — Customer Name, Business Name, Phone, GST and State are mandatory."
        else
            "B2C — quick sale (customer details optional)"

        // Pop the active chip so the selection feels tactile.
        val activeChip = if (isB2B) chipB2B else chipB2C
        popChip(activeChip)
    }

    // ================= ANIMATION HELPERS =================

    /**
     * Stagger the section cards into place as the screen opens.
     * Each card slides up + fades from 0 → 1 with a small per-card
     * delay so the eye walks down the screen.
     */
    private fun playEntryAnimations() {
        val targets = listOfNotNull(
            findViewById(R.id.heroCard),
            findViewById(R.id.chipsCard),
            findViewById(R.id.cardCustomer),
            findViewById(R.id.itemsCard),
            findViewById(R.id.cardGstSummary),
            findViewById(R.id.cardTaxBreakdown),
            findViewById(R.id.totalsCard)
        ) as List<View>

        targets.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 36f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + index * 70L)
                .setDuration(420L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /**
     * Hover/press feedback — scales the view to [scaleTo] on
     * ACTION_DOWN and back to 1.0 on UP/CANCEL. Doesn't consume
     * the touch event so the underlying click still fires.
     */
    @Suppress("ClickableViewAccessibility")
    private fun attachPressFeedback(view: View, scaleTo: Float) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(scaleTo).scaleY(scaleTo)
                        .setDuration(110L)
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(140L)
                        .start()
                }
            }
            false
        }
    }

    /** Brief "pop" animation to call attention to the active chip. */
    private fun popChip(view: View) {
        view.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(110L)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140L)
                    .start()
            }
            .start()
    }

    /** Animates the grand-total number when it changes. */
    private fun pulseTotal() {
        tvTotal.animate()
            .scaleX(1.06f).scaleY(1.06f)
            .setDuration(120L)
            .withEndAction {
                tvTotal.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(160L)
                    .start()
            }
            .start()
    }

    // ================= CALCULATION =================

    /**
     * Recompute the tax breakdown and refresh every UI surface
     * that depends on it — the per-tax rows, the totals card,
     * and the supply-type badge.
     *
     * Always runs on the main thread; the calculator itself is
     * pure CPU and doesn't touch the DB.
     */
    private fun recalculate() {

        if (isUpdating) return

        val customerStateCode = resolveBuyerStateCode()

        val breakdown = GstBillingCalculator.calculate(
            items           = items,
            gstScheme       = gstScheme,
            sellerStateCode = sellerStateCode,
            buyerStateCode  = customerStateCode
        )
        lastBreakdown = breakdown

        val rawDiscount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
        val maxDiscount = breakdown.grandTotal
        val discount = if (rawDiscount > maxDiscount) {
            isUpdating = true
            etDiscount.setText(maxDiscount.toInt().toString())
            etDiscount.setSelection(etDiscount.text.length)
            isUpdating = false
            Toast.makeText(this, "Discount cannot exceed total", Toast.LENGTH_SHORT).show()
            maxDiscount
        } else rawDiscount

        val payable = breakdown.grandTotal - discount

        tvSubtotal.text = CurrencyHelper.format(this, breakdown.subtotal)
        tvGst.text      = CurrencyHelper.format(this, breakdown.totalTax)
        val newTotalText = CurrencyHelper.format(this, payable)
        if (tvTotal.text?.toString() != newTotalText) {
            tvTotal.text = newTotalText
            pulseTotal()
        }

        // Tax breakdown card.
        val isComposition = breakdown.gstScheme.equals(
            GstBillingCalculator.SCHEME_COMPOSITION, ignoreCase = true
        )
        val isInter = breakdown.supplyType == "interstate"
        val isIntra = breakdown.supplyType == "intrastate"

        tvCompositionNote.visibility = if (isComposition) View.VISIBLE else View.GONE
        rowCgst.visibility = if (isComposition || isInter) View.GONE else View.VISIBLE
        rowSgst.visibility = if (isComposition || isInter) View.GONE else View.VISIBLE
        rowIgst.visibility = if (isComposition || isIntra) View.GONE else View.VISIBLE

        tvCgstAmount.text = CurrencyHelper.format(this, breakdown.totalCgst)
        tvSgstAmount.text = CurrencyHelper.format(this, breakdown.totalSgst)
        tvIgstAmount.text = CurrencyHelper.format(this, breakdown.totalIgst)
        tvTotalTax.text   = CurrencyHelper.format(this, breakdown.totalTax)

        // Effective GST percent indicator (rounded to 2 dp).
        val effectivePct = if (breakdown.subtotal > 0)
            (breakdown.totalTax / breakdown.subtotal) * 100.0 else 0.0
        tvGstPercent.text = "(${"%.1f".format(effectivePct)}%)"

        // Supply-type badge + scheme line.
        tvSupplyTypeBadge.text = when {
            isComposition -> "COMPOSITION"
            isInter       -> "INTERSTATE"
            else          -> "INTRASTATE"
        }
        tvSchemeLine.text = "Scheme: ${breakdown.gstScheme.ifBlank { "—" }}"
        tvSupplyExplain.text = when {
            isComposition -> "Composition Scheme — tax is included in the selling price."
            isInter       -> "Different state — IGST will be charged."
            else          -> "Same-state sale — CGST + SGST will be charged."
        }

        // Tell the line-item adapter which GST columns to light up.
        if (::invoiceAdapter.isInitialized) {
            invoiceAdapter.updateMode(
                supplyType = breakdown.supplyType,
                gstScheme  = breakdown.gstScheme
            )
        }
    }

    /**
     * Pick the buyer state in this priority:
     *   1. Customer-state text input (B2B field).
     *   2. First two characters of the customer's GSTIN.
     *   3. Empty (B2C default).
     */
    private fun resolveBuyerStateCode(): String? {
        val nameInput  = etCustomerState.text?.toString()?.trim().orEmpty()
        if (nameInput.isNotEmpty()) {
            GstEngine.getStateCodeFromName(nameInput)?.let { return it }
            // If user typed a 2-digit code already, accept it.
            if (nameInput.length == 2 && nameInput.all { it.isDigit() }) return nameInput
        }
        val gstin = etCustomerGst.text?.toString()?.trim().orEmpty()
        if (gstin.length >= 2) return gstin.substring(0, 2)
        return null
    }

    // ================= VALIDATION =================

    private fun validateB2BFields(): Boolean {
        if (invoiceType != "B2B") return true

        val name     = etCustomerName.text.toString().trim()
        val business = etBusinessName.text.toString().trim()
        val phone    = etCustomerPhone.text.toString().trim()
        val gstin    = etCustomerGst.text.toString().trim()
        val state    = etCustomerState.text.toString().trim()

        val missing = mutableListOf<String>()
        if (name.isEmpty())     missing.add("Customer Name")
        if (business.isEmpty()) missing.add("Business Name")
        if (phone.isEmpty())    missing.add("Phone Number")
        if (gstin.isEmpty())    missing.add("GST Number")
        if (state.isEmpty())    missing.add("State")

        if (missing.isNotEmpty()) {
            Toast.makeText(
                this,
                "Required for B2B: ${missing.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        if (gstin.length != 15) {
            Toast.makeText(this, "GSTIN must be 15 characters", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    // ================= SAVE BILL =================

    private fun saveBill() {

        if (isBillSaved) return
        isBillSaved = true
        btnConfirm.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {

            try {
                val db = AppDatabase.getDatabase(this@InvoiceActivity)
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

                // 1. Stock validation — preserve existing flow.
                for (cartItem in items) {
                    val product = cartItem.product
                    if (product.trackInventory) {
                        val inventory = db.inventoryDao().getInventory(product.id)
                        if (inventory != null && inventory.currentStock < cartItem.quantity) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@InvoiceActivity,
                                    "Out of stock: ${product.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isBillSaved = false
                            withContext(Dispatchers.Main) { btnConfirm.isEnabled = true }
                            return@launch
                        }
                    }
                }

                // 2. Resolve breakdown & customer state.
                val storeInfo = db.storeInfoDao().get()
                val customerStateCode = resolveBuyerStateCode().orEmpty()
                val breakdown = GstBillingCalculator.calculate(
                    items           = items,
                    gstScheme       = gstScheme,
                    sellerStateCode = sellerStateCode,
                    buyerStateCode  = customerStateCode.ifBlank { null }
                )

                val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
                val total    = (breakdown.grandTotal - discount).coerceAtLeast(0.0)

                val customerName     = etCustomerName.text.toString().trim().ifBlank { null }
                val businessName     = etBusinessName.text.toString().trim().ifBlank { null }
                val customerPhone    = etCustomerPhone.text.toString().trim().ifBlank { null }
                val customerGstin    = etCustomerGst.text.toString().trim().ifBlank { null }
                val customerStateRaw = etCustomerState.text.toString().trim().ifBlank { null }

                // 3. Build legacy bill items (preserves bill_items shape).
                val billItemsTmp = mutableListOf<BillItem>()
                for ((idx, cartItem) in items.withIndex()) {
                    val product  = cartItem.product
                    val quantity = cartItem.quantity
                    val line     = breakdown.lines[idx]
                    val avgCost  = if (product.trackInventory) {
                        db.inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
                    } else 0.0

                    val effectiveRate =
                        line.cgstPercentage + line.sgstPercentage + line.igstPercentage

                    billItemsTmp.add(
                        BillItem(
                            billId        = 0,
                            productId     = product.id,
                            productName   = product.name,
                            variant       = product.variant,
                            unit          = product.unit ?: "unit",
                            price         = product.price,
                            quantity      = quantity,
                            subTotal      = line.taxableAmount,
                            costPriceUsed = avgCost * quantity,
                            profit        = line.taxableAmount - (avgCost * quantity),
                            hsnCode       = product.hsnCode ?: "",
                            gstRate       = effectiveRate,
                            cgstAmount    = line.cgstAmount,
                            sgstAmount    = line.sgstAmount,
                            igstAmount    = line.igstAmount,
                            taxableValue  = line.taxableAmount,
                            isSynced      = false
                        )
                    )
                }

                // 4. Persist legacy `bills` row.
                val finalBill = Bill(
                    billNumber     = billNumber,
                    date           = date,
                    subTotal       = breakdown.subtotal,
                    gst            = breakdown.totalTax,
                    discount       = discount,
                    total          = total,
                    paymentMethod  = getPaymentMethod(),
                    customerType   = invoiceType,
                    customerGstin  = customerGstin,
                    placeOfSupply  = customerStateCode.ifBlank { sellerStateCode },
                    supplyType     = breakdown.supplyType,
                    cgstAmount     = breakdown.totalCgst,
                    sgstAmount     = breakdown.totalSgst,
                    igstAmount     = breakdown.totalIgst,
                    isSynced       = false
                )
                val billId = db.billDao().insertBill(finalBill).toInt()
                val finalBillItems = billItemsTmp.map { it.copy(billId = billId) }
                db.billItemDao().insertAll(finalBillItems)

                // 5. Persist new GST-aware invoice + items (the spec's new tables).
                val shopId = getSharedPreferences("auth", MODE_PRIVATE)
                    .getInt("SHOP_ID", 1).toString()

                val gstInvoice = GstSalesInvoice(
                    billId         = billId,
                    shopId         = shopId,
                    invoiceType    = invoiceType,
                    gstScheme      = breakdown.gstScheme,
                    customerName   = customerName,
                    businessName   = businessName,
                    customerPhone  = customerPhone,
                    customerGst    = customerGstin,
                    customerState  = customerStateRaw,
                    subtotal       = breakdown.subtotal,
                    totalCgst      = breakdown.totalCgst,
                    totalSgst      = breakdown.totalSgst,
                    totalIgst      = breakdown.totalIgst,
                    totalTax       = breakdown.totalTax,
                    grandTotal     = breakdown.grandTotal,
                    syncStatus     = "pending"
                )
                val gstInvoiceId = db.gstSalesInvoiceDao().insert(gstInvoice).toInt()
                val gstItems = GstBillingCalculator.toInvoiceItems(gstInvoiceId, breakdown)
                db.gstSalesInvoiceItemDao().insertAll(gstItems)

                // 6. Existing GstSalesRecord (per-line) — keep intact for legacy reports.
                if (storeInfo != null && storeInfo.gstin.isNotBlank()) {
                    val deviceId = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("DEVICE_ID", UUID.randomUUID().toString()) ?: ""
                    val gstRecords = GstEngine.buildSalesRecords(
                        bill      = finalBill.copy(id = billId),
                        items     = finalBillItems,
                        storeInfo = storeInfo,
                        deviceId  = deviceId
                    )
                    db.gstSalesRecordDao().insertAll(gstRecords)
                }

                savedBillId = billId

                // 7. Backend profit pulse — preserve existing flow.
                try {
                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)
                    if (!token.isNullOrEmpty()) {
                        val saleItems = items.map { ci ->
                            val product = ci.product
                            val avgCost = if (product.trackInventory) {
                                db.inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
                            } else 0.0
                            SaleItemDto(
                                product_id    = product.serverId ?: product.id,
                                quantity      = ci.quantity,
                                selling_price = product.price,
                                cost_price    = avgCost,
                                product_name  = product.name,
                                variant       = product.variant
                            )
                        }
                        RetrofitClient.api.createSale(
                            "Bearer $token",
                            CreateSaleRequest(saleItems)
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 8. Inventory deduction — preserve existing flow.
                for (cartItem in items) {
                    val product = cartItem.product
                    if (product.trackInventory) {
                        val inventory = db.inventoryDao().getInventory(product.id)
                        if (inventory != null) {
                            InventoryManager.reduceStock(
                                db = db,
                                productId = product.id,
                                quantity = cartItem.quantity
                            )
                        }
                    }
                }

                // 9. Backend bill creation — preserve existing flow.
                try {
                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)
                    val request = CreateBillRequest(
                        bill_number    = "",
                        items          = items.map {
                            if (it.product.serverId == null)
                                throw Exception("Product not synced: ${it.product.name}")
                            BillItemRequest(
                                it.product.serverId,
                                it.quantity,
                                it.product.variant
                            )
                        },
                        payment_method = getPaymentMethod(),
                        discount       = discount,
                        gst            = breakdown.totalTax,
                        total_amount   = total
                    )
                    val response = RetrofitClient.api.createBill("Bearer $token", request)
                    if (response.bill_number.isNotEmpty()) {
                        db.billDao().updateBillNumber(savedBillId, response.bill_number)
                        db.billDao().markBillSynced(savedBillId)
                        db.billItemDao().markItemsSynced(savedBillId)
                    }
                } catch (_: Exception) {
                    // offline safe
                }

                // 10. Push the new GST invoice batch — best effort.
                try {
                    SyncManager(this@InvoiceActivity).syncGstInvoices()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    btnPrint.isEnabled = true
                    Toast.makeText(this@InvoiceActivity, "Bill Saved", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                isBillSaved = false
                withContext(Dispatchers.Main) {
                    btnConfirm.isEnabled = true
                    Toast.makeText(
                        this@InvoiceActivity,
                        e.message ?: "Error saving bill",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= PRINT =================

    private fun generatePdfAndPrint() {
        if (savedBillId == -1) {
            Toast.makeText(this, "Please save bill first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val bill = db.billDao().getBillById(savedBillId)
            val billItems = db.billDao().getItemsForBill(savedBillId)
            val storeInfo = db.storeInfoDao().get()
            withContext(Dispatchers.Main) {
                InvoicePdfGenerator.generatePdfFromBill(this@InvoiceActivity, bill, billItems, storeInfo)
            }
        }
    }

    // ================= STORE INFO =================

    private fun loadStoreInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            var store = db.storeInfoDao().get()
            if (store == null) {
                store = StoreInfo(
                    name = "My Store",
                    address = "",
                    phone = "",
                    gstin = "",
                    isSynced = false
                )
                db.storeInfoDao().insert(store)
            }
            applyStoreInfo(store)

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            try {
                val response = RetrofitClient.api.getStoreSettings("Bearer $token")
                val updated = StoreInfo(
                    name     = response.shop_name ?: "",
                    address  = response.store_address ?: "",
                    phone    = response.phone ?: "",
                    gstin    = response.store_gstin ?: "",
                    isSynced = true
                )
                db.storeInfoDao().insert(updated)
                val refreshed = db.storeInfoDao().get()
                refreshed?.let { applyStoreInfo(it) }
            } catch (_: Exception) {
                // offline ignore
            }

            // Pull the GST profile so we know the scheme (Normal vs Composition).
            val profile = db.gstProfileDao().get()
            if (profile != null) {
                val scheme = if (profile.gstScheme.contains("compos", ignoreCase = true))
                    GstBillingCalculator.SCHEME_COMPOSITION
                else
                    GstBillingCalculator.SCHEME_NORMAL
                withContext(Dispatchers.Main) {
                    gstScheme = scheme
                    sellerStateCode = profile.stateCode.ifBlank { sellerStateCode }
                    sellerGstin     = profile.gstin.ifBlank { sellerGstin }
                    tvSchemeBadge.text = scheme.uppercase()
                    recalculate()
                }
            }
        }
    }

    private fun applyStoreInfo(store: StoreInfo) {
        runOnUiThread {
            tvStoreName.text = store.name.ifBlank { "My Store" }
            sellerStateCode = store.stateCode.ifBlank {
                GstEngine.getStateCode(store.gstin)
            }
            sellerGstin = store.gstin
            if (store.gstScheme.isNotBlank()) {
                gstScheme = if (store.gstScheme.contains("compos", ignoreCase = true))
                    GstBillingCalculator.SCHEME_COMPOSITION
                else
                    GstBillingCalculator.SCHEME_NORMAL
            }
            tvSchemeBadge.text = gstScheme.uppercase()
            recalculate()
        }
    }

    // ================= BILLING SETTINGS =================

    private fun loadBillingSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val local = db.billingSettingsDao().get()
            withContext(Dispatchers.Main) {
                local?.let {
                    defaultGstFallback = it.defaultGst.toDouble()
                    recalculate()
                }
            }
            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null) ?: return@launch
            try {
                val response = RetrofitClient.api.getBillingSettings("Bearer $token")
                val updated = BillingSettings(
                    defaultGst    = response.default_gst,
                    printerLayout = response.printer_layout
                )
                db.billingSettingsDao().insert(updated)
                withContext(Dispatchers.Main) {
                    defaultGstFallback = updated.defaultGst.toDouble()
                    recalculate()
                }
            } catch (_: Exception) {
                // offline → ignore
            }
        }
    }

    private fun getPaymentMethod(): String = when (rgPaymentMethod.checkedRadioButtonId) {
        R.id.rbCash   -> "CASH"
        R.id.rbUpi    -> "UPI"
        R.id.rbCard   -> "CARD"
        R.id.rbCredit -> "CREDIT"
        else          -> "CASH"
    }

    // ================= CREDIT =================

    private fun handleCreditFlow() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_customer_picker, null)

        val etSearch = view.findViewById<EditText>(R.id.etSearchCustomer)
        val rvCustomers = view.findViewById<RecyclerView>(R.id.rvCustomers)
        val btnNew = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewCustomer)

        rvCustomers.layoutManager = LinearLayoutManager(this)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val shopId = getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", 1)
            val allCustomers = db.creditAccountDao().getAll(shopId)
            var currentList = allCustomers.toMutableList()

            val adapter = CreditAdapter(currentList) { customer ->
                dialog.dismiss()
                addCreditAndSaveBill(customer)
            }
            rvCustomers.adapter = adapter

            fun updateList(data: List<CreditAccount>) {
                currentList.clear(); currentList.addAll(data)
                adapter.notifyDataSetChanged()
            }

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = Runnable {
                        val query = s?.toString()?.trim()?.take(50) ?: ""
                        val result = if (query.isEmpty()) allCustomers else allCustomers.filter {
                            it.name.contains(query, true) || it.phone.contains(query)
                        }
                        updateList(result)
                    }
                    handler.postDelayed(runnable!!, 300)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        btnNew.setOnClickListener {
            dialog.dismiss(); showAddCustomerDialog()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun addCreditAndSaveBill(account: CreditAccount) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)

            val customerStateCode = resolveBuyerStateCode().orEmpty()
            val breakdown = GstBillingCalculator.calculate(
                items           = items,
                gstScheme       = gstScheme,
                sellerStateCode = sellerStateCode,
                buyerStateCode  = customerStateCode.ifBlank { null }
            )
            val discount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            val total    = (breakdown.grandTotal - discount).coerceAtLeast(0.0)

            val shopId = getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", 1)
            val newDue = account.dueAmount + total
            db.creditAccountDao().updateDue(account.id, newDue, shopId)
            db.creditTransactionDao().insert(
                CreditTransaction(
                    accountId = account.id,
                    shopId    = shopId,
                    amount    = total,
                    type      = "ADD"
                )
            )
            SyncManager(this@InvoiceActivity).syncCredit()
            withContext(Dispatchers.Main) { saveBill() }
        }
    }

    private fun showAddCustomerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_customer, null)
        val etName  = view.findViewById<EditText>(R.id.etName)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val btnSave   = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter all fields", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@InvoiceActivity)
                val shopId = getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", 1)
                val existing = db.creditAccountDao().getByPhone(phone, shopId)
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InvoiceActivity, "Customer already exists", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val api = RetrofitClient.api
                val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
                if (token == null) {
                    db.creditAccountDao().insert(
                        CreditAccount(name = name, phone = phone, isSynced = false, shopId = shopId)
                    )
                    return@launch
                }
                try {
                    val response = api.createCreditAccount(
                        "Bearer $token", CreateCreditAccountRequest(name, phone)
                    )
                    db.creditAccountDao().insert(
                        CreditAccount(
                            name = response.name, phone = response.phone,
                            dueAmount = response.due_amount, serverId = response.id,
                            isSynced = true, shopId = shopId
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    db.creditAccountDao().insert(
                        CreditAccount(name = name, phone = phone, isSynced = false, shopId = shopId)
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InvoiceActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
