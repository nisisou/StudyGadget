package com.example.studycompanion.update

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeUtilsTest {
    @Test fun generatedMatrixDecodesToOriginalUrl() {
        val url=QrCodeUtils.ciaDownloadUrl("192.168.1.24",8765,"token+with/symbols")
        val matrix=QrCodeUtils.encodeQrCodeMatrix(url)
        val pixels=IntArray(matrix.width*matrix.height){index->
            if(matrix.get(index%matrix.width,index/matrix.width))0xff000000.toInt() else -1
        }
        val source=RGBLuminanceSource(matrix.width,matrix.height,pixels)
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text

        assertEquals(url,decoded)
        assertTrue(url.contains("t=token%2Bwith%2Fsymbols"))
    }

    @Test(expected=IllegalArgumentException::class)
    fun invalidIpCannotProduceInstallQrUrl() {
        QrCodeUtils.ciaDownloadUrl("0.0.0.999",8765,"token")
    }
}
