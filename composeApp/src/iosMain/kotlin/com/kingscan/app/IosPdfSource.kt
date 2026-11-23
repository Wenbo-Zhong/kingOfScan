@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.pdf

import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.toByteArray
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIImageJPEGRepresentation

actual fun openPdfSource(path: String, password: String): PdfSource {
    val document = PDFDocument(NSURL.fileURLWithPath(path))
    if (document.isLocked && !document.unlockWithPassword(password)) throw PdfPasswordRequired()
    require(document.pageCount > 0u) { "PDF 没有可读取的页面" }
    return object : PdfSource {
        override val pageCount: Int get() = document.pageCount.toInt()
        override fun renderPage(index: Int, maxEdge: Int): RgbaImage {
            val page = document.pageAtIndex(index.toULong()) ?: error("PDF 页面不存在")
            val bounds = page.boundsForBox(kPDFDisplayBoxCropBox)
            val dimensions = bounds.useContents { size.width to size.height }
            require(dimensions.first.isFinite() && dimensions.second.isFinite() && dimensions.first > 0 && dimensions.second > 0) { "PDF 页面尺寸无效" }
            val scale = maxEdge / maxOf(dimensions.first, dimensions.second)
            val image = page.thumbnailOfSize(
                CGSizeMake(dimensions.first * scale, dimensions.second * scale), kPDFDisplayBoxCropBox,
            )
            val bytes = UIImageJPEGRepresentation(image, 0.96)?.toByteArray() ?: error("无法渲染 PDF 页面")
            return AppGraph.codec.decode(bytes, maxEdge)
        }
        override fun close() = Unit
    }
}
