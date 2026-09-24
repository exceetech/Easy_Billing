with open("../pos-backend/app/routes/subscription_routes.py", "r") as f:
    content = f.read()

content = content.replace(
    'if extra_days <= 0:\n        return {"error": "extra_days must be positive"}',
    'if extra_days == 0:\n        return {"error": "extra_days must be non-zero (can be negative to remove days)"}'
)

with open("../pos-backend/app/routes/subscription_routes.py", "w") as f:
    f.write(content)
