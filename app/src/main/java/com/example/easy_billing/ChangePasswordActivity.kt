package com.example.easy_billing


import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.easy_billing.MainActivity
import com.example.easy_billing.R

class ChangePasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val btnSave = findViewById<Button>(R.id.btnSavePassword)

        val prefs = getSharedPreferences("easy_billing_prefs", MODE_PRIVATE)

        btnSave.setOnClickListener {

            val newPass = etNewPassword.text.toString().trim()

            if (newPass.length < 4) {
                Toast.makeText(this, "Password too short", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("PASSWORD", newPass)
                .putBoolean("FIRST_LOGIN", false)
                .apply()

            Toast.makeText(this, "Password updated. Please login again.", Toast.LENGTH_LONG).show()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}