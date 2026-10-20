package com.kingscan.app.pdf

import kotlin.math.round
import okio.Buffer

data class PdfImage(
    val jpeg: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    val widthPt: Float get() = pixelWidth * 72f / 150f
    val heightPt: Float get() = pixelHeight * 72f / 150f
}

object PdfWriter {
    fun write(pages: List<PdfImage>): ByteArray {
        require(pages.isNotEmpty()) { "至少需要一页才能生成 PDF" }
        val buffer = Buffer()
        fun ascii(text: String) = buffer.writeUtf8(text)

        ascii("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
        val offsets = ArrayList<Long>(2 + pages.size * 3)

        fun beginObject(id: Int) {
            check(id == offsets.size + 1)
            offsets += buffer.size
            ascii("$id 0 obj\n")
        }

        val kids = buildString {
            pages.indices.forEach { index ->
                if (index > 0) append(' ')
                append(3 + index * 3)
                append(" 0 R")
            }
        }
        beginObject(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObject(2)
        ascii("<< /Type /Pages /Count ${pages.size} /Kids [$kids] >>\nendobj\n")

        pages.forEachIndexed { index, page ->
            val pageId = 3 + index * 3
            val contentId = pageId + 1
            val imageId = pageId + 2
            val content = "q\n${fmt(page.widthPt)} 0 0 ${fmt(page.heightPt)} 0 0 cm\n/Im0 Do\nQ\n"
            val contentBytes = content.encodeToByteArray()

            beginObject(pageId)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(page.widthPt)} ${fmt(page.heightPt)}] " +
                    "/Contents $contentId 0 R /Resources << /XObject << /Im0 $imageId 0 R >> >> >>\nendobj\n",
            )

            beginObject(contentId)
            ascii("<< /Length ${contentBytes.size} >>\nstream\n")
            buffer.write(contentBytes)
            ascii("\nendstream\nendobj\n")

            beginObject(imageId)
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.pixelWidth} /Height ${page.pixelHeight} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
            )
            buffer.write(page.jpeg)
            ascii("\nendstream\nendobj\n")
        }

        val xref = buffer.size
        val size = offsets.size + 1
        ascii("xref\n0 $size\n")
        ascii("0000000000 65535 f \n")
        offsets.forEach { offset ->
            ascii(offset.toString().padStart(10, '0'))
            ascii(" 00000 n \n")
        }
        ascii("trailer\n<< /Size $size /Root 1 0 R >>\n")
        ascii("startxref\n$xref\n%%EOF\n")
        return buffer.readByteArray()
    }

    private fun fmt(value: Float): String {
        val rounded = round(value * 100f) / 100.0
        val text = rounded.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}
