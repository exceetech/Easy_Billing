import re

with open("../pos-backend/app/services/subscription_pricing_service.py", "r") as f:
    content = f.read()

# Add import for app_config_service
if "from app.services import app_config_service" not in content:
    content = content.replace(
        "from app.util.time_utils import utc_now",
        "from app.util.time_utils import utc_now\nfrom app.services import app_config_service"
    )

# Modify compute_pricing_breakdown signature
content = content.replace(
    "def compute_pricing_breakdown(plan: Plan, coupon: Coupon | None, credit_paise: int = 0) -> dict:",
    "def compute_pricing_breakdown(db: Session, plan: Plan, coupon: Coupon | None, credit_paise: int = 0) -> dict:"
)

# Replace the GST calculation inside compute_pricing_breakdown
old_gst_logic = """    service_charge = round(payable * (SERVICE_CHARGE_PERCENT / 100.0))
    taxable = payable + service_charge
    gst = round(taxable * (GST_PERCENT / 100.0))
    final = taxable + gst"""

new_gst_logic = """    service_charge = round(payable * (SERVICE_CHARGE_PERCENT / 100.0))
    taxable = payable + service_charge
    
    gst_enabled = app_config_service.get_config_bool(db, "sub_gst_enabled")
    if gst_enabled:
        gst_percent = float(app_config_service.get_config(db, "sub_gst_percent"))
        gst = round(taxable * (gst_percent / 100.0))
    else:
        gst = 0
        
    final = taxable + gst"""

content = content.replace(old_gst_logic, new_gst_logic)

with open("../pos-backend/app/services/subscription_pricing_service.py", "w") as f:
    f.write(content)
