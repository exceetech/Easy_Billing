package com.example.easy_billing

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.*
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Full-screen "Confirm and pay" step — order summary, coupon entry,
 * Razorpay checkout, and server-side payment verification.
 *
 * TRUST BOUNDARY: this Activity's job is to collect payment and report
 * identifiers back. It must never treat Razorpay's local success
 * callback (onPaymentSuccess) as the actual confirmation — that's only
 * shown to the user AFTER verifySubscriptionPayment() on the backend
 * confirms the payment signature. See subscription_payment_routes.py's
 * module docstring for the matching backend-side explanation.
 *
 * Reached from SubscriptionActivity after a plan + billing cycle has
 * already been picked there; this screen owns everything from coupon
 * entry through activation. Calls setResult(RESULT_OK) + finish() on a
 * verified success so the caller can auto-return.
 */
class ConfirmPaymentActivity : BaseActivity(), PaymentResultWithDataListener {

    companion object {
        const val EXTRA_PLAN_CODE = "plan_code"
        const val EXTRA_PLAN_TIER = "plan_tier"
        const val EXTRA_PLAN_DURATION_DAYS = "plan_duration_days"
        const val EXTRA_PLAN_PRICE_PAISE = "plan_price_paise"
        const val EXTRA_BASELINE_PAISE = "baseline_paise"
    }

    private lateinit var ivPlanIcon: ImageView
    private lateinit var planIconBadge: FrameLayout
    private lateinit var tvPlanName: TextView
    private lateinit var tvPlanCycle: TextView
    private lateinit var tvPlanPrice: TextView
    private lateinit var rowDiscount: LinearLayout
    private lateinit var tvDiscountLabel: TextView
    private lateinit var tvDiscountAmount: TextView
    private lateinit var rowCoupon: LinearLayout
    private lateinit var tvCouponDiscountAmount: TextView
    private lateinit var tvBonusDays: TextView
    private lateinit var tvServiceCharge: TextView
    private lateinit var tvGst: TextView
    private lateinit var tvFinalPrice: TextView
    private lateinit var etCoupon: EditText
    private lateinit var btnApplyCoupon: Button
    private lateinit var tvCouponResult: TextView
    private lateinit var btnPay: Button
    private lateinit var progressPayment: ProgressBar

    private lateinit var planCode: String
    private lateinit var planTier: String
    private var planDurationDays: Int = 30
    private var planPricePaise: Int = 0
    private var baselinePaise: Int? = null

    // Only set once validate-coupon has actually succeeded for the
    // CURRENT coupon text — cleared on any edit, so a stale discount can
    // never silently apply after the code changes without re-applying.
    private var validatedCouponCode: String? = null
    private var couponDiscountPaise: Int = 0
    private var lastComputedFinalPaise: Int? = null

    // Mirrors subscription_pricing_service.SERVICE_CHARGE_PERCENT/
    // GST_PERCENT on the backend — used ONLY to render an immediate
    // preview before any network round trip (initial screen load, before
    // a coupon is applied). The backend's response is always authoritative
    // once available (validate-coupon, create-order); this local copy
    // exists purely so the screen doesn't show ₹0/blank rows for a beat
    // while waiting on a call that, for the no-coupon case, never even
    // happens until Pay is tapped.
    private val serviceChargePercent = 2.0
    private val gstPercent = 18.0
    private var subtotalPaise: Int = 0
    private var serviceChargePaise: Int = 0
    private var gstPaise: Int = 0

    /** Recomputes subtotal/service charge/GST from [subtotalPaise] using
     * the same order of operations as the backend: service charge on the
     * subtotal, then GST on (subtotal + service charge). Called whenever
     * there's no authoritative backend breakdown to use instead (initial
     * load, or a coupon that was just cleared/invalidated). */
    private fun recomputeLocalBreakdown(newSubtotalPaise: Int) {
        subtotalPaise = newSubtotalPaise
        serviceChargePaise = Math.round(subtotalPaise * (serviceChargePercent / 100.0)).toInt()
        val taxable = subtotalPaise + serviceChargePaise
        gstPaise = Math.round(taxable * (gstPercent / 100.0)).toInt()
        lastComputedFinalPaise = taxable + gstPaise
    }

    // Set right before Checkout.open(); read back in onPaymentSuccess to
    // know which Order row to verify against.
    private var pendingOrderDbId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_payment)

        // Popup screen — no toolbar/action bar, just a floating close (X)
        // button in the top-right corner instead of a back arrow.
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        planCode = intent.getStringExtra(EXTRA_PLAN_CODE) ?: run { finish(); return }
        planTier = intent.getStringExtra(EXTRA_PLAN_TIER) ?: "base"
        planDurationDays = intent.getIntExtra(EXTRA_PLAN_DURATION_DAYS, 30)
        planPricePaise = intent.getIntExtra(EXTRA_PLAN_PRICE_PAISE, 0)
        baselinePaise = if (intent.hasExtra(EXTRA_BASELINE_PAISE)) intent.getIntExtra(EXTRA_BASELINE_PAISE, 0) else null

        Checkout.preload(applicationContext)

        ivPlanIcon = findViewById(R.id.ivPlanIcon)
        planIconBadge = findViewById(R.id.planIconBadge)
        tvPlanName = findViewById(R.id.tvPlanName)
        tvPlanCycle = findViewById(R.id.tvPlanCycle)
        tvPlanPrice = findViewById(R.id.tvPlanPrice)
        rowDiscount = findViewById(R.id.rowDiscount)
        tvDiscountLabel = findViewById(R.id.tvDiscountLabel)
        tvDiscountAmount = findViewById(R.id.tvDiscountAmount)
        rowCoupon = findViewById(R.id.rowCoupon)
        tvCouponDiscountAmount = findViewById(R.id.tvCouponDiscountAmount)
        tvBonusDays = findViewById(R.id.tvBonusDays)
        tvServiceCharge = findViewById(R.id.tvServiceCharge)
        tvGst = findViewById(R.id.tvGst)
        tvFinalPrice = findViewById(R.id.tvFinalPrice)
        etCoupon = findViewById(R.id.etCoupon)
        btnApplyCoupon = findViewById(R.id.btnApplyCoupon)
        tvCouponResult = findViewById(R.id.tvCouponResult)
        btnPay = findViewById(R.id.btnPay)
        progressPayment = findViewById(R.id.progressPayment)

        btnApplyCoupon.setOnClickListener { onApplyCouponClicked() }
        btnPay.setOnClickListener { onPayClicked() }

        // Any edit to the coupon field invalidates a previously-validated
        // coupon — prevents paying at a stale discounted price after the
        // user changes the code without re-applying.
        etCoupon.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (validatedCouponCode != null && validatedCouponCode != s?.toString()?.trim()?.uppercase()) {
                    validatedCouponCode = null
                    couponDiscountPaise = 0
                    tvCouponResult.visibility = View.GONE
                    recomputeLocalBreakdown(planPricePaise)
                    updatePriceSummary()
                }
            }
        })

        recomputeLocalBreakdown(planPricePaise)
        renderPlanSummary()
        updatePriceSummary()
    }

    // ================= SUMMARY =================

    private fun cycleLabel(days: Int): String = when (days) {
        30 -> "1 Month"
        90 -> "3 Months"
        195 -> "6 Months"
        395 -> "12 Months"
        else -> "$days days"
    }

    private fun renderPlanSummary() {
        val isPremium = planTier == "premium"
        tvPlanName.text = if (isPremium) "Premium" else "Base"
        tvPlanCycle.text = "${cycleLabel(planDurationDays)} billing cycle"
        tvPlanPrice.text = "₹${planPricePaise / 100}"

        planIconBadge.setBackgroundResource(
            if (isPremium) R.drawable.bg_onboard_avatar_current else R.drawable.bg_terms_icon_teal
        )
        ivPlanIcon.setImageResource(if (isPremium) R.drawable.ic_lucide_sparkles else R.drawable.ic_lucide_badge_check)
        ivPlanIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (isPremium) 0xFFB8895A.toInt() else 0xFF085041.toInt()
        )

        val baseline = baselinePaise
        if (baseline != null && baseline > planPricePaise) {
            val savedPaise = baseline - planPricePaise
            val savedPct = Math.round(savedPaise * 100.0 / baseline).toInt()
            rowDiscount.visibility = View.VISIBLE
            tvDiscountLabel.text = "Cycle discount ($savedPct%)"
            tvDiscountAmount.text = "-₹${savedPaise / 100}"
        } else {
            rowDiscount.visibility = View.GONE
        }

        val bonusDays = when (planDurationDays) {
            195 -> 15
            395 -> 30
            else -> 0
        }
        if (bonusDays > 0) {
            tvBonusDays.visibility = View.VISIBLE
            tvBonusDays.text = if (bonusDays == 30) "+1 month free" else "+$bonusDays days free"
        } else {
            tvBonusDays.visibility = View.GONE
        }
    }

    // ================= COUPON =================

    private fun onApplyCouponClicked() {
        val code = etCoupon.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            Toast.makeText(this, "Enter a coupon code", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null) ?: return@launch
            try {
                val res = RetrofitClient.api.validateCoupon(token, ValidateCouponRequest(planCode, code))
                validatedCouponCode = code
                couponDiscountPaise = res.discount_amount_paise
                // Authoritative breakdown from the backend now replaces the
                // local preview — the coupon discount changes the subtotal,
                // which changes the service charge and GST too since both
                // are percentages of it.
                subtotalPaise = res.subtotal_after_discount_paise
                serviceChargePaise = res.service_charge_paise
                gstPaise = res.gst_paise
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
                couponDiscountPaise = 0
                recomputeLocalBreakdown(planPricePaise)
                tvCouponResult.visibility = View.VISIBLE
                tvCouponResult.setTextColor(getColor(R.color.red))
                tvCouponResult.text = parseErrorDetail(e) ?: "Invalid coupon"
                updatePriceSummary()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ConfirmPaymentActivity, "Couldn't validate coupon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePriceSummary() {
        val finalPaise = lastComputedFinalPaise ?: (subtotalPaise + serviceChargePaise + gstPaise)

        rowCoupon.visibility = if (validatedCouponCode != null && couponDiscountPaise > 0) View.VISIBLE else View.GONE
        tvCouponDiscountAmount.text = "-₹${couponDiscountPaise / 100}"

        tvServiceCharge.text = "₹${serviceChargePaise / 100}"
        tvGst.text = "₹${gstPaise / 100}"

        tvFinalPrice.text = if (finalPaise == 0) "Free" else "₹${finalPaise / 100}"
        btnPay.text = if (finalPaise == 0) "Activate" else "Pay ₹${finalPaise / 100} securely"
    }

    // ================= PAY =================

    private fun onPayClicked() {
        setPaymentInProgress(true)

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                setPaymentInProgress(false)
                Toast.makeText(this@ConfirmPaymentActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val order = RetrofitClient.api.createSubscriptionOrder(
                    token,
                    CreateOrderRequest(planCode, validatedCouponCode)
                )

                if (order.is_free) {
                    // Backend already activated the subscription directly
                    // (zero-amount coupon) — nothing left to do here except
                    // report success back to SubscriptionActivity.
                    setPaymentInProgress(false)
                    Toast.makeText(this@ConfirmPaymentActivity, "Subscription activated", Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                    return@launch
                }

                pendingOrderDbId = order.order_db_id
                openRazorpayCheckout(order)

            } catch (e: retrofit2.HttpException) {
                // Surfaces the backend's actual reason (e.g. "downgrade
                // blocked until your Premium period ends on ...", or an
                // expired/already-redeemed coupon race) instead of a
                // generic "please try again" — this used to fall through
                // to the catch-all Exception branch below, which threw
                // away exactly the message create-order was built to
                // return.
                setPaymentInProgress(false)
                e.printStackTrace()
                Toast.makeText(
                    this@ConfirmPaymentActivity,
                    parseErrorDetail(e) ?: "Couldn't start payment. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                setPaymentInProgress(false)
                e.printStackTrace()
                Toast.makeText(this@ConfirmPaymentActivity, "Couldn't start payment. Please try again.", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@ConfirmPaymentActivity, "Payment successful — subscription activated", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_OK)
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
        // Report success-in-flight to the caller anyway — if the webhook
        // lands, the subscription will show as active when the user
        // returns to it; there's nothing more to keep this screen open
        // for. RESULT_OK still triggers SubscriptionActivity's refresh.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun setPaymentInProgress(inProgress: Boolean) {
        btnPay.isEnabled = !inProgress
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
}
