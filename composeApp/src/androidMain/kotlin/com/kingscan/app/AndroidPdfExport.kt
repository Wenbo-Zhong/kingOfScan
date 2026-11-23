package com.kingscan.app.pdf

import android.content.Context
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.ExportOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.File
import java.util.UUID

object AndroidPdfResources {
    lateinit var context: Context
}

actual fun writeNativePdf(pages: List<PdfExportPage>, options: ExportOptions, path: String, title: String) {
    require(pages.isNotEmpty())
    PDDocument().use { document ->
        document.documentInformation.title = title
        document.documentInformation.creator = "KingOfScan"
        val font = if (options.searchable && pages.any { !it.ocr?.text.isNullOrBlank() })
            AndroidPdfResources.context.assets.open("fonts/NotoSansSC-Regular.ttf").use {
                PDType0Font.load(document, it, true)
            } else null
        pages.forEachIndexed { index, source ->
            val layout = PdfLayout.forPage(source.width, source.height, options)
            val page = PDPage(PDRectangle(layout.width, layout.height))
            document.addPage(page)
            val imageBytes = if (options.jpegQuality >= 95) File(source.imagePath).readBytes() else {
                val image = AppGraph.codec.decode(File(source.imagePath).readBytes(), ScanLimits.MAX_EDGE)
                AppGraph.codec.encodeJpeg(image, options.jpegQuality)
            }
            val image = JPEGFactory.createFromStream(document, imageBytes.inputStream())
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, layout.imageX, layout.height - layout.imageY - layout.imageHeight,
                    layout.imageWidth, layout.imageHeight)
                if (font != null) {
                    source.textLines().forEach { line ->
                        // Keep representable Unicode glyphs; controls and unsupported characters cannot be PDF text.
                        val text = line.text.filter { it >= ' ' && runCatching { font.encode(it.toString()); true }.getOrDefault(false) }
                        if (text.isNotEmpty()) {
                            val size = ((line.bottomRight.y - line.topLeft.y) * layout.imageHeight * 0.85f).coerceAtLeast(1f)
                            val naturalWidth = font.getStringWidth(text) / 1000f * size
                            val targetWidth = ((line.bottomRight.x - line.topLeft.x) * layout.imageWidth).coerceAtLeast(1f)
                            content.beginText()
                            content.setFont(font, size)
                            content.setRenderingMode(RenderingMode.NEITHER)
                            content.setHorizontalScaling((targetWidth / naturalWidth * 100).coerceIn(1f, 1000f))
                            content.newLineAtOffset(layout.imageX + line.topLeft.x * layout.imageWidth,
                                layout.height - layout.imageY - line.bottomRight.y * layout.imageHeight)
                            content.showText(text)
                            content.endText()
                        }
                    }
                }
                if (options.pageNumbers) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 10f)
                    content.setRenderingMode(RenderingMode.FILL)
                    content.setHorizontalScaling(100f)
                    val label = "${index + 1} / ${pages.size}"
                    content.newLineAtOffset((layout.width - PDType1Font.HELVETICA.getStringWidth(label) / 100) / 2, 12f)
                    content.showText(label)
                    content.endText()
                }
            }
        }
        if (options.password.isNotEmpty()) {
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), options.password, AccessPermission())
            policy.encryptionKeyLength = 256
            policy.isPreferAES = true
            document.protect(policy)
        }
        document.save(path)
    }
}
