package com.example.easy_billing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.apply
import kotlin.text.contains

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val etShopName = findViewById<EditText>(R.id.ShopName)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val tvBackToLogin = findViewById<TextView>(R.id.tvBackToLogin)

        btnRegister.setOnClickListener {

            val name = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()
            val shopName = etShopName.text.toString().trim()


            if (name.isEmpty() || email.isEmpty()) {
                Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ticket = generateTicketNumber()

            val subject = "New Enquiry | Ticket $ticket"
            val body = """
        New enquiry received.

        Name: $name
        Email: $email
        Phone: $phone
        Shop Name: $shopName
        Ticket: $ticket

        Please respond to the user.
    """.trimIndent()

            Thread {
                try {
                    EmailSender.sendEmail(subject, body)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Registration Successful!\nWe'll get back to you shortly",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            e.javaClass.simpleName + " : " + e.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }

        tvBackToLogin.setOnClickListener {
            finish()
        }
        generateAndSaveShopId() //MUST BE CALLED AFTER FIRST LOGIN
    }

    private fun sendEmail(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:exceetech@gmail.com")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateTicketNumber(): String {
        val time = System.currentTimeMillis()
        return "EXE-"+findViewById<EditText>(R.id.etPhoneNumber).text.toString().trim()+"$time"
    }

    private fun generateAndSaveShopId() {
        val prefs = getSharedPreferences("SHOP_PREFS", MODE_PRIVATE)

        // If already exists, do NOT regenerate
        if (prefs.contains("SHOP_ID")) return

        val random4Digit = (1000..9999).random()
        val shopId = "EXE-$random4Digit"

        prefs.edit()
            .putString("SHOP_ID", shopId)
            .apply()
    }
}