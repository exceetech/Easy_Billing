package com.example.easy_billing.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.easy_billing.db.Product
import com.example.easy_billing.DefaultProduct
import com.example.easy_billing.DefaultProductDao

@Database(
    entities = [
        Product::class,
        Bill::class,
        BillItem::class,
        DefaultProduct::class,
        StoreInfo::class,
        BillingSettings::class,
        CreditAccount::class,
        CreditTransaction::class,

        Inventory::class,
        InventoryTransaction::class,
        InventoryLog::class,
        LossEntry::class
    ],
    version = 10
)

abstract class AppDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun billDao(): BillDao

    abstract fun billItemDao(): BillItemDao

    abstract fun defaultProductDao(): DefaultProductDao

    abstract fun storeInfoDao(): StoreInfoDao

    abstract fun billingSettingsDao(): BillingSettingsDao

    abstract fun creditAccountDao(): CreditAccountDao

    abstract fun creditTransactionDao(): CreditTransactionDao

    abstract fun inventoryDao(): InventoryDao
    abstract fun inventoryTransactionDao(): InventoryTransactionDao

    abstract fun lossDao(): LossDao

    abstract fun inventoryLogDao(): InventoryLogDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easy_billing_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        // 🔥 ADD THIS
        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}