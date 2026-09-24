import re
with open("../pos-backend/app/routes/subscription_routes.py", "r") as f:
    content = f.read()

logic = """    base = max(sub.expiry_date, utc_now()) if sub.expiry_date else utc_now()
    sub.expiry_date = base + timedelta(days=extra_days)

    if sub.expiry_date <= utc_now():
        sub.status = "expired"
    elif sub.status == "expired":
        sub.status = "trial" if sub.plan == "trial" else "active"
"""

content = re.sub(
    r'base = max\(.*?if sub\.status == "expired":\n\s*sub\.status = "trial" if sub\.plan == "trial" else "active"',
    logic.strip(),
    content,
    flags=re.DOTALL
)

with open("../pos-backend/app/routes/subscription_routes.py", "w") as f:
    f.write(content)
