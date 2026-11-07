package app.barq.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

fun formatBytes(b: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatSpeed(bps: Double): String {
    if (bps < 1) return "0 MB/s"
    val mb = bps / (1024 * 1024)
    return if (mb >= 10) String.format(Locale.US, "%.0f MB/s", mb) else String.format(Locale.US, "%.1f MB/s", mb)
}

fun formatDuration(sec: Long): String {
    if (sec < 0) return "—"
    val m = sec / 60
    val s = sec % 60
    return if (m >= 60) String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun qrBitmap(text: String, sizePx: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) for (x in 0 until sizePx) px[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
}
