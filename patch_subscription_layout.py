with open("app/src/main/res/layout/activity_subscription.xml", "r") as f:
    content = f.read()

part2_old = """                <TextView
                    android:text="@string/subscription_title_part2"
                    android:textSize="21sp"
                    android:textStyle="italic"
                    android:fontFamily="serif"
                    android:textColor="#0F6E56"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"/>"""

part2_new = """                <TextView
                    android:text="@string/subscription_title_part2"
                    android:textSize="21sp"
                    android:textStyle="bold"
                    android:fontFamily="@font/googlesans_bold"
                    android:textColor="#1A1A18"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"/>"""

content = content.replace(part2_old, part2_new)

with open("app/src/main/res/layout/activity_subscription.xml", "w") as f:
    f.write(content)
