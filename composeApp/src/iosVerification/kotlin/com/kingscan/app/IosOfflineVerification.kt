@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kingscan.app

import com.kingscan.app.imaging.toByteArray
import com.kingscan.app.model.*
import com.kingscan.app.ocr.OcrLanguage
import com.kingscan.app.ocr.createOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.*

/** Included only by -PkingscanVerification=true, never by regular application builds. */
fun runIosOfflineVerification(root: String, completion: (String) -> Unit) {
    check(UIApplication.sharedApplication.windows.isNotEmpty()) { "Vision verification requires an application host" }
    FileSystem.SYSTEM.createDirectories(root.toPath())
    AppGraph.init(root)
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(1200.0, 1600.0), true, 1.0)
    val bytes = try {
        UIColor.whiteColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, 1200.0, 1600.0))
        val attributes = mapOf<Any?, Any>(NSFontAttributeName to UIFont.systemFontOfSize(62.0),
            NSForegroundColorAttributeName to UIColor.blackColor)
        listOf("离线扫描测试", "KingOfScan OFFLINE", "2026 123456").forEachIndexed { index, text ->
            NSString.create(string = text).drawAtPoint(CGPointMake(80.0, 80.0 + index * 160), withAttributes = attributes)
        }
        UIImageJPEGRepresentation(UIGraphicsGetImageFromCurrentImageContext()!!, 0.98)!!.toByteArray()
    } finally { UIGraphicsEndImageContext() }
    FileSystem.SYSTEM.write("$root/input.jpg".toPath()) { write(bytes) }
    CoroutineScope(Dispatchers.Main).launch {
        val result = runCatching {
            val image = AppGraph.codec.decode(bytes, 2400)
            val ocr = createOcrEngine().recognize(image, OcrLanguage.Chinese)
            FileSystem.SYSTEM.write("$root/recognized.txt".toPath()) { writeUtf8(ocr.text) }
            check(ocr.text.contains("离线扫描测试") && ocr.text.contains("123456")) { "OCR: ${ocr.text}" }
            val document = AppGraph.repository.create("离线验证", listOf(EncodedPage(bytes, image.width, image.height,
                FilterType.Original, bytes, PageEdit(filter = FilterType.Original), ocr)))
            val path = AppGraph.repository.exportPdf(document.id, ExportOptions(paper = PdfPaper.A4,
                pageNumbers = true, password = "scan-2026", searchable = true))
            val pdf = PDFDocument(NSURL.fileURLWithPath(path))
            check(pdf.isLocked && !pdf.unlockWithPassword("wrong") && pdf.unlockWithPassword("scan-2026"))
            check(pdf.pageCount == 1uL)
            val text = pdf.string.orEmpty()
            FileSystem.SYSTEM.write("$root/pdf-text.txt".toPath()) { writeUtf8(text) }
            check(text.contains("离线扫描测试") && text.contains("123456")) { "PDF text: $text" }
            val preview = pdf.pageAtIndex(0u)!!.thumbnailOfSize(CGSizeMake(595.0, 842.0), kPDFDisplayBoxMediaBox)
            FileSystem.SYSTEM.write("$root/pdf-page.jpg".toPath()) { write(UIImageJPEGRepresentation(preview, 0.95)!!.toByteArray()) }
            FileSystem.SYSTEM.copy(path.toPath(), "$root/encrypted-searchable.pdf".toPath())
            val barcode = com.kingscan.app.codes.createBarcodeCodec()
            val payload = "离线二维码 KingOfScan 2026\nhttps://example.org/doc?id=123&name=合同"
            val qrBytes = AppGraph.codec.encodeJpeg(barcode.generate(payload), 100)
            FileSystem.SYSTEM.write("$root/qr-generated.jpg".toPath()) { write(qrBytes) }
            check(barcode.decode(AppGraph.codec.decode(qrBytes, 2400).rotate90CW()).single().text == payload)
            check(barcode.decode(com.kingscan.app.imaging.RgbaImage.filled(600, 600, -1)).isEmpty())
            "PASS Chinese offline OCR\nPASS incorrect PDF password rejected\nPASS correct password accepted\nPASS CJK searchable PDF text\nPASS PDF page rendering\nPASS Unicode rotated QR JPEG round trip\nPASS blank QR image rejected"
        }.fold({ it }, { "FAIL ${it.stackTraceToString()}" })
        FileSystem.SYSTEM.write("$root/result.txt".toPath()) { writeUtf8(result) }
        completion(result)
    }
}
