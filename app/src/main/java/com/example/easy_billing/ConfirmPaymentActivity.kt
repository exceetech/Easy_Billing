package com.example.easy_billing

import com.example.easy_billing.R

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
        // On-device estimate only (see the caption this drives in
        // activity_confirm_payment.xml) — the backend has no tier-upgrade
        // proration logic yet, so these two extras are purely informational
        // and never touch subtotalPaise/serviceChargePaise/gstPaise/
        // lastComputedFinalPaise or the actual Razorpay order amount.
        const val EXTRA_UPGRADE_CREDIT_PAISE = "upgrade_credit_paise"
        const val EXTRA_UPGRADE_REMAINING_DAYS = "upgrade_remaining_days"
    }

    private lateinit var ivPlanIcon: ImageView
    private lateinit var planIconBadge: FrameLayout
    private lateinit var tvPlanName: TextView
    private lateinit var tvPlanCycle: TextView
    private lateinit var tvPlanPrice: TextView
    private lateinit var tvPlanPriceLabel: TextView
    private lateinit var rowUpgradeCredit: LinearLayout
    private lateinit var tvUpgradeCreditLabel: TextView
    private lateinit var tvUpgradeCreditAmount: TextView
    private lateinit var tvUpgradeCreditNote: TextView
    private lateinit var rowPayableAmount: LinearLayout
    private lateinit var tvPayableAmount: TextView
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
    private lateinit var mainContent: View
    private lateinit var bottomBar: View
    private lateinit var loadingOverlay: View
    private lateinit var cardContainer: View
    private lateinit var payLoadingOverlay: View
    private lateinit var btnClose: View

    private lateinit var planCode: String
    private lateinit var planTier: String
    private var planDurationDays: Int = 30
    private var planPricePaise: Int = 0
    private var baselinePaise: Int? = null
    private var upgradeCreditPaise: Int = 0
    private var upgradeRemainingDays: Int = 0

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

    /** Recomputes subtotal/service charge/GST from [newSubtotalPaise] using
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

    /** Plan price with the on-device upgrade-credit ESTIMATE subtracted —
     * this is what the local preview's service charge/GST/Total are
     * computed on, so the estimate shown before create-order responds
     * (see prefetchAuthoritativeOrder()) already reflects the reduced
     * payable amount instead of sitting at the full plan price until the
     * real order lands. Still just a client-side guess until then — the
     * moment the real order comes back, applyAuthoritativeOrderBreakdown()
     * overwrites all of this with the server's actual numbers regardless. */
    private fun localSubtotalBasePaise(): Int =
        (planPricePaise - upgradeCreditPaise).coerceAtLeast(0)

    /** Every money row on this screen used to do plain `paise / 100`
     * integer division, which silently truncated the paise remainder
     * (₹361.37 rendered as "₹361") — Razorpay's own checkout shows the
     * exact amount, so any non-round total made the two screens disagree
     * even though the underlying paise value was identical. Only show
     * decimals when there actually is a remainder, so round amounts stay
     * clean ("₹999") but anything with paise matches Razorpay exactly. */
    private fun formatRupees(paise: Int): String {
        val rupees = paise / 100.0
        return if (paise % 100 == 0) "₹${paise / 100}" else "₹%.2f".format(rupees)
    }

    // Set right before Checkout.open(); read back in onPaymentSuccess to
    // know which Order row to verify against.
    private var pendingOrderDbId: Int? = null

    // The real amount the backend's order was created for, kept separately
    // from whatever lastComputedFinalPaise ends up displaying. Needed
    // because applyAuthoritativeOrderBreakdown() now REFUSES to overwrite
    // a known, larger client-side upgrade credit with the backend's
    // uncredited breakdown (see that function's doc comment) — so the
    // screen can show ₹361.08 while Razorpay's actual order is still for
    // ₹1,202.40 until the backend implements proration. onPayClicked()
    // uses this to warn before opening checkout instead of silently
    // charging more than what was just shown.
    private var pendingOrderRealAmountPaise: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_payment)

        // Size the floating window itself — same percentage-of-screen-
        // width + capped-max technique already used for every Dialog
        // popup elsewhere in the app (ui/ThemedDropdown.kt). Used to be a
        // flat 340dp, which didn't adapt to different phone widths — see
        // phone_compatibility_plan.md Phase 2. Height stays WRAP_CONTENT
        // (matching the same established Dialog pattern) rather than the
        // old flat 540dp, since this screen's content is fixed/non-
        // scrolling and should size to exactly what it needs.
        val confirmWidthPx = minOf(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (400 * resources.displayMetrics.density).toInt()
        )
        window.setLayout(confirmWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        // Popup screen — no toolbar/action bar, just a floating close (X)
        // button in the top-right corner instead of a back arrow.
        btnClose = findViewById(R.id.btnClose)
        btnClose.setOnClickListener { finish() }

        planCode = intent.getStringExtra(EXTRA_PLAN_CODE) ?: run { finish(); return }
        planTier = intent.getStringExtra(EXTRA_PLAN_TIER) ?: "base"
        planDurationDays = intent.getIntExtra(EXTRA_PLAN_DURATION_DAYS, 30)
        planPricePaise = intent.getIntExtra(EXTRA_PLAN_PRICE_PAISE, 0)
        baselinePaise = if (intent.hasExtra(EXTRA_BASELINE_PAISE)) intent.getIntExtra(EXTRA_BASELINE_PAISE, 0) else null
        upgradeCreditPaise = intent.getIntExtra(EXTRA_UPGRADE_CREDIT_PAISE, 0)
        upgradeRemainingDays = intent.getIntExtra(EXTRA_UPGRADE_REMAINING_DAYS, 0)

        Checkout.preload(applicationContext)

        ivPlanIcon = findViewById(R.id.ivPlanIcon)
        planIconBadge = findViewById(R.id.planIconBadge)
        tvPlanName = findViewById(R.id.tvPlanName)
        tvPlanCycle = findViewById(R.id.tvPlanCycle)
        tvPlanPrice = findViewById(R.id.tvPlanPrice)
        tvPlanPriceLabel = findViewById(R.id.tvPlanPriceLabel)
        rowUpgradeCredit = findViewById(R.id.rowUpgradeCredit)
        tvUpgradeCreditLabel = findViewById(R.id.tvUpgradeCreditLabel)
        tvUpgradeCreditAmount = findViewById(R.id.tvUpgradeCreditAmount)
        tvUpgradeCreditNote = findViewById(R.id.tvUpgradeCreditNote)
        rowPayableAmount = findViewById(R.id.rowPayableAmount)
        tvPayableAmount = findViewById(R.id.tvPayableAmount)
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
        mainContent = findViewById(R.id.mainContent)
        bottomBar = findViewById(R.id.bottomBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        cardContainer = findViewById(R.id.cardContainer)
        payLoadingOverlay = findViewById(R.id.payLoadingOverlay)

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
                    recomputeLocalBreakdown(localSubtotalBasePaise())
                    updatePriceSummary()
                }
            }
        })

        // Computed immediately so the fields are populated with a valid
        // fallback, but NOT shown yet — mainContent/bottomBar stay behind
        // loadingOverlay (set VISIBLE below) until the authoritative order
        // resolves. Previously this local estimate rendered straight to
        // screen here, then got silently swapped for the real numbers a
        // moment later when prefetchAuthoritativeOrder() returned — the
        // exact "glance of another confirm and pay, then it recalculates"
        // flash that was reported. Now the user only ever sees ONE
        // version of this screen: the final one.
        recomputeLocalBreakdown(localSubtotalBasePaise())
        renderPlanSummary()
        updatePriceSummary()

        mainContent.visibility = View.INVISIBLE
        bottomBar.visibility = View.INVISIBLE
        loadingOverlay.visibility = View.VISIBLE

        prefetchAuthoritativeOrder()
    }

    private fun revealFinalScreen() {
        if (loadingOverlay.visibility == View.GONE) return
        mainContent.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        loadingOverlay.visibility = View.GONE
    }

    /** Three distinct, sequential steps — matching what was asked for
     * literally: (1) the card fades out and is fully gone (not just
     * covered by a loading state inside it), (2) THEN a plain spinner
     * appears on its own with no card behind it, (3) THEN — after it's
     * had a moment to actually spin, not just flash — [onReady] runs
     * (opens Razorpay). The activity itself is never finished (it can't
     * be: Checkout.open() needs it alive to deliver
     * onPaymentSuccess/onPaymentError back to), only its own window
     * content is animated away. */
    private suspend fun closeCardThenShowSpinner(onReady: () -> Unit) {
        val fadeOutMs = 220L
        val spinnerHoldMs = 450L

        cardContainer.isClickable = false
        cardContainer.animate().alpha(0f).setDuration(fadeOutMs).start()
        btnClose.animate().alpha(0f).setDuration(fadeOutMs).withEndAction {
            btnClose.visibility = View.INVISIBLE
        }.start()
        kotlinx.coroutines.delay(fadeOutMs)

        cardContainer.visibility = View.INVISIBLE

        payLoadingOverlay.alpha = 0f
        payLoadingOverlay.visibility = View.VISIBLE
        payLoadingOverlay.animate().alpha(1f).setDuration(150).start()
        kotlinx.coroutines.delay(spinnerHoldMs)

        onReady()
    }

    /** Reverses closeCardThenShowSpinner() — called when the user cancels
     * or the payment fails and lands back on this screen to retry, or if
     * checkout.open() itself throws before Razorpay's window ever
     * appeared. */
    private fun reopenCardAfterCancelledOrFailedCheckout() {
        payLoadingOverlay.animate().alpha(0f).setDuration(150).withEndAction {
            payLoadingOverlay.visibility = View.GONE
        }.start()

        cardContainer.visibility = View.VISIBLE
        cardContainer.isClickable = true
        cardContainer.animate().alpha(1f).setDuration(220).start()

        btnClose.visibility = View.VISIBLE
        btnClose.animate().alpha(1f).setDuration(220).start()
    }

    /** Fetches the real order (and therefore the real price breakdown —
     * including any server-side upgrade proration) as soon as the screen
     * opens, instead of waiting for Pay to be tapped. Without this, the
     * numbers shown here were only ever a client-side guess (2%/18% on
     * the sticker plan price) until the moment Pay was pressed, which is
     * exactly why they could disagree with what Razorpay went on to show
     * for a Base→Premium upgrade — the backend's proration only shows up
     * in create-order's response, and that call used to happen too late
     * to matter for what the user actually saw. The resulting order is
     * cached in [prefetchedOrder] and reused by onPayClicked() as-is (no
     * second create-order call, no duplicate pending order row) as long
     * as no coupon has since been applied — a coupon changes the price,
     * so that path still creates its own fresh order.
     *
     * The screen stays behind loadingOverlay (see onCreate/revealFinalScreen)
     * until this resolves ONE way or the other — success or failure — so
     * the user never sees the rough local estimate at all, only ever the
     * final numbers (or, if this genuinely fails, e.g. offline, the local
     * estimate is revealed as a fallback rather than leaving the user
     * stuck on a spinner forever). */
    private var prefetchedOrder: CreateOrderResponse? = null

    private fun prefetchAuthoritativeOrder() {
        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                revealFinalScreen()
                return@launch
            }
            try {
                val order = RetrofitClient.api.createSubscriptionOrder(token, CreateOrderRequest(planCode, null))

                if (!order.is_free && validatedCouponCode == null) {
                    prefetchedOrder = order
                    pendingOrderDbId = order.order_db_id
                    applyAuthoritativeOrderBreakdown(order)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Leave the local estimate as the fallback — Pay will
                // create a fresh order itself and still work correctly.
            } finally {
                revealFinalScreen()
            }
        }
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
        tvPlanPrice.text = formatRupees(planPricePaise)

        planIconBadge.setBackgroundResource(
            if (isPremium) R.drawable.bg_onboard_avatar_current else R.drawable.bg_terms_icon_teal
        )
        ivPlanIcon.setImageResource(if (isPremium) R.drawable.ic_lucide_sparkles else R.drawable.ic_lucide_badge_check)
        ivPlanIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (isPremium) 0xFFB8895A.toInt() else 0xFF085041.toInt()
        )

        if (upgradeCreditPaise > 0 && upgradeRemainingDays > 0) {
            rowUpgradeCredit.visibility = View.VISIBLE
            tvUpgradeCreditNote.visibility = View.VISIBLE
            rowPayableAmount.visibility = View.VISIBLE
            tvUpgradeCreditLabel.text = "Upgrade credit (est., ~$upgradeRemainingDays days left on Base)"
            tvUpgradeCreditAmount.text = "-${formatRupees(upgradeCreditPaise)}"
        } else {
            rowUpgradeCredit.visibility = View.GONE
            tvUpgradeCreditNote.visibility = View.GONE
            rowPayableAmount.visibility = View.GONE
        }

        val baseline = baselinePaise
        if (baseline != null && baseline > planPricePaise) {
            val savedPaise = baseline - planPricePaise
            val savedPct = Math.round(savedPaise * 100.0 / baseline).toInt()
            rowDiscount.visibility = View.VISIBLE
            tvDiscountLabel.text = "Cycle discount ($savedPct%)"
            tvDiscountAmount.text = "-${formatRupees(savedPaise)}"
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
            Toast.makeText(this, R.string.enter_a_coupon_code, Toast.LENGTH_SHORT).show()
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
                    "Coupon applied — you save ${formatRupees(res.discount_amount_paise)}"
                else
                    "Coupon applied"

                updatePriceSummary()
            } catch (e: retrofit2.HttpException) {
                validatedCouponCode = null
                couponDiscountPaise = 0
                recomputeLocalBreakdown(localSubtotalBasePaise())
                tvCouponResult.visibility = View.VISIBLE
                tvCouponResult.setTextColor(getColor(R.color.red))
                tvCouponResult.text = parseErrorDetail(e) ?: "Invalid coupon"
                updatePriceSummary()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ConfirmPaymentActivity, getString(R.string.confirmpaymentactivity_couldnt_validate_coupon), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** BUG FIXED HERE: Total used to come from lastComputedFinalPaise —
     * a separate field set independently (order.amount_paise from the
     * backend, or a locally-recomputed value) that could silently drift
     * from what subtotalPaise/serviceChargePaise/gstPaise actually add
     * up to on screen (e.g. the backend rounding GST slightly differently
     * than subtotal+service+gst would sum to). The Total shown must
     * always be the literal sum of the rows above it — Payable amount +
     * Service charge + GST — never a fourth, independently-sourced
     * number, so the three visible line items always add up to exactly
     * what "Total" says. */
    private fun updatePriceSummary() {
        val finalPaise = subtotalPaise + serviceChargePaise + gstPaise
        lastComputedFinalPaise = finalPaise

        rowCoupon.visibility = if (validatedCouponCode != null && couponDiscountPaise > 0) View.VISIBLE else View.GONE
        tvCouponDiscountAmount.text = "-${formatRupees(couponDiscountPaise)}"

        tvPayableAmount.text = formatRupees(subtotalPaise)
        tvServiceCharge.text = formatRupees(serviceChargePaise)
        tvGst.text = formatRupees(gstPaise)

        tvFinalPrice.text = if (finalPaise == 0) "Free" else formatRupees(finalPaise)
        btnPay.text = if (finalPaise == 0) "Activate" else "Pay ${formatRupees(finalPaise)} securely"
    }

    // ================= PAY =================

    private fun onPayClicked() {
        setPaymentInProgress(true)

        lifecycleScope.launch {
            val token = getSharedPreferences("auth", MODE_PRIVATE).getString("TOKEN", null)
            if (token.isNullOrEmpty()) {
                setPaymentInProgress(false)
                Toast.makeText(this@ConfirmPaymentActivity, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                // Reuse the order already fetched on screen load instead of
                // creating a second one — only valid when no coupon has
                // been applied since (a coupon changes the price, so that
                // case always asks the backend for a fresh order below).
                val reusable = prefetchedOrder
                val order = if (reusable != null && validatedCouponCode == null) {
                    reusable
                } else {
                    RetrofitClient.api.createSubscriptionOrder(
                        token,
                        CreateOrderRequest(planCode, validatedCouponCode)
                    )
                }

                if (order.is_free) {
                    // Backend already activated the subscription directly
                    // (zero-amount coupon) — nothing left to do here except
                    // report success back to SubscriptionActivity.
                    setPaymentInProgress(false)
                    Toast.makeText(this@ConfirmPaymentActivity, R.string.subscription_activated, Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                    return@launch
                }

                pendingOrderDbId = order.order_db_id
                applyAuthoritativeOrderBreakdown(order)

                // Belt-and-braces only at this point — applyAuthoritativeOrderBreakdown()
                // just set both of these from the same order response, so
                // they should always be equal. Kept as a defensive check in
                // case some future code path calls openRazorpayCheckout()
                // without going through applyAuthoritativeOrderBreakdown()
                // first; should never actually fire in normal use.
                val realAmount = pendingOrderRealAmountPaise
                val shown = lastComputedFinalPaise
                if (realAmount != null && shown != null && realAmount > shown) {
                    Toast.makeText(
                        this@ConfirmPaymentActivity,
                        "Your Base plan credit isn't applied by the payment provider yet — you'll be charged ${formatRupees(realAmount)}.",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Close the card, THEN spin, THEN open Razorpay — three
                // distinct sequential steps. reopenCardAfterCancelledOrFailedCheckout()
                // (called from onPaymentError / the checkout-open failure
                // catch below) reverses this if the user cancels/fails and
                // lands back here to retry.
                closeCardThenShowSpinner { openRazorpayCheckout(order) }

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
                Toast.makeText(this@ConfirmPaymentActivity, getString(R.string.confirmpaymentactivity_couldnt_start_payment_please), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** create-order is the ONLY backend call that returns the real,
     * authoritative price breakdown — it's what Razorpay actually charges
     * (order.amount_paise). Everything shown before this point (including
     * the "Upgrade credit (est.)" row) is a client-side guess, because
     * there's no side-effect-free preview endpoint to call earlier. Once
     * the real order exists, overwrite the on-screen numbers with it so
     * that Total always equals order.amount_paise exactly — the same
     * amount Razorpay's own checkout displays and charges. If
     * subtotal_after_discount_paise came back lower than the sticker
     * plan price with NO coupon applied, that gap is the backend's own
     * upgrade proration — show it as a real, confirmed credit rather
     * than the earlier "estimated" one.
     *
     * Now ALWAYS trusts the backend unconditionally (the earlier
     * "backendIgnoredKnownCredit" fallback — keeping the local estimate
     * on screen when the backend's own credit looked absent — has been
     * removed). That fallback existed only because the backend used to
     * have zero upgrade-proration logic; now that
     * subscription_pricing_service/subscription_entitlement_service
     * compute it correctly, keeping the fallback around was actively
     * dangerous: any time the app's local ESTIMATE (a rough guess from
     * SubscriptionActivity, based on the Base plan's monthly list price)
     * disagreed with a legitimately-different backend figure, the
     * fallback would show the wrong number while Razorpay still charged
     * the correct backend one — the exact "Total on screen != what
     * Razorpay shows" bug this was all trying to fix in the first place.
     * pendingOrderRealAmountPaise still tracks the order's real amount
     * purely as a belt-and-braces check in onPayClicked(). */
    private fun applyAuthoritativeOrderBreakdown(order: CreateOrderResponse) {
        pendingOrderRealAmountPaise = order.amount_paise

        subtotalPaise = order.subtotal_after_discount_paise
        serviceChargePaise = order.service_charge_paise
        gstPaise = order.gst_paise
        lastComputedFinalPaise = order.amount_paise

        if (validatedCouponCode == null) {
            val serverCredit = planPricePaise - order.subtotal_after_discount_paise
            if (serverCredit > 0) {
                rowUpgradeCredit.visibility = View.VISIBLE
                tvUpgradeCreditNote.visibility = View.GONE
                rowPayableAmount.visibility = View.VISIBLE
                tvUpgradeCreditLabel.text = "Upgrade credit"
                tvUpgradeCreditAmount.text = "-${formatRupees(serverCredit)}"
            } else {
                rowUpgradeCredit.visibility = View.GONE
                tvUpgradeCreditNote.visibility = View.GONE
                rowPayableAmount.visibility = View.GONE
            }
        }

        updatePriceSummary()
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
            reopenCardAfterCancelledOrFailedCheckout()
            setPaymentInProgress(false)
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.confirmpaymentactivity_couldnt_open_payment_screen), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@ConfirmPaymentActivity, R.string.payment_successful_subscription_activated, Toast.LENGTH_LONG).show()
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
        // User is back on this screen (cancelled or failed) — reopen the
        // card, it was closed right before Razorpay's window took over.
        reopenCardAfterCancelledOrFailedCheckout()
        setPaymentInProgress(false)
        // Razorpay uses a specific code for user-initiated cancellation;
        // avoid scaring the user with "payment failed" language for a
        // simple back-button cancel.
        if (code == Checkout.PAYMENT_CANCELED) {
            Toast.makeText(this, R.string.payment_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.payment_failed_try_again, Toast.LENGTH_LONG).show()
        }
    }

    private fun showPendingVerificationState() {
        Toast.makeText(
            this,
            R.string.verifying_payment_message,
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
