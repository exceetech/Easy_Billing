import re

with open("app/src/main/res/layout-sw600dp/activity_invoice.xml", "r") as f:
    content = f.read()

cess_xml = """                        </LinearLayout>

                        <LinearLayout
                            android:id="@+id/rowCess"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal"
                            android:gravity="center_vertical"
                            android:background="@drawable/bg_inv_tax_tile"
                            android:paddingHorizontal="11dp"
                            android:paddingVertical="10dp"
                            android:layout_marginTop="8dp">

                            <FrameLayout
                                android:layout_width="20dp"
                                android:layout_height="20dp"
                                android:background="@drawable/bg_inv_chip_xs_amber">

                                <ImageView
                                    android:layout_width="11dp"
                                    android:layout_height="11dp"
                                    android:layout_gravity="center"
                                    android:src="@drawable/ic_lc_percent"
                                    app:tint="#8A6526"
                                    android:contentDescription="@null"/>
                            </FrameLayout>

                            <TextView
                                android:id="@+id/tvCessLabel"
                                android:text="CESS"
                                android:textColor="#8A6526"
                                android:textSize="10.5sp"
                                android:textStyle="bold"
                                android:letterSpacing="0.04"
                                android:layout_marginStart="6dp"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"/>

                            <TextView
                                android:id="@+id/tvCessRate"
                                android:text=""
                                android:textColor="#B0A48C"
                                android:textSize="9sp"
                                android:layout_marginStart="4dp"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"/>

                            <Space
                                android:layout_width="0dp"
                                android:layout_weight="1"
                                android:layout_height="1dp"/>

                            <TextView
                                android:id="@+id/tvCessAmount"
                                android:text="—"
                                android:textColor="#18181B"
                                android:textStyle="bold"
                                android:textSize="14sp"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"/>
                        </LinearLayout>

                        <!-- Total tax panel -->"""

content = content.replace("                        </LinearLayout>\n\n                        <!-- Total tax panel -->", cess_xml)

with open("app/src/main/res/layout-sw600dp/activity_invoice.xml", "w") as f:
    f.write(content)
