package com.example.easy_billing

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.network.RetrofitClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SubscriptionActivity : BaseActivity() {

    private lateinit var tvPlan: TextView
    private lateinit var tvExpiry: TextView
    private lateinit var tvDaysLeft: TextView
    private lateinit var tvStatus: TextView

    private lateinit var imgQR: ImageView
    private lateinit var btnMonthly: Button
    private lateinit var btnYearly: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

        tvPlan = findViewById(R.id.tvPlan)
        tvExpiry = findViewById(R.id.tvExpiry)
        tvDaysLeft = findViewById(R.id.tvDaysLeft)
        tvStatus = findViewById(R.id.tvStatus)

        imgQR = findViewById(R.id.imgQR)
        btnMonthly = findViewById(R.id.btnMonthly)
        btnYearly = findViewById(R.id.btnYearly)

        btnMonthly.setOnClickListener {
            generateQR(999)
            Toast.makeText(this, "Pay ₹999 and contact admin", Toast.LENGTH_LONG).show()
        }

        btnYearly.setOnClickListener {
            generateQR(9999)
            Toast.makeText(this, "Pay ₹9999 and contact admin", Toast.LENGTH_LONG).show()
        }

        loadSubscription()
    }

    override fun onResume() {
        super.onResume()
        loadSubscription()
    }

    // ================= LOAD =================

    private fun loadSubscription() {

        lifecycleScope.launch {

            val token = getSharedPreferences("auth", MODE_PRIVATE)
                .getString("TOKEN", null)

            if (token.isNullOrEmpty()) {
                Toast.makeText(this@SubscriptionActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val res = RetrofitClient.api.getSubscription("Bearer $token")

                tvPlan.text = "Plan: ${res.plan ?: "None"}"

                tvExpiry.text = if (res.expiry_date != null)
                    "Expiry: ${formatDate(res.expiry_date)}"
                else "Expiry: -"

                tvDaysLeft.text = "Days left: ${res.remaining_days}"

                if (res.status == "active") {
                    tvStatus.text = "Active ✅"
                    tvStatus.setTextColor(getColor(R.color.green))
                } else {
                    tvStatus.text = "Expired ❌"
                    tvStatus.setTextColor(getColor(R.color.red))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SubscriptionActivity, "Failed to load", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= QR =================

    private fun generateQR(amount: Int) {

        val upiId = "yourupi@bank"

        val upiUri = "upi://pay?pa=$upiId&pn=EasyBilling&am=$amount&cu=INR"

        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(
            upiUri,
            com.google.zxing.BarcodeFormat.QR_CODE,
            400,
            400
        )

        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565)

        for (x in 0 until 400) {
            for (y in 0 until 400) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }

        imgQR.setImageBitmap(bitmap)
    }

    // ================= DATE =================

    private fun formatDate(dateStr: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val date = parser.parse(dateStr)
            formatter.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }
}