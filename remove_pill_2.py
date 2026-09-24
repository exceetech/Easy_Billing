with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "r") as f:
    content = f.read()

import re
# Remove floating pill block
block = r"""            // Floating "Selected" pill — sits at the wrapper's actual top
            // \(y=0\), while the card itself starts dp\(9\) lower, so the pill
            // visually overlaps the card's top-left border corner\.
            val selectedPill = TextView\(this\)\.apply \{.*?wrapper\.addView\(selectedPill\)"""
content = re.sub(block, '', content, flags=re.DOTALL)

content = content.replace("planSelectedPillViews[plan.plan_code] = selectedPill\n", "")

with open("app/src/main/java/com/example/easy_billing/SubscriptionActivity.kt", "w") as f:
    f.write(content)
