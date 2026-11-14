package com.example.epsonusbprint

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * الاتصال المباشر بالطابعة عبر USB Host (كابل OTG).
 * يكشف طابعة Epson، يطلب الصلاحية، يقرأ IEEE-1284 Device ID وحالة المنفذ، ويرسل بيانات خام.
 */
class UsbPrinter(private val ctx: Context) {

    companion object {
        const val EPSON_VID = 0x04B8
        const val ACTION_PERMISSION = "com.example.epsonusbprint.USB_PERMISSION"
    }

    data class Status(
        val deviceId: String?,
        val paperOut: Boolean?,
        val online: Boolean?,
        val error: Boolean?
    )

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findPrinter(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == EPSON_VID && printerInterface(it) != null }

    fun printerInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return f
        }
        return null
    }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    fun requestPermission(d: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    /** يقرأ Device ID (يحتوي عادة على CMD: لغات الطباعة المدعومة) وبايت حالة المنفذ. */
    fun query(d: UsbDevice): Status? {
        val intf = printerInterface(d) ?: return null
        val conn = usb.openDevice(d) ?: return null
        try {
            if (!conn.claimInterface(intf, true)) return null
            val index = (intf.id shl 8) or intf.alternateSetting

            val buf = ByteArray(1024)
            val n = conn.controlTransfer(0xA1, 0, 0, index, buf, buf.size, 3000)
            val id = if (n > 2) String(buf, 2, n - 2, Charsets.ISO_8859_1).trim('\u0000', ' ') else null

            val sb = ByteArray(1)
            val s = conn.controlTransfer(0xA1, 1, 0, index, sb, 1, 3000)
            return if (s == 1) {
                val b = sb[0].toInt() and 0xFF
                Status(id, (b and 0x20) != 0, (b and 0x10) != 0, (b and 0x08) == 0)
            } else {
                Status(id, null, null, null)
            }
        } finally {
            conn.releaseInterface(intf)
            conn.close()
        }
    }

    /** يرسل بايتات خام إلى نقطة Bulk OUT الخاصة بالطابعة. */
    fun send(d: UsbDevice, data: ByteArray) {
        val intf = printerInterface(d) ?: throw IOException("لا توجد واجهة طابعة")
        var out: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT) out = e
        }
        val endpoint = out ?: throw IOException("لا توجد نقطة إرسال Bulk OUT")
        val conn = usb.openDevice(d) ?: throw IOException("تعذّر فتح الجهاز (الصلاحية؟)")
        try {
            if (!conn.claimInterface(intf, true)) throw IOException("تعذّر حجز الواجهة")
            var off = 0
            while (off < data.size) {
                val len = minOf(16 * 1024, data.size - off)
                val r = conn.bulkTransfer(endpoint, data, off, len, 10_000)
                if (r < 0) throw IOException("فشل الإرسال عند البايت $off")
                off += r
            }
        } finally {
            conn.releaseInterface(intf)
            conn.close()
        }
    }
}
