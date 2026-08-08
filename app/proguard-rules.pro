# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for readable crash stack traces from Play Console / logs.
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ===== App data/DTO/entity classes (Gson + Retrofit + Room) =====
# These are deserialized/serialized by field name reflectively, so R8 must not
# rename or strip their fields even though nothing "calls" them directly.
-keep class com.example.easy_billing.network.** { *; }
-keep interface com.example.easy_billing.network.** { *; }
-keep class com.example.easy_billing.db.** { *; }
-keep class com.example.easy_billing.api.** { *; }
-keep class com.example.easy_billing.gstr1.** { *; }
-keep class com.example.easy_billing.gstr2.** { *; }
-keep class com.example.easy_billing.DefaultProduct { *; }

# ===== Gson =====
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn com.google.gson.**
# Keep generic type info for Gson's TypeToken (used with List<T>/Map<K,V> parsing)
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ===== Retrofit / OkHttp =====
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**

# ===== Room =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ===== Razorpay Checkout SDK =====
-keepclassmembers class * implements com.razorpay.PaymentResultListener {
    public void onPaymentSuccess(java.lang.String);
    public void onPaymentError(int, java.lang.String);
}
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-optimizations !method/inlining/*
-keepclasseswithmembers class * {
    public void onPayment*(...);
}
-keepattributes JavascriptInterface
-keep class * extends android.webkit.WebChromeClient { *; }

# ===== Firebase Messaging =====
-keep class com.google.firebase.messaging.** { *; }
-dontwarn com.google.firebase.**

# ===== ZXing (barcode scanning) =====
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ===== MPAndroidChart =====
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ===== Apache POI / xmlbeans / woodstox (GSTR-1 Excel export) =====
# Large reflective library; keep it wholesale rather than risk broken exports.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.ctc.wstx.** { *; }
-keep class org.codehaus.stax2.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.commons.**
-dontwarn com.ctc.wstx.**
-dontwarn org.codehaus.stax2.**
-dontwarn javax.xml.stream.**
-dontwarn org.w3c.dom.**
-dontwarn org.osgi.**

# ===== javax.mail (com.sun.mail:android-mail) =====
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**

# ===== Kotlin coroutines / WorkManager =====
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# ===== General Android/Kotlin hygiene =====
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
