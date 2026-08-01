package com.example.easy_billing

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.*
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Subscription purchase flow — plan selection, optional coupon, Razorpay
 * checkout, and server-side payment verification.
 *
 * TRUST BOUNDARY: this Activity's job is to collect payment and report
 * identifiers back. It must never treat Razorpay's local success
 * callback (onPaymentSuccess) as the actual confirmation — that's only
 * shown to the user AFTER verifySubscriptionPayment() on the backend
 * confirms the payment signature. See subscription_payment_routes.py's
 * module docstring for the matching backend-side explanation.
 *
 * Replaces the old static-QR-code "pay and contact admin" flow entirely.
 */
class SubscriptionActivity : BaseActivity(), PaymentResultWithDataListener {

    private lateinit var tvPlan: TextView
    private lateinit var tvExpiry: TextView
    private lateinit var tvDaysLeft: TextView
    private lateinit var tvStatus: TextView

    private lateinit var cardTrial: LinearLayout
    private lateinit var btnStartTrial: Button

    private lateinit var llPlans: LinearLayout
    private lateinit var etCoupon: EditText
    private lateinit var btnApplyCoupon: Button
    private lateinit var tvCouponResult: TextView
    private lateinit var tvFinalPrice: TextView
    private lateinit var btnPay: Button
    private lateinit var progressPayment: ProgressBar

    private var plans: List<PlanResponse> = emptyList()
    private var selectedPlan: PlanResponse? = null
    private var planCardViews: MutableMap<String, LinearLayout> = mutableMapOf()

    // Only set once validate-coupon has actually succeeded for the
    // CURRENT plan + coupon text combination — cleared on any edit to
    // either, so a stale discount can never silently apply to a
    // different selection than the one it was validated against.
    private var validatedCouponCode: String? = null
    private var lastComputedFinalPaise: Int? = null

    // Set right before Checkout.open(); read back in onPaymentSuccess to
    // know which Order row to verify against.
    private var pendingOrderDbId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        Checkout.preload(applicationContext)

        tvPlan = findViewById(R.id.tvPlan)
        tvExpiry = findViewById(R.id.tvExpiry)
        tvDaysLeft = findViewById(R.id.tvDaysLeft)
        tvStatus = findViewById(R.id.tvStatus)

        cardTrial = findViewById(R.id.cardTrial)
        btnStartTrial = findViewById(R.id.btnStartTrial)

        llPlans = findViewById(R.id.llPlans)
        etCoupon = findViewById(R.id.etCoupon)
        btnApplyCoupon = findViewById(R.id.btnApplyCoupon)
        tvCouponResult = findViewById(R.id.tvCouponResult)
        tvFinalPrice = findViewById(R.id.tvFinalPrice)
        btnPay = findViewById(R.id.btnPay)
        progressPayment = findViewById(R.id.progressPayment)

        btnApplyCoupon.setOnClickListener { onApplyCouponClicked() }
        btnPay.setOnClickListener { onPayClicked() }
        btnStartTrial.setOnClickListener { onStartTrialClicked() }

