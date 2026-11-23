package com.kingscan.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.codes.createBarcodeCodec
import com.kingscan.app.imaging.RgbaImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineToolsIntegrationTest {
    @Test fun qrSurvivesJpegRotationAndDetectsMultipleWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(File(context.filesDir, "kingscan").absolutePath)
        val directory = File(context.filesDir, "tools-verification").apply { mkdirs() }
        val codec = createBarcodeCodec()
        val payload = "离线二维码 KingOfScan 2026\nhttps://example.org/doc?id=123&name=合同"
        val generated = codec.generate(payload)
        val jpeg = AppGraph.codec.encodeJpeg(generated, 100)
        File(directory, "qr-generated.jpg").writeBytes(jpeg)
        val decoded = codec.decode(AppGraph.codec.decode(jpeg, 2400).rotate90CW())
        assertEquals(payload, decoded.single().text)
        assertTrue(codec.decode(RgbaImage.filled(600, 600, -1)).isEmpty())
        val other = codec.generate("第二个本地码 456")
        val multi = RgbaImage.filled(generated.width + other.width + 80, maxOf(generated.height, other.height) + 40, -1)
        for ((image, offset) in listOf(generated to 0, other to (generated.width + 80))) {
            for (y in 0 until image.height) image.pixels.copyInto(multi.pixels, (y + 20) * multi.width + offset, y * image.width, (y + 1) * image.width)
        }
        assertEquals(setOf(payload, "第二个本地码 456"), codec.decode(multi).map { it.text }.toSet())
        File(directory, "qr-multiple.jpg").writeBytes(AppGraph.codec.encodeJpeg(multi, 100))
        File(directory, "result.txt").writeText("PASS Unicode QR JPEG round trip\nPASS rotated QR decoding\nPASS blank image rejected\nPASS multiple QR detection\n")
    }
}
