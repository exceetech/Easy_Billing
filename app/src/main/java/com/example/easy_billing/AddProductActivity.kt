package com.example.easy_billing

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.ProductDao
import kotlinx.coroutines.launch

class AddProductActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_product)

        val cbRice = findViewById<CheckBox>(R.id.cbRice)
        val cbMilk = findViewById<CheckBox>(R.id.cbMilk)
        val cbBread = findViewById<CheckBox>(R.id.cbBread)
        val cbSugar = findViewById<CheckBox>(R.id.cbSugar)

        val etItemName = findViewById<EditText>(R.id.etItemName)
        val etItemPrice = findViewById<EditText>(R.id.etItemPrice)

        val btnSave = findViewById<Button>(R.id.btnSave)

        btnSave.setOnClickListener {

            lifecycleScope.launch {
                val db = AppDatabase.getDatabase(this@AddProductActivity)

                // Common items
                if (cbRice.isChecked)
                    db.productDao().insert(Product(name = "Rice", price = 50.0, isCustom = false))

                if (cbMilk.isChecked)
                    db.productDao().insert(Product(name = "Milk", price = 30.0, isCustom = false))

                if (cbBread.isChecked)
                    db.productDao().insert(Product(name = "Bread", price = 40.0, isCustom = false))

                if (cbSugar.isChecked)
                    db.productDao().insert(Product(name = "Sugar", price = 45.0, isCustom = false))

                // Custom item
                val customName = etItemName.text.toString()
                val customPrice = etItemPrice.text.toString()

                if (customName.isNotEmpty() && customPrice.isNotEmpty()) {
                    db.productDao().insert(
                        Product(
                            name = customName,
                            price = customPrice.toDouble(),
                            isCustom = true
                        )
                    )
                }

                runOnUiThread {
                    Toast.makeText(
                        this@AddProductActivity,
                        "Products saved",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }
}