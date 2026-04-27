package com.example.easy_billing

import android.app.Application
import com.example.easy_billing.network.RetrofitClient

class EasyBillingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RetrofitClient.setContext(this)
    }
}