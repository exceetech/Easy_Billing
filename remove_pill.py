import re
with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "r") as f:
    content = f.read()

# 1. Remove planSelectedPillViews declaration
content = re.sub(r'private var planSelectedPillViews: MutableMap<String, TextView> = mutableMapOf\(\)\n', '', content)

# 2. Remove planSelectedPillViews.clear()
content = re.sub(r'planSelectedPillViews\.clear\(\)\n', '', content)

# 3. Remove the pill creation logic inside renderPlans
pill_logic = r'            // Floating "Selected" pill.*?cardWrapper\.addView\(selectedPill\)\n\n            planSelectedPillViews\[plan\.plan_code\] = selectedPill\n'
content = re.sub(pill_logic, '', content, flags=re.DOTALL)

# 4. Remove the visibility toggle in updatePlanSelections
visibility_toggle = r'            planSelectedPillViews\[code\]\?\.visibility = if \(selected\) View\.VISIBLE else View\.GONE\n'
content = re.sub(visibility_toggle, '', content)

with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "w") as f:
    f.write(content)
