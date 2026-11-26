package com.kingscan.app.core.pdf

import com.kingscan.app.core.document.ExportOptions
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.document.PdfPaper
import com.kingscan.app.core.document.OcrLine
import com.kingscan.app.core.document.PointF

data class PdfExportPage(val imagePath: String, val width: Int, val height: Int, val ocr: OcrResult?)

internal fun PdfExportPage.textLines(): List<OcrLine> {
    ocr?.lines?.takeIf { it.isNotEmpty() }?.let { return it }
    val text = ocr?.text.orEmpty().lines().filter { it.isNotBlank() }
    if (text.isEmpty()) return emptyList()
    val lineHeight = minOf(0.04f, 0.96f / text.size)
    return text.mapIndexed { row, line ->
        OcrLine(line, PointF(0.02f, 0.02f + row * lineHeight), PointF(0.98f, 0.02f + (row + 1) * lineHeight))
    }
}

/** Image and text share this top-left-origin layout on both platforms. All values are PDF points. */
data class PdfLayout(
    val width: Float, val height: Float,
    val imageX: Float, val imageY: Float, val imageWidth: Float, val imageHeight: Float,
) {
    companion object {
        fun forPage(width: Int, height: Int, options: ExportOptions): PdfLayout {
            require(width > 0 && height > 0) { "页面尺寸无效" }
            val paper = when (options.paper) {
                PdfPaper.FitImage -> maxOf(72f, width * 72f / 150f) to maxOf(72f, height * 72f / 150f)
                PdfPaper.A4 -> 595.28f to 841.89f
                PdfPaper.Letter -> 612f to 792f
            }
            val w = if (options.landscape) maxOf(paper.first, paper.second) else
                if (options.paper == PdfPaper.FitImage) paper.first else minOf(paper.first, paper.second)
            val h = if (options.landscape) minOf(paper.first, paper.second) else
                if (options.paper == PdfPaper.FitImage) paper.second else maxOf(paper.first, paper.second)
            val margin = options.marginPoints.coerceIn(0f, minOf(w, h) / 4)
            val footer = if (options.pageNumbers) 18f else 0f
            val scale = minOf((w - 2 * margin) / width, (h - 2 * margin - footer) / height)
            return PdfLayout(w, h, (w - width * scale) / 2, margin + (h - 2 * margin - footer - height * scale) / 2,
                width * scale, height * scale)
        }
    }
}

expect fun writeNativePdf(pages: List<PdfExportPage>, options: ExportOptions, path: String, title: String)
