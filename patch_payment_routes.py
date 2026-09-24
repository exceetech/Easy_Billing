with open("../pos-backend/app/routes/subscription_payment_routes.py", "r") as f:
    content = f.read()

content = content.replace(
    "breakdown = compute_pricing_breakdown(plan, coupon, credit_paise=upgrade_credit)",
    "breakdown = compute_pricing_breakdown(db, plan, coupon, credit_paise=upgrade_credit)"
)

content = content.replace(
    "breakdown = compute_pricing_breakdown(plan, coupon, credit_paise=credit_applied)",
    "breakdown = compute_pricing_breakdown(db, plan, coupon, credit_paise=credit_applied)"
)

with open("../pos-backend/app/routes/subscription_payment_routes.py", "w") as f:
    f.write(content)
