package com.kingscan.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual fun createImageCodec(): ImageCodec = AndroidImageCodec()

actual fun RgbaImage.toComposeImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

class AndroidImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray, maxEdge: Int): RgbaImage {
        val upright = normalizeExif(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(upright, 0, upright.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法读取图片")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(upright, 0, upright.size, options) ?: error("无法读取图片")
        val bitmap = scaleDown(decoded, maxEdge)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = RgbaImage(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        return image
    }

    override fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(image.pixels, 0, image.width, 0, 0, image.width, image.height)
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), stream)
        bitmap.recycle()
        if (!ok) error("无法编码图片")
        return stream.toByteArray()
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var edge = maxOf(width, height)
        while (edge / 2 >= maxEdge) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    private fun normalizeExif(bytes: ByteArray): ByteArray {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bytes
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, output)
        if (rotated != bitmap) rotated.recycle()
        bitmap.recycle()
        return output.toByteArray()
    }
}
