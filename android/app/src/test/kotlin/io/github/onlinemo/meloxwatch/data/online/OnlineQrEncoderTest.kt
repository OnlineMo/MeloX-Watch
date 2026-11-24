package io.github.onlinemo.meloxwatch.data.online

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.onlinemo.meloxwatch.data.netease.LoginQrKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineQrEncoderTest {
    @Test fun theQrCanBeDecodedBackToTheExactLocallyEscapedLoginContent() {
        val content = LoginQrKey.parse("fixture&key=a%b").content()
        val grid = OnlineQrEncoder.encode(content)
        assertEquals(content, decode(grid))
        assertFalse(grid.toString().contains("fixture"))
    }

    @Test fun theLargestAcceptedEscapedKeyStillHasABoundedQrAndAQuietZone() {
        val content = LoginQrKey.parse("&".repeat(512)).content()
        val grid = OnlineQrEncoder.encode(content)
        assertTrue(grid.edge <= 185)
        assertEquals(content, decode(grid))
        for (edge in 0 until grid.edge) {
            assertFalse(grid[edge, 0])
            assertFalse(grid[0, edge])
            assertFalse(grid[edge, grid.edge - 1])
            assertFalse(grid[grid.edge - 1, edge])
        }
    }

    @Test fun emptyOrUnboundedContentIsRejectedBeforeEncoding() {
        assertThrows(IllegalArgumentException::class.java) { OnlineQrEncoder.encode("") }
        assertThrows(IllegalArgumentException::class.java) { OnlineQrEncoder.encode("a".repeat(2049)) }
    }

    private fun decode(grid: OnlineQrGrid): String {
        val scale = 4
        val edge = grid.edge * scale
        val pixels = IntArray(edge * edge) { index -> if (grid[(index % edge) / scale, (index / edge) / scale]) -0x1000000 else -1 }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(edge, edge, pixels)))).text
    }
}
