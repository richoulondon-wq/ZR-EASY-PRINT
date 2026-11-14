package com.example.epsonusbprint

import android.graphics.Bitmap

/**
 * طبقة بروتوكول الطباعة المباشرة.
 *
 * الطابعة Epson Stylus SX130 تستعمل لغة Epson خاصة (host-based / ESC/P-R حسب الموديل)
 * وليست مستندة إلى نص عادي أو PostScript، لذلك لا يمكن "إرسال PDF" مباشرة عبر USB.
 * يجب تحويل كل صفحة إلى Raster بصيغة الطابعة (ألوان CMYK، ضغط، أوامر تغذية الورق...).
 *
 * هذا الملف هو المكان الوحيد الذي يلزم إكماله للطباعة المباشرة:
 * implement encode() ليُرجع البايتات الكاملة للمهمة (تهيئة + صفحات + إخراج الورق).
 * استعمل زر "فحص" في التطبيق لقراءة حقل CMD: من Device ID ومعرفة اللغة المدعومة فعلياً.
 */
interface RasterJobEncoder {
    fun encode(pages: List<Bitmap>): ByteArray
}

class UnimplementedEncoder : RasterJobEncoder {
    override fun encode(pages: List<Bitmap>): ByteArray =
        throw UnsupportedOperationException(
            "مرمّز بروتوكول Epson لم يُنفَّذ بعد. استعمل «الطباعة عبر نظام أندرويد» حالياً."
        )
}
