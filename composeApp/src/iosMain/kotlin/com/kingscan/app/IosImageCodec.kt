@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kingscan.app.imaging

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

actual fun createImageCodec(): ImageCodec = IosImageCodec()

actual fun RgbaImage.toComposeImageBitmap(): ImageBitmap {
    val jpeg = IosImageCodec().encodeJpeg(this, 90)
    return Image.makeFromEncoded(jpeg).toComposeImageBitmap()
}

class IosImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray, maxEdge: Int): RgbaImage {
        val image = UIImage.imageWithData(bytes.toNSData()) ?: error("无法读取图片")
        val sourceWidth = image.size.useContents { width }
        val sourceHeight = image.size.useContents { height }
        if (sourceWidth < 1.0 || sourceHeight < 1.0) error("无法读取图片")
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(sourceWidth, sourceHeight))
        val width = maxOf(1, (sourceWidth * scale).toInt())
        val height = maxOf(1, (sourceHeight * scale).toInt())
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        val drawn = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val cgImage = drawn?.CGImage ?: error("无法读取图片")
        return readPixels(cgImage, width, height)
    }

    override fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray {
        val raw = ByteArray(image.width * image.height * 4)
        var offset = 0
        for (pixel in image.pixels) {
            raw[offset++] = pixel.red().toByte()
            raw[offset++] = pixel.green().toByte()
            raw[offset++] = pixel.blue().toByte()
            raw[offset++] = 255.toByte()
        }
        val cgImage = raw.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                pinned.addressOf(0),
                image.width.toULong(),
                image.height.toULong(),
                8u,
                (image.width * 4).toULong(),
                CGColorSpaceCreateDeviceRGB(),
                bitmapInfo(),
            ) ?: error("无法创建画布")
            CGBitmapContextCreateImage(context)
        } ?: error("无法编码图片")
        val uiImage = UIImage.imageWithCGImage(cgImage)
        val jpeg = UIImageJPEGRepresentation(uiImage, quality.coerceIn(50, 100) / 100.0) ?: error("无法编码图片")
        return jpeg.toByteArray()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readPixels(cgImage: kotlinx.cinterop.CPointer<cnames.structs.CGImage>, width: Int, height: Int): RgbaImage {
    val bytesPerRow = width * 4
    val raw = ByteArray(bytesPerRow * height)
    raw.usePinned { pinned ->
        val context = CGBitmapContextCreate(
            pinned.addressOf(0),
            width.toULong(),
            height.toULong(),
            8u,
            bytesPerRow.toULong(),
            CGColorSpaceCreateDeviceRGB(),
            bitmapInfo(),
        ) ?: error("无法创建画布")
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
    }
    val pixels = IntArray(width * height)
    var offset = 0
    for (index in pixels.indices) {
        val red = raw[offset].toInt() and 0xFF
        val green = raw[offset + 1].toInt() and 0xFF
        val blue = raw[offset + 2].toInt() and 0xFF
        val alpha = raw[offset + 3].toInt() and 0xFF
        val (ur, ug, ub) = if (alpha == 0 || alpha == 255) {
            Triple(red, green, blue)
        } else {
            Triple(red * 255 / alpha, green * 255 / alpha, blue * 255 / alpha)
        }
        pixels[index] = argb(255, ur, ug, ub)
        offset += 4
    }
    return RgbaImage(width, height, pixels)
}

private fun bitmapInfo(): UInt {
    return CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count <= 0) return ByteArray(0)
    val output = ByteArray(count)
    val source = bytes ?: return ByteArray(0)
    output.usePinned { pinned ->
        memcpy(pinned.addressOf(0), source, length)
    }
    return output
}

