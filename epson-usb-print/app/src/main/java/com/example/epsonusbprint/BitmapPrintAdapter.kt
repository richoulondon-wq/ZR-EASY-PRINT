package com.example.epsonusbprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream

/** يمرّر الصفحات إلى نظام الطباعة في أندرويد (PrintManager) عبر خدمة طباعة مثبّتة. */
class BitmapPrintAdapter(
    private val ctx: Context,
    private val pages: List<Bitmap>,
    private val name: String
) : PrintDocumentAdapter() {

    private var attrs: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        attrs = newAttributes
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        ranges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val pdf = PrintedPdfDocument(ctx, attrs ?: PrintAttributes.Builder().build())
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            pages.forEachIndexed { i, bmp ->
                val page = pdf.startPage(i)
                val c = page.canvas
                val scale = minOf(c.width.toFloat() / bmp.width, c.height.toFloat() / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val l = (c.width - w) / 2
                val t = (c.height - h) / 2
                c.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), paint)
                pdf.finishPage(page)
            }
            FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            pdf.close()
        }
    }
}
