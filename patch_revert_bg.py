with open("app/src/main/res/drawable/bg_search_premium.xml", "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <gradient
                android:type="linear"
                android:angle="315"
                android:startColor="#D4A574"
                android:endColor="#0F6E56"/>
            <corners android:radius="18dp"/>
        </shape>
    </item>
    <item android:top="1.4dp" android:bottom="1.4dp" android:left="1.4dp" android:right="1.4dp">
        <shape android:shape="rectangle">
            <solid android:color="#FFFFFF"/>
            <corners android:radius="16.6dp"/>
        </shape>
    </item>
</layer-list>
""")
