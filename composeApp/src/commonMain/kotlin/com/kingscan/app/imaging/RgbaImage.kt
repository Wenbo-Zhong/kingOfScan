package com.kingscan.app.imaging

import androidx.compose.ui.graphics.ImageBitmap

class RgbaImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "图像尺寸无效" }
        require(pixels.size == width * height) { "像素数量与尺寸不一致" }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun pixelClamped(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }

    fun rotate90CW(): RgbaImage {
        val newWidth = height
        val newHeight = width
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                out[ny * newWidth + nx] = pixels[row + x]
            }
        }
        return RgbaImage(newWidth, newHeight, out)
    }

    /** Nearest-neighbor shrink used for detection and filter previews. */
    fun scaledDown(maxEdge: Int): RgbaImage {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return this
        val scale = maxEdge.toFloat() / edge.toFloat()
        val newWidth = maxOf(1, (width * scale).toInt())
        val newHeight = maxOf(1, (height * scale).toInt())
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val sy = (y * height) / newHeight
            val srcRow = sy * width
            val dstRow = y * newWidth
            for (x in 0 until newWidth) {
                val sx = (x * width) / newWidth
                out[dstRow + x] = pixels[srcRow + sx]
            }
        }
        return RgbaImage(newWidth, newHeight, out)
    }

    companion object {
        fun filled(width: Int, height: Int, color: Int): RgbaImage {
            return RgbaImage(width, height, IntArray(width * height) { color })
        }
    }
}

fun argb(a: Int, r: Int, g: Int, b: Int): Int {
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

fun Int.red(): Int = (this shr 16) and 0xFF
fun Int.green(): Int = (this shr 8) and 0xFF
fun Int.blue(): Int = this and 0xFF
fun Int.alpha(): Int = (this ushr 24) and 0xFF

expect fun createImageCodec(): ImageCodec

interface ImageCodec {
    fun decode(bytes: ByteArray, maxEdge: Int): RgbaImage
    fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray
}

expect fun RgbaImage.toComposeImageBitmap(): ImageBitmap
