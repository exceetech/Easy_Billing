import re

with open("app/src/main/res/layout/activity_confirm_payment.xml", "r") as f:
    content = f.read()

gst_row = """                <LinearLayout
                    android:orientation="horizontal"
                    android:layout_marginTop="6dp"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content">

                    <TextView
                        android:text="@string/gst_18_pct"
"""
gst_row_with_id = """                <LinearLayout
                    android:id="@+id/rowGst"
                    android:orientation="horizontal"
                    android:layout_marginTop="6dp"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content">

                    <TextView
                        android:text="@string/gst_18_pct"
"""

content = content.replace(gst_row, gst_row_with_id)

with open("app/src/main/res/layout/activity_confirm_payment.xml", "w") as f:
    f.write(content)
