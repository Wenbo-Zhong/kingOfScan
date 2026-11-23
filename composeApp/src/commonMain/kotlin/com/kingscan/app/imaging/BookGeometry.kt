package com.kingscan.app.imaging

import com.kingscan.app.model.BookPageEdit
import com.kingscan.app.model.PageEdit
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PageGeometry {
    /** The caller supplies the original image after PageEdit.quarterTurns. */
    fun apply(source: RgbaImage, edit: PageEdit): RgbaImage {
        var page = PerspectiveWarp.warp(source, edit.quad)
        edit.book?.let { page = BookGeometry.flatten(page, it) }
        repeat(edit.outputQuarterTurns.mod(4)) { page = page.rotate90CW() }
        return page
    }
}

object BookGeometry {
    /** Estimate a dark binding only when it stands out from neighboring paper. */
    fun estimateSeam(spread: RgbaImage): Float {
        val image = spread.scaledDown(480)
        val start = (image.width * 0.35f).toInt()
        val end = (image.width * 0.65f).toInt()
        val columns = (start..end).map { x ->
            var sum = 0L; var count = 0
            for (y in image.height / 8 until image.height * 7 / 8 step 3) {
                val p = image.pixel(x, y)
                sum += (p.red() * 77 + p.green() * 150 + p.blue() * 29) shr 8
                count++
            }
            x to sum.toFloat() / count.coerceAtLeast(1)
        }
        val minimum = columns.minByOrNull { it.second } ?: return 0.5f
        val background = columns.map { it.second }.sorted().let { it[it.size * 3 / 4] }
        return if (background - minimum.second > 22f) minimum.first.toFloat() / image.width else 0.5f
    }

    /**
     * A developable page surface specified by its binding and curved top/bottom edges.
     * Horizontal sampling follows arc length, vertical sampling interpolates the two boundaries.
     * The editable geometry makes no assumption that a single homography flattens a bent page.
     */
    fun flatten(spread: RgbaImage, edit: BookPageEdit): RgbaImage {
        val seamTop = edit.seamTop.coerceIn(0.2f, 0.8f)
        val seamBottom = edit.seamBottom.coerceIn(0.2f, 0.8f)
        val fraction = if (edit.rightPage) 1f - (seamTop + seamBottom) / 2 else (seamTop + seamBottom) / 2
        val width = (spread.width * fraction).roundToInt().coerceAtLeast(2)
        val height = spread.height
        val topCurve = edit.topCurve.coerceIn(-0.18f, 0.18f)
        val bottomCurve = edit.bottomCurve.coerceIn(-0.18f, 0.18f)
        // Integrate the boundary curve to remove compression near the book spine.
        val arc = FloatArray(257)
        val bend = (topCurve + bottomCurve) / 2 * height
        for (i in 1..256) {
            val u = (i - 0.5f) / 256
            val derivative = 2 * bend * (if (edit.rightPage) u - 1 else u)
            arc[i] = arc[i - 1] + sqrt(width.toFloat() * width + derivative * derivative) / 256
        }
        val map = FloatArray(width)
        var cursor = 0
        for (x in 0 until width) {
            val target = x.toFloat() / (width - 1) * arc.last()
            while (cursor < 255 && arc[cursor + 1] < target) cursor++
            map[x] = (cursor + (target - arc[cursor]) / (arc[cursor + 1] - arc[cursor]).coerceAtLeast(0.0001f)) / 256
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val v = y.toFloat() / (height - 1)
            val seam = (seamTop * (1 - v) + seamBottom * v) * (spread.width - 1)
            for (x in 0 until width) {
                val u = map[x]
                val bindingWeight = if (edit.rightPage) (1 - u) * (1 - u) else u * u
                val top = topCurve * bindingWeight * (height - 1)
                val bottom = (1 + bottomCurve * bindingWeight) * (height - 1)
                val sx = if (edit.rightPage) seam + u * (spread.width - 1 - seam) else u * seam
                val sy = top * (1 - v) + bottom * v
                pixels[y * width + x] = sampleBilinear(spread, sx, sy)
            }
        }
        return RgbaImage(width, height, pixels)
    }
}

internal fun sampleBilinear(image: RgbaImage, x: Float, y: Float): Int {
    val cx = x.coerceIn(0f, image.width - 1f); val cy = y.coerceIn(0f, image.height - 1f)
    val ix = cx.toInt(); val iy = cy.toInt(); val dx = cx - ix; val dy = cy - iy
    val a = image.pixelClamped(ix, iy); val b = image.pixelClamped(ix + 1, iy)
    val c = image.pixelClamped(ix, iy + 1); val d = image.pixelClamped(ix + 1, iy + 1)
    fun channel(get: (Int) -> Int) = ((get(a) * (1 - dx) + get(b) * dx) * (1 - dy) +
        (get(c) * (1 - dx) + get(d) * dx) * dy).roundToInt().coerceIn(0, 255)
    return argb(255, channel { it.red() }, channel { it.green() }, channel { it.blue() })
}
