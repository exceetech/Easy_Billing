import re

with open("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml", "r") as f:
    content = f.read()

old_layout = """                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="horizontal" android:gravity="center_vertical"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_inv_field_light_grey"
                        android:paddingStart="14dp" android:paddingEnd="12dp" android:paddingVertical="10dp">
                        <TextView
                            android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:text="@string/xml_purchase_line_dialog_includes_gst_label"
                            android:textColor="#1A1A18" android:textSize="12.5sp"
                            android:fontFamily="@font/googlesans_medium"/>
                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/switchTaxInclusive"
                            style="@style/EpSwitch"
                            android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    </LinearLayout>"""

new_layout = """                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="horizontal" android:gravity="center_vertical"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_inv_field_light_grey"
                        android:paddingStart="14dp" android:paddingEnd="12dp" android:paddingVertical="10dp">
                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">
                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/add_product_price_includes_tax"
                                android:textColor="#1A1A18"
                                android:textSize="15sp"/>
                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/add_product_price_mrp_note"
                                android:textColor="#8A8272"
                                android:textSize="11.5sp"/>
                        </LinearLayout>
                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/switchTaxInclusive"
                            style="@style/EpSwitch"
                            android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    </LinearLayout>"""

content = content.replace(old_layout, new_layout)

with open("app/src/main/res/layout-sw600dp/dialog_purchase_line.xml", "w") as f:
    f.write(content)
