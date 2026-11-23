package com.kingscan.app.codes

import com.kingscan.app.imaging.RgbaImage

data class BarcodeValue(val text: String, val format: String)

interface BarcodeCodec {
    fun generate(text: String): RgbaImage
    fun decode(image: RgbaImage): List<BarcodeValue>
}

expect fun createBarcodeCodec(): BarcodeCodec

internal fun validateQrText(text: String) {
    require(text.isNotBlank()) { "请输入二维码内容" }
    require(text.encodeToByteArray().size <= 1200) { "二维码内容最多 1200 个 UTF-8 字节" }
}

/** Four-module quiet zone and integral scaling keep every QR module sharply defined. */
internal fun renderQr(modules: RgbaImage): RgbaImage {
    val size = modules.width + 8
    val scale = (900 / size).coerceAtLeast(1)
    val edge = size * scale
    val pixels = IntArray(edge * edge) { -1 }
    for (y in 0 until modules.height) for (x in 0 until modules.width) {
        val pixel = modules.pixels[y * modules.width + x]
        for (dy in 0 until scale) for (dx in 0 until scale)
            pixels[((y + 4) * scale + dy) * edge + (x + 4) * scale + dx] = pixel
    }
    return RgbaImage(edge, edge, pixels)
}
