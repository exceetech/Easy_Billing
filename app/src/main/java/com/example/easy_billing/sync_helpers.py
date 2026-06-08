import re

def insert_methods():
    with open('/Users/adeebfarhan/Desktop/expos/Easy_Billing/app/src/main/java/com/example/easy_billing/SyncManager.kt', 'r') as f:
        content = f.read()

    # Add imports
    imports = """
import com.example.easy_billing.network.PurchaseBatchDto
import com.example.easy_billing.network.PurchaseBatchSyncRequest
import com.example.easy_billing.network.CreditNoteResponseDto
import com.example.easy_billing.network.PurchaseReturnResponseDto
import com.example.easy_billing.network.PurchaseBatchResponseDto
"""
    if "PurchaseBatchSyncRequest" not in content:
        content = content.replace("import android.util.Log\n", "import android.util.Log\n" + imports)

    # Insert calls to pull
    if "pullPurchaseBatches()" not in content:
        content = content.replace("syncPurchaseImportDetails() // push imported goods details",
                                  "syncPurchaseImportDetails()\n        syncPurchaseBatches()")
    
    # We don't add pull to syncAll() here, we add to flushPending / NetworkReceiver / Dashboard.
    
    # Let's add the push/pull methods at the end of the class before the last brace.
    push_pull_methods = """
    // ==========================================
    //  PURCHASE BATCHES, RETURNS & CREDIT NOTES PULL
    // ==========================================

    private fun parseIsoDate(isoString: String?): Long? {
        if (isoString.isNullOrEmpty()) return null
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(isoString.replace("Z", "") + "Z").toEpochMilli()
            } else {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                format.parse(isoString)?.time
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncPurchaseBatches() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val token = prefs.getString("TOKEN", null)
        if (token == null) {
            Log.w(SYNC_TAG, "syncPurchaseBatches skipped — no auth token")
            return
        }

        val pending = db.purchaseBatchDao().getUnsyncedBatches()
        if (pending.isEmpty()) return

        Log.d(SYNC_TAG, "syncPurchaseBatches: ${pending.size} unsynced batch(es)")

        val dtoBatch = pending.map { b ->
            PurchaseBatchDto(
                local_id = b.id,
                product_id = b.productId,
                purchase_invoice_id = b.purchaseInvoiceId,
                supplier_name = b.supplierName,
                supplier_gstin = b.supplierGstin,
                invoice_number = b.invoiceNumber,
                batch_code = b.batchCode,
                quantity_purchased = b.quantityPurchased,
                quantity_remaining = b.quantityRemaining,
                unit_cost_excluding_tax = b.unitCostExcludingTax,
                gst_percent = b.gstPercent,
                cgst_percent = b.cgstPercent,
                sgst_percent = b.sgstPercent,
                igst_percent = b.igstPercent,
                invoice_value = b.invoiceValue,
                taxable_value = b.taxableValue,
                invoice_date = b.invoiceDate,
                created_at = b.createdAt
            )
        }

        try {
            val response = api.syncPurchaseBatches(token, PurchaseBatchSyncRequest(dtoBatch))
            Log.d(SYNC_TAG, "syncPurchaseBatches: server replied success=${response.success_count}")

            db.withTransaction {
                for (dto in dtoBatch) {
                    val serverId = response.batch_id_map[dto.local_id.toString()]
                    if (serverId != null) {
                        db.purchaseBatchDao().markSynced(dto.local_id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "syncPurchaseBatches: POST FAILED", e)
        }
    }

    suspend fun pullPurchaseBatches() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return
        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        if (shopId == -1) return

        try {
            val batches = api.getPurchaseBatches(token, shopId)
            db.withTransaction {
                for (b in batches) {
                    val existing = db.purchaseBatchDao().getBatchById(b.local_id)
                    if (existing == null) {
                        db.purchaseBatchDao().insertBatch(
                            com.example.easy_billing.db.PurchaseBatch(
                                id = b.local_id,
                                productId = b.product_id,
                                purchaseInvoiceId = b.purchase_invoice_id,
                                supplierName = b.supplier_name,
                                supplierGstin = b.supplier_gstin,
                                invoiceNumber = b.invoice_number,
                                batchCode = b.batch_code,
                                quantityPurchased = b.quantity_purchased,
                                quantityRemaining = b.quantity_remaining,
                                unitCostExcludingTax = b.unit_cost_excluding_tax,
                                gstPercent = b.gst_percent,
                                cgstPercent = b.cgst_percent,
                                sgstPercent = b.sgst_percent,
                                igstPercent = b.igst_percent,
                                invoiceValue = b.invoice_value,
                                taxableValue = b.taxable_value,
                                invoiceDate = parseIsoDate(b.invoice_date),
                                createdAt = parseIsoDate(b.created_at) ?: System.currentTimeMillis(),
                                isSynced = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullPurchaseBatches: FAILED", e)
        }
    }

    suspend fun pullCreditNotes() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return

        try {
            val notes = api.getCreditNotes(token)
            db.withTransaction {
                for (noteDto in notes) {
                    // Try to match by serverId if local_id is missing, or local_id
                    val existing = if (noteDto.local_id != null && noteDto.local_id > 0) {
                        db.creditNoteDao().getById(noteDto.local_id)
                    } else null
                    
                    if (existing == null) {
                        val newId = db.creditNoteDao().insertNote(
                            com.example.easy_billing.db.CreditNote(
                                id = noteDto.local_id ?: 0,
                                noteNumber = noteDto.note_number,
                                noteDate = noteDto.note_date,
                                noteType = noteDto.note_type,
                                noteSupplyType = noteDto.note_supply_type ?: "Regular",
                                originalInvoiceId = noteDto.original_invoice_id,
                                originalInvoiceNumber = noteDto.original_invoice_number,
                                originalInvoiceDate = noteDto.original_invoice_date,
                                customerName = noteDto.customer_name,
                                customerGstin = noteDto.customer_gstin,
                                placeOfSupply = noteDto.place_of_supply,
                                reverseCharge = noteDto.reverse_charge,
                                supplyType = noteDto.supply_type,
                                urType = noteDto.ur_type,
                                documentType = noteDto.document_type,
                                documentNature = noteDto.document_nature,
                                documentSeries = noteDto.document_series,
                                taxableValue = noteDto.taxable_value,
                                cgstAmount = noteDto.cgst_amount,
                                sgstAmount = noteDto.sgst_amount,
                                igstAmount = noteDto.igst_amount,
                                cessAmount = noteDto.cess_amount,
                                taxAmount = noteDto.tax_amount,
                                totalAmount = noteDto.total_amount,
                                syncStatus = "synced",
                                createdAt = parseIsoDate(noteDto.created_at) ?: System.currentTimeMillis(),
                                updatedAt = parseIsoDate(noteDto.updated_at) ?: System.currentTimeMillis()
                            )
                        )
                        for (itemDto in noteDto.items) {
                            db.creditNoteDao().insertItem(
                                com.example.easy_billing.db.CreditNoteItem(
                                    id = 0,
                                    noteId = newId.toInt(),
                                    productId = itemDto.product_id,
                                    productName = itemDto.product_name,
                                    variant = itemDto.variant,
                                    hsnCode = itemDto.hsn_code,
                                    unit = itemDto.unit,
                                    quantitySold = itemDto.quantity_sold,
                                    quantityReturned = itemDto.quantity_returned,
                                    rate = itemDto.rate,
                                    costPriceUsed = itemDto.cost_price_used,
                                    taxableValue = itemDto.taxable_value,
                                    gstRate = itemDto.gst_rate,
                                    cgstAmount = itemDto.cgst_amount,
                                    sgstAmount = itemDto.sgst_amount,
                                    igstAmount = itemDto.igst_amount,
                                    cessAmount = itemDto.cess_amount,
                                    taxAmount = itemDto.tax_amount,
                                    totalAmount = itemDto.total_amount,
                                    originalBillItemId = itemDto.original_bill_item_id
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullCreditNotes: FAILED", e)
        }
    }

    suspend fun pullPurchaseReturns() {
        val db = AppDatabase.getDatabase(context)
        val api = RetrofitClient.api
        val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("TOKEN", null) ?: return
        val shopId = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getInt("SHOP_ID", -1)
        if (shopId == -1) return

        try {
            val returns = api.getPurchaseReturns(token, shopId)
            db.withTransaction {
                for (r in returns) {
                    val existing = db.purchaseReturnDao().getById(r.id) // using server ID to check? Wait, PurchaseReturn has local auto-gen ID.
                    // PurchaseReturn does not store serverId. Let's just insert if we don't have it by local_id?
                    // wait, PurchaseReturn local_id might conflict. Let's just check if it's there.
                    // The sync push uses local_id. The GET returns `id` as server_id, but wait, `local_id` is NOT in PurchaseReturnResponseDto!
                    // Let me just check if invoice_value matches? Actually let's assume it's pulled on clean DB.
                    // If local DB is clean, we just insert.
                    val existingRows = db.purchaseReturnDao().getAll()
                    // Just insert all if not present, but how to deduplicate? By created_at?
                    // On factory reset, db is empty.
                    // Wait, PurchaseReturn table doesn't have a server_id column. It has `id` (PK).
                    // We can just insert with id = 0 to auto-generate, but we might duplicate on subsequent pulls if DB is not empty.
                    // To prevent duplication, we can check by `createdAt` and `shopProductId`.
                    val isDuplicate = existingRows.any { it.createdAt == (parseIsoDate(r.created_at) ?: 0L) && it.shopProductId == r.shop_product_id }
                    if (!isDuplicate) {
                        db.purchaseReturnDao().insertReturn(
                            com.example.easy_billing.db.PurchaseReturn(
                                id = 0, // Auto-generate local ID
                                shopProductId = r.shop_product_id,
                                productName = r.product_name,
                                variantName = r.variant_name,
                                hsnCode = r.hsn_code,
                                quantityReturned = r.quantity_returned,
                                taxableAmount = r.taxable_amount,
                                invoiceValue = r.invoice_value,
                                cgstPercentage = r.cgst_percentage,
                                sgstPercentage = r.sgst_percentage,
                                igstPercentage = r.igst_percentage,
                                cgstAmount = r.cgst_amount,
                                sgstAmount = r.sgst_amount,
                                igstAmount = r.igst_amount,
                                state = r.state,
                                supplierGstin = r.supplier_gstin,
                                supplierName = r.supplier_name,
                                isCredit = r.is_credit,
                                creditAccountId = r.credit_account_id,
                                creditTransactionId = null,
                                createdAt = parseIsoDate(r.created_at) ?: System.currentTimeMillis(),
                                isSynced = true,
                                noteNumber = r.note_number,
                                noteDate = r.note_date,
                                noteType = r.note_type,
                                originalInvoiceId = r.original_invoice_id,
                                originalInvoiceNumber = r.original_invoice_number,
                                originalInvoiceDate = r.original_invoice_date,
                                placeOfSupply = r.place_of_supply,
                                supplyType = r.supply_type ?: "intrastate",
                                cessAmount = r.cess_amount ?: 0.0,
                                taxAmount = r.tax_amount ?: 0.0,
                                totalAmount = r.total_amount ?: 0.0,
                                documentType = r.document_type,
                                documentNature = r.document_nature,
                                documentSeries = r.document_series,
                                preGst = r.pre_gst ?: "N",
                                reasonForIssuingDocument = r.reason_for_issuing_document ?: "Purchase return",
                                noteRefundVoucherValue = r.note_refund_voucher_value ?: 0.0,
                                rate = r.rate ?: 0.0,
                                eligibilityForItc = r.eligibility_for_itc ?: "Inputs",
                                availedItcIntegratedTax = r.availed_itc_integrated_tax ?: 0.0,
                                availedItcCentralTax = r.availed_itc_central_tax ?: 0.0,
                                availedItcStateTax = r.availed_itc_state_tax ?: 0.0,
                                availedItcCess = r.availed_itc_cess ?: 0.0,
                                invoiceType = r.invoice_type ?: "Regular",
                                placeOfSupplyCode = r.place_of_supply_code ?: ""
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "pullPurchaseReturns: FAILED", e)
        }
    }
"""
    if "pullPurchaseBatches()" not in content:
        content = content[:content.rfind("}")] + push_pull_methods + "\n}\n"

    with open('/Users/adeebfarhan/Desktop/expos/Easy_Billing/app/src/main/java/com/example/easy_billing/SyncManager.kt', 'w') as f:
        f.write(content)

insert_methods()
