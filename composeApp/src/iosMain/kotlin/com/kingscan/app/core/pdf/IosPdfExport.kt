@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kingscan.app.core.pdf

import cnames.structs.__CFAttributedString
import com.kingscan.app.core.document.ExportOptions
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.*
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineDraw
import platform.CoreText.CTLineGetTypographicBounds
import platform.Foundation.NSAttributedString
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.PDFKit.*
import platform.UIKit.*

actual fun writeNativePdf(pages: List<PdfExportPage>, options: ExportOptions, path: String, title: String) {
    require(pages.isNotEmpty())
    check(UIGraphicsBeginPDFContextToFile(path, CGRectMake(0.0, 0.0, 1.0, 1.0), mapOf("Title" to title, "Creator" to "KingOfScan")))
    try {
        pages.forEachIndexed { index, source ->
            val layout = PdfLayout.forPage(source.width, source.height, options)
            UIGraphicsBeginPDFPageWithInfo(CGRectMake(0.0, 0.0, layout.width.toDouble(), layout.height.toDouble()), null)
            val context = UIGraphicsGetCurrentContext() ?: error("无法创建 PDF 页面")
            val data = NSData.dataWithContentsOfFile(source.imagePath) ?: error("页面图片不存在")
            val image = UIImage.imageWithData(data) ?: error("页面图片无法读取")
            val jpeg = UIImageJPEGRepresentation(image, options.jpegQuality.coerceIn(50, 100) / 100.0)
            val compressed = jpeg?.let { UIImage.imageWithData(it) } ?: error("无法压缩页面")
            compressed.drawInRect(CGRectMake(layout.imageX.toDouble(), layout.imageY.toDouble(),
                layout.imageWidth.toDouble(), layout.imageHeight.toDouble()))
            if (options.searchable) {
                source.textLines().forEach { line ->
                    val size = ((line.bottomRight.y - line.topLeft.y) * layout.imageHeight * 0.85f).coerceAtLeast(1f)
                    val attributed = NSAttributedString.create(string = line.text,
                        attributes = mapOf(NSFontAttributeName to UIFont.systemFontOfSize(size.toDouble())))
                    val textLine = CTLineCreateWithAttributedString(interpretCPointer<__CFAttributedString>(attributed.objcPtr()))
                        ?: error("无法生成文字层")
                    try {
                        val width = CTLineGetTypographicBounds(textLine, null, null, null).coerceAtLeast(1.0)
                        val target = ((line.bottomRight.x - line.topLeft.x) * layout.imageWidth).coerceAtLeast(1f)
                        CGContextSaveGState(context)
                        CGContextTranslateCTM(context, (layout.imageX + line.topLeft.x * layout.imageWidth).toDouble(),
                            (layout.imageY + line.bottomRight.y * layout.imageHeight).toDouble())
                        CGContextScaleCTM(context, target / width, -1.0)
                        CGContextSetTextMatrix(context, CGAffineTransformMake(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
                        CGContextSetTextPosition(context, 0.0, 0.0)
                        CGContextSetTextDrawingMode(context, CGTextDrawingMode.kCGTextInvisible)
                        CTLineDraw(textLine, context)
                        CGContextRestoreGState(context)
                    } finally { CFRelease(textLine) }
                }
            }
            if (options.pageNumbers) {
                val number = NSString.create(string = "${index + 1} / ${pages.size}")
                number.drawAtPoint(CGPointMake(layout.width / 2.0 - 14, layout.height - 22.0),
                    withAttributes = mapOf(NSFontAttributeName to UIFont.systemFontOfSize(10.0),
                        NSForegroundColorAttributeName to UIColor.blackColor))
            }
        }
    } finally { UIGraphicsEndPDFContext() }
    if (options.password.isNotEmpty()) {
        val document = PDFDocument(NSURL.fileURLWithPath(path))
        val encryptedPath = "$path.encrypted"
        try {
            check(document.writeToURL(NSURL.fileURLWithPath(encryptedPath), withOptions = mapOf(
                PDFDocumentUserPasswordOption to options.password,
                PDFDocumentOwnerPasswordOption to platform.Foundation.NSUUID().UUIDString,
            ))) { "无法加密 PDF" }
            // The repository moves this complete temporary file into place atomically.
            val data = NSData.dataWithContentsOfFile(encryptedPath) ?: error("无法读取加密 PDF")
            check(data.writeToFile(path, atomically = true)) { "无法保存加密 PDF" }
        } finally { NSFileManager.defaultManager.removeItemAtPath(encryptedPath, null) }
    }
}
