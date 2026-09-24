with open("app/src/main/res/layout/activity_confirm_payment.xml", "r") as f:
    content = f.read()

gst_label = """                    <TextView
                        android:text="@string/gst_18_pct"
"""
gst_label_with_id = """                    <TextView
                        android:id="@+id/tvGstLabel"
                        android:text="@string/gst_18_pct"
"""

content = content.replace(gst_label, gst_label_with_id)

with open("app/src/main/res/layout/activity_confirm_payment.xml", "w") as f:
    f.write(content)
