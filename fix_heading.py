import re
with open("app/src/main/res/layout/activity_subscription.xml", "r") as f:
    content = f.read()

old_heading_block = """            <LinearLayout
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginTop="2dp"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content">

                <TextView
                    android:text="@string/your_word"
                    android:textSize="21sp"
                    android:textStyle="bold"
                    android:fontFamily="@font/googlesans_bold"
                    android:textColor="#1A1A18"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"/>

                <TextView
                    android:text="@string/subscription_title_part2"
                    android:textSize="21sp"
                    android:textStyle="bold"
                    android:fontFamily="@font/googlesans_bold"
                    android:textColor="#1A1A18"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"/>

            </LinearLayout>"""

new_heading_block = """            <LinearLayout
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginTop="2dp"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:accessibilityHeading="true">

                <TextView
                    android:text="@string/your_word"
                    android:textSize="21sp"
                    android:textStyle="bold"
                    android:letterSpacing="-0.02"
                    android:fontFamily="@font/googlesans_bold"
                    android:textColor="#1A1A18"
                    android:includeFontPadding="false"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />

                <TextView
                    android:text="@string/subscription_title_part2"
                    android:textSize="21sp"
                    android:textStyle="italic"
                    android:fontFamily="serif"
                    android:textColor="#0F6E56"
                    android:includeFontPadding="false"
                    android:layout_marginStart="6dp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />

            </LinearLayout>"""

content = content.replace(old_heading_block, new_heading_block)

with open("app/src/main/res/layout/activity_subscription.xml", "w") as f:
    f.write(content)
