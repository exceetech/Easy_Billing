package com.example.easy_billing

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.apply
import kotlin.text.contains
import androidx.core.content.edit
import androidx.core.net.toUri

class RegisterActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Setup professional toolbar with back arrow
        setupToolbar(R.id.toolbar)
        supportActionBar?.title = " "

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


            // Check for empty fields
            if (name.isEmpty() || email.isEmpty()) {
                Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check for valid email
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Generate ticket number
            val ticket = generateTicketNumber()

            // Create mail subject and body
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

            // Send email in background thread
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
                }
                catch (e: Exception) {
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

        // Back to Login
        tvBackToLogin.setOnClickListener {
            finish()
        }
        generateAndSaveShopId() //MUST BE CALLED AFTER FIRST LOGIN
    }

    // Send email using intent
    @SuppressLint("QueryPermissionsNeeded")
    private fun sendEmail(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:exceetech@gmail.com".toUri()
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        // Check for email app
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
        else {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    // Generate ticket number
    private fun generateTicketNumber(): String {
        val time = System.currentTimeMillis()
        return "EXE-"+findViewById<EditText>(R.id.etPhoneNumber).text.toString().trim()+"$time"
    }

    // Generate and save shop id
    private fun generateAndSaveShopId() {
        val prefs = getSharedPreferences("SHOP_PREFS", MODE_PRIVATE)

        // If already exists, do NOT regenerate
        if (prefs.contains("SHOP_ID")) return

        val random4Digit = (1000..999999).random()
        val shopId = "EXE-$random4Digit"

        prefs.edit {
            putString("SHOP_ID", shopId)
        }
    }
}