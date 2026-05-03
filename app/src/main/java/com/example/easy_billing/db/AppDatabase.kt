package com.example.easy_billing.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        LossEntry::class,

        // GST Entities
        GstProfile::class,
        GstSalesRecord::class,
        GstPurchaseRecord::class,

        // Purchase / inventory ops (v13)
        Purchase::class,
        PurchaseItem::class,
        PurchaseReturn::class,
        ScrapEntry::class
    ],
    version = 15
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

    // GST DAOs
    abstract fun gstProfileDao(): GstProfileDao
    abstract fun gstSalesRecordDao(): GstSalesRecordDao
    abstract fun gstPurchaseRecordDao(): GstPurchaseRecordDao

    // Purchase / inventory ops (v13)
    abstract fun purchaseDao(): PurchaseDao
    abstract fun purchaseItemDao(): PurchaseItemDao
    abstract fun purchaseReturnDao(): PurchaseReturnDao
    abstract fun scrapDao(): ScrapDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create gst_profile table (fixed column names to match GstProfile entity)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `gst_profile` (
                        `id` INTEGER NOT NULL,
                        `gstin` TEXT NOT NULL,
                        `legal_name` TEXT NOT NULL,
                        `trade_name` TEXT NOT NULL,
                        `gst_scheme` TEXT NOT NULL,
                        `registration_type` TEXT NOT NULL,
                        `state_code` TEXT NOT NULL,
                        `sync_status` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // 2. Create gst_sales_records table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `gst_sales_records` (
                        `id` TEXT NOT NULL,
                        `invoiceNumber` TEXT NOT NULL,
                        `invoiceDate` INTEGER NOT NULL,
                        `customerType` TEXT NOT NULL,
                        `customerGstin` TEXT,
                        `placeOfSupply` TEXT NOT NULL,
                        `supplyType` TEXT NOT NULL,
                        `hsnCode` TEXT NOT NULL,
                        `productName` TEXT NOT NULL,
                        `quantity` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `taxableValue` REAL NOT NULL,
                        `gstRate` REAL NOT NULL,
                        `cgstAmount` REAL NOT NULL,
                        `sgstAmount` REAL NOT NULL,
                        `igstAmount` REAL NOT NULL,
                        `totalAmount` REAL NOT NULL,
                        `sync_status` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // 3. Create gst_purchase_records table (fixed to match GstPurchaseRecord entity)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `gst_purchase_records` (
                        `id` TEXT NOT NULL,
                        `vendorGstin` TEXT,
                        `vendorName` TEXT,
                        `invoiceNumber` TEXT NOT NULL,
                        `invoiceDate` INTEGER NOT NULL,
                        `totalInvoiceValue` REAL NOT NULL,
                        `taxableValue` REAL NOT NULL,
                        `gstRate` REAL NOT NULL,
                        `cgstAmount` REAL NOT NULL,
                        `sgstAmount` REAL NOT NULL,
                        `igstAmount` REAL NOT NULL,
                        `cessAmount` REAL NOT NULL,
                        `hsnCode` TEXT NOT NULL,
                        `itcEligibility` TEXT NOT NULL,
                        `expenseType` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `sync_status` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // 4. Update products table
                db.execSQL("ALTER TABLE `products` ADD COLUMN `hsnCode` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `defaultGstRate` REAL NOT NULL DEFAULT 0.0")

                // 5. Update store_info table
                db.execSQL("ALTER TABLE `store_info` ADD COLUMN `legalName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `store_info` ADD COLUMN `tradeName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `store_info` ADD COLUMN `gstScheme` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `store_info` ADD COLUMN `stateCode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `store_info` ADD COLUMN `registrationType` TEXT NOT NULL DEFAULT ''")

                // 6. Update bills table
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerType` TEXT NOT NULL DEFAULT 'B2C'")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerGstin` TEXT")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `placeOfSupply` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `supplyType` TEXT NOT NULL DEFAULT 'intrastate'")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `cgstAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `sgstAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `igstAmount` REAL NOT NULL DEFAULT 0.0")

                // 7. Update bill_items table
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `hsnCode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `gstRate` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `cgstAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `sgstAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `igstAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `bill_items` ADD COLUMN `taxableValue` REAL NOT NULL DEFAULT 0.0")
            }
        }

        /**
         * v11 → v12
         *
         * Extends `gst_profile` to mirror the backend `store_gst_profile`
         * schema introduced for the Profile screen + Billing-side tax
         * percentages. All new columns are NOT NULL with safe defaults
         * so existing rows are unaffected.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gst_profile` ADD COLUMN `shop_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `gst_profile` ADD COLUMN `address` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `gst_profile` ADD COLUMN `cgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_profile` ADD COLUMN `sgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_profile` ADD COLUMN `igst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL(
                    "ALTER TABLE `gst_profile` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0"
                )

                // Back-fill created_at for any pre-existing row so it is not 0.
                db.execSQL(
                    "UPDATE `gst_profile` SET `created_at` = `updated_at` WHERE `created_at` = 0"
                )

                // Billing settings — extend with explicit CGST/SGST/IGST columns.
                db.execSQL("ALTER TABLE `billing_settings` ADD COLUMN `cgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `billing_settings` ADD COLUMN `sgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `billing_settings` ADD COLUMN `igst_percentage` REAL NOT NULL DEFAULT 0.0")
            }
        }

        /**
         * v12 → v13
         *
         *   • products: add product-level CGST/SGST/IGST + name/HSN
         *     indices used by the autofill DAO queries.
         *   • New tables: purchase_table, purchase_items_table,
         *     purchase_return_table, scrap_table.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // products — product-level tax columns + autofill indices
                db.execSQL("ALTER TABLE `products` ADD COLUMN `cgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `sgst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `igst_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_name` ON `products`(`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_hsnCode` ON `products`(`hsnCode`)")

                // purchase_table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `purchase_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `invoiceNumber` TEXT NOT NULL,
                        `supplierGstin` TEXT,
                        `supplierName` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `taxableAmount` REAL NOT NULL,
                        `cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `invoiceValue` REAL NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `is_synced` INTEGER NOT NULL DEFAULT 0,
                        `server_id` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_table_invoiceNumber` ON `purchase_table`(`invoiceNumber`)")

                // purchase_items_table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `purchase_items_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `purchaseId` INTEGER NOT NULL,
                        `productId` INTEGER,
                        `productName` TEXT NOT NULL,
                        `variant` TEXT,
                        `hsnCode` TEXT,
                        `quantity` REAL NOT NULL,
                        `unit` TEXT,
                        `taxableAmount` REAL NOT NULL,
                        `invoiceValue` REAL NOT NULL,
                        `cost_price` REAL NOT NULL,
                        `purchase_cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `purchase_sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `purchase_igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `purchase_cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `purchase_sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `purchase_igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sales_cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sales_sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sales_igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `is_synced` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`purchaseId`) REFERENCES `purchase_table`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_items_table_purchaseId` ON `purchase_items_table`(`purchaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_items_table_hsnCode` ON `purchase_items_table`(`hsnCode`)")

                // purchase_return_table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `purchase_return_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `productId` INTEGER,
                        `productName` TEXT NOT NULL,
                        `hsnCode` TEXT,
                        `quantityReturned` REAL NOT NULL,
                        `taxableAmount` REAL NOT NULL,
                        `invoiceValue` REAL NOT NULL,
                        `cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `supplierGstin` TEXT,
                        `supplierName` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `is_synced` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_productId` ON `purchase_return_table`(`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_hsnCode` ON `purchase_return_table`(`hsnCode`)")

                // scrap_table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scrap_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `productId` INTEGER,
                        `productName` TEXT NOT NULL,
                        `hsnCode` TEXT,
                        `quantity` REAL NOT NULL,
                        `taxableAmount` REAL NOT NULL,
                        `invoiceValue` REAL NOT NULL,
                        `cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `reason` TEXT NOT NULL DEFAULT 'Scrap',
                        `created_at` INTEGER NOT NULL,
                        `is_synced` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrap_table_productId` ON `scrap_table`(`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrap_table_hsnCode` ON `scrap_table`(`hsnCode`)")
            }
        }

        /**
         * v13 → v14
         *
         * Hard-remove the legacy CGST/SGST/IGST percentage columns
         * from `gst_profile` and `billing_settings`. Tax is now
         * product-level only — see [Product.cgstPercentage] etc.
         *
         * SQLite (pre-3.35) doesn't support DROP COLUMN, so we use
         * the canonical "create new table, copy data, drop old,
         * rename" sequence inside a transaction.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ---- billing_settings ----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `billing_settings_new` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `defaultGst` REAL NOT NULL,
                        `printerLayout` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `billing_settings_new` (id, defaultGst, printerLayout)
                    SELECT id, defaultGst, printerLayout FROM `billing_settings`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `billing_settings`")
                db.execSQL("ALTER TABLE `billing_settings_new` RENAME TO `billing_settings`")

                // ---- gst_profile ----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_profile_new` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `shop_id` TEXT NOT NULL DEFAULT '',
                        `gstin` TEXT NOT NULL,
                        `legal_name` TEXT NOT NULL DEFAULT '',
                        `trade_name` TEXT NOT NULL DEFAULT '',
                        `gst_scheme` TEXT NOT NULL DEFAULT '',
                        `registration_type` TEXT NOT NULL DEFAULT '',
                        `state_code` TEXT NOT NULL DEFAULT '',
                        `address` TEXT NOT NULL DEFAULT '',
                        `sync_status` TEXT NOT NULL DEFAULT 'pending',
                        `device_id` TEXT NOT NULL DEFAULT '',
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `gst_profile_new` (
                        id, shop_id, gstin, legal_name, trade_name, gst_scheme,
                        registration_type, state_code, address, sync_status,
                        device_id, created_at, updated_at
                    )
                    SELECT
                        id, shop_id, gstin, legal_name, trade_name, gst_scheme,
                        registration_type, state_code, address, sync_status,
                        device_id, created_at, updated_at
                    FROM `gst_profile`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `gst_profile`")
                db.execSQL("ALTER TABLE `gst_profile_new` RENAME TO `gst_profile`")
            }
        }

        /**
         * v14 → v15
         *
         * `products` gains:
         *   • `is_purchased` — true when the row was created via a
         *     purchase invoice (PurchaseRepository). Used by the
         *     edit screens to lock down stock/inventory editing.
         *   • `shop_id`      — bound at insert time. Filters tiles
         *     to the currently authenticated shop.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `is_purchased` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `shop_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_shopId` ON `products`(`shop_id`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easy_billing_db"
                )
                    .addMigrations(
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}