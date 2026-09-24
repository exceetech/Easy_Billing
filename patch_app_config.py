with open("../pos-backend/app/services/app_config_service.py", "r") as f:
    content = f.read()

if '"sub_gst_enabled":' not in content:
    content = content.replace(
        '"required_terms_version": "1.0",',
        '"required_terms_version": "1.0",\n    "sub_gst_enabled": "false",\n    "sub_gst_percent": "18.0",'
    )

with open("../pos-backend/app/services/app_config_service.py", "w") as f:
    f.write(content)
