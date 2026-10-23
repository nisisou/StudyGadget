package com.example.studycompanion.update

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object QrCodeUtils {
    fun ciaDownloadUrl(host: String, port: Int, token: String): String {
        val octets=host.split('.')
        require(octets.size==4 && octets.all{part->part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255}){"接続先IPアドレスが不正です"}
        require(port in 1..65535){"ポート番号が不正です"}
        require(token.isNotBlank()){"認証トークンがありません"}
        val encodedToken=URLEncoder.encode(token,StandardCharsets.UTF_8.name())
        return "http://$host:$port/api/update/3ds/app.cia?t=$encodedToken"
    }

    fun encodeQrCodeMatrix(
        content: String,
        width: Int = 512,
        height: Int = 512,
        margin: Int = 1
    ): BitMatrix {
        require(content.isNotBlank()){"QRコードの内容が空です"}
        require(width in 128..2048 && height in 128..2048){"QRコードのサイズが不正です"}
        require(margin in 0..8){"QRコードの余白が不正です"}
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
    }

    fun generateQrCodeBitmap(
        content: String,
        width: Int = 512,
        height: Int = 512,
        margin: Int = 1
    ): Bitmap {
        val bitMatrix=encodeQrCodeMatrix(content,width,height,margin)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun generateQrCodeImageBitmap(
        content: String,
        width: Int = 512,
        height: Int = 512,
        margin: Int = 1
    ): ImageBitmap {
        return generateQrCodeBitmap(content, width, height, margin).asImageBitmap()
    }
}
