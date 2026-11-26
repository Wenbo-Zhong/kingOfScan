@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kingscan.app.core.document

import com.kingscan.app.AppGraph

import com.kingscan.app.core.imaging.toByteArray
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import kotlinx.cinterop.toKString
import platform.PDFKit.PDFDocument
import platform.UIKit.*
import kotlin.test.*

class IosOfflineDocumentIntegrationTest {
    // Kotlin's standalone test runner has no UIApplication host. Actual Vision runs in verification/ios.
    @Test fun chineseSearchablePasswordPdf() = runBlocking {
        val root = platform.posix.getenv("KINGSCAN_TEST_OUTPUT")?.toKString() ?: NSTemporaryDirectory() + "kingscan-offline-evidence"
        println("KMP_OFFLINE_IOS_EVIDENCE=$root")
        FileSystem.SYSTEM.createDirectories(root.toPath())
        AppGraph.init(root)
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(1200.0, 1600.0), true, 1.0)
        val input = try {
            UIColor.whiteColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, 1200.0, 1600.0))
            val attributes = mapOf<Any?, Any>(NSFontAttributeName to UIFont.systemFontOfSize(62.0),
                NSForegroundColorAttributeName to UIColor.blackColor)
            listOf("离线扫描测试", "KingOfScan OFFLINE", "2026 123456").forEachIndexed { index, text ->
                NSString.create(string = text).drawAtPoint(CGPointMake(80.0, 80.0 + index * 160), withAttributes = attributes)
            }
            UIImageJPEGRepresentation(UIGraphicsGetImageFromCurrentImageContext()!!, 0.98)!!.toByteArray()
        } finally { UIGraphicsEndImageContext() }
        FileSystem.SYSTEM.write("$root/input.jpg".toPath()) { write(input) }
        val image = AppGraph.codec.decode(input, 2400)
        FileSystem.SYSTEM.write("$root/roundtrip.jpg".toPath()) { write(AppGraph.codec.encodeJpeg(image, 98)) }
        val texts = listOf("离线扫描测试", "KingOfScan OFFLINE", "2026 123456")
        val ocr = OcrResult(texts.joinToString("\n"), texts.mapIndexed { index, text ->
            OcrLine(text, PointF(0.0667f, (80 + index * 160) / 1600f),
                PointF(0.8f, (155 + index * 160) / 1600f))
        })
        val document = AppGraph.repository.create("离线验证", listOf(EncodedPage(input, image.width, image.height,
            FilterType.Original, input, PageEdit(filter = FilterType.Original), ocr)))
        val path = AppGraph.repository.exportPdf(document.id, ExportOptions(paper = PdfPaper.A4,
            pageNumbers = true, password = "scan-2026", searchable = true))
        val pdf = PDFDocument(NSURL.fileURLWithPath(path))
        assertTrue(pdf.isLocked)
        assertFalse(pdf.unlockWithPassword("wrong"))
        assertTrue(pdf.unlockWithPassword("scan-2026"))
        assertEquals(1uL, pdf.pageCount)
        val text = pdf.string.orEmpty()
        FileSystem.SYSTEM.write("$root/pdf-text.txt".toPath()) { writeUtf8(text) }
        assertTrue(text.contains("离线扫描测试"), text)
        assertTrue(text.contains("123456"), text)
        FileSystem.SYSTEM.copy(path.toPath(), "$root/encrypted-searchable.pdf".toPath())
        val encryptedBytes = AppGraph.repository.readBytes(path)
        val printed = AppGraph.repository.exportPdf(document.id, ExportOptions(password = "", searchable = true))
        assertNotEquals(path, printed)
        assertContentEquals(encryptedBytes, AppGraph.repository.readBytes(path))
        assertFalse(PDFDocument(NSURL.fileURLWithPath(printed)).isLocked)
        AppGraph.repository.addPages(document.id, listOf(EncodedPage(input, image.width, image.height, FilterType.Original)))
        val revised = AppGraph.repository.exportPdf(document.id, ExportOptions(password = "different"))
        assertNotEquals(path, revised)
        assertContentEquals(encryptedBytes, AppGraph.repository.readBytes(path))
        val originalSnapshot = PDFDocument(NSURL.fileURLWithPath(path))
        assertTrue(originalSnapshot.isLocked)
        assertTrue(originalSnapshot.unlockWithPassword("scan-2026"))
        assertEquals(1uL, originalSnapshot.pageCount)
        println("KMP_OFFLINE_IOS_EVIDENCE=$root")
    }
}
