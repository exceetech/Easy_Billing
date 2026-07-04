package com.example.easy_billing.repository

import com.example.easy_billing.util.appNow

import android.content.Context
import androidx.room.withTransaction
import com.example.easy_billing.InventoryManager
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.BillItem
import com.example.easy_billing.db.CreditNote
import com.example.easy_billing.db.CreditNoteItem

/**
 * Handles creation of Sales Return Credit Notes.
 *
 * Architecture contract:
 *   • NEVER bypass InventoryManager — all stock restorations go through
 *     [InventoryManager.addStock] with a dedicated SALES_RETURN batch so
 *     FIFO integrity and valuation accuracy are preserved.
 *   • NEVER create purchase invoices or fake purchase records.
 *   • Proportional tax calculations follow the rule:
 *       returnedTaxable = (returnedQty / soldQty) × originalTaxable
 *   • Note numbers are sequential (CN-00001 …) and generated atomically
 *     inside a transaction to prevent gaps or duplicates.
 *   • The repository is offline-first: all writes go to Room first;
 *     the SyncManager pushes them to the backend independently.
 */
class CreditNoteRepository private constructor(private val db: AppDatabase) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Data classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A single line the user wants to return.
     *
     * @param billItem     The original [BillItem] from the invoice.
     * @param returnQty    How many units are being returned (> 0, ≤ remainingQty).
     */
    data class ReturnLine(
        val billItem: BillItem,
        val returnQty: Double
    )

    /**
     * Outcome of [createCreditNote].
     */
    sealed class Result {
        data class Success(val creditNote: CreditNote) : Result()
        data class ValidationError(val message: String) : Result()
        data class SaveError(val cause: Throwable) : Result()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates the return lines, creates a [CreditNote] + [CreditNoteItem]s
     * inside a single Room transaction, then restores stock through
     * [InventoryManager.addStock] for each returned item.
     *
     * @param billId               Local `bills.id`.
     * @param billNumber           Invoice number shown on the original bill.
     * @param billDateMillis       Epoch millis of the original bill's date.
     * @param customerName         Customer name (may be empty for B2C).
     * @param customerGstin        Customer GSTIN (null for B2C).
     * @param placeOfSupply        State of supply copied from the bill.
     * @param reverseCharge        "Y" or "N".
     * @param supplyType           "intrastate" or "interstate".
     * @param urType               GST invoice type for CDN: "B2B", "B2CS", etc.
     * @param lines                The items and quantities to return.
     */
    suspend fun createCreditNote(
        billId: Int,
        billNumber: String,
        billDateMillis: Long,
        customerName: String,
        customerGstin: String?,
        placeOfSupply: String,
        reverseCharge: String,
        supplyType: String,
        urType: String,
        lines: List<ReturnLine>
    ): Result {

        // ── 1. Validate inputs ───────────────────────────────────────────────
        val originalBill = db.billDao().getBillById(billId)
        if (originalBill.isCancelled) {
            return Result.ValidationError("Cannot create a credit note for a cancelled invoice.")
        }
        if (lines.isEmpty()) return Result.ValidationError("No items selected for return.")

        for (line in lines) {
            if (line.returnQty <= 0.0) {
                return Result.ValidationError(
                    "Return quantity for '${line.billItem.productName}' must be greater than zero."
                )
            }
            // Guard against returning more than what remains after prior returns
            val alreadyReturned = db.creditNoteItemDao()
                .getTotalReturnedForBillProduct(billId, line.billItem.productId)
            val maxAllowed = line.billItem.quantity - alreadyReturned
            if (line.returnQty > maxAllowed) {
                return Result.ValidationError(
                    "'${line.billItem.productName}': maximum returnable qty is " +
                        "%.2f (sold %.2f, already returned %.2f).".format(
                            maxAllowed, line.billItem.quantity, alreadyReturned
                        )
                )
            }
        }

        return try {
            db.withTransaction {

                // ── 2. Generate note number ─────────────────────────────────
                val nextSeq = db.creditNoteDao().getMaxSequence() + 1
                val noteNumber = "CN-%05d".format(nextSeq)
                val now = appNow()

                // ── 3. Compute aggregate financials ─────────────────────────
                var totalTaxable = 0.0
                var totalCgst    = 0.0
                var totalSgst    = 0.0
                var totalIgst    = 0.0
                var totalCess    = 0.0

                val noteItems = lines.map { line ->
                    val bi       = line.billItem
                    val ratio    = line.returnQty / bi.quantity

                    val taxable  = bi.taxableValue  * ratio
                    val cgst     = bi.cgstAmount    * ratio
                    val sgst     = bi.sgstAmount    * ratio
                    val igst     = bi.igstAmount    * ratio
                    // CESS: bill_items doesn't store cess separately → 0 for now
                    val cess     = 0.0
                    val tax      = cgst + sgst + igst + cess
                    val total    = taxable + tax

                    totalTaxable += taxable
                    totalCgst    += cgst
                    totalSgst    += sgst
                    totalIgst    += igst
                    val unitCost = if (bi.quantity > 0.0) bi.costPriceUsed / bi.quantity else 0.0

                    CreditNoteItem(
                        noteId             = 0,          // back-filled after header insert
                        productId          = bi.productId,
                        productName        = bi.productName,
                        variant            = bi.variant,
                        hsnCode            = bi.hsnCode,
                        unit               = bi.unit,
                        quantitySold       = bi.quantity,
                        quantityReturned   = line.returnQty,
                        rate               = bi.price,
                        costPriceUsed      = round2(unitCost * line.returnQty),
                        taxableValue       = round2(taxable),
                        gstRate            = bi.gstRate,
                        cgstAmount         = round2(cgst),
                        sgstAmount         = round2(sgst),
                        igstAmount         = round2(igst),
                        cessAmount         = round2(cess),
                        taxAmount          = round2(tax),
                        totalAmount        = round2(total),
                        originalBillItemId = bi.id
                    )
                }

                val totalTax   = round2(totalCgst + totalSgst + totalIgst + totalCess)
                val grandTotal = round2(round2(totalTaxable) + totalTax)

                // Note: the original bill discount was applied PRE-TAX, so each
                // BillItem already stores the discounted taxable + reduced tax.
                // Returning those values therefore refunds the correct (already
                // discounted) amount and reverses the correct tax — no extra
                // discount handling is needed here.

                // ── 4. Insert CreditNote header ─────────────────────────────
                val header = CreditNote(
                    noteNumber            = noteNumber,
                    noteDate              = now,
                    noteType              = "C",
                    originalInvoiceId     = billId,
                    originalInvoiceNumber = billNumber,
                    originalInvoiceDate   = billDateMillis,
                    customerName          = customerName,
                    customerGstin         = customerGstin,
                    placeOfSupply         = placeOfSupply,
                    reverseCharge         = reverseCharge,
                    supplyType            = supplyType,
                    urType                = urType,
                    documentType          = "Credit Note",
                    documentNature        = "Credit Note",
                    documentSeries        = if (noteNumber.contains("-")) noteNumber.split("-").firstOrNull() ?: "CN" else "CN",
                    taxableValue          = round2(totalTaxable),
                    taxAmount             = totalTax,
                    cessAmount            = round2(totalCess),
                    totalAmount           = grandTotal,
                    cgstAmount            = round2(totalCgst),
                    sgstAmount            = round2(totalSgst),
                    igstAmount            = round2(totalIgst),
                    syncStatus            = "pending",
                    createdAt             = now,
                    updatedAt             = now
                )
                val noteId = db.creditNoteDao().insert(header).toInt()

                // ── 5. Insert line items ────────────────────────────────────
                val itemsWithNoteId = noteItems.map { it.copy(noteId = noteId) }
                db.creditNoteItemDao().insertAll(itemsWithNoteId)

                // ── 6. Restore inventory via InventoryManager ───────────────
                // Each returned product gets its own FIFO batch tagged
                // "SALES_RETURN-<noteId>" so valuation stays accurate and
                // this stock is never confused with real purchase lots.
                for (line in lines) {
                    val bi = line.billItem
                    val product = db.productDao().getById(bi.productId) ?: continue
                    if (!product.trackInventory) continue

                    val unitCost = if (bi.quantity > 0.0) bi.costPriceUsed / bi.quantity else 0.0

                    // Carry the original sale's GST split onto the restock batch so
                    // the synced purchase_batches row has the REAL rates (these were
                    // hardcoded to 0, so the backend showed 0% for sales-return lots).
                    // The split mirrors the note's supply type — same rule used above
                    // for the credit-note item amounts.
                    val interstate = supplyType.equals("interstate", ignoreCase = true)
                    val cgstPct = if (interstate) 0.0 else bi.gstRate / 2.0
                    val sgstPct = if (interstate) 0.0 else bi.gstRate / 2.0
                    val igstPct = if (interstate) bi.gstRate else 0.0

                    // Taxable = net cost × qty. Invoice value = taxable + GST (gross),
                    // so the batch's invoice_value matches real purchase batches and
                    // the reduce dialog (invoiceValue / qty) shows the GST-inclusive
                    // per-unit price for sales-return lots too.
                    val batchTaxable = round2(unitCost * line.returnQty)
                    val batchInvoice = round2(batchTaxable * (1.0 + bi.gstRate / 100.0))

                    InventoryManager.addStock(
                        db        = db,
                        productId = bi.productId,
                        quantity  = line.returnQty,
                        costPrice = unitCost,
                        batchMeta = InventoryManager.StockBatchMeta(
                            purchaseInvoiceId    = null,   // NOT a purchase
                            supplierName         = null,
                            supplierGstin        = null,
                            invoiceNumber        = null,
                            batchCode            = "SALES_RETURN-$noteId",
                            unitCostExcludingTax = unitCost,
                            gstPercent           = bi.gstRate,
                            cgstPercent          = cgstPct,
                            sgstPercent          = sgstPct,
                            igstPercent          = igstPct,
                            invoiceValue         = batchInvoice,
                            taxableValue         = batchTaxable
                        )
                    )
                }

                Result.Success(header.copy(id = noteId))
            }
        } catch (e: Exception) {
            Result.SaveError(e)
        }
    }

    /**
     * A single line for adding value via Debit Note.
     *
     * @param billItem          The original [BillItem] from the invoice.
     * @param additionalQty     Additional quantity being charged.
     */
    data class DebitLine(
        val billItem: BillItem,
        val additionalQty: Double
    )

    /**
     * Creates a Sales Debit Note ("D").
     * Does NOT restore or move inventory.
     * Quantity returned is explicitly 0.0.
     */
    suspend fun createDebitNote(
        billId: Int,
        billNumber: String,
        billDateMillis: Long,
        customerName: String,
        customerGstin: String?,
        placeOfSupply: String,
        reverseCharge: String,
        supplyType: String,
        noteSupplyType: String,
        urType: String,
        lines: List<DebitLine>
    ): Result {
        val originalBill = db.billDao().getBillById(billId)
        if (originalBill.isCancelled) {
            return Result.ValidationError("Cannot create a debit note for a cancelled invoice.")
        }
        if (lines.isEmpty()) return Result.ValidationError("No items selected for debit note.")

        for (line in lines) {
            if (line.additionalQty <= 0.0) {
                return Result.ValidationError(
                    "Additional quantity for '${line.billItem.productName}' must be greater than zero."
                )
            }
        }

        return try {
            db.withTransaction {
                val nextSeq = db.creditNoteDao().getMaxSequence() + 1
                val noteNumber = "DN-%05d".format(nextSeq)
                val now = appNow()

                var totalTaxable = 0.0
                var totalCgst    = 0.0
                var totalSgst    = 0.0
                var totalIgst    = 0.0
                var totalCess    = 0.0

                val noteItems = lines.map { line ->
                    val bi       = line.billItem
                    val unitTaxable = if (bi.quantity > 0.0) bi.taxableValue / bi.quantity else 0.0
                    val taxable  = line.additionalQty * unitTaxable
                    val taxRate  = bi.gstRate
                    
                    var cgst = 0.0
                    var sgst = 0.0
                    var igst = 0.0
                    
                    if (supplyType.equals("interstate", ignoreCase = true)) {
                        igst = taxable * (taxRate / 100.0)
                    } else {
                        cgst = taxable * ((taxRate / 2) / 100.0)
                        sgst = taxable * ((taxRate / 2) / 100.0)
                    }

                    val cess = 0.0
                    val tax = cgst + sgst + igst + cess
                    val total = taxable + tax

                    totalTaxable += taxable
                    totalCgst    += cgst
                    totalSgst    += sgst
                    totalIgst    += igst

                    val unitCost = if (bi.quantity > 0.0) bi.costPriceUsed / bi.quantity else 0.0

                    CreditNoteItem(
                        noteId             = 0,          
                        productId          = bi.productId,
                        productName        = bi.productName,
                        variant            = bi.variant,
                        hsnCode            = bi.hsnCode,
                        unit               = bi.unit,
                        quantitySold       = bi.quantity,
                        quantityReturned   = line.additionalQty, // Store debited qty here
                        rate               = bi.price,
                        costPriceUsed      = round2(unitCost * line.additionalQty),
                        taxableValue       = round2(taxable),
                        gstRate            = bi.gstRate,
                        cgstAmount         = round2(cgst),
                        sgstAmount         = round2(sgst),
                        igstAmount         = round2(igst),
                        cessAmount         = round2(cess),
                        taxAmount          = round2(tax),
                        totalAmount        = round2(total),
                        originalBillItemId = bi.id
                    )
                }

                val totalTax   = round2(totalCgst + totalSgst + totalIgst + totalCess)
                val grandTotal = round2(round2(totalTaxable) + totalTax)

                val header = CreditNote(
                    noteNumber            = noteNumber,
                    noteDate              = now,
                    noteType              = "D",
                    noteSupplyType        = noteSupplyType,
                    originalInvoiceId     = billId,
                    originalInvoiceNumber = billNumber,
                    originalInvoiceDate   = billDateMillis,
                    customerName          = customerName,
                    customerGstin         = customerGstin,
                    placeOfSupply         = placeOfSupply,
                    reverseCharge         = reverseCharge,
                    supplyType            = supplyType,
                    urType                = urType,
                    documentType          = "Debit Note",
                    documentNature        = "Debit Note",
                    documentSeries        = if (noteNumber.contains("-")) noteNumber.split("-").firstOrNull() ?: "DN" else "DN",
                    taxableValue          = round2(totalTaxable),
                    taxAmount             = totalTax,
                    cessAmount            = round2(totalCess),
                    totalAmount           = grandTotal,
                    cgstAmount            = round2(totalCgst),
                    sgstAmount            = round2(totalSgst),
                    igstAmount            = round2(totalIgst),
                    syncStatus            = "pending",
                    createdAt             = now,
                    updatedAt             = now
                )
                val noteId = db.creditNoteDao().insert(header).toInt()

                val itemsWithNoteId = noteItems.map { it.copy(noteId = noteId) }
                db.creditNoteItemDao().insertAll(itemsWithNoteId)

                // ── Deduct inventory for Debit Note (like a SALE) ───────────
                for (line in lines) {
                    val bi = line.billItem
                    val product = db.productDao().getById(bi.productId) ?: continue
                    if (!product.trackInventory) continue

                    InventoryManager.reduceStock(
                        db = db,
                        productId = bi.productId,
                        quantity = line.additionalQty,
                        type = "SALE"
                    )
                }

                Result.Success(header.copy(id = noteId))
            }
        } catch (e: Exception) {
            Result.SaveError(e)
        }
    }

    /**
     * Fetches all credit notes for a given original bill, newest first.
     */
    suspend fun getByBill(billId: Int): List<CreditNote> =
        db.creditNoteDao().getByOriginalInvoice(billId)

    /**
     * Fetches the line items for a given credit note.
     */
    suspend fun getItems(noteId: Int): List<CreditNoteItem> =
        db.creditNoteItemDao().getByNote(noteId)

    /**
     * How much of [productId] has already been returned on [billId].
     * Used to compute the maximum returnable quantity before showing the UI.
     */
    suspend fun alreadyReturnedQty(billId: Int, productId: Int): Double =
        db.creditNoteItemDao().getTotalReturnedForBillProduct(billId, productId)

    // ─────────────────────────────────────────────────────────────────────────

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    // ─────────────────────────────────────────────────────────────────────────
    //  Singleton
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: CreditNoteRepository? = null

        fun get(context: Context): CreditNoteRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CreditNoteRepository(
                    AppDatabase.getDatabase(context)
                ).also { INSTANCE = it }
            }
    }
}
