package com.kingscan.app.core.pdf

import com.kingscan.app.core.imaging.RgbaImage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

actual fun openPdfSource(path: String, password: String): PdfSource {
    val document = try { PDDocument.load(File(path), password) }
    catch (_: InvalidPasswordException) { throw PdfPasswordRequired() }
    return object : PdfSource {
        override val pageCount: Int get() = document.numberOfPages
        private val renderer = PDFRenderer(document)
        override fun renderPage(index: Int, maxEdge: Int): RgbaImage {
            val box = document.getPage(index).cropBox
            require(box.width.isFinite() && box.height.isFinite() && box.width > 0 && box.height > 0) { "PDF 页面尺寸无效" }
            val scale = maxEdge.toFloat() / maxOf(box.width, box.height)
            val bitmap = renderer.renderImage(index, scale)
            return try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                RgbaImage(bitmap.width, bitmap.height, pixels)
            } finally { bitmap.recycle() }
        }
        override fun close() = document.close()
    }
}
