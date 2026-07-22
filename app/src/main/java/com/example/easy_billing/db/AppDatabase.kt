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
import com.example.easy_billing.gstr1.Gstr1DraftEntity
import com.example.easy_billing.gstr2.Gstr2DraftEntity

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
        // GstPurchaseRecord retired (v43) — table dropped via MIGRATION_42_43.

        // GST-aware billing (v18) — see [GstSalesInvoice]
        GstSalesInvoice::class,
        GstSalesInvoiceItem::class,

        // Purchase / inventory ops (v13)
        Purchase::class,
        PurchaseItem::class,
        PurchaseReturn::class,
        ScrapEntry::class,

        // Hybrid inventory architecture (v21) — purchase batches
        // back the FIFO ledger so supplier returns can be valued at
        // their original batch cost while sales / scrap stay on the
        // weighted average. See [PurchaseBatch].
        PurchaseBatch::class,

        // Return management (v25) — Sales Return (Credit Note) entities.
        // Purchase Return (Debit Note) extends the existing PurchaseReturn
        // entity with new columns — see MIGRATION_24_25.
        CreditNote::class,
        CreditNoteItem::class,

        // GSTR-1 drafts (v30)
        Gstr1DraftEntity::class,
        Gstr2DraftEntity::class,

        PurchaseImportDetails::class,
        ImportService::class,

        // Categories + Customer master (v40)
        ProductCategory::class,
        Customer::class,

        // Supplier autofill index (v47) — a convenience lookup only;
        // purchase_table keeps its own denormalised supplier fields.
        Supplier::class
    ],
    version = 52
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
    // gstPurchaseRecordDao retired (v43) — table dropped via MIGRATION_42_43.

    // Purchase / inventory ops (v13)
    abstract fun purchaseDao(): PurchaseDao
    abstract fun purchaseItemDao(): PurchaseItemDao
    abstract fun purchaseReturnDao(): PurchaseReturnDao
    abstract fun purchaseImportDetailsDao(): PurchaseImportDetailsDao
    abstract fun scrapDao(): ScrapDao

    // GST-aware billing (v18)
    abstract fun gstSalesInvoiceDao(): GstSalesInvoiceDao
    abstract fun gstSalesInvoiceItemDao(): GstSalesInvoiceItemDao

    // Hybrid inventory architecture (v21) — purchase batches.
    abstract fun purchaseBatchDao(): PurchaseBatchDao

    // Return management (v25) — Credit Notes (Sales Returns).
    abstract fun creditNoteDao(): CreditNoteDao
    abstract fun creditNoteItemDao(): CreditNoteItemDao

    // GSTR-1 drafts (v30)
    abstract fun gstr1DraftDao(): com.example.easy_billing.gstr1.Gstr1DraftDao
    abstract fun gstr2DraftDao(): com.example.easy_billing.gstr2.Gstr2DraftDao

    // Import Services (v38)
    abstract fun importServiceDao(): ImportServiceDao

    // Categories + Customer master (v40)
    abstract fun productCategoryDao(): ProductCategoryDao
    abstract fun customerDao(): CustomerDao

    abstract fun supplierDao(): SupplierDao

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

        /**
         * v15 → v16
         *
         * Aligns purchase_return_table + scrap_table with the
         * backend `purchase_return` / `scrap` SQLAlchemy schema:
         * adds `shop_id` and `state` columns + a shop_id index on
         * each so per-shop reads stay fast.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `shop_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `state` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_shop_id` ON `purchase_return_table`(`shop_id`)")

                db.execSQL("ALTER TABLE `scrap_table` ADD COLUMN `shop_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `scrap_table` ADD COLUMN `state` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrap_table_shop_id` ON `scrap_table`(`shop_id`)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `variantName` TEXT")
                db.execSQL("ALTER TABLE `scrap_table` ADD COLUMN `variantName` TEXT")
            }
        }

        /**
         * v17 → v18
         *
         * Introduces the GST-aware billing structure:
         *   • `gst_sales_invoice_table` — invoice header with B2B/B2C
         *     metadata, scheme, and pre-rounded GST totals.
         *   • `gst_sales_items_table`   — per-line breakdown linked to
         *     the parent invoice via `gst_invoice_id`.
         *
         * Both tables sit alongside the legacy `bills` / `bill_items`
         * pair instead of replacing them — bill-history, reports and
         * inventory deduction continue to work unchanged.
         */

        /**
         * v18 → v19
         *
         * Adds `invoice_date` to `purchase_table` so purchase invoices
         * remember the date the supplier printed on the bill — distinct
         * from `created_at` (when the user keyed it in). Stored as
         * epoch millis. Nullable so the column back-fills cleanly for
         * pre-existing rows.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `invoice_date` INTEGER")
            }
        }

        /**
         * v19 → v20
         *
         *   • purchase_table: add is_credit, credit_account_id, credit_transaction_id.
         *   • purchase_return_table: add is_credit, credit_account_id, credit_transaction_id.
         *   • credit_transactions: add referenceInvoice.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // purchase_table
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `is_credit` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `credit_account_id` INTEGER")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `credit_transaction_id` INTEGER")

                // purchase_return_table
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `is_credit` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `credit_account_id` INTEGER")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `credit_transaction_id` INTEGER")

                // credit_transactions
                db.execSQL("ALTER TABLE `credit_transactions` ADD COLUMN `referenceInvoice` TEXT")
            }
        }

        /**
         * v20 → v21
         *
         * Introduces the `purchase_batches` table that underpins the
         * hybrid valuation model:
         *   • weighted average for valuation / sales / dashboards
         *   • batch-cost for supplier returns
         *   • FIFO internal consumption
         *
         * One synthetic batch is back-filled per existing inventory
         * row so SUM(quantityRemaining) immediately equals the
         * current stock — without this, products that existed before
         * the hybrid rollout would be invisible to the batch ledger.
         * The synthetic row carries the current average cost and a
         * created_at of 0 so any FIFO walk drains it first.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `purchase_batches` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `productId` INTEGER NOT NULL,
                        `purchaseInvoiceId` INTEGER,
                        `supplierName` TEXT,
                        `supplierGstin` TEXT,
                        `invoiceNumber` TEXT,
                        `batchCode` TEXT,
                        `quantityPurchased` REAL NOT NULL,
                        `quantityRemaining` REAL NOT NULL,
                        `unit_cost_excluding_tax` REAL NOT NULL,
                        `gst_percent` REAL NOT NULL DEFAULT 0.0,
                        `cgst_percent` REAL NOT NULL DEFAULT 0.0,
                        `sgst_percent` REAL NOT NULL DEFAULT 0.0,
                        `igst_percent` REAL NOT NULL DEFAULT 0.0,
                        `invoiceValue` REAL NOT NULL DEFAULT 0.0,
                        `taxableValue` REAL NOT NULL DEFAULT 0.0,
                        `created_at` INTEGER NOT NULL,
                        `is_synced` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchase_batches_productId` " +
                        "ON `purchase_batches`(`productId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchase_batches_purchaseInvoiceId` " +
                        "ON `purchase_batches`(`purchaseInvoiceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchase_batches_is_synced` " +
                        "ON `purchase_batches`(`is_synced`)"
                )

                // Back-fill synthetic migration batches so legacy
                // inventory rows are immediately visible to the FIFO
                // walker. createdAt = 0 → these get consumed first.
                db.execSQL(
                    """
                    INSERT INTO `purchase_batches` (
                        productId, purchaseInvoiceId, supplierName, supplierGstin,
                        invoiceNumber, batchCode,
                        quantityPurchased, quantityRemaining,
                        unit_cost_excluding_tax,
                        gst_percent, cgst_percent, sgst_percent, igst_percent,
                        invoiceValue, taxableValue,
                        created_at, is_synced
                    )
                    SELECT
                        productId, NULL, NULL, NULL,
                        NULL, 'MIGRATION',
                        currentStock, currentStock,
                        averageCost,
                        0.0, 0.0, 0.0, 0.0,
                        currentStock * averageCost, currentStock * averageCost,
                        0, 1
                    FROM `inventory`
                    WHERE currentStock > 0
                    """.trimIndent()
                )
            }
        }

        /**
         * v22 → v23
         *
         * Adds GSTR-1 support fields across four tables and a new
         * cancellation flag on bills.
         *
         *  gst_sales_invoice_table  – invoice_number, invoice_date,
         *    reverse_charge, gstr_invoice_type, customer_state_code,
         *    ecommerce_gstin, ecommerce_operator_name,
         *    is_cancelled, cancelled_at
         *
         *  gst_sales_items_table    – cess_rate, cess_amount, uqc,
         *    hsn_description
         *
         *  gst_sales_records        – customer_name, business_name,
         *    customer_phone, customer_state, customer_state_code,
         *    reverse_charge, gstr_invoice_type, ecommerce_gstin,
         *    ecommerce_operator_name, cess_rate, cess_amount, uqc,
         *    hsn_description, is_cancelled
         *
         *  products                 – official_uqc, hsn_description,
         *    cess_rate
         *
         *  bills                    – is_cancelled, cancelled_at
         *
         * All adds use safe defaults — existing rows are untouched.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── gst_sales_invoice_table ───────────────────────────
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `invoice_number` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `invoice_date` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `reverse_charge` TEXT NOT NULL DEFAULT 'N'")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `gstr_invoice_type` TEXT NOT NULL DEFAULT 'Regular'")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `customer_state_code` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `ecommerce_gstin` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `ecommerce_operator_name` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `is_cancelled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `cancelled_at` INTEGER")

                // ── gst_sales_items_table ─────────────────────────────
                db.execSQL("ALTER TABLE `gst_sales_items_table` ADD COLUMN `cess_rate` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_sales_items_table` ADD COLUMN `cess_amount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_sales_items_table` ADD COLUMN `uqc` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_items_table` ADD COLUMN `hsn_description` TEXT")

                // ── gst_sales_records ─────────────────────────────────
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `customerName` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `businessName` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `customerPhone` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `customerState` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `customerStateCode` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `reverseCharge` TEXT NOT NULL DEFAULT 'N'")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `gstrInvoiceType` TEXT NOT NULL DEFAULT 'Regular'")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `ecommerceGstin` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `ecommerceOperatorName` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `cessRate` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `cessAmount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `uqc` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `hsnDescription` TEXT")
                db.execSQL("ALTER TABLE `gst_sales_records` ADD COLUMN `is_cancelled` INTEGER NOT NULL DEFAULT 0")

                // ── products ──────────────────────────────────────────
                db.execSQL("ALTER TABLE `products` ADD COLUMN `official_uqc` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `hsn_description` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `cess_rate` REAL NOT NULL DEFAULT 0.0")

                // ── bills ─────────────────────────────────────────────
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `is_cancelled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `bills` ADD COLUMN `cancelled_at` INTEGER")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_sales_invoice_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `bill_id` INTEGER NOT NULL,
                        `shop_id` TEXT NOT NULL DEFAULT '',
                        `invoice_type` TEXT NOT NULL DEFAULT 'B2C',
                        `gst_scheme` TEXT NOT NULL DEFAULT '',
                        `customer_name` TEXT,
                        `business_name` TEXT,
                        `customer_phone` TEXT,
                        `customer_gst` TEXT,
                        `customer_state` TEXT,
                        `subtotal` REAL NOT NULL DEFAULT 0.0,
                        `total_cgst` REAL NOT NULL DEFAULT 0.0,
                        `total_sgst` REAL NOT NULL DEFAULT 0.0,
                        `total_igst` REAL NOT NULL DEFAULT 0.0,
                        `total_tax` REAL NOT NULL DEFAULT 0.0,
                        `grand_total` REAL NOT NULL DEFAULT 0.0,
                        `created_at` INTEGER NOT NULL,
                        `sync_status` TEXT NOT NULL DEFAULT 'pending',
                        `server_id` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_sales_invoice_table_bill_id` ON `gst_sales_invoice_table`(`bill_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_sales_invoice_table_shop_id` ON `gst_sales_invoice_table`(`shop_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_sales_invoice_table_sync_status` ON `gst_sales_invoice_table`(`sync_status`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_sales_items_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `gst_invoice_id` INTEGER NOT NULL,
                        `product_id` INTEGER NOT NULL,
                        `product_name` TEXT NOT NULL,
                        `variant_name` TEXT,
                        `hsn_code` TEXT NOT NULL DEFAULT '',
                        `quantity` REAL NOT NULL,
                        `selling_price` REAL NOT NULL,
                        `taxable_amount` REAL NOT NULL,
                        `sales_cgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sales_sgst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `sales_igst_percentage` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `net_value` REAL NOT NULL DEFAULT 0.0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_sales_items_table_gst_invoice_id` ON `gst_sales_items_table`(`gst_invoice_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_sales_items_table_product_id` ON `gst_sales_items_table`(`product_id`)")
            }
        }

//        fun getDatabase(context: Context): AppDatabase {
//            return INSTANCE ?: synchronized(this) {
//                INSTANCE ?: Room.databaseBuilder(
//                    context.applicationContext,
//                    AppDatabase::class.java,
//                    "easy_billing_db"
//                )
//                    .addMigrations(
//                        MIGRATION_10_11,
//                        MIGRATION_11_12,
//                        MIGRATION_12_13,
//                        MIGRATION_13_14,
//                        MIGRATION_14_15,
//                        MIGRATION_15_16,
//                        MIGRATION_16_17,
//                        MIGRATION_17_18,
//                        MIGRATION_18_19,
//                        MIGRATION_19_20
//                    )
//                    .fallbackToDestructiveMigration()
//                    .build()
//                    .also { INSTANCE = it }
//            }
//        }

        /**
         * v24 → v25  — Return Management System
         *
         *  1. `credit_notes`       — Sales Return (Credit Note) header.
         *  2. `credit_note_items`  — Sales Return line items.
         *  3. `purchase_return_table` — Extended with Debit Note fields:
         *       note_number, note_date, note_type, original_invoice_id,
         *       original_invoice_number, original_invoice_date,
         *       place_of_supply, supply_type, cess_amount.
         *
         * All new columns use safe defaults so existing rows are unaffected.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── 1. credit_notes ──────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `credit_notes` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `noteNumber` TEXT NOT NULL,
                        `noteDate` INTEGER NOT NULL,
                        `noteType` TEXT NOT NULL DEFAULT 'C',
                        `originalInvoiceId` INTEGER NOT NULL,
                        `originalInvoiceNumber` TEXT NOT NULL,
                        `originalInvoiceDate` INTEGER NOT NULL,
                        `customerName` TEXT NOT NULL DEFAULT '',
                        `customerGstin` TEXT,
                        `placeOfSupply` TEXT NOT NULL DEFAULT '',
                        `reverseCharge` TEXT NOT NULL DEFAULT 'N',
                        `supplyType` TEXT NOT NULL DEFAULT 'intrastate',
                        `urType` TEXT NOT NULL DEFAULT 'B2CS',
                        `taxableValue` REAL NOT NULL DEFAULT 0.0,
                        `taxAmount` REAL NOT NULL DEFAULT 0.0,
                        `cessAmount` REAL NOT NULL DEFAULT 0.0,
                        `totalAmount` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `syncStatus` TEXT NOT NULL DEFAULT 'pending',
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_notes_noteNumber` ON `credit_notes`(`noteNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_notes_originalInvoiceId` ON `credit_notes`(`originalInvoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_notes_syncStatus` ON `credit_notes`(`syncStatus`)")

                // ── 2. credit_note_items ──────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `credit_note_items` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `noteId` INTEGER NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `productName` TEXT NOT NULL,
                        `variant` TEXT,
                        `hsnCode` TEXT NOT NULL DEFAULT '',
                        `unit` TEXT NOT NULL DEFAULT '',
                        `quantitySold` REAL NOT NULL,
                        `quantityReturned` REAL NOT NULL,
                        `rate` REAL NOT NULL,
                        `costPriceUsed` REAL NOT NULL DEFAULT 0.0,
                        `taxableValue` REAL NOT NULL,
                        `gst_rate` REAL NOT NULL DEFAULT 0.0,
                        `cgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `sgst_amount` REAL NOT NULL DEFAULT 0.0,
                        `igst_amount` REAL NOT NULL DEFAULT 0.0,
                        `cessAmount` REAL NOT NULL DEFAULT 0.0,
                        `taxAmount` REAL NOT NULL DEFAULT 0.0,
                        `totalAmount` REAL NOT NULL DEFAULT 0.0,
                        `originalBillItemId` INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_note_items_noteId` ON `credit_note_items`(`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_note_items_productId` ON `credit_note_items`(`productId`)")

                // ── 3. Extend purchase_return_table with Debit Note fields ──
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `note_number` TEXT")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `note_date` INTEGER")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `note_type` TEXT")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `original_invoice_id` INTEGER")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `original_invoice_number` TEXT")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `original_invoice_date` INTEGER")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `place_of_supply` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `supply_type` TEXT NOT NULL DEFAULT 'intrastate'")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `cess_amount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_note_number` ON `purchase_return_table`(`note_number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_original_invoice_id` ON `purchase_return_table`(`original_invoice_id`)")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Delete duplicate products, keeping the most recent one (max ID)
                db.execSQL("""
                    DELETE FROM products 
                    WHERE id NOT IN (
                        SELECT MAX(id) 
                        FROM products 
                        GROUP BY shop_id, name, IFNULL(variant, '')
                    )
                """.trimIndent())
                
                // 2. Create the new unique index
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_shop_id_name_variant` ON `products`(`shop_id`, `name`, `variant`)")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tables = listOf("gst_sales_invoice_table", "gst_sales_records")
                for (table in tables) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_nature_of_supply` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_document_type` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_supplier_gstin` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_supplier_name` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_recipient_gstin` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_recipient_name` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `eco_role` TEXT")
                }
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add noteSupplyType to credit_notes
                db.execSQL("ALTER TABLE `credit_notes` ADD COLUMN `noteSupplyType` TEXT NOT NULL DEFAULT 'Regular'")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add DOCS fields to gst_sales_invoice_table
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `document_type` TEXT NOT NULL DEFAULT 'Invoice'")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `document_nature` TEXT NOT NULL DEFAULT 'Invoices for outward supply'")
                db.execSQL("ALTER TABLE `gst_sales_invoice_table` ADD COLUMN `document_series` TEXT NOT NULL DEFAULT 'INV'")

                // 2. Add EXEMP field to gst_sales_items_table
                db.execSQL("ALTER TABLE `gst_sales_items_table` ADD COLUMN `supply_classification` TEXT NOT NULL DEFAULT 'TAXABLE'")

                // 3. Add EXEMP field to products
                db.execSQL("ALTER TABLE `products` ADD COLUMN `supply_classification` TEXT NOT NULL DEFAULT 'TAXABLE'")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `credit_notes` ADD COLUMN `document_type` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `credit_notes` ADD COLUMN `document_nature` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `credit_notes` ADD COLUMN `document_series` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v29 → v30  — GSTR-1 Drafts
         *
         * Adds `gstr1_drafts` table for persisting generated GSTR-1
         * reports between sessions. The report is stored as a JSON blob
         * so no schema changes are needed when sheet models evolve.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `gstr1_drafts` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `gstin` TEXT NOT NULL,
                        `financial_year` TEXT NOT NULL,
                        `period` TEXT NOT NULL,
                        `return_type` TEXT NOT NULL,
                        `report_json` TEXT NOT NULL,
                        `generated_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `place_of_supply_code` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `reverse_charge` TEXT NOT NULL DEFAULT 'N'")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `invoice_type` TEXT NOT NULL DEFAULT 'Regular'")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `supply_type` TEXT NOT NULL DEFAULT 'intrastate'")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `cess_paid` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `eligibility_for_itc` TEXT NOT NULL DEFAULT 'Inputs'")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `availed_itc_integrated_tax` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `availed_itc_central_tax` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `availed_itc_state_tax` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `availed_itc_cess` REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `cess_percentage` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `cess_amount` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `eligibility_for_itc` TEXT NOT NULL DEFAULT 'Inputs'")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `availed_itc_igst` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `availed_itc_cgst` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `availed_itc_sgst` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `availed_itc_cess` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `hsn_description` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `official_uqc` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `document_type` TEXT")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `document_nature` TEXT")
                db.execSQL("ALTER TABLE `purchase_return_table` ADD COLUMN `document_series` TEXT")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create temporary table with version 34 schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `purchase_return_table_temp` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `shop_id` TEXT NOT NULL,
                        `productId` INTEGER,
                        `productName` TEXT NOT NULL,
                        `variantName` TEXT,
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
                        `state` TEXT NOT NULL,
                        `supplierGstin` TEXT,
                        `supplierName` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `is_synced` INTEGER NOT NULL DEFAULT 0,
                        `is_credit` INTEGER NOT NULL DEFAULT 0,
                        `credit_account_id` INTEGER,
                        `credit_transaction_id` INTEGER,
                        `note_number` TEXT,
                        `note_date` INTEGER,
                        `note_type` TEXT,
                        `original_invoice_id` INTEGER,
                        `original_invoice_number` TEXT,
                        `original_invoice_date` INTEGER,
                        `place_of_supply` TEXT NOT NULL DEFAULT '',
                        `supply_type` TEXT NOT NULL DEFAULT 'intrastate',
                        `cess_amount` REAL NOT NULL DEFAULT 0.0,
                        `document_type` TEXT NOT NULL DEFAULT 'Debit Note',
                        `document_nature` TEXT,
                        `document_series` TEXT,
                        `pre_gst` TEXT NOT NULL DEFAULT 'N',
                        `reason_for_issuing_document` TEXT NOT NULL DEFAULT 'Purchase return',
                        `note_refund_voucher_value` REAL NOT NULL DEFAULT 0.0,
                        `rate` REAL NOT NULL DEFAULT 0.0,
                        `eligibility_for_itc` TEXT NOT NULL DEFAULT 'Inputs',
                        `availed_itc_integrated_tax` REAL NOT NULL DEFAULT 0.0,
                        `availed_itc_central_tax` REAL NOT NULL DEFAULT 0.0,
                        `availed_itc_state_tax` REAL NOT NULL DEFAULT 0.0,
                        `availed_itc_cess` REAL NOT NULL DEFAULT 0.0,
                        `invoice_type` TEXT NOT NULL DEFAULT 'Regular',
                        `place_of_supply_code` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                // 2. Copy existing data into the temp table
                db.execSQL("""
                    INSERT INTO `purchase_return_table_temp` (
                        id, shop_id, productId, productName, variantName, hsnCode,
                        quantityReturned, taxableAmount, invoiceValue,
                        cgst_percentage, sgst_percentage, igst_percentage,
                        cgst_amount, sgst_amount, igst_amount, state,
                        supplierGstin, supplierName, created_at, is_synced,
                        is_credit, credit_account_id, credit_transaction_id,
                        note_number, note_date, note_type, original_invoice_id,
                        original_invoice_number, original_invoice_date,
                        place_of_supply, supply_type, cess_amount,
                        document_type, document_nature, document_series,
                        pre_gst, reason_for_issuing_document, note_refund_voucher_value,
                        rate, eligibility_for_itc, availed_itc_integrated_tax,
                        availed_itc_central_tax, availed_itc_state_tax, availed_itc_cess,
                        invoice_type, place_of_supply_code
                    )
                    SELECT
                        id, shop_id, productId, productName, variantName, hsnCode,
                        quantityReturned, taxableAmount, invoiceValue,
                        cgst_percentage, sgst_percentage, igst_percentage,
                        cgst_amount, sgst_amount, igst_amount, state,
                        supplierGstin, supplierName, created_at, is_synced,
                        is_credit, credit_account_id, credit_transaction_id,
                        note_number, note_date, note_type, original_invoice_id,
                        original_invoice_number, original_invoice_date,
                        place_of_supply, supply_type, cess_amount,
                        COALESCE(document_type, 'Debit Note'), document_nature, document_series,
                        'N', 'Purchase return', 0.0, 0.0, 'Inputs', 0.0, 0.0, 0.0, 0.0, 'Regular', ''
                    FROM `purchase_return_table`
                """.trimIndent())

                // 3. Drop the old table
                db.execSQL("DROP TABLE IF EXISTS `purchase_return_table`")

                // 4. Rename temp table to standard table name
                db.execSQL("ALTER TABLE `purchase_return_table_temp` RENAME TO `purchase_return_table`")

                // 5. Recreate indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_productId` ON `purchase_return_table`(`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_hsnCode` ON `purchase_return_table`(`hsnCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_shop_id` ON `purchase_return_table`(`shop_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_note_number` ON `purchase_return_table`(`note_number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_return_table_original_invoice_id` ON `purchase_return_table`(`original_invoice_id`)")
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add purchase_source to purchase_table
                db.execSQL("ALTER TABLE `purchase_table` ADD COLUMN `purchase_source` TEXT NOT NULL DEFAULT 'DOMESTIC'")

                // Create purchase_import_details table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `purchase_import_details` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `purchase_id` INTEGER NOT NULL,
                        `shop_id` TEXT NOT NULL DEFAULT '',
                        `local_purchase_id` INTEGER NOT NULL,
                        `port_code` TEXT NOT NULL,
                        `bill_of_entry_number` TEXT NOT NULL,
                        `bill_of_entry_date` INTEGER NOT NULL,
                        `bill_of_entry_value` REAL NOT NULL DEFAULT 0.0,
                        `document_type` TEXT NOT NULL DEFAULT 'Bill of Entry',
                        `sez_supplier_gstin` TEXT,
                        `sync_status` TEXT NOT NULL DEFAULT 'pending',
                        `device_id` TEXT NOT NULL DEFAULT '',
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_import_details_purchase_id` ON `purchase_import_details`(`purchase_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_import_details_local_purchase_id` ON `purchase_import_details`(`local_purchase_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_import_details_sync_status` ON `purchase_import_details`(`sync_status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_import_details_bill_of_entry_number` ON `purchase_import_details`(`bill_of_entry_number`)")
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `supply_classification` TEXT NOT NULL DEFAULT 'TAXABLE'")
                } catch (e: Exception) {
                    // Ignore if already added
                }
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `purchase_items_table` ADD COLUMN `supply_classification` TEXT NOT NULL DEFAULT 'TAXABLE'")
                } catch (e: Exception) {
                    // Ignore if already added
                }
            }
        }

        
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gstr2_drafts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `gstin` TEXT NOT NULL,
                        `financial_year` TEXT NOT NULL,
                        `period` TEXT NOT NULL,
                        `return_type` TEXT NOT NULL,
                        `report_json` TEXT NOT NULL,
                        `generated_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `import_services` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `invoice_number` TEXT NOT NULL,
                        `invoice_date` INTEGER NOT NULL,
                        `invoice_value` REAL NOT NULL,
                        `place_of_supply` TEXT NOT NULL,
                        `rate` REAL NOT NULL,
                        `taxable_value` REAL NOT NULL,
                        `igst_paid` REAL NOT NULL,
                        `cess_paid` REAL NOT NULL,
                        `eligibility_for_itc` TEXT NOT NULL,
                        `availed_itc_igst` REAL NOT NULL,
                        `availed_itc_cess` REAL NOT NULL,
                        `sync_status` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v39 → v40 — Product categories + Customer master.
         * Purely additive: one new column on `products` and two new
         * tables. Safe for existing installs (no drops/renames).
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `product_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `serverId` INTEGER,
                        `shop_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isCustom` INTEGER NOT NULL DEFAULT 1,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_product_categories_shop_id_name`
                    ON `product_categories` (`shop_id`, `name`)
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `customers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `serverId` INTEGER,
                        `shop_id` INTEGER NOT NULL,
                        `phone` TEXT NOT NULL,
                        `name` TEXT NOT NULL DEFAULT '',
                        `customer_type` TEXT NOT NULL DEFAULT 'B2C',
                        `business_name` TEXT,
                        `gstin` TEXT,
                        `state` TEXT,
                        `state_code` TEXT,
                        `credit_account_id` INTEGER,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_customers_shop_id_phone`
                    ON `customers` (`shop_id`, `phone`)
                """.trimIndent())
            }
        }

        /**
         * v40 → v41 — Allow a customer to have separate B2C and B2B
         * records under the same phone. The uniqueness key moves from
         * (shop_id, phone) to (shop_id, phone, customer_type).
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_customers_shop_id_phone`")
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_customers_shop_id_phone_customer_type`
                    ON `customers` (`shop_id`, `phone`, `customer_type`)
                """.trimIndent())
            }
        }

        // N3 (v42): tracks whether a bill's void has been acknowledged by
        // the server, so syncBillCancellations stops re-pushing it every
        // cycle. Existing cancelled bills default to 0 → pushed once more,
        // then marked.
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bills ADD COLUMN cancel_synced INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v43: retire the unused gst_purchase_records table. It was never read
        // on the device; GST purchase data lives in purchase_table/items.
        // DROP (not recreate) so the schema matches the entity list — without
        // this, fallbackToDestructiveMigration would wipe the whole DB.
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS gst_purchase_records")
            }
        }

        // v44: Add isTaxInclusive flag to products
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN isTaxInclusive INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchase_items_table ADD COLUMN discount_amount REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bill_items ADD COLUMN discountAmount REAL NOT NULL DEFAULT 0.0")
            }
        }

        /**
         * v47 — supplier autofill index.
         *
         * Purely additive: no existing table is touched, and purchases keep
         * their own copy of the supplier details, so nothing about already
         * recorded invoices changes.
         *
         * `(shopId, gstin)` is unique: SQLite compares NULLs as distinct,
         * so this pins one row per registered supplier while leaving any
         * number of unregistered (NULL-GSTIN) rows alone. Names are only
         * indexed for lookup speed — two registered suppliers may legally
         * share one.
         */
        /**
         * v48 — track whether a product's hide/restore has reached the
         * server. Existing rows are assumed already in sync, so the default
         * is 1: nothing is re-pushed on upgrade.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE products ADD COLUMN activeStateSynced " +
                    "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Reclassifies historical `SETTLE` rows into `WRITE_OFF` / `REFUND`.
         *
         * `SETTLE` meant two opposite events — a debt forgiven and money handed
         * back — so every reader had to reconstruct which one by replaying the
         * ledger. Worse, settles written before the amount was recorded stored
         * 0, so the sum involved was lost from the row entirely.
         *
         * This walks each account's history and, for every settle, works out
         * the balance standing at that moment:
         *
         *   • balance positive → the debt was forgiven  → WRITE_OFF
         *   • balance negative → the advance was returned → REFUND
         *
         * The amount is rewritten to that balance, repairing the rows that
         * stored 0. Amounts become magnitudes; the type carries the direction.
         *
         * No schema change — `type` is already a free-text column — so this is
         * purely a data rewrite. Verified against real SQLite for: a single
         * write-off, a refund, two settles on one account (the second must
         * measure only from the first), and a settle that already held the
         * correct amount.
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // Balance standing immediately before settle row `r`: the sum
                // of everything on that account since the previous settle.
                // The NOT EXISTS clause is what stops an earlier settle's
                // history leaking into a later one's figure.
                //
                // It matches all three type names because rows already
                // converted by this same statement must still act as
                // boundaries.
                val balanceBefore = """
                    (SELECT COALESCE(SUM(CASE
                        WHEN t.type IN ('ADD','PURCHASE_CREDIT','PURCHASE_RETURN') THEN t.amount
                        WHEN t.type = 'PAY' THEN -t.amount
                        ELSE 0 END), 0)
                     FROM credit_transactions t
                     WHERE t.accountId = r.accountId AND t.shopId = r.shopId
                       AND (t.timestamp < r.timestamp
                            OR (t.timestamp = r.timestamp AND t.id < r.id))
                       AND NOT EXISTS (
                            SELECT 1 FROM credit_transactions s
                            WHERE s.accountId = r.accountId AND s.shopId = r.shopId
                              AND s.type IN ('SETTLE','WRITE_OFF','REFUND')
                              AND (s.timestamp > t.timestamp
                                   OR (s.timestamp = t.timestamp AND s.id > t.id))
                              AND (s.timestamp < r.timestamp
                                   OR (s.timestamp = r.timestamp AND s.id < r.id))))
                """.trimIndent()

                // Only rows already pushed to the server are re-typed.
                //
                // An unsent SETTLE must stay a SETTLE: it will be pushed as
                // written, and if the app reaches a device before the backend
                // is updated, a WRITE_OFF would be rejected as an unknown type
                // — which now holds back every later transaction for that
                // customer. SETTLE is understood by every backend version.
                db.execSQL("""
                    UPDATE credit_transactions AS r
                       SET type   = CASE WHEN $balanceBefore < 0 THEN 'REFUND' ELSE 'WRITE_OFF' END,
                           amount = ABS($balanceBefore)
                     WHERE r.type = 'SETTLE' AND r.isSynced = 1
                """.trimIndent())
            }
        }

        /**
         * Gives `supplier_table` a real modification time.
         *
         * Before this, sync sent `lastUsedAt` as the row's `updated_at`, so
         * the server's newest-wins rule was comparing *recency of use*, not
         * recency of edit — a supplier renamed on one device and then left
         * alone lost to a device that merely bought from them again, and the
         * rename was silently discarded.
         *
         * Existing rows seed `updatedAt` from `lastUsedAt`: it is the only
         * timestamp on file, and it is exactly what the server already holds
         * for them, so nothing changes rank on the first sync after upgrade.
         * Seeding "now" instead would make every local row outrank genuine
         * edits waiting on other devices.
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `supplier_table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE `supplier_table` SET `updatedAt` = `lastUsedAt`")
            }
        }

        /**
         * Links a bill to the credit account it was charged to, and each
         * credit transaction to the bill (and the exact document) it came
         * from. Everything is additive and nullable, so existing rows keep
         * working untouched — old credit bills simply have a null account
         * link and are treated as "not linked" by the adjustment flow.
         *
         * All columns are added with defaults so no data has to move.
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // bills → which credit account it was charged to (nullable).
                db.execSQL(
                    "ALTER TABLE `bills` ADD COLUMN `credit_account_id` INTEGER DEFAULT NULL"
                )
                // credit_transactions → originating bill + document.
                db.execSQL(
                    "ALTER TABLE `credit_transactions` ADD COLUMN `billId` INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `credit_transactions` ADD COLUMN `sourceDoc` TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Purchase-side of the bill-adjustment feature.
         *
         *  - credit_transactions.purchaseId → ties a supplier transaction to
         *    the purchase it came from (mirror of billId).
         *  - purchase_table gets cancellation columns so a credit purchase can
         *    be voided the way a bill can.
         *
         * All additive and nullable/defaulted, so existing rows are untouched.
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `credit_transactions` ADD COLUMN `purchaseId` INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `purchase_table` ADD COLUMN `is_cancelled` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `purchase_table` ADD COLUMN `cancelled_at` INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `purchase_table` ADD COLUMN `cancel_synced` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `supplier_table` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `serverId` INTEGER,
                        `gstin` TEXT,
                        `name` TEXT NOT NULL,
                        `nameKey` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `shopId` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_supplier_table_shopId_gstin` " +
                    "ON `supplier_table` (`shopId`, `gstin`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supplier_table_shopId_nameKey` " +
                    "ON `supplier_table` (`shopId`, `nameKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supplier_table_lastUsedAt` " +
                    "ON `supplier_table` (`lastUsedAt`)"
                )
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
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        // 21→22: no migration defined — fallback handles it
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38, MIGRATION_38_39,
                        MIGRATION_39_40, MIGRATION_40_41,
                        MIGRATION_41_42, MIGRATION_42_43,
                        MIGRATION_43_44, MIGRATION_44_45,
                        MIGRATION_45_46, MIGRATION_46_47,
                        MIGRATION_47_48, MIGRATION_48_49,
                        MIGRATION_49_50, MIGRATION_50_51,
                        MIGRATION_51_52
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