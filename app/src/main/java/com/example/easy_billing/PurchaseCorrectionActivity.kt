package com.example.easy_billing

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.correction.PurchaseCorrectionPlanner as Planner
import com.example.easy_billing.db.PurchaseItem
import com.example.easy_billing.repository.CreditAdjustmentRepository.Kind
import com.example.easy_billing.repository.PurchaseCorrectionRepository
import com.example.easy_billing.util.CreditAdjustmentPrompt
import com.example.easy_billing.util.CurrencyHelper
import com.example.easy_billing.viewmodel.PurchaseReturnViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Correct a purchase bill after the fact (Phase 5).
 *
 *  MODE = "QTY"   : the supplier delivered fewer / more pens than the bill says
 *                   (quantity debit / credit note).
 *  MODE = "PRICE" : the bill's rate or GST was wrong (price / tax debit / credit note).
 *
 * The screen only collects input. Every figure shown in the "What will change"
 * card comes from the SAME planner the save uses, so the preview always equals
 * what is saved. Built in code (no XML) so it has no layout dependencies.
 */
class PurchaseCorrectionActivity : AppCompatActivity() {

    private val vm: PurchaseReturnViewModel by viewModels()

    private var purchaseId = -1
    private var mode = MODE_PRICE
    private var items: List<PurchaseItem> = emptyList()
    private var line: PurchaseCorrectionRepository.LineView? = null
    private var updating = false
    private var origNet = ""
    private var origCess = ""
    private var currentPlan: Planner.Plan? = null

    private lateinit var spinner: Spinner
    private lateinit var tvInfo: TextView
    private lateinit var priceGroup: LinearLayout
    private lateinit var qtyGroup: LinearLayout
    private lateinit var etNet: EditText
    private lateinit var etGross: EditText
    private lateinit var etCgst: EditText
    private lateinit var etSgst: EditText
    private lateinit var etIgst: EditText
    private lateinit var etCess: EditText
    private lateinit var rbReduce: RadioButton
    private lateinit var rbAdd: RadioButton
    private lateinit var etPens: EditText
    private lateinit var etReason: EditText
    private lateinit var tvPreview: TextView
    private lateinit var btnSave: View
    // ── Quantity-mode (XML) UI state ──
    private var qtySel = 0
    private var qtyAdd = false
    private var qtyReasonIdx = 0
    private var qtyAccOpen = false
    private lateinit var llQtyItems: LinearLayout
    private lateinit var tvQtyHint: TextView
    private lateinit var llQtyOk: LinearLayout
    private lateinit var tvQtyBig: TextView
    private lateinit var tvQtyLines: TextView
    private lateinit var tvQtySay: TextView
    private lateinit var tvQtyAccToggle: TextView
    private lateinit var llQtyAcc: LinearLayout
    private lateinit var tvGstSplit: TextView
    private lateinit var llPriceAdv: LinearLayout
    private lateinit var tvPriceAdvToggle: TextView
    private val gstValues = doubleArrayOf(0.0, 5.0, 12.0, 18.0, 28.0)
    private val gstBtns = ArrayList<View>()
    private val gstChecks = ArrayList<View>()
    private var priceAdvOpen = false
    private val qtyItemRows = ArrayList<Pair<View, View>>()
    private fun isAdd() = qtyAdd
    private fun selIndex() = qtySel
    private fun setSaveEnabled(on: Boolean) { btnSave.isEnabled = on; btnSave.alpha = if (on) 1f else 0.5f }

    private val gold = Color.parseColor("#8A6526")
    private val green = Color.parseColor("#0F6E56")
    private val red = Color.parseColor("#B23A3A")
    private val ink = Color.parseColor("#1F1B14")
    private val muted = Color.parseColor("#6F6858")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        purchaseId = intent.getIntExtra("PURCHASE_ID", -1)
        mode = if (intent.getStringExtra("MODE") == MODE_QTY) MODE_QTY else MODE_PRICE
        if (purchaseId <= 0) { finish(); return }

        setContentView(buildQtyUi())
        vm.loadPurchase(purchaseId)

        lifecycleScope.launch {
            vm.purchaseItems.collectLatest { list ->
                items = list.filter { it.productId != null }
                val names = items.map { it.productName + (it.variant?.takeIf { v -> v.isNotBlank() }?.let { v -> " ($v)" } ?: "") }
                buildQtyItems()
                if (items.isNotEmpty()) loadSelected()
            }
        }
        lifecycleScope.launch {
            vm.correctionResult.collectLatest { r ->
                r ?: return@collectLatest
                when (r) {
                    is PurchaseReturnViewModel.CorrectionResult.Saved -> {
                        Toast.makeText(
                            this@PurchaseCorrectionActivity,
                            "Saved: ${r.noteNumbers.joinToString(", ")}", Toast.LENGTH_LONG
                        ).show()
                        vm.clearCorrectionResult()
                        runAdjustments(r.adjustments, 0)
                    }
                    is PurchaseReturnViewModel.CorrectionResult.Refused -> {
                        showMessage("Cannot save", r.message)
                        vm.clearCorrectionResult()
                    }
                    is PurchaseReturnViewModel.CorrectionResult.Stale -> {
                        showMessage("Bill changed", r.message)
                        vm.clearCorrectionResult()
                        loadSelected()
                    }
                    is PurchaseReturnViewModel.CorrectionResult.Error -> {
                        showMessage("Could not save", r.cause.message ?: "Something went wrong. Nothing was saved.")
                        vm.clearCorrectionResult()
                    }
                }
            }
        }
    }

    // ───────────────────────── UI ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun semibold(): Typeface? = runCatching { ResourcesCompat.getFont(this, R.font.googlesans_semibold) }.getOrNull()

    private fun text(s: String, sizeSp: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        if (bold) typeface = semibold() ?: Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#E7DFCC"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun field(parent: LinearLayout, label: String, hint: String = "", decimal: Boolean = true): EditText {
        parent.addView(text(label, 12f, muted).apply { setPadding(0, dp(8), 0, dp(2)) })
        val e = EditText(this).apply {
            this.hint = hint
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            textSize = 15f; setTextColor(ink)
            setSingleLine(true)
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun watch(e: EditText, onChange: () -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!updating) onChange() }
        })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(text(if (mode == MODE_QTY) "Correct quantity" else "Correct price & tax", 22f, ink, true))
        root.addView(text(
            if (mode == MODE_QTY) "Use this when the supplier delivered fewer or more pens than the bill says."
            else "Use this when the bill's rate or GST was wrong. Enter the CORRECT figures; the app works out the debit / credit note.",
            13f, muted
        ).apply { setPadding(0, dp(4), 0, dp(4)) })

        val c1 = card()
        c1.addView(text("Bill line", 12f, muted))
        spinner = Spinner(this)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { loadSelected() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        c1.addView(spinner)
        tvInfo = text("", 13f, muted).apply { setPadding(0, dp(6), 0, 0) }
        c1.addView(tvInfo)
        root.addView(c1)

        // PRICE inputs
        priceGroup = card()
        etNet = field(priceGroup, "Corrected rate per pen, EXCLUDING tax")
        etGross = field(priceGroup, "…or the price per pen INCLUDING tax (fills the rate above)")
        etCgst = field(priceGroup, "CGST %")
        etSgst = field(priceGroup, "SGST %")
        etIgst = field(priceGroup, "IGST %")
        etCess = field(priceGroup, "Cess %")
        watch(etNet) { onNetOrTaxEdited(); refresh() }
        for (e in listOf(etCgst, etSgst, etIgst, etCess)) watch(e) { onNetOrTaxEdited(); refresh() }
        watch(etGross) { onGrossEdited(); refresh() }
        root.addView(priceGroup)

        // QTY inputs
        qtyGroup = card()
        qtyGroup.addView(text("What changed?", 12f, muted))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbReduce = RadioButton(this).apply { text = "Supplier delivered LESS (reduce the bill)"; id = View.generateViewId(); isChecked = true }
        rbAdd = RadioButton(this).apply { text = "Supplier delivered MORE (increase the bill)"; id = View.generateViewId() }
        rg.addView(rbReduce); rg.addView(rbAdd)
        rg.setOnCheckedChangeListener { _, _ -> refresh() }
        qtyGroup.addView(rg)
        etPens = field(qtyGroup, "Number of pens")
        watch(etPens) { refresh() }
        root.addView(qtyGroup)

        priceGroup.visibility = if (mode == MODE_PRICE) View.VISIBLE else View.GONE
        qtyGroup.visibility = if (mode == MODE_QTY) View.VISIBLE else View.GONE

        val c3 = card()
        etReason = field(c3, "Reason (shown on the note)", decimal = false)
        etReason.setText(if (mode == MODE_QTY) "Quantity correction" else "Price correction")
        root.addView(c3)

        val c4 = card()
        c4.addView(text("What will change", 15f, ink, true))
        tvPreview = text("", 13.5f, ink).apply { setPadding(0, dp(6), 0, 0) }
        c4.addView(tvPreview)
        root.addView(c4)

        btnSave = Button(this).apply {
            text = "Review & save"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(gold); cornerRadius = dp(14).toFloat() }
            setOnClickListener { confirmSave() }
            isEnabled = false
        }
        root.addView(btnSave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FAF7F0"))
            isFillViewport = true
            addView(root)
        }
    }

    // ───────────────────────── accountant details (grouped) ─────────────────────────

    private fun styleAccToggle(t: TextView, open: Boolean) {
        t.text = getString(if (open) R.string.correct_qty_accountant_hide else R.string.correct_qty_accountant_show)
        t.setCompoundDrawablesWithIntrinsicBounds(0, 0,
            if (open) R.drawable.ic_lc_chevron_down else R.drawable.ic_lc_chevron_right, 0)
    }

    private fun accSection(parent: LinearLayout, title: String, chipText: String, chipBg: Int, chipFg: String, tag: String? = null): LinearLayout {
        val sec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_acc_section)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_acc_section_head)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        head.addView(TextView(this).apply {
            text = chipText; textSize = 11f; gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor(chipFg)); setBackgroundResource(chipBg)
            typeface = semibold() ?: Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        })
        head.addView(TextView(this).apply {
            text = title.uppercase(); textSize = 11f; letterSpacing = 0.04f
            setTextColor(Color.parseColor("#5C4A22")); typeface = semibold() ?: Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (tag != null) head.addView(TextView(this).apply {
            text = tag; textSize = 10f; setTextColor(Color.parseColor("#9B2C2C"))
            typeface = semibold() ?: Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_pill_red); setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        sec.addView(head)
        parent.addView(sec)
        return sec
    }

    private fun accRow(sec: LinearLayout, label: String, value: String, color: String = "#1A1A18") {
        sec.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3EDDD")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        r.addView(TextView(this).apply { text = label; textSize = 12.5f; setTextColor(Color.parseColor("#8A8272"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) } })
        r.addView(TextView(this).apply { text = value; textSize = 12.5f; gravity = android.view.Gravity.END
            setTextColor(Color.parseColor(color)); typeface = semibold() ?: Typeface.DEFAULT_BOLD })
        sec.addView(r)
    }

    private fun accTaxGrid(sec: LinearLayout, cells: List<Triple<String, Double, Double>>) {
        sec.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3EDDD")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) })
        val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cells.forEachIndexed { i, (name, amt, pctv) ->
            val used = amt > 0.004
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            c.addView(TextView(this).apply {
                text = if (used) "$name ${pct(pctv)}%" else name
                textSize = 10f; setTextColor(Color.parseColor("#8A8272")); typeface = semibold() ?: Typeface.DEFAULT_BOLD
            })
            c.addView(TextView(this).apply {
                text = if (used) money(amt) else "–"; textSize = 12.5f
                setTextColor(Color.parseColor(if (used) "#1A1A18" else "#C9BFA8"))
                if (used) typeface = semibold() ?: Typeface.DEFAULT_BOLD
            })
            g.addView(c)
            if (i < cells.lastIndex) g.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3EDDD")); layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT) })
        }
        sec.addView(g)
    }

    /** Fills [box] with a simple white "receipt" card: the note (taxable, GST, value) then "Your books" (tax credit, stock, cost). */
    private fun fillAccountant(box: LinearLayout, plan: Planner.Plan, l: PurchaseCorrectionRepository.LineView) {
        box.removeAllViews()
        val redC = "#B23A3A"; val greenC = "#0F6E56"; val ink = "#1A1A18"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_summary_white)
            setPadding(dp(14), dp(6), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        fun head(t: String, tag: String? = null) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4)) }
            h.addView(TextView(this).apply { text = t.uppercase(); textSize = 11f; letterSpacing = 0.06f
                setTextColor(Color.parseColor("#B8895A")); typeface = semibold() ?: Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            if (tag != null) h.addView(TextView(this).apply { text = tag; textSize = 10f
                setTextColor(Color.parseColor("#9B2C2C")); typeface = semibold() ?: Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_pill_red); setPadding(dp(8), dp(2), dp(8), dp(2)) })
            card.addView(h)
        }
        fun row(label: String, value: String, color: String = ink, sub: String? = null, bold: Boolean = false) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(TextView(this).apply { text = label; textSize = 13f
                setTextColor(Color.parseColor(if (bold) ink else "#6F6858"))
                if (bold) typeface = semibold() ?: Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) } })
            line.addView(TextView(this).apply { text = value; textSize = 13.5f; gravity = android.view.Gravity.END
                setTextColor(Color.parseColor(color)); typeface = semibold() ?: Typeface.DEFAULT_BOLD })
            r.addView(line)
            if (sub != null) r.addView(TextView(this).apply { text = sub; textSize = 11f
                setTextColor(Color.parseColor("#8A8272")); setPadding(0, dp(2), 0, 0) })
            card.addView(r)
        }
        fun divider(dashed: Boolean = true) = card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EFE8D6"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) })
        for ((n, rw) in plan.rows.withIndex()) {
            val isD = rw.direction == "D"
            if (n > 0) divider()
            head(getString(R.string.correct_acc_note),
                getString(if (isD) R.string.correct_debit_note else R.string.correct_credit_note))
            val tax = rw.cgst + rw.sgst + rw.igst + rw.cess
            fun p(a: Double) = if (rw.taxable > 1e-9) a / rw.taxable * 100.0 else 0.0
            row(getString(R.string.correct_taxable), money(rw.taxable))
            val parts = listOf("CGST" to rw.cgst, "SGST" to rw.sgst, "IGST" to rw.igst, "Cess" to rw.cess)
                .filter { it.second > 0.004 }.joinToString("  ·  ") { "${it.first} ${pct(p(it.second))}% ${money(it.second)}" }
            row(getString(R.string.correct_acc_gst) + " " + pct(p(tax)) + "%", money(tax),
                sub = parts.ifEmpty { null })
            divider()
            row(getString(R.string.correct_note_value), signed(rw.signedValue),
                if (rw.signedValue < 0) redC else greenC, bold = true)
        }
        divider()
        head(getString(R.string.correct_acc_books))
        val itc = plan.rows.sumOf { (if (it.direction == "D") -1.0 else 1.0) * (it.availedCgst + it.availedSgst + it.availedIgst + it.availedCess) }
        row(getString(R.string.correct_acc_itc), if (abs(itc) >= 0.005) signed(itc) else money(0.0),
            if (itc < -0.004) redC else if (itc > 0.004) greenC else ink)
        val c = plan.correction
        if (l.tracksStock) {
            row(getString(R.string.correct_stock), "${qty(l.stock)} → ${qty(c.newStock)}")
            row(getString(R.string.correct_avg_cost),
                if (abs(c.newAverage - l.averageCost) > 1e-9) "${money(l.averageCost)} → ${money(c.newAverage)}"
                else getString(R.string.correct_no_change, money(l.averageCost)))
            val v = c.totalVariance
            if (abs(v) >= 0.005) row(getString(R.string.correct_profit_loss),
                getString(if (v < 0) R.string.correct_profit_gain else R.string.correct_profit_loss_amt, money(abs(v))),
                if (v < 0) greenC else redC)
            else row(getString(R.string.correct_profit_loss), getString(R.string.correct_no_effect))
            val shelf = c.totalShelfValueDelta
            if (abs(shelf) >= 0.005) row(getString(R.string.correct_stock_value), signed(shelf), if (shelf < 0) redC else greenC)
        } else {
            row(getString(R.string.correct_stock), getString(R.string.correct_no_effect))
        }
        box.addView(card)
    }

    // ───────────────────────── Price & tax page (XML) ─────────────────────────

    private fun setupPriceInputs(v: View) {
        tvGstSplit = v.findViewById(R.id.tvGstSplit)
        llPriceAdv = v.findViewById(R.id.llPriceAdv)
        tvPriceAdvToggle = v.findViewById(R.id.tvPriceAdvToggle)
        val ids = intArrayOf(R.id.rowGst0, R.id.rowGst1, R.id.rowGst2, R.id.rowGst3, R.id.rowGst4)
        val checks = intArrayOf(R.id.checkGst0, R.id.checkGst1, R.id.checkGst2, R.id.checkGst3, R.id.checkGst4)
        val titles = intArrayOf(R.id.tvGstTitle0, R.id.tvGstTitle1, R.id.tvGstTitle2, R.id.tvGstTitle3, R.id.tvGstTitle4)
        gstBtns.clear(); gstChecks.clear()
        ids.forEachIndexed { i, id ->
            val b = v.findViewById<View>(id)
            gstBtns.add(b)
            gstChecks.add(v.findViewById(checks[i]))
            v.findViewById<TextView>(titles[i]).text =
                if (gstValues[i] == 0.0) getString(R.string.correct_price_gst_none)
                else getString(R.string.correct_price_gst_title, pct(gstValues[i]))
            b.setOnClickListener { pickGst(gstValues[i]) }
        }
        watch(etNet) { onNetOrTaxEdited(); refresh() }
        for (e in listOf(etCgst, etSgst, etIgst, etCess)) watch(e) { onNetOrTaxEdited(); refresh() }
        watch(etGross) { onGrossEdited(); refresh() }
        tvPriceAdvToggle.setOnClickListener {
            priceAdvOpen = !priceAdvOpen
            llPriceAdv.visibility = if (priceAdvOpen) View.VISIBLE else View.GONE
            tvPriceAdvToggle.text = getString(if (priceAdvOpen) R.string.correct_price_adv_hide else R.string.correct_price_adv_show)
        }
    }

    private fun pct(x: Double) = qty(Math.round(x * 100.0) / 100.0)

    private fun gstTotal(): Double =
        (num(etCgst) ?: 0.0) + (num(etSgst) ?: 0.0) + (num(etIgst) ?: 0.0)

    /** One tap on a GST rate: split it the same way the bill was (IGST for another-state suppliers). */
    private fun pickGst(g: Double) {
        val l = line ?: return
        val inter = l.rates.igstPct > 1e-9
        updating = true
        etCgst.setText(if (inter) "0" else fmt4(g / 2.0))
        etSgst.setText(if (inter) "0" else fmt4(g / 2.0))
        etIgst.setText(if (inter) fmt4(g) else "0")
        updating = false
        onNetOrTaxEdited()
        refresh()
    }

    private fun paintGst() {
        if (gstBtns.isEmpty()) return
        val t = gstTotal()
        var matched = false
        gstBtns.forEachIndexed { i, b ->
            val on = abs(gstValues[i] - t) < 1e-6
            if (on) matched = true
            b.setBackgroundResource(if (on) R.drawable.bg_reason_card_selected else R.drawable.bg_reason_card_unselected)
            gstChecks[i].setBackgroundResource(if (on) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
        }
        val inter = (num(etIgst) ?: 0.0) > 1e-9
        tvGstSplit.text = when {
            !matched -> getString(R.string.correct_price_split_custom, pct(t))
            t < 1e-9 -> getString(R.string.correct_price_split_none)
            inter -> getString(R.string.correct_price_split_inter, pct(t))
            else -> getString(R.string.correct_price_split_intra, pct(t / 2.0), pct(t / 2.0))
        }
    }

    private fun priceRefresh() {
        paintGst()
        val l = line
        if (l == null) { setSaveEnabled(false); return }
        fun showMsg(msg: String, color: Int) {
            llQtyOk.visibility = View.GONE
            tvPreview.visibility = View.VISIBLE
            tvPreview.text = msg; tvPreview.setTextColor(color)
            currentPlan = null; setSaveEnabled(false)
        }
        if (etNet.text.toString().trim().toDoubleOrNull() == null) {
            showMsg(getString(R.string.correct_price_empty), muted); return
        }
        when (val r = plan()) {
            null -> showMsg("", muted)
            is Planner.Result.Blocked -> showMsg(r.message,
                if (r.reason == com.example.easy_billing.correction.PurchaseCorrectionMath.BlockReason.NO_CHANGE ||
                    r.message.startsWith("Enter")) muted else red)
            is Planner.Result.Ok -> {
                currentPlan = r.plan
                val net = r.plan.signedNoteValue
                tvPreview.visibility = View.GONE
                llQtyOk.visibility = View.VISIBLE
                tvQtyBig.text = signed(net)
                tvQtyBig.setTextColor(if (net < 0) red else green)
                val req = buildPriceRequest()
                val oldGst = l.rates.cgstPct + l.rates.sgstPct + l.rates.igstPct
                val newGst = gstTotal()
                val sb = StringBuilder()
                sb.append(getString(R.string.correct_price_price_line, money(l.rates.net), money(req?.newNetRate ?: l.rates.net)))
                sb.append('\n').append(getString(R.string.correct_price_gst_line, pct(oldGst), pct(newGst)))
                tvQtyLines.text = sb.toString()
                tvQtySay.text = if (l.purchase.isCredit && l.purchase.creditAccountId != null) {
                    getString(if (net < 0) R.string.correct_qty_owe_less else R.string.correct_qty_owe_more, money(abs(net)))
                } else {
                    if (net < 0) getString(R.string.correct_cash_refund, money(abs(net)))
                    else getString(R.string.correct_cash_pay, money(abs(net)))
                }
                fillAccountant(llQtyAcc, r.plan, l)
                setSaveEnabled(true)
            }
        }
    }

    // ───────────────────────── Quantity page (XML) ─────────────────────────

    private fun buildQtyUi(): View {
        val v = layoutInflater.inflate(
            if (mode == MODE_QTY) R.layout.activity_purchase_correction_qty else R.layout.activity_purchase_correction_price, null)
        // Widgets that belong to the other mode are not on this page, but the shared
        // prefill/plan code writes to them, so give them harmless detached instances.
        tvInfo = TextView(this)
        etReason = EditText(this)
        if (mode == MODE_QTY) {
            etNet = EditText(this); etGross = EditText(this); etCgst = EditText(this)
            etSgst = EditText(this); etIgst = EditText(this); etCess = EditText(this)
        } else {
            etPens = EditText(this)
            etNet = v.findViewById(R.id.etNetPrice); etGross = v.findViewById(R.id.etGrossPrice)
            etCgst = v.findViewById(R.id.etCgstBox); etSgst = v.findViewById(R.id.etSgstBox)
            etIgst = v.findViewById(R.id.etIgstBox); etCess = v.findViewById(R.id.etCessBox)
        }

        llQtyItems = v.findViewById(R.id.llQtyItems)
        tvPreview = v.findViewById(R.id.tvQtyMsg)
        llQtyOk = v.findViewById(R.id.llQtyOk)
        tvQtyBig = v.findViewById(R.id.tvQtyBig)
        tvQtyLines = v.findViewById(R.id.tvQtyLines)
        tvQtySay = v.findViewById(R.id.tvQtySay)
        tvQtyAccToggle = v.findViewById(R.id.tvQtyAccToggle)
        llQtyAcc = v.findViewById(R.id.llQtyAcc)
        btnSave = v.findViewById(R.id.btnQtySave)

        v.findViewById<View>(R.id.btnQtyBack).setOnClickListener { finish() }
        v.findViewById<View>(R.id.btnQtyCancel).setOnClickListener { finish() }
        btnSave.setOnClickListener { confirmSave() }
        setSaveEnabled(false)

        if (mode == MODE_QTY) {
        etPens = v.findViewById(R.id.etPens)
        tvQtyHint = v.findViewById(R.id.tvQtyHint)
        // Less / More choice
        val rowLess = v.findViewById<View>(R.id.rowQtyLess)
        val rowMore = v.findViewById<View>(R.id.rowQtyMore)
        val chkLess = v.findViewById<View>(R.id.checkQtyLess)
        val chkMore = v.findViewById<View>(R.id.checkQtyMore)
        fun paintChoice() {
            rowLess.setBackgroundResource(if (!qtyAdd) R.drawable.bg_reason_card_selected_red else R.drawable.bg_reason_card_unselected)
            chkLess.setBackgroundResource(if (!qtyAdd) R.drawable.bg_radio_selected_red else R.drawable.bg_radio_unselected)
            rowMore.setBackgroundResource(if (qtyAdd) R.drawable.bg_reason_card_selected else R.drawable.bg_reason_card_unselected)
            chkMore.setBackgroundResource(if (qtyAdd) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
        }
        paintChoice()
        rowLess.setOnClickListener { qtyAdd = false; paintChoice(); refresh() }
        rowMore.setOnClickListener { qtyAdd = true; paintChoice(); refresh() }

        // − / + stepper
        fun step(delta: Double) {
            val cur = etPens.text.toString().trim().toDoubleOrNull() ?: 0.0
            val n = (cur + delta).coerceAtLeast(0.0)
            etPens.setText(if (n == 0.0) "" else qty(n))
            etPens.setSelection(etPens.text.length)
        }
        v.findViewById<View>(R.id.btnQtyMinus).setOnClickListener { step(-1.0) }
        v.findViewById<View>(R.id.btnQtyPlus).setOnClickListener { step(1.0) }
        watch(etPens) { refresh() }
        } else setupPriceInputs(v)

        // Reason rows
        val reasonRes = if (mode == MODE_QTY) intArrayOf(R.string.correct_qty_reason_qty, R.string.correct_qty_reason_damaged,
            R.string.correct_qty_reason_short, R.string.correct_qty_reason_extra)
        else intArrayOf(R.string.correct_price_reason_price, R.string.correct_price_reason_gst,
            R.string.correct_price_reason_discount, R.string.correct_price_reason_wrong)
        val rows = listOf(R.id.rowReason0, R.id.rowReason1, R.id.rowReason2, R.id.rowReason3).map { v.findViewById<View>(it) }
        val checks = listOf(R.id.checkReason0, R.id.checkReason1, R.id.checkReason2, R.id.checkReason3).map { v.findViewById<View>(it) }
        fun paintReasons() {
            rows.forEachIndexed { i, r ->
                r.setBackgroundResource(if (i == qtyReasonIdx) R.drawable.bg_reason_card_selected else R.drawable.bg_reason_card_unselected)
                checks[i].setBackgroundResource(if (i == qtyReasonIdx) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
            }
            etReason.setText(getString(reasonRes[qtyReasonIdx]))
        }
        paintReasons()
        rows.forEachIndexed { i, r -> r.setOnClickListener { qtyReasonIdx = i; paintReasons() } }

        // Accountant details toggle
        tvQtyAccToggle.setOnClickListener {
            qtyAccOpen = !qtyAccOpen
            llQtyAcc.visibility = if (qtyAccOpen) View.VISIBLE else View.GONE
            styleAccToggle(tvQtyAccToggle, qtyAccOpen)
        }
        refresh()
        return v
    }

    private fun buildQtyItems() {
        llQtyItems.removeAllViews()
        qtyItemRows.clear()
        if (qtySel >= items.size) qtySel = 0
        items.forEachIndexed { i, it ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(13), dp(13))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) topMargin = dp(10) }
            }
            val initials = it.productName.trim().split(" ").filter { w -> w.isNotEmpty() }.take(2)
                .joinToString("") { w -> w.first().uppercase() }.ifEmpty { "?" }
            val av = TextView(this).apply {
                text = initials; gravity = android.view.Gravity.CENTER
                textSize = 13f; setTextColor(Color.parseColor("#2A5CA8"))
                typeface = semibold() ?: Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_circle_soft_blue)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            }
            val name = android.text.SpannableStringBuilder(it.productName)
            it.variant?.takeIf { v -> v.isNotBlank() }?.let { v ->
                val st = name.length + 1
                name.append(" ").append(v)
                name.setSpan(android.text.style.TypefaceSpan("serif"), st, name.length, 0)
                name.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), st, name.length, 0)
                name.setSpan(android.text.style.ForegroundColorSpan(green), st, name.length, 0)
            }
            col.addView(TextView(this).apply {
                text = name; textSize = 13.5f; setTextColor(Color.parseColor("#1A1A18")); typeface = semibold() ?: Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                text = getString(R.string.correct_qty_item_sub, qty(it.quantity)); textSize = 10.5f
                setTextColor(Color.parseColor("#8A8272"))
            })
            val radio = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)) }
            row.addView(av); row.addView(col); row.addView(radio)
            row.setOnClickListener { qtySel = i; paintQtyItems(); loadSelected() }
            llQtyItems.addView(row)
            qtyItemRows.add(row to radio)
        }
        paintQtyItems()
    }

    private fun paintQtyItems() {
        qtyItemRows.forEachIndexed { i, (r, c) ->
            r.setBackgroundResource(if (i == qtySel) R.drawable.bg_reason_card_selected else R.drawable.bg_reason_card_unselected)
            c.setBackgroundResource(if (i == qtySel) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected)
        }
    }

    private fun qtyRefresh() {
        val l = line
        val pens = etPens.text.toString().trim().toDoubleOrNull()
        val overBy = l != null && !qtyAdd && pens != null && pens > l.held + 1e-9
        tvQtyHint.text = when {
            overBy -> getString(R.string.correct_qty_hint_over, qty(l!!.held))
            qtyAdd -> getString(R.string.correct_qty_hint_more)
            else -> getString(R.string.correct_qty_hint_less)
        }
        tvQtyHint.setTextColor(if (overBy) red else Color.parseColor("#9A8F76"))
        if (l == null) { setSaveEnabled(false); return }

        fun showMsg(msg: String, color: Int) {
            llQtyOk.visibility = View.GONE
            tvPreview.visibility = View.VISIBLE
            tvPreview.text = msg; tvPreview.setTextColor(color)
            currentPlan = null; setSaveEnabled(false)
        }
        if (pens == null || pens <= 0.0) {
            showMsg(getString(if (qtyAdd) R.string.correct_qty_empty_more else R.string.correct_qty_empty_less), muted); return
        }
        when (val r = plan()) {
            null -> showMsg("", muted)
            is Planner.Result.Blocked -> showMsg(r.message,
                if (r.reason == com.example.easy_billing.correction.PurchaseCorrectionMath.BlockReason.NO_CHANGE) muted else red)
            is Planner.Result.Ok -> {
                currentPlan = r.plan
                val net = r.plan.signedNoteValue
                tvPreview.visibility = View.GONE
                llQtyOk.visibility = View.VISIBLE
                tvQtyBig.text = signed(net)
                tvQtyBig.setTextColor(if (net < 0) red else green)
                val newHeld = l.held + if (qtyAdd) pens else -pens
                val sb = StringBuilder()
                sb.append(l.item.productName).append(": ").append(qty(l.held)).append(" → ").append(qty(newHeld))
                if (l.tracksStock) sb.append('\n').append(getString(R.string.correct_qty_stock_line, qty(l.stock), qty(r.plan.correction.newStock)))
                tvQtyLines.text = sb.toString()
                val owe = if (l.purchase.isCredit && l.purchase.creditAccountId != null) {
                    getString(if (net < 0) R.string.correct_qty_owe_less else R.string.correct_qty_owe_more, money(abs(net)))
                } else {
                    if (net < 0) getString(R.string.correct_cash_refund, money(abs(net)))
                    else getString(R.string.correct_cash_pay, money(abs(net)))
                }
                tvQtySay.text = owe
                fillAccountant(llQtyAcc, r.plan, l)
                setSaveEnabled(true)
            }
        }
    }

    // ───────────────────────── line loading ─────────────────────────

    private fun fmt4(x: Double): String =
        BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun money(x: Double) = CurrencyHelper.format(this, x)
    private fun signed(x: Double) = (if (x < 0) "− " else "+ ") + money(abs(x))
    private fun qty(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else "%.2f".format(x)

    private fun loadSelected() {
        if (items.isEmpty()) return
        val item = items.getOrNull(selIndex()) ?: return
        lifecycleScope.launch {
            when (val r = vm.loadCorrectionLine(purchaseId, item.id)) {
                is PurchaseCorrectionRepository.LoadResult.Unavailable -> {
                    line = null
                    tvInfo.text = r.message
                    tvPreview.text = r.message
                    tvPreview.setTextColor(red)
                    if (mode == MODE_QTY) { llQtyOk.visibility = View.GONE; tvPreview.visibility = View.VISIBLE }
                    setSaveEnabled(false)
                }
                is PurchaseCorrectionRepository.LoadResult.Ok -> {
                    line = r.view
                    prefill(r.view)
                    refresh()
                }
            }
        }
    }

    private fun prefill(l: PurchaseCorrectionRepository.LineView) {
        val rt = l.rates
        tvInfo.text = buildString {
            append("Billed ${qty(l.item.quantity)} · now held ${qty(l.held)}")
            if (l.tracksStock) append(" · on shelf ${qty(l.shelf)}")
            append("\nRate ${money(rt.net)} excl. tax · ${money(rt.gross)} incl. tax")
            if (l.tracksStock) append("\nAverage cost ${money(l.averageCost)}")
            else append("\nNot a stock item (value and GST only)")
        }
        updating = true
        origNet = fmt4(rt.net)
        origCess = if (rt.net > 1e-9) fmt4(rt.cessPerPen / rt.net * 100.0) else "0"
        etNet.setText(origNet)
        etGross.setText(fmt4(rt.gross))
        etCgst.setText(fmt4(rt.cgstPct))
        etSgst.setText(fmt4(rt.sgstPct))
        etIgst.setText(fmt4(rt.igstPct))
        etCess.setText(origCess)
        etPens.setText("")
        updating = false
    }

    private fun num(e: EditText): Double? {
        val t = e.text.toString().trim()
        return if (t.isEmpty()) 0.0 else t.toDoubleOrNull()
    }

    private fun totalPct(): Double? {
        val c = num(etCgst) ?: return null
        val s = num(etSgst) ?: return null
        val i = num(etIgst) ?: return null
        val x = num(etCess) ?: return null
        return c + s + i + x
    }

    /** Rate or a tax % was typed: keep the net rate, refresh the tax-inclusive price. */
    private fun onNetOrTaxEdited() {
        val net = etNet.text.toString().trim().toDoubleOrNull() ?: return
        val tot = totalPct() ?: return
        updating = true
        etGross.setText(fmt4(net * (1 + tot / 100.0)))
        updating = false
    }

    /** The tax-inclusive price was typed: derive the rate excluding tax. */
    private fun onGrossEdited() {
        val g = etGross.text.toString().trim().toDoubleOrNull() ?: return
        val tot = totalPct() ?: return
        updating = true
        etNet.setText(fmt4(g / (1 + tot / 100.0)))
        updating = false
    }

    // ───────────────────────── preview ─────────────────────────

    private fun buildPriceRequest(): Planner.PriceRequest? {
        val l = line ?: return null
        val netText = etNet.text.toString().trim()
        val net = if (netText == origNet) l.rates.net else (netText.toDoubleOrNull() ?: return null)
        val cg = num(etCgst) ?: return null
        val sg = num(etSgst) ?: return null
        val ig = num(etIgst) ?: return null
        val cessText = etCess.text.toString().trim()
        val cess: Double? = if (cessText == origCess) null else (num(etCess) ?: return null)
        return Planner.PriceRequest(net, cg, sg, ig, cess)
    }

    private fun plan(): Planner.Result? {
        val l = line ?: return null
        return if (mode == MODE_PRICE) {
            val req = buildPriceRequest() ?: return Planner.Result.Blocked("Enter the corrected rate and tax percentages.")
            PurchaseCorrectionRepository.previewPrice(l, req)
        } else {
            val pens = etPens.text.toString().trim().toDoubleOrNull()
            if (pens == null || pens <= 0.0) return Planner.Result.Blocked("Enter the number of pens.")
            PurchaseCorrectionRepository.previewQuantity(l, pens, isAdd())
        }
    }

    private fun refresh() {
        if (mode == MODE_QTY) { qtyRefresh(); return }
        if (mode == MODE_PRICE) { priceRefresh(); return }
        val l = line
        if (l == null) { setSaveEnabled(false); return }
        when (val r = plan()) {
            null -> { currentPlan = null; setSaveEnabled(false) }
            is Planner.Result.Blocked -> {
                currentPlan = null
                tvPreview.text = r.message
                tvPreview.setTextColor(if (r.reason == com.example.easy_billing.correction.PurchaseCorrectionMath.BlockReason.NO_CHANGE ||
                    r.message.startsWith("Enter")) muted else red)
                setSaveEnabled(false)
            }
            is Planner.Result.Ok -> {
                currentPlan = r.plan
                tvPreview.text = describe(r.plan, l)
                tvPreview.setTextColor(ink)
                setSaveEnabled(true)
            }
        }
    }

    private fun describe(plan: Planner.Plan, l: PurchaseCorrectionRepository.LineView): String {
        val sb = StringBuilder()
        for (row in plan.rows) {
            val isD = row.direction == "D"
            sb.append(if (isD) "Debit note: " else "Credit note: ").append(signed(row.signedValue)).append('\n')
            val tax = row.cgst + row.sgst + row.igst + row.cess
            sb.append("   Taxable ").append(money(row.taxable)).append("  ·  Tax ").append(money(tax))
            val bits = ArrayList<String>()
            if (row.cgst > 0) bits.add("CGST ${money(row.cgst)}")
            if (row.sgst > 0) bits.add("SGST ${money(row.sgst)}")
            if (row.igst > 0) bits.add("IGST ${money(row.igst)}")
            if (row.cess > 0) bits.add("Cess ${money(row.cess)}")
            if (bits.isNotEmpty()) sb.append("\n   ").append(bits.joinToString(" · "))
            sb.append('\n')
        }
        val net = plan.signedNoteValue
        sb.append("\nBill total changes by ").append(signed(net)).append('\n')

        if (l.purchase.isCredit && l.purchase.creditAccountId != null) {
            sb.append(if (net < 0) "You will owe the supplier ${money(abs(net))} less." else "You will owe the supplier ${money(abs(net))} more.")
            sb.append(" You will be asked how to settle it if it is more than is still owed.\n")
        } else {
            sb.append(if (net < 0) getString(R.string.correct_cash_refund, money(abs(net)))
            else getString(R.string.correct_cash_pay, money(abs(net)))).append('\n')
        }

        val itc = plan.rows.sumOf { (if (it.direction == "D") -1.0 else 1.0) * (it.availedCgst + it.availedSgst + it.availedIgst + it.availedCess) }
        if (abs(itc) >= 0.005) sb.append("Input tax credit changes by ").append(signed(itc)).append('\n')

        val c = plan.correction
        if (l.tracksStock) {
            if (abs(c.newStock - l.stock) > 1e-9) sb.append("Stock: ${qty(l.stock)} → ${qty(c.newStock)} pens\n")
            if (abs(c.newAverage - l.averageCost) > 1e-9) sb.append("Average cost: ${money(l.averageCost)} → ${money(c.newAverage)}\n")
            val v = c.totalVariance
            if (abs(v) >= 0.005) {
                sb.append(if (v < 0) "Profit: +${money(abs(v))} gain" else "Profit: −${money(abs(v))} loss")
                sb.append(" (the part for pens already sold or returned)\n")
            }
            val shelf = c.totalShelfValueDelta
            if (abs(shelf) >= 0.005) sb.append("Goes into stock value: ").append(signed(shelf)).append(" (pens still on the shelf)\n")
        } else {
            sb.append("No stock change (value and GST only).\n")
        }
        return sb.toString().trimEnd()
    }

    // ───────────────────────── save ─────────────────────────

    private fun confirmSave() {
        val l = line ?: return
        val p = currentPlan ?: return
        val view = layoutInflater.inflate(R.layout.dialog_confirm_correction, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Same figures as the result card on this page, so the popup never disagrees with it.
        view.findViewById<TextView>(R.id.tvConfirmBig).apply {
            text = tvQtyBig.text; setTextColor(tvQtyBig.currentTextColor)
        }
        view.findViewById<TextView>(R.id.tvConfirmLines).text = tvQtyLines.text
        view.findViewById<TextView>(R.id.tvConfirmSay).text = tvQtySay.text
        val acc = view.findViewById<LinearLayout>(R.id.llConfirmAcc)
        fillAccountant(acc, p, l)
        val toggle = view.findViewById<TextView>(R.id.tvConfirmAccToggle)
        toggle.setOnClickListener {
            val open = acc.visibility != View.VISIBLE
            acc.visibility = if (open) View.VISIBLE else View.GONE
            styleAccToggle(toggle, open)
        }
        view.findViewById<View>(R.id.btnConfirmSave).setOnClickListener { dialog.dismiss(); doSave(l) }
        view.findViewById<View>(R.id.btnConfirmBack).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun doSave(l: PurchaseCorrectionRepository.LineView) {
        setSaveEnabled(false)
        val reason = etReason.text.toString().trim().ifBlank { if (mode == MODE_QTY) "Quantity correction" else "Price correction" }
        if (mode == MODE_PRICE) {
            val req = buildPriceRequest() ?: return
            vm.submitPriceCorrection(purchaseId, l.item.id, req, l.held, reason)
        } else {
            val pens = etPens.text.toString().trim().toDoubleOrNull() ?: return
            vm.submitQuantityCorrection(purchaseId, l.item.id, pens, isAdd(), l.held, reason)
        }
    }

    private fun runAdjustments(list: List<PurchaseCorrectionRepository.Adjustment>, i: Int) {
        if (i >= list.size) { finish(); return }
        val a = list[i]
        val kind = if (a.isDebitNote) Kind.PURCHASE_DEBIT_NOTE else Kind.PURCHASE_CREDIT_NOTE
        CreditAdjustmentPrompt.handlePurchase(
            activity = this, purchaseId = a.purchaseId, kind = kind,
            amount = a.amount, documentLocalId = a.docSeq,
            onDone = { runAdjustments(list, i + 1) }
        )
    }

    private fun showMessage(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
        refresh()
    }

    companion object {
        const val MODE_QTY = "QTY"
        const val MODE_PRICE = "PRICE"
    }
}
