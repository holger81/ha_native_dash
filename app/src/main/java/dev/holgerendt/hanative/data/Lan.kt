package dev.holgerendt.hanative.data

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddresses {
    fun ipv4(): List<String> {
        val found = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    found += address.hostAddress.orEmpty()
                }
            }
        }
        return found.distinct()
    }
}

object QrCodes {
    fun bitmap(content: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix.get(x, y)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
