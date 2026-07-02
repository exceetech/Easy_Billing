package com.example.easy_billing

import com.example.easy_billing.util.appNow

import com.example.easy_billing.util.AppTime

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import com.example.easy_billing.network.CreateCreditAccountRequest
import com.example.easy_billing.network.CreateSaleRequest
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.SaleItemDto
import com.example.easy_billing.sync.SyncManager
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.util.GstBillingCalculator
import com.example.easy_billing.util.GstEngine
import com.example.easy_billing.util.InvoicePdfGenerator
import com.example.easy_billing.util.UqcMapper
import com.google.android.material.switchmaterial.SwitchMaterial
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

    // Reactive LIVE / OFFLINE status for the header pill.
    private var liveStatusCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var invDotAnim: android.animation.ObjectAnimator? = null

    // ---- Header / scheme ----
    private lateinit var tvStoreName: TextView
    private lateinit var tvStoreMonogram: TextView
    private lateinit var tvBillInfo: TextView
    private lateinit var tvSchemeBadge: TextView

    // ---- Type selector ----
    private lateinit var cgInvoiceType: RadioGroup
    private lateinit var chipB2C: RadioButton
    private lateinit var chipB2B: RadioButton
    private lateinit var tvInvoiceTypeHint: TextView

    // ---- Customer card ----
    private lateinit var cardCustomer: View
    private lateinit var tvCustomerRequirement: TextView
    private lateinit var etCustomerName: EditText
    private lateinit var tilBusinessName: View
    private lateinit var etBusinessName: EditText
    private lateinit var etCustomerPhone: EditText
    private lateinit var tilCustomerGst: View
    private lateinit var etCustomerGst: EditText
    private lateinit var tilCustomerState: View
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
    private lateinit var tvCgstLabel: TextView
    private lateinit var tvSgstLabel: TextView
    private lateinit var tvIgstLabel: TextView
    private lateinit var tvCgstRate: TextView
    private lateinit var tvSgstRate: TextView
    private lateinit var tvIgstRate: TextView
    private lateinit var tvTotalTax: TextView
    private lateinit var tvCompositionNote: TextView

    // ---- Totals ----
    private lateinit var tvSubtotal: TextView
    private lateinit var tvChargesSubtotal: TextView
    private lateinit var tvNetAmount: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvGstPercent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var rowInvRoundOff: View
    private lateinit var tvInvRoundOff: TextView
    /** Mirrors the "Round off" toggle in Invoice Design (app_settings). */
    private var roundOffEnabled: Boolean = false
    private lateinit var etDiscount: EditText
    private lateinit var rgPaymentMethod: RadioGroup
    private lateinit var btnConfirm: View
    private lateinit var btnPrint: View
    private lateinit var btnClose: View

    // ---- State ----
    private lateinit var items: MutableList<CartItem>
    private var savedBillId: Int = -1
    private var isBillSaved = false
    private var isUpdating = false
    private var billNumber: String = " "

    private var defaultGstFallback: Double = 0.0
    private var gstScheme: String = GstBillingCalculator.SCHEME_NORMAL
    private var sellerStateCode: String = ""
    private var sellerGstin: String = ""
    private var sellerName: String = ""

    private var invoiceType: String = "B2C"

    // ---- GST Options section (collapsible) ----
    private lateinit var rowGstOptionsHeader: View
    private lateinit var tvGstOptionsToggle: android.widget.ImageView
    private lateinit var layoutGstOptionsBody: View
    private lateinit var switchReverseCharge: SwitchMaterial
    private lateinit var spinnerGstrInvoiceType: AutoCompleteTextView
    private lateinit var switchEcommerce: SwitchMaterial
    private lateinit var layoutEcommerceFields: View
    private lateinit var etEcommerceGstin: EditText
    private lateinit var etEcommerceOperatorName: EditText
    private lateinit var spinnerEcoNatureOfSupply: AutoCompleteTextView
    private lateinit var spinnerEcoDocumentType: AutoCompleteTextView
    private lateinit var spinnerEcoRole: AutoCompleteTextView
    private lateinit var etEcoSupplierGstin: EditText
    private lateinit var etEcoSupplierName: EditText
    private lateinit var etEcoRecipientGstin: EditText
    private lateinit var etEcoRecipientName: EditText

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
        items = (intent.getSerializableExtra("CART_ITEMS") as? List<CartItem>)
            ?.toMutableList() ?: mutableListOf()

        val rvItems = findViewById<RecyclerView>(R.id.rvInvoiceItems)
        rvItems.layoutManager = LinearLayoutManager(this)
        invoiceAdapter = InvoiceAdapter(
            items       = items,
            supplyType  = InvoiceAdapter.SUPPLY_INTRASTATE,
            gstScheme   = gstScheme
        )
        rvItems.adapter = invoiceAdapter

        // "Add more items to cart" → return to the dashboard cart.
        findViewById<View>(R.id.btnAddItem).setOnClickListener {
            setResult(if (isBillSaved) RESULT_OK else RESULT_CANCELED)
            finish()
        }
        // Per-row "fall in" animation when the screen first appears.
        rvItems.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            this, R.anim.layout_animation_fall_down
        )
        rvItems.scheduleLayoutAnimation()

        val date = AppTime.nowBillStamp()   // app-timezone wall clock (matches backend)
        tvBillInfo.text = date

        // Premium polish — staggered card reveal + press scaling on
        // anything tappable. Set up *once* per onCreate.
        playEntryAnimations()
        attachPressFeedback(btnConfirm, scaleTo = 0.97f)
        attachPressFeedback(btnPrint,   scaleTo = 0.96f)
        attachPressFeedback(btnClose,   scaleTo = 0.96f)
        attachPressFeedback(chipB2C,    scaleTo = 0.95f)
        attachPressFeedback(chipB2B,    scaleTo = 0.95f)

        wireInvoiceTypeSelector()
        wireGstOptionsSection()
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

        // Phone-first customer lookup: a complete mobile number fetches the
        // saved customer (local-first, then server) and auto-fills details.
        // Works for both B2B and B2C.
        etCustomerPhone.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val phone = s?.toString()?.trim().orEmpty()
                if (phone.length == 10) lookupCustomerByPhone(phone)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadStoreInfo()
        loadBillingSettings()
        recalculate()

        btnConfirm.setOnClickListener {
            if (!validateB2BFields()) return@setOnClickListener
            if (!validateEcommerceFields()) return@setOnClickListener
            if (getPaymentMethod() == "CREDIT") handleCreditFlow() else saveBill()
        }
        btnPrint.setOnClickListener { generatePdfAndPrint() }
        btnClose.setOnClickListener {
            setResult(if (isBillSaved) RESULT_OK else RESULT_CANCELED)
            finish()
        }
        // Print stays cosmetically disabled until the invoice is generated.
        setCosmeticEnabled(btnPrint, false)
    }

    // ================= BIND =================

    private fun bindViews() {
        tvStoreName       = findViewById(R.id.tvStoreName)
        tvStoreMonogram   = findViewById(R.id.tvStoreMonogram)
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
        tvCgstLabel       = findViewById(R.id.tvCgstLabel)
        tvSgstLabel       = findViewById(R.id.tvSgstLabel)
        tvIgstLabel       = findViewById(R.id.tvIgstLabel)
        tvCgstRate        = findViewById(R.id.tvCgstRate)
        tvSgstRate        = findViewById(R.id.tvSgstRate)
        tvIgstRate        = findViewById(R.id.tvIgstRate)
        tvTotalTax        = findViewById(R.id.tvTotalTax)
        tvCompositionNote = findViewById(R.id.tvCompositionNote)

        tvSubtotal        = findViewById(R.id.tvSubtotal)
        tvChargesSubtotal = findViewById(R.id.tvChargesSubtotal)
        tvNetAmount       = findViewById(R.id.tvNetAmount)
        tvGst          = findViewById(R.id.tvGst)
        tvGstPercent   = findViewById(R.id.tvGstPercent)
        tvTotal        = findViewById(R.id.tvTotal)
        rowInvRoundOff = findViewById(R.id.rowInvRoundOff)
        tvInvRoundOff  = findViewById(R.id.tvInvRoundOff)
        etDiscount     = findViewById(R.id.etDiscount)
        rgPaymentMethod = findViewById(R.id.rgPaymentMethod)
        btnConfirm     = findViewById(R.id.btnConfirm)
        btnPrint       = findViewById(R.id.btnPrint)
        btnClose       = findViewById(R.id.btnClose)

        setupStateDropdown()

        // ---- GST Options section ----
        rowGstOptionsHeader    = findViewById(R.id.rowGstOptionsHeader)
        tvGstOptionsToggle     = findViewById(R.id.tvGstOptionsToggle)
        layoutGstOptionsBody   = findViewById(R.id.layoutGstOptionsBody)
        switchReverseCharge    = findViewById(R.id.switchReverseCharge)
        spinnerGstrInvoiceType = findViewById(R.id.spinnerGstrInvoiceType)
        switchEcommerce        = findViewById(R.id.switchEcommerce)
        layoutEcommerceFields  = findViewById(R.id.layoutEcommerceFields)
        etEcommerceGstin       = findViewById(R.id.etEcommerceGstin)
        etEcommerceOperatorName= findViewById(R.id.etEcommerceOperatorName)
        spinnerEcoNatureOfSupply = findViewById(R.id.spinnerEcoNatureOfSupply)
        spinnerEcoDocumentType   = findViewById(R.id.spinnerEcoDocumentType)
        spinnerEcoRole           = findViewById(R.id.spinnerEcoRole)
        etEcoSupplierGstin       = findViewById(R.id.etEcoSupplierGstin)
        etEcoSupplierName        = findViewById(R.id.etEcoSupplierName)
        etEcoRecipientGstin      = findViewById(R.id.etEcoRecipientGstin)
        etEcoRecipientName       = findViewById(R.id.etEcoRecipientName)
    }

    /**
     * Wires the customer-state field as an exposed dropdown showing
     * every Indian state (mirrors the pattern in PurchaseActivity).
     * Tapping the field opens the list; tapping a row populates the
     * input — and our existing TextWatcher on it triggers
     * [recalculate] so the intra/inter supply type updates instantly.
     */
    private fun setupStateDropdown() {
        // Tap opens a styled popup sheet (matches "Place of supply" in
        // Add Import Service) instead of the default autocomplete list.
        etCustomerState.isFocusable = false
        etCustomerState.isFocusableInTouchMode = false
        etCustomerState.setOnClickListener { showStatePopup(it as android.widget.TextView) }
    }

    private fun dpPx(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** State picker styled like the Import-Service "Place of supply" sheet. */
    private fun showStatePopup(anchor: android.widget.TextView) {
        showChooserPopup(anchor, GstEngine.INDIA_STATES.values.toList())
    }

    /** Generic picker sheet matching the Import-Service "Place of supply" popup. */
    private fun showChooserPopup(
        anchor: android.widget.TextView,
        options: List<String>,
        onPick: (String) -> Unit = {}
    ) {
        val states = options
        val current = anchor.text?.toString()?.trim().orEmpty()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_pos_dropdown)
            setPadding(dpPx(5), dpPx(5), dpPx(5), dpPx(5))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        // Anchor to the field BOX (parent) so the sheet drops from the
        // full-width field, not the inner value view.
        val box = (anchor.parent as? android.view.View) ?: anchor

        // Size to content like the "Place of supply" sheet; cap tall lists
        // (e.g. states) so they scroll instead of running off-screen.
        val sheetHeight = minOf(options.size * dpPx(44) + dpPx(10), dpPx(320))

        val popup = android.widget.PopupWindow(
            scroll, box.width,
            sheetHeight, true
        ).apply {
            elevation = dpPx(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        states.forEach { opt ->
            val isSel = opt.equals(current, ignoreCase = true)
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpPx(44)
                )
                setPadding(dpPx(12), 0, dpPx(12), 0)
                isClickable = true
                if (isSel) setBackgroundResource(R.drawable.bg_pos_row_selected)
            }
            val label = android.widget.TextView(this).apply {
                text = opt
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor(if (isSel) "#185FA5" else "#1A1A18"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(label)
            if (isSel) {
                row.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(android.graphics.Color.parseColor("#185FA5"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(dpPx(16), dpPx(16))
                })
            }
            row.setOnClickListener {
                anchor.text = opt
                popup.dismiss()
                onPick(opt)
            }
            container.addView(row)
        }

        popup.showAsDropDown(box, 0, dpPx(6))
    }

    /**
     * Wires the collapsible "GST Options" section and the inner
     * e-commerce fields toggle.
     *
     *   • Tapping the header row expands / collapses the body with
     *     an animated transition.
     *   • The GSTR invoice-type dropdown is populated from the four
     *     valid GSTR-1 values.
     *   • Toggling the e-commerce switch reveals the GSTIN and
     *     operator-name fields.
     */
    private fun wireGstOptionsSection() {
        // Populate the GSTR invoice-type dropdown.
        val invoiceTypes = listOf(
            "Regular",
            "SEZ supplies with payment",
            "SEZ supplies without payment",
            "Deemed Exp"
        )
        val typeAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, invoiceTypes
        )
        spinnerGstrInvoiceType.setAdapter(typeAdapter)
        spinnerGstrInvoiceType.setText("Regular", false)

        val natureAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, listOf("B2B", "B2C", "URP2B")
        )
        spinnerEcoNatureOfSupply.setAdapter(natureAdapter)
        spinnerEcoNatureOfSupply.setText("B2C", false)

        val docAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, listOf("Invoice", "Credit Note", "Debit Note")
        )
        spinnerEcoDocumentType.setAdapter(docAdapter)
        spinnerEcoDocumentType.setText("Invoice", false)

        val roleAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, listOf("Supplier", "E-Commerce Operator")
        )
        spinnerEcoRole.setAdapter(roleAdapter)
        spinnerEcoRole.setText("Supplier", false)

        // Tap opens the styled chooser sheet (matches "Place of supply").
        spinnerGstrInvoiceType.setOnClickListener {
            showChooserPopup(it as android.widget.TextView, invoiceTypes)
        }
        spinnerEcoNatureOfSupply.setOnClickListener {
            showChooserPopup(it as android.widget.TextView, listOf("B2B", "B2C", "URP2B"))
        }
        spinnerEcoDocumentType.setOnClickListener {
            showChooserPopup(it as android.widget.TextView, listOf("Invoice", "Credit Note", "Debit Note"))
        }
        spinnerEcoRole.setOnClickListener {
            showChooserPopup(it as android.widget.TextView, listOf("Supplier", "E-Commerce Operator")) {
                autofillEcoFields()
            }
        }

        // Collapse/expand toggle.
        rowGstOptionsHeader.setOnClickListener {
            val expanding = layoutGstOptionsBody.visibility != View.VISIBLE
            // No layout transition here — animating the card resize made the
            // rounded-corner background redraw with a visible lag on collapse.
            layoutGstOptionsBody.visibility = if (expanding) View.VISIBLE else View.GONE
            tvGstOptionsToggle.animate()
                .rotation(if (expanding) 180f else 0f)
                .setDuration(200L)
                .start()
        }

        // E-commerce toggle → show/hide fields + autofill from store / customer.
        switchEcommerce.setOnCheckedChangeListener { _, isChecked ->
            (layoutEcommerceFields.parent as? ViewGroup)?.let { parent ->
                TransitionManager.beginDelayedTransition(
                    parent,
                    AutoTransition().setDuration(200L)
                )
            }
            layoutEcommerceFields.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) autofillEcoFields()
        }
    }

    /**
     * Pre-fills the e-commerce GST fields, leaving every field editable so the
     * cashier can override an autofilled value.
     *
     *   • Document type → always "Invoice".
     *   • Nature of supply → taken from the B2C / B2B selector at the top.
     *   • Role = Supplier → Supplier GSTIN/Name = this shop (GST profile),
     *     Recipient GSTIN/Name = the customer just entered.
     *   • Role = E-Commerce Operator → supplier & recipient left blank.
     */
    private fun autofillEcoFields() {
        if (!switchEcommerce.isChecked) return

        spinnerEcoDocumentType.setText("Invoice", false)
        spinnerEcoNatureOfSupply.setText(invoiceType, false)

        if (spinnerEcoRole.text.toString().equals("Supplier", ignoreCase = true)) {
            etEcoSupplierGstin.setText(sellerGstin)
            etEcoSupplierName.setText(
                sellerName.ifBlank { tvStoreName.text?.toString().orEmpty() }
            )
            etEcoRecipientGstin.setText(etCustomerGst.text?.toString()?.trim().orEmpty())
            val recipientName = etBusinessName.text?.toString()?.trim().orEmpty()
                .ifBlank { etCustomerName.text?.toString()?.trim().orEmpty() }
            etEcoRecipientName.setText(recipientName)
        } else {
            etEcoSupplierGstin.setText("")
            etEcoSupplierName.setText("")
            etEcoRecipientGstin.setText("")
            etEcoRecipientName.setText("")
        }
    }

    private fun wireInvoiceTypeSelector() {
        cgInvoiceType.setOnCheckedChangeListener { _, checkedId ->
            invoiceType = if (checkedId == R.id.chipB2B) "B2B" else "B2C"

            // Clear all customer detail boxes when toggling B2C ⇄ B2B.
            etCustomerName.setText("")
            etBusinessName.setText("")
            etCustomerPhone.setText("")
            etCustomerGst.setText("")
            etCustomerState.setText("")

            applyInvoiceTypeUi(invoiceType)
            recalculate()
            autofillEcoFields()
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
    override fun onResume() {
        super.onResume()
        updateLiveStatus()
        // Reflect the "Round off" toggle from Invoice Design (may have changed).
        roundOffEnabled = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getBoolean("round_off", false)
        if (::tvTotal.isInitialized) recalculate()
        if (liveStatusCb == null) {
            liveStatusCb = com.example.easy_billing.util.NetworkUtils
                .registerCallback(this) { runOnUiThread { updateLiveStatus() } }
        }
    }

    /**
     * Round-off (floor to whole rupee) computed in integer paise to avoid
     * floating-point drift. Returns 0 when the toggle is off.
     */
    private fun roundOffAmount(payable: Double): Double {
        if (!roundOffEnabled) return 0.0
        val paise = Math.round(payable * 100.0)
        val flooredPaise = (paise / 100L) * 100L
        return (paise - flooredPaise) / 100.0
    }

    override fun onPause() {
        super.onPause()
        com.example.easy_billing.util.NetworkUtils.unregister(this, liveStatusCb)
        liveStatusCb = null
    }

    /** Paints the header pill LIVE (internet) or OFFLINE. */
    private fun updateLiveStatus() {
        val online = com.example.easy_billing.util.NetworkUtils.isOnline(this)
        com.example.easy_billing.util.NetworkUtils.applyStatus(
            findViewById(R.id.containerInvStatus), findViewById(R.id.tvInvStatus), online
        )
        invDotAnim = com.example.easy_billing.util.NetworkUtils.blinkDot(
            findViewById(R.id.viewInvDot), invDotAnim, online
        )
    }

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
    // ---------------- FULL-SCREEN (immersive, like other activities) ----------------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    /**
     * Paints one tax tile. Active → white tile, gold label, ink amount and
     * effective rate; inactive → muted tile with "—" / "n/a".
     */
    private fun bindTaxTile(
        tile: View, label: TextView, amount: TextView, rate: TextView,
        active: Boolean, taxAmount: Double, subtotal: Double
    ) {
        if (active) {
            tile.setBackgroundResource(R.drawable.bg_inv_tax_tile)
            label.setTextColor(0xFF8A6526.toInt())
            amount.setTextColor(0xFF18181B.toInt())
            amount.text = CurrencyHelper.format(this, taxAmount)
            val pct = if (subtotal > 0) taxAmount / subtotal * 100.0 else 0.0
            val pretty = if (pct % 1.0 == 0.0) pct.toInt().toString()
                         else String.format("%.2f", pct).trimEnd('0').trimEnd('.')
            rate.setTextColor(0xFFB7AB91.toInt())
            rate.text = "$pretty%"
        } else {
            tile.setBackgroundResource(R.drawable.bg_inv_tax_tile_muted)
            label.setTextColor(0xFFB7AB91.toInt())
            amount.setTextColor(0xFFB7AB91.toInt())
            amount.text = "—"
            rate.setTextColor(0xFFB7AB91.toInt())
            rate.text = "n/a"
        }
    }

    private fun recalculate() {

        if (isUpdating) return

        val customerStateCode = resolveBuyerStateCode()

        val rawDiscount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0

        // Discount is applied PRE-TAX inside the calculator (reduces the
        // taxable value → reduces GST). grandTotal is already net of it.
        val breakdown = GstBillingCalculator.calculate(
            items           = items,
            gstScheme       = gstScheme,
            sellerStateCode = sellerStateCode,
            buyerStateCode  = customerStateCode,
            billDiscount    = rawDiscount
        )
        lastBreakdown = breakdown

        // A discount can't exceed the goods value (gross subtotal).
        if (rawDiscount > breakdown.subtotal) {
            isUpdating = true
            etDiscount.setText(breakdown.subtotal.toInt().toString())
            etDiscount.setSelection(etDiscount.text.length)
            isUpdating = false
            Toast.makeText(this, "Discount cannot exceed subtotal", Toast.LENGTH_SHORT).show()
        }

        val payable = breakdown.grandTotal
        val roundOff = roundOffAmount(payable)
        val finalTotal = (payable - roundOff).coerceAtLeast(0.0)

        tvSubtotal.text        = CurrencyHelper.format(this, breakdown.taxableValue)
        tvChargesSubtotal.text = CurrencyHelper.format(this, breakdown.subtotal)
        tvNetAmount.text       = CurrencyHelper.format(this, breakdown.taxableValue)
        tvGst.text             = CurrencyHelper.format(this, breakdown.totalTax)

        if (roundOffEnabled) {
            rowInvRoundOff.visibility = View.VISIBLE
            tvInvRoundOff.text = if (roundOff > 0.0)
                "− ${CurrencyHelper.format(this, roundOff)}"
            else CurrencyHelper.format(this, 0.0)
        } else {
            rowInvRoundOff.visibility = View.GONE
        }

        val newTotalText = CurrencyHelper.format(this, finalTotal)
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

        // Tiles stay visible; the side that doesn't apply is muted.
        val cgstOn = !isComposition && isIntra
        val sgstOn = !isComposition && isIntra
        val igstOn = !isComposition && isInter
        bindTaxTile(rowCgst, tvCgstLabel, tvCgstAmount, tvCgstRate, cgstOn, breakdown.totalCgst, breakdown.taxableValue)
        bindTaxTile(rowSgst, tvSgstLabel, tvSgstAmount, tvSgstRate, sgstOn, breakdown.totalSgst, breakdown.taxableValue)
        bindTaxTile(rowIgst, tvIgstLabel, tvIgstAmount, tvIgstRate, igstOn, breakdown.totalIgst, breakdown.taxableValue)

        tvTotalTax.text   = CurrencyHelper.format(this, breakdown.totalTax)

        // Effective GST percent indicator (on the net taxable value).
        val effectivePct = if (breakdown.taxableValue > 0)
            (breakdown.totalTax / breakdown.taxableValue) * 100.0 else 0.0
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
            // Feed per-line discounted values so rows strike the original
            // amount and show the discounted amount + recalculated tax.
            invoiceAdapter.updateBreakdown(breakdown.lines)
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

    /**
     * E-commerce validation — runs for BOTH B2C and B2B (B2C e-commerce
     * sales are common). When the toggle is on the operator's GSTIN is
     * mandatory and must be 15 characters, since TCS is attributed to it.
     */
    private fun validateEcommerceFields(): Boolean {
        if (!switchEcommerce.isChecked) return true

        val ecoGstin = etEcommerceGstin.text.toString().trim()
        if (ecoGstin.isEmpty()) {
            Toast.makeText(this, "E-Commerce operator GSTIN is required", Toast.LENGTH_LONG).show()
            etEcommerceGstin.requestFocus()
            return false
        }
        if (ecoGstin.length != 15) {
            Toast.makeText(this, "E-Commerce GSTIN must be 15 characters", Toast.LENGTH_LONG).show()
            etEcommerceGstin.requestFocus()
            return false
        }
        return true
    }

    // ================= SAVE BILL =================

    /** Enable/disable a control and dim it so the state reads visually. */
    private fun setCosmeticEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
    }

    private fun saveBill() {

        if (isBillSaved) return
        isBillSaved = true
        setCosmeticEnabled(btnConfirm, false)

        lifecycleScope.launch(Dispatchers.IO) {

            try {
                val db = AppDatabase.getDatabase(this@InvoiceActivity)
                val date = AppTime.nowBillStamp()   // app-timezone wall clock (matches backend)

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
                            withContext(Dispatchers.Main) { setCosmeticEnabled(btnConfirm, true) }
                            return@launch
                        }
                    }
                }

                // 2. Resolve breakdown & customer state.
                //    Discount is applied PRE-TAX inside the calculator; the
                //    returned per-line taxable/tax and grandTotal are already
                //    net of it, so all downstream records (BillItem, GST
                //    invoice, GST records, GSTR-1, returns) stay consistent.
                val storeInfo = db.storeInfoDao().get()
                val customerStateCode = resolveBuyerStateCode().orEmpty()
                val userDiscount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
                val breakdown = GstBillingCalculator.calculate(
                    items           = items,
                    gstScheme       = gstScheme,
                    sellerStateCode = sellerStateCode,
                    buyerStateCode  = customerStateCode.ifBlank { null },
                    billDiscount    = userDiscount
                )

                // Round-off is a post-tax rounding of the final payable only.
                val discount = breakdown.discount
                val roundOff = roundOffAmount(breakdown.grandTotal)
                val total    = (breakdown.grandTotal - roundOff).coerceAtLeast(0.0)

                val customerName     = etCustomerName.text.toString().trim().ifBlank { null }
                val businessName     = etBusinessName.text.toString().trim().ifBlank { null }
                val customerPhone    = etCustomerPhone.text.toString().trim().ifBlank { null }
                val customerGstin    = etCustomerGst.text.toString().trim().ifBlank { null }
                val customerStateRaw = etCustomerState.text.toString().trim().ifBlank { null }

                // ── Final state name + code, always a consistent pair ──
                // Code: buyer's resolved code, else the shop's own state
                //       (B2C local sale with no state entered).
                // Name: as typed by the user, else looked up from the
                //       final code so name and code never disagree.
                val finalStateCode = customerStateCode
                    .ifBlank { sellerStateCode }
                    .ifBlank { null }
                val finalStateName = customerStateRaw
                    ?: finalStateCode?.let { GstEngine.INDIA_STATES[it] }

                // ── Read new GSTR-1 invoice-level fields (v23) ──
                val reverseCharge        = if (switchReverseCharge.isChecked) "Y" else "N"
                val gstrInvoiceType      = spinnerGstrInvoiceType.text.toString()
                    .ifBlank { "Regular" }
                val ecommerceEnabled     = switchEcommerce.isChecked
                val ecommerceGstin       = if (ecommerceEnabled)
                    etEcommerceGstin.text.toString().trim().ifBlank { null } else null
                val ecommerceOperatorName= if (ecommerceEnabled)
                    etEcommerceOperatorName.text.toString().trim().ifBlank { null } else null

                val ecoNatureOfSupply = if (ecommerceEnabled) spinnerEcoNatureOfSupply.text.toString().ifBlank { "B2C" } else null
                val ecoDocumentType   = if (ecommerceEnabled) spinnerEcoDocumentType.text.toString().ifBlank { "Invoice" } else null
                val ecoSupplierGstin  = if (ecommerceEnabled) etEcoSupplierGstin.text.toString().trim().ifBlank { null } else null
                val ecoSupplierName   = if (ecommerceEnabled) etEcoSupplierName.text.toString().trim().ifBlank { null } else null
                val ecoRecipientGstin = if (ecommerceEnabled) etEcoRecipientGstin.text.toString().trim().ifBlank { null } else null
                val ecoRecipientName  = if (ecommerceEnabled) etEcoRecipientName.text.toString().trim().ifBlank { null } else null
                val ecoRole           = if (ecommerceEnabled) spinnerEcoRole.text.toString().ifBlank { "Supplier" } else null

                // ── Build per-item GSTR-1 enrichments from product master ──
                val enrichments = items.map { ci ->
                    val p = ci.product
                    val cessAmt = if (p.cessRate > 0)
                        GstBillingCalculator.round2Pub(ci.quantity * p.price * p.cessRate / 100.0)
                    else 0.0
                    GstEngine.SalesRecordEnrichment(
                        cessRate       = p.cessRate,
                        cessAmount     = cessAmt,
                        uqc            = UqcMapper.resolve(p.unit, p.officialUqc),
                        hsnDescription = p.hsnDescription,
                        supplyClassification = p.supplyClassification
                    )
                }

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

                val nowMillis = appNow()
                val gstInvoice = GstSalesInvoice(
                    billId                = billId,
                    shopId                = shopId,
                    invoiceType           = invoiceType,
                    gstScheme             = breakdown.gstScheme,
                    customerName          = customerName,
                    businessName          = businessName,
                    customerPhone         = customerPhone,
                    customerGst           = customerGstin,
                    customerState         = finalStateName,
                    subtotal              = breakdown.taxableValue,   // NET taxable, so subtotal + tax == grandTotal
                    totalCgst             = breakdown.totalCgst,
                    totalSgst             = breakdown.totalSgst,
                    totalIgst             = breakdown.totalIgst,
                    totalTax              = breakdown.totalTax,
                    grandTotal            = breakdown.grandTotal,
                    syncStatus            = "pending",
                    // ── GSTR-1 v23 fields ──
                    invoiceNumber         = billNumber,
                    invoiceDate           = nowMillis,
                    reverseCharge         = reverseCharge,
                    gstrInvoiceType       = gstrInvoiceType,
                    customerStateCode     = finalStateCode,
                    ecommerceGstin        = ecommerceGstin,
                    ecommerceOperatorName = ecommerceOperatorName,
                    // ── New ECO fields (Table 14/15) ──
                    ecoNatureOfSupply     = ecoNatureOfSupply,
                    ecoDocumentType       = ecoDocumentType,
                    ecoSupplierGstin      = ecoSupplierGstin,
                    ecoSupplierName       = ecoSupplierName,
                    ecoRecipientGstin     = ecoRecipientGstin,
                    ecoRecipientName      = ecoRecipientName,
                    ecoRole               = ecoRole
                )
                val gstInvoiceId = db.gstSalesInvoiceDao().insert(gstInvoice).toInt()
                val gstItems = GstBillingCalculator.toInvoiceItems(gstInvoiceId, breakdown, enrichments)
                db.gstSalesInvoiceItemDao().insertAll(gstItems)

                // 5b. Upsert the customer master (phone-keyed). The invoice
                // keeps its own snapshot above; this only powers future
                // lookups/auto-fill. Skipped for anonymous B2C (no phone).
                upsertCustomerMaster(
                    db           = db,
                    phone        = customerPhone,
                    name         = customerName,
                    businessName = businessName,
                    gstin        = customerGstin,
                    state        = customerStateRaw,
                    stateCode    = customerStateCode.ifBlank { null },
                    type         = invoiceType
                )

                // 6. Existing GstSalesRecord (per-line) — keep intact for legacy reports.
                if (storeInfo != null && storeInfo.gstin.isNotBlank()) {
                    val deviceId = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("DEVICE_ID", UUID.randomUUID().toString()) ?: ""
                    val gstRecords = GstEngine.buildSalesRecords(
                        bill                  = finalBill.copy(id = billId),
                        items                 = finalBillItems,
                        storeInfo             = storeInfo,
                        deviceId              = deviceId,
                        // ── GSTR-1 v23 enrichment ──
                        customerName          = customerName,
                        businessName          = businessName,
                        customerPhone         = customerPhone,
                        customerState         = finalStateName,
                        customerStateCode     = finalStateCode,
                        reverseCharge         = reverseCharge,
                        gstrInvoiceType       = gstrInvoiceType,
                        ecommerceGstin        = ecommerceGstin,
                        ecommerceOperatorName = ecommerceOperatorName,
                        enrichments           = enrichments
                    )
                    db.gstSalesRecordDao().insertAll(gstRecords)
                }

                savedBillId = billId

                // 7. Backend profit pulse — preserve existing flow.
                try {
                    val token = getSharedPreferences("auth", MODE_PRIVATE)
                        .getString("TOKEN", null)
                    if (!token.isNullOrEmpty()) {
                        val saleItems = items.mapIndexed { idx, ci ->
                            val product = ci.product
                            val avgCost = if (product.trackInventory) {
                                db.inventoryDao().getInventory(product.id)?.averageCost ?: 0.0
                            } else 0.0
                            // Send the DISCOUNTED per-unit price (the line's net
                            // taxable ÷ qty) so backend profit reflects the bill
                            // discount and matches the local per-item profit.
                            val netTaxable = breakdown.lines.getOrNull(idx)?.taxableAmount
                                ?: (product.price * ci.quantity)
                            val discountedUnitPrice = if (ci.quantity > 0.0)
                                GstBillingCalculator.round2Pub(netTaxable / ci.quantity)
                            else product.price
                            SaleItemDto(
                                product_id    = product.serverId ?: product.id,
                                quantity      = ci.quantity,
                                selling_price = discountedUnitPrice,
                                cost_price    = avgCost,
                                product_name  = product.name,
                                variant       = product.variant
                            )
                        }
                        RetrofitClient.api.createSale(
                            token,
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

                // 9. Backend bill creation — single code path.
                //
                // Previously this block posted the bill to /bills/create
                // itself, while SyncManager.syncBills() (Dashboard /
                // periodic sync) could post the SAME still-unsynced bill
                // again → two backend rows per sale. It also hardcoded
                // invoice_type = "B2C" and customer_state = null,
                // clobbering B2B/state data on the backend.
                //
                // Now there is exactly ONE writer: SyncManager.syncBills(),
                // which posts every unsynced bill with the full GST
                // snapshot (invoice type, state, scheme) and marks it
                // synced. It is internally mutex-guarded, so a concurrent
                // Dashboard sync can never double-post.
                try {
                    SyncManager(this@InvoiceActivity).syncBills()
                } catch (_: Exception) {
                    // offline safe — bill stays flagged unsynced and the
                    // next sync cycle will push it exactly once.
                }

                // 10. Push the new GST invoice batch — best effort.
                try {
                    SyncManager(this@InvoiceActivity).syncGstInvoices()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    // Invoice generated → enable Print, lock Generate + Discount.
                    setCosmeticEnabled(btnPrint, true)
                    setCosmeticEnabled(btnConfirm, false)
                    setCosmeticEnabled(etDiscount, false)
                    Toast.makeText(this@InvoiceActivity, "Bill Saved", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                isBillSaved = false
                withContext(Dispatchers.Main) {
                    setCosmeticEnabled(btnConfirm, true)
                    Toast.makeText(
                        this@InvoiceActivity,
                        e.message ?: "Error saving bill",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ================= CUSTOMER LOOKUP (phone-first) =================

    private fun shopIdInt(): Int =
        getSharedPreferences("auth", MODE_PRIVATE).getInt("SHOP_ID", 1)

    /**
     * Looks a customer up by phone AND the currently selected invoice type
     * — local DB first, then the server if online — and auto-fills the
     * form. B2C and B2B are kept as SEPARATE records, so a B2C lookup only
     * ever returns the B2C row (name only) and a B2B lookup returns the
     * B2B row (full details). Never blocks the cashier.
     */
    private fun lookupCustomerByPhone(phone: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@InvoiceActivity)
            val shopId = shopIdInt()
            val type = invoiceType

            var found = withContext(Dispatchers.IO) {
                db.customerDao().getByPhoneAndType(phone, type, shopId)
            }

            if (found == null) {
                val token = getSharedPreferences("auth", MODE_PRIVATE)
                    .getString("TOKEN", null)
                if (!token.isNullOrEmpty()) {
                    runCatching {
                        val resp = withContext(Dispatchers.IO) {
                            RetrofitClient.api.getCustomerByPhone("Bearer $token", phone, type)
                        }
                        resp.customer?.let { r ->
                            val local = com.example.easy_billing.db.Customer(
                                serverId      = r.id,
                                shopId        = shopId,
                                phone         = r.phone,
                                name          = r.name.orEmpty(),
                                customerType  = r.customer_type ?: type,
                                businessName  = r.business_name,
                                gstin         = r.gstin,
                                state         = r.state,
                                stateCode     = r.state_code,
                                updatedAt     = if (r.updated_at > 0) r.updated_at else appNow()
                            )
                            withContext(Dispatchers.IO) { db.customerDao().insert(local) }
                            found = local
                        }
                    }
                }
            }

            val c = found ?: return@launch
            withContext(Dispatchers.Main) {
                // Type already matches the selection; B2C rows carry only a
                // name, B2B rows carry the full details.
                if (c.name.isNotBlank()) etCustomerName.setText(c.name)
                if (type.equals("B2B", true)) {
                    c.businessName?.takeIf { it.isNotBlank() }?.let { etBusinessName.setText(it) }
                    c.gstin?.takeIf { it.isNotBlank() }?.let { etCustomerGst.setText(it) }
                    c.state?.takeIf { it.isNotBlank() }?.let { etCustomerState.setText(it) }
                }
            }
        }
    }

    /**
     * Upserts the customer master from the form on bill save, keyed by
     * (shopId, phone, type). B2C and B2B are SEPARATE records — saving a
     * B2C bill never touches the B2B record for the same number, and vice
     * versa. A blank phone (quick anonymous B2C) creates no record.
     */
    private suspend fun upsertCustomerMaster(
        db: AppDatabase,
        phone: String?,
        name: String?,
        businessName: String?,
        gstin: String?,
        state: String?,
        stateCode: String?,
        type: String
    ) {
        val ph = phone?.trim().orEmpty()
        if (ph.isEmpty()) return
        val shopId = shopIdInt()
        val now = appNow()

        val newName       = name?.trim().orEmpty()
        val newBusiness   = businessName?.trim()?.ifBlank { null }
        val newGstin      = gstin?.trim()?.ifBlank { null }
        val newState      = state?.trim()?.ifBlank { null }
        val newStateCode  = stateCode?.trim()?.ifBlank { null }

        val existing = db.customerDao().getByPhoneAndType(ph, type, shopId)
        if (existing == null) {
            db.customerDao().insert(
                com.example.easy_billing.db.Customer(
                    shopId       = shopId,
                    phone        = ph,
                    name         = newName,
                    customerType = type,
                    businessName = newBusiness,
                    gstin        = newGstin,
                    state        = newState,
                    stateCode    = newStateCode,
                    createdAt    = now,
                    updatedAt    = now
                )
            )
        } else {
            // Update only this (phone, type) record. Blank fields don't
            // overwrite saved values.
            val merged = existing.copy(
                name         = newName.ifBlank { existing.name },
                businessName = newBusiness ?: existing.businessName,
                gstin        = newGstin ?: existing.gstin,
                state        = newState ?: existing.state,
                stateCode    = newStateCode ?: existing.stateCode,
                updatedAt    = now,
                serverId     = null   // re-flag for sync so the edit propagates
            )
            if (merged.copy(updatedAt = existing.updatedAt, serverId = existing.serverId) != existing) {
                db.customerDao().update(merged)
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
            // GST invoice saved with THIS bill — drives the print layout
            // (GST mode) and supplies the captured B2B/B2C customer details.
            val savedInvoice = db.gstSalesInvoiceDao().getByBillId(savedBillId)
            withContext(Dispatchers.Main) {
                InvoicePdfGenerator.generatePdfFromBill(
                    this@InvoiceActivity, bill, billItems, storeInfo,
                    savedInvoice?.gstScheme, savedInvoice
                )
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
                val response = RetrofitClient.api.getStoreSettings(token)
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
                    sellerName      = profile.tradeName.ifBlank { profile.legalName }
                        .ifBlank { sellerName }
                    tvSchemeBadge.text = scheme.uppercase()
                    recalculate()
                }
            }
        }
    }

    private fun applyStoreInfo(store: StoreInfo) {
        runOnUiThread {
            val resolvedName = store.name.ifBlank { "My Store" }
            tvStoreName.text = resolvedName
            tvStoreMonogram.text = resolvedName.trim().firstOrNull()?.uppercase() ?: "M"
            if (sellerName.isBlank()) sellerName = resolvedName
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
                val response = RetrofitClient.api.getBillingSettings(token)
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
            val userDiscount = etDiscount.text.toString().toDoubleOrNull() ?: 0.0
            val breakdown = GstBillingCalculator.calculate(
                items           = items,
                gstScheme       = gstScheme,
                sellerStateCode = sellerStateCode,
                buyerStateCode  = customerStateCode.ifBlank { null },
                billDiscount    = userDiscount
            )
            val roundOff = roundOffAmount(breakdown.grandTotal)
            val total    = (breakdown.grandTotal - roundOff).coerceAtLeast(0.0)

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
                        token, CreateCreditAccountRequest(name, phone)
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
