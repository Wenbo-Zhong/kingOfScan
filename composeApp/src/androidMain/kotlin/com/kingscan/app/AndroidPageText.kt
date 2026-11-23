package com.kingscan.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.kingscan.app.model.PageMark

actual fun drawPageText(source: RgbaImage, marks: List<PageMark>): RgbaImage {
    if (marks.isEmpty()) return source
    val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.setPixels(source.pixels, 0, source.width, 0, 0, source.width, source.height)
        val canvas = Canvas(bitmap)
        marks.forEach { mark ->
            val point = mark.points.firstOrNull() ?: return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mark.color.toInt()
                alpha = (255 * mark.opacity).toInt().coerceIn(0, 255)
                textSize = mark.strokeWidth * minOf(source.width, source.height)
                typeface = Typeface.DEFAULT
            }
            canvas.save()
            canvas.rotate(mark.quarterTurns.mod(4) * 90f, point.x * source.width, point.y * source.height)
            mark.text.lines().forEachIndexed { row, line ->
                canvas.drawText(line, point.x * source.width, point.y * source.height - paint.ascent() + row * paint.fontSpacing, paint)
            }
            canvas.restore()
        }
        val pixels = IntArray(source.pixels.size)
        bitmap.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        RgbaImage(source.width, source.height, pixels)
    } finally { bitmap.recycle() }
}
