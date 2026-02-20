package com.example.easy_billing.util

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.measureTimeMillis

class PdfPrintAdapter(
    private val context: Context,
    private val path: String,
    private val shopId: String,        // 4-digit shop id
    private val invoiceNumber: Long     // incremental invoice no
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }

        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            .format(Date())

        val invoiceFormatted = String.format("%04d", invoiceNumber)

        val timeMillis = System.currentTimeMillis()

        val fileName =
            "${shopId}_${date}_INV_${invoiceFormatted}_${timeMillis}.pdf"

        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()

        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val inputFile = File(path)

        FileInputStream(inputFile).use { input ->
            FileOutputStream(destination.fileDescriptor).use { output ->
                input.copyTo(output)
            }
        }

        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }
}