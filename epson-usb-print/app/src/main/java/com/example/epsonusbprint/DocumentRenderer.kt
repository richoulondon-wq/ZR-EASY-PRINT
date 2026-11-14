package com.example.epsonusbprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint

/** يحوّل PDF / صورة / نص إلى صفحات A4 (150 dpi) جاهزة للمعاينة والطباعة. */
object DocumentRenderer {

    const val PAGE_W = 1240   // A4 @150dpi
    const val PAGE_H = 1754
    private const val MARGIN = 100
    private const val MAX_PAGES = 30

    private fun blankPage(): Bitmap =
        Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }

    fun load(ctx: Context, uri: Uri): List<Bitmap> {
        val type = ctx.contentResolver.getType(uri) ?: ""
        return if (type == "application/pdf") pdfPages(ctx, uri) else listOf(imagePage(ctx, uri))
    }

    private fun pdfPages(ctx: Context, uri: Uri): List<Bitmap> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                return (0 until count).map { i ->
                    renderer.openPage(i).use { page ->
                        val h = (PAGE_W.toFloat() * page.height / page.width).toInt()
                        val bmp = Bitmap.createBitmap(PAGE_W, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bmp
                    }
                }
            }
        }
    }

    private fun imagePage(ctx: Context, uri: Uri): Bitmap {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("تعذّر قراءة الصورة")
        val page = blankPage()
        val availW = (PAGE_W - 2 * MARGIN / 2).toFloat()
        val availH = (PAGE_H - 2 * MARGIN / 2).toFloat()
        val scale = minOf(availW / src.width, availH / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (PAGE_W - w) / 2
        val top = (PAGE_H - h) / 2
        Canvas(page).drawBitmap(src, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return page
    }

    fun textToPages(text: String): List<Bitmap> {
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = 40f
            isAntiAlias = true
        }
        val width = PAGE_W - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
        val usableH = PAGE_H - 2 * MARGIN
        val pages = mutableListOf<Bitmap>()
        var line = 0
        while (line < layout.lineCount && pages.size < MAX_PAGES) {
            val startTop = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startTop <= usableH) end++
            if (end == line) end = line + 1
            val bmp = blankPage()
            val c = Canvas(bmp)
            c.save()
            c.translate(MARGIN.toFloat(), MARGIN - startTop.toFloat())
            c.clipRect(0f, startTop.toFloat(), width.toFloat(), layout.getLineBottom(end - 1).toFloat())
            layout.draw(c)
            c.restore()
            pages.add(bmp)
            line = end
        }
        if (pages.isEmpty()) pages.add(blankPage())
        return pages
    }
}