        // Any edit to the coupon field invalidates a previously-validated
        // coupon — prevents paying at a stale discounted price after the
        // user changes the code without re-applying.
        etCoupon.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (validatedCouponCode != null && validatedCouponCode != s?.toString()?.trim()?.uppercase()) {
                    validatedCouponCode = null
                    tvCouponResult.visibility = View.GONE
                    updatePriceSummary()
                }
            }
        })

        loadSubscription()
        loadPlans()
    }

    override fun onResume() {
        super.onResume()
        loadSubscription()
    }

    // ================= CURRENT SUBSCRIPTION STATUS =================

    private fun loadSubscription() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                Toast.makeText(this@SubscriptionActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val res = RetrofitClient.api.getSubscription(token)

                val planLabel = if (res.tier != null) "${res.plan ?: "None"} (${res.tier})" else (res.plan ?: "None")
                tvPlan.text = "Plan: $planLabel"

                tvExpiry.text = when {
                    res.expiry_ms != null ->
                        "Expiry: ${com.example.easy_billing.util.AppTime.formatter("dd MMM yyyy").format(java.util.Date(res.expiry_ms))}"
                    res.expiry_date != null -> "Expiry: ${formatDate(res.expiry_date)}"
                    else -> "Expiry: -"
                }

                tvDaysLeft.text = "Days left: ${res.remaining_days}"

                // "trial" is a genuinely usable, active status — must not
                // fall into the same visual bucket as "expired" the way a
                // naive `if (status == "active")` check would (see the
                // backend fix in dependencies.get_current_shop for the
                // same class of bug on the enforcement side).
                when (res.status) {
                    "active" -> {
                        tvStatus.text = "Active ✅"
                        tvStatus.setTextColor(getColor(R.color.green))
                    }
                    "trial" -> {
                        tvStatus.text = "Trial — ${res.remaining_days} day(s) left"
                        tvStatus.setTextColor(getColor(R.color.primary))
                    }
                    else -> {
                        tvStatus.text = "Expired ❌"
                        tvStatus.setTextColor(getColor(R.color.red))
                    }
                }

                // Trial card only makes sense to offer when the shop
                // hasn't already burned its one trial (plan §4.3, server-
                // enforced — this is just matching the UI to that truth,
                // not a second enforcement point) AND isn't already on
                // Premium right now (no point offering a trial on top of
                // an active Premium subscription).
                cardTrial.visibility =
                    if (!res.has_used_trial && res.tier != "premium") View.VISIBLE else View.GONE

            } catch (e: Exception) {
                e.printStackTrace()
                com.google.android.material.snackbar.Snackbar.make(
                    tvPlan,
                    "Failed to load",
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).setAction("Retry") { loadSubscription() }.show()
            }
        }
    }

    // ================= TRIAL =================

    private fun onStartTrialClicked() {
        btnStartTrial.isEnabled = false

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                btnStartTrial.isEnabled = true
                Toast.makeText(this@SubscriptionActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                RetrofitClient.api.startTrial(token)
                Toast.makeText(this@SubscriptionActivity, "Free trial started", Toast.LENGTH_LONG).show()
                loadSubscription()
                finish()
            } catch (e: retrofit2.HttpException) {
                btnStartTrial.isEnabled = true
                Toast.makeText(
                    this@SubscriptionActivity,
                    parseErrorDetail(e) ?: "Couldn't start trial",
                    Toast.LENGTH_LONG
                ).show()
                // A 400 here means the trial was already used (server is
                // the source of truth) — refresh so the card correctly
                // disappears instead of staying visible and re-offering
                // an already-used trial.
                loadSubscription()
            } catch (e: Exception) {
                btnStartTrial.isEnabled = true
                e.printStackTrace()
                Toast.makeText(this@SubscriptionActivity, "Couldn't start trial", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= PLANS =================

    private fun loadPlans() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
            try {
                plans = RetrofitClient.api.getPlans(token)
                renderPlans()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SubscriptionActivity, "Couldn't load plans", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderPlans() {
        llPlans.removeAllViews()
        planCardViews.clear()

        for (plan in plans) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 28, 32, 28)
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
                isClickable = true
                isFocusable = true
            }

            val title = TextView(this).apply {
                text = plan.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF111827.toInt())
            }

            val price = TextView(this).apply {
                text = if (plan.price_paise == 0) "Free" else "₹${plan.price_paise / 100} / ${plan.duration_days} days"
                textSize = 14f
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, 8, 0, 0)
            }

            card.addView(title)
            card.addView(price)
            card.setOnClickListener { onPlanSelected(plan) }

            llPlans.addView(card)
            planCardViews[plan.plan_code] = card
        }
    }

    private fun onPlanSelected(plan: PlanResponse) {
        selectedPlan = plan
        // Selecting a different plan invalidates any coupon validated
        // against the previous one — validate-coupon's discount is
        // plan-specific (percentage-of-price), so it can't just carry over.
        validatedCouponCode = null
        tvCouponResult.visibility = View.GONE

        for ((code, view) in planCardViews) {
            view.setBackgroundResource(if (code == plan.plan_code) R.drawable.bg_card_selected else R.drawable.bg_card)
        }

        updatePriceSummary()
    }

    // ================= COUPON =================

    private fun onApplyCouponClicked() {
        val plan = selectedPlan
        if (plan == null) {
            Toast.makeText(this, "Select a plan first", Toast.LENGTH_SHORT).show()
            return
        }
        val code = etCoupon.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            Toast.makeText(this, "Enter a coupon code", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
            try {
                val res = RetrofitClient.api.validateCoupon(token, ValidateCouponRequest(plan.plan_code, code))
                validatedCouponCode = code
                lastComputedFinalPaise = res.final_amount_paise

                tvCouponResult.visibility = View.VISIBLE
                tvCouponResult.setTextColor(getColor(R.color.green))
                tvCouponResult.text = if (res.discount_amount_paise > 0)
                    "Coupon applied — you save ₹${res.discount_amount_paise / 100}"
                else
                    "Coupon applied"

                updatePriceSummary()
            } catch (e: retrofit2.HttpException) {
                validatedCouponCode = null
                tvCouponResult.visibility = View.VISIBLE
                tvCouponResult.setTextColor(getColor(R.color.red))
                tvCouponResult.text = parseErrorDetail(e) ?: "Invalid coupon"
                updatePriceSummary()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SubscriptionActivity, "Couldn't validate coupon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePriceSummary() {
        val plan = selectedPlan
        if (plan == null) {
            tvFinalPrice.text = "Select a plan to continue"
            btnPay.isEnabled = false
            return
        }

        val finalPaise = if (validatedCouponCode != null) (lastComputedFinalPaise ?: plan.price_paise) else plan.price_paise

        tvFinalPrice.text = if (finalPaise == 0) "Total: Free" else "Total: ₹${finalPaise / 100}"
        btnPay.isEnabled = true
        btnPay.text = if (finalPaise == 0) "Activate" else "Pay ₹${finalPaise / 100}"
    }

    // ================= PAY =================

    private fun onPayClicked() {
        val plan = selectedPlan ?: return

        setPaymentInProgress(true)

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                setPaymentInProgress(false)
                Toast.makeText(this@SubscriptionActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val order = RetrofitClient.api.createSubscriptionOrder(
                    token,
                    CreateOrderRequest(plan.plan_code, validatedCouponCode)
                )

                if (order.is_free) {
                    // Backend already activated the subscription directly
                    // (zero-amount coupon) — nothing left to do here except
                    // reflect the new state.
                    setPaymentInProgress(false)
                    Toast.makeText(this@SubscriptionActivity, "Subscription activated", Toast.LENGTH_LONG).show()
                    loadSubscription()
                    finish()
                    return@launch
                }

                pendingOrderDbId = order.order_db_id
                openRazorpayCheckout(order)

            } catch (e: Exception) {
                setPaymentInProgress(false)
                e.printStackTrace()
                Toast.makeText(this@SubscriptionActivity, "Couldn't start payment. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openRazorpayCheckout(order: CreateOrderResponse) {
        val checkout = Checkout()
        checkout.setKeyID(order.razorpay_key_id)

        val shopName = getSharedPreferences("auth", MODE_PRIVATE).getString("SHOP_NAME", "") ?: ""

        try {
            val options = JSONObject().apply {
                put("name", "Easy Billing")
                put("description", "Subscription")
                put("order_id", order.razorpay_order_id)
                put("currency", order.currency)
                put("amount", order.amount_paise)
                put("prefill", JSONObject().apply {
                    put("name", shopName)
                })
                // Retry lets the user fix a declined card without losing
                // the order — Razorpay reopens checkout against the same
                // order_id rather than requiring a fresh create-order call.
                put("retry", JSONObject().apply { put("enabled", true) })
            }
            // Checkout.open() requires an Activity implementing
            // PaymentResultWithDataListener — this class does, so
            // onPaymentSuccess/onPaymentError below receive the result.
            checkout.open(this, options)
        } catch (e: Exception) {
            setPaymentInProgress(false)
            e.printStackTrace()
            Toast.makeText(this, "Couldn't open payment screen", Toast.LENGTH_LONG).show()
        }
    }

    // ================= RAZORPAY CALLBACKS =================

    override fun onPaymentSuccess(razorpayPaymentId: String?, data: PaymentData?) {
        // IMPORTANT: this is Razorpay's LOCAL callback, not proof of
        // payment on its own — see the trust-boundary note in the class
        // doc comment. The success state is only shown to the user after
        // verifySubscriptionPayment() below confirms it server-side.
        val orderDbId = pendingOrderDbId
        val razorpayOrderId = data?.orderId
        val razorpaySignature = data?.signature

        if (orderDbId == null || razorpayPaymentId == null || razorpayOrderId == null || razorpaySignature == null) {
            setPaymentInProgress(false)
            showPendingVerificationState()
            return
        }

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                setPaymentInProgress(false)
                showPendingVerificationState()
                return@launch
            }

            try {
                RetrofitClient.api.verifySubscriptionPayment(
                    token,
                    VerifyPaymentRequest(orderDbId, razorpayOrderId, razorpayPaymentId, razorpaySignature)
                )
                setPaymentInProgress(false)
                Toast.makeText(this@SubscriptionActivity, "Payment successful — subscription activated", Toast.LENGTH_LONG).show()
                loadSubscription()
                finish()
            } catch (e: Exception) {
                // Network drop right after Razorpay's success callback —
                // the exact case the webhook (razorpay-webhook, backend)
                // exists to catch independently. Don't tell the user the
                // payment failed; it may well have gone through.
                e.printStackTrace()
                setPaymentInProgress(false)
                showPendingVerificationState()
            }
        }
    }

    override fun onPaymentError(code: Int, response: String?, data: PaymentData?) {
        setPaymentInProgress(false)
        // Razorpay uses a specific code for user-initiated cancellation;
        // avoid scaring the user with "payment failed" language for a
        // simple back-button cancel.
        if (code == Checkout.PAYMENT_CANCELED) {
            Toast.makeText(this, "Payment cancelled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPendingVerificationState() {
        Toast.makeText(
            this,
            "Verifying your payment — this can take a moment. Pull to refresh shortly if it doesn't update.",
            Toast.LENGTH_LONG
        ).show()
        // A brief re-check rather than leaving the screen stale — if the
        // webhook lands in the meantime, this will pick up the activated
        // subscription without the user needing to do anything.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            loadSubscription()
        }
    }

    private fun setPaymentInProgress(inProgress: Boolean) {
        btnPay.isEnabled = !inProgress && selectedPlan != null
        btnApplyCoupon.isEnabled = !inProgress
        progressPayment.visibility = if (inProgress) View.VISIBLE else View.GONE
    }

    private fun parseErrorDetail(e: retrofit2.HttpException): String? {
        return try {
            val body = e.response()?.errorBody()?.string() ?: return null
            JSONObject(body).optString("detail", null)
        } catch (ex: Exception) {
            null
        }
    }

    // ================= DATE =================

    private fun formatDate(dateStr: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val date = parser.parse(dateStr)
            formatter.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }
}
