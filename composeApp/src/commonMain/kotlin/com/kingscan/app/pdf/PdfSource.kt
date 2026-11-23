package com.kingscan.app.pdf

import com.kingscan.app.imaging.RgbaImage

class PdfPasswordRequired : Exception("PDF 已加密，请输入密码")

interface PdfSource {
    val pageCount: Int
    fun renderPage(index: Int, maxEdge: Int): RgbaImage
    fun close()
}

expect fun openPdfSource(path: String, password: String = ""): PdfSource
