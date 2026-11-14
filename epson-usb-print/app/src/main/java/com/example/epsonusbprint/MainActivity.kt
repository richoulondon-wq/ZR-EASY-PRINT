package com.example.epsonusbprint

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var printer: UsbPrinter
    private var device: UsbDevice? = null
    private var pages: List<Bitmap> = emptyList()
    private var index = 0
    private val encoder: RasterJobEncoder = UnimplementedEncoder()

    private lateinit var statusView: TextView
    private lateinit var preview: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var textInput: EditText

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshPrinter(false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printer = UsbPrinter(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        statusView = TextView(this).apply { textSize = 15f; text = "..." }
        textInput = EditText(this).apply {
            hint = "اكتب أو الصق نصاً للطباعة"
            minLines = 3
            gravity = Gravity.TOP
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        pageLabel = TextView(this).apply { gravity = Gravity.CENTER }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("◀") { go(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pageLabel, LinearLayout.LayoutParams(0, -2, 2f))
            addView(button("▶") { go(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        root.addView(statusView)
        root.addView(button("كشف الطابعة / فحص") { refreshPrinter(true) })
        root.addView(button("اختيار ملف (PDF أو صورة)") { pickFile() })
        root.addView(textInput)
        root.addView(button("معاينة النص") { previewText() })
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))
        root.addView(nav)
        root.addView(button("طباعة عبر نظام أندرويد") { printViaSystem() })
        root.addView(button("إرسال مباشر عبر USB (تجريبي)") { printDirect() })

        setContentView(ScrollView(this).apply { addView(root) })

        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(UsbPrinter.ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshPrinter(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshPrinter(true)
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun refreshPrinter(askPermission: Boolean) {
        Thread {
            val d = printer.findPrinter()
            device = d
            val msg: String = when {
                d == null ->
                    "لم يتم العثور على طابعة Epson.\nوصّل الطابعة بكابل USB (مع محوّل OTG) وشغّلها."
                !printer.hasPermission(d) -> {
                    if (askPermission) runOnUiThread { printer.requestPermission(d) }
                    "تم العثور على الطابعة (${d.productName ?: "Epson"}).\nاسمح للتطبيق بالوصول إلى USB."
                }
                else -> describe(printer.query(d))
            }
            runOnUiThread { statusView.text = msg }
        }.start()
    }

    private fun describe(s: UsbPrinter.Status?): String {
        if (s == null) return "الطابعة موصولة لكن تعذّرت قراءة حالتها."
        fun yn(v: Boolean?, yes: String, no: String) = when (v) { true -> yes; false -> no; null -> "غير معروف" }
        return buildString {
            appendLine("الطابعة موصولة ✔")
            appendLine("الاتصال: " + yn(s.online, "متصلة", "غير متصلة"))
            appendLine("الورق: " + yn(s.paperOut, "فارغ", "متوفر"))
            appendLine("خطأ: " + yn(s.error, "نعم", "لا"))
            append("Device ID: ").append(s.deviceId ?: "—")
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    val p = DocumentRenderer.load(this, uri)
                    runOnUiThread { setPages(p) }
                } catch (e: Exception) {
                    toast("تعذّر فتح الملف: ${e.message}")
                }
            }.start()
        }
    }

    private fun previewText() {
        val t = textInput.text.toString()
        if (t.isBlank()) return toast("اكتب نصاً أولاً")
        Thread {
            val p = DocumentRenderer.textToPages(t)
            runOnUiThread { setPages(p) }
        }.start()
    }

    private fun setPages(p: List<Bitmap>) {
        pages = p
        index = 0
        showPage()
    }

    private fun go(delta: Int) {
        if (pages.isEmpty()) return
        index = (index + delta).coerceIn(0, pages.size - 1)
        showPage()
    }

    private fun showPage() {
        if (pages.isEmpty()) {
            preview.setImageDrawable(null)
            pageLabel.text = ""
            return
        }
        preview.setImageBitmap(pages[index])
        pageLabel.text = "صفحة ${index + 1} / ${pages.size}"
    }

    private fun printViaSystem() {
        if (pages.isEmpty()) return toast("لا يوجد ما يُطبع")
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(
            "EpsonUsbPrint",
            BitmapPrintAdapter(this, pages, "EpsonUsbPrint"),
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        )
    }

    private fun printDirect() {
        val d = device ?: return toast("لا توجد طابعة")
        if (pages.isEmpty()) return toast("لا يوجد ما يُطبع")
        if (!printer.hasPermission(d)) return refreshPrinter(true)
        Thread {
            try {
                printer.send(d, encoder.encode(pages))
                toast("تم الإرسال")
            } catch (e: Exception) {
                toast(e.message ?: "فشل")
            }
        }.start()
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
