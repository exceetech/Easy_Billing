plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.easy_billing"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.easy_billing"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API host — single source of truth (read via BuildConfig.API_BASE_URL).
        // NOTE on reachability (this is what makes the app "load"):
        //   • Android emulator → the host machine is 10.0.2.2, NOT localhost/127.0.0.1.
        //   • Physical device  → use the dev machine's LAN IP (e.g. http://192.168.1.100:8080/)
        //     and make sure the phone is on the same Wi-Fi and the server is running.
        // Default below targets the emulator; the release build overrides it (see buildTypes).
        buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.100:8080/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // Override here if you test on a physical device instead of the emulator.
            // buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.100:8080/\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            // TODO: set the real production HTTPS endpoint before shipping.
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.com/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            // Apache POI requires these exclusions on Android
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/versions/9/module-info.class"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Room - Fixed dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.activity)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.appcompat:appcompat:1.6.1")


    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.android.material:material:1.11.0")

    implementation("com.google.firebase:firebase-messaging:23.4.1")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ===== MVVM (ViewModel + Fragment + LiveData/Flow) =====
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // ===== Apache POI — GSTR-1 Excel export =====
    // poi-ooxml covers xlsx; stax is the streaming XML parser it needs on Android.
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.xmlbeans:xmlbeans:5.1.1")
    implementation("com.fasterxml.woodstox:woodstox-core:6.5.1")

    // Gson for GSTR-1 draft serialisation (converter-gson already pulls it in,
    // but make it explicit so ProGuard rules apply correctly).
    implementation("com.google.code.gson:gson:2.10.1")
}
