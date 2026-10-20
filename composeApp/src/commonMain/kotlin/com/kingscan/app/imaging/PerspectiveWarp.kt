package com.kingscan.app.imaging

import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class DPoint(val x: Double, val y: Double)

object PerspectiveWarp {
    fun warp(source: RgbaImage, quad: Quad): RgbaImage {
        if (quad.isAlmostRect()) return source
        val topLeft = toPixel(quad.topLeft, source)
        val topRight = toPixel(quad.topRight, source)
        val bottomRight = toPixel(quad.bottomRight, source)
        val bottomLeft = toPixel(quad.bottomLeft, source)
        val width = maxOf(
            hypot((topRight.x - topLeft.x).toDouble(), (topRight.y - topLeft.y).toDouble()),
            hypot((bottomRight.x - bottomLeft.x).toDouble(), (bottomRight.y - bottomLeft.y).toDouble()),
        ).roundToInt().coerceIn(2, ScanLimits.MAX_EDGE)
        val height = maxOf(
            hypot((bottomLeft.x - topLeft.x).toDouble(), (bottomLeft.y - topLeft.y).toDouble()),
            hypot((bottomRight.x - topRight.x).toDouble(), (bottomRight.y - topRight.y).toDouble()),
        ).roundToInt().coerceIn(2, ScanLimits.MAX_EDGE)
        val homography = homography(
            dest = listOf(
                DPoint(0.0, 0.0),
                DPoint(width.toDouble(), 0.0),
                DPoint(width.toDouble(), height.toDouble()),
                DPoint(0.0, height.toDouble()),
            ),
            src = listOf(topLeft, topRight, bottomRight, bottomLeft),
        ) ?: return cropBounds(source, topLeft, topRight, bottomRight, bottomLeft)
        return sample(source, width, height, homography)
    }

    private fun toPixel(point: PointF, image: RgbaImage): DPoint {
        return DPoint(
            x = point.x.coerceIn(0f, 1f) * (image.width - 1).toDouble(),
            y = point.y.coerceIn(0f, 1f) * (image.height - 1).toDouble(),
        )
    }

    /**
     * Maps destination pixels back to the source. The returned 8 coefficients are
     * h0..h7 of a homography whose last element is fixed at 1.
     */
    private fun homography(
        dest: List<DPoint>,
        src: List<DPoint>,
    ): DoubleArray? {
        val matrix = Array(8) { DoubleArray(8) }
        val values = DoubleArray(8)
        for (i in 0 until 4) {
            val (x, y) = dest[i]
            val (u, v) = src[i]
            val row = i * 2
            matrix[row][0] = x
            matrix[row][1] = y
            matrix[row][2] = 1.0
            matrix[row][6] = -u * x
            matrix[row][7] = -u * y
            values[row] = u
            matrix[row + 1][3] = x
            matrix[row + 1][4] = y
            matrix[row + 1][5] = 1.0
            matrix[row + 1][6] = -v * x
            matrix[row + 1][7] = -v * y
            values[row + 1] = v
        }
        return solve(matrix, values)
    }

    private fun sample(source: RgbaImage, width: Int, height: Int, h: DoubleArray): RgbaImage {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val yd = y.toDouble()
            for (x in 0 until width) {
                val xd = x.toDouble()
                val denominator = h[6] * xd + h[7] * yd + 1.0
                if (kotlin.math.abs(denominator) < 1e-8) {
                    out[row + x] = source.pixelClamped(0, 0)
                    continue
                }
                val sx = (h[0] * xd + h[1] * yd + h[2]) / denominator
                val sy = (h[3] * xd + h[4] * yd + h[5]) / denominator
                out[row + x] = bilinear(source, sx, sy)
            }
        }
        return RgbaImage(width, height, out)
    }

    private fun bilinear(source: RgbaImage, x: Double, y: Double): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = (x - x0).toFloat().coerceIn(0f, 1f)
        val ty = (y - y0).toFloat().coerceIn(0f, 1f)
        val c00 = source.pixelClamped(x0, y0)
        val c10 = source.pixelClamped(x0 + 1, y0)
        val c01 = source.pixelClamped(x0, y0 + 1)
        val c11 = source.pixelClamped(x0 + 1, y0 + 1)
        fun channel(pick: (Int) -> Int): Int {
            val top = pick(c00) * (1f - tx) + pick(c10) * tx
            val bottom = pick(c01) * (1f - tx) + pick(c11) * tx
            return (top * (1f - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
        }
        return argb(255, channel { it.red() }, channel { it.green() }, channel { it.blue() })
    }

    private fun cropBounds(
        source: RgbaImage,
        vararg points: DPoint,
    ): RgbaImage {
        val minX = points.minOf { it.x }.toInt().coerceIn(0, source.width - 1)
        val maxX = points.maxOf { it.x }.toInt().coerceIn(minX, source.width - 1)
        val minY = points.minOf { it.y }.toInt().coerceIn(0, source.height - 1)
        val maxY = points.maxOf { it.y }.toInt().coerceIn(minY, source.height - 1)
        val width = (maxX - minX + 1).coerceAtLeast(1)
        val height = (maxY - minY + 1).coerceAtLeast(1)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = (minY + y) * source.width
            val dstRow = y * width
            for (x in 0 until width) {
                out[dstRow + x] = source.pixels[srcRow + minX + x]
            }
        }
        return RgbaImage(width, height, out)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 8
        val rows = Array(n) { index ->
            DoubleArray(n + 1) { column -> if (column < n) a[index][column] else b[index] }
        }
        for (column in 0 until n) {
            var pivot = column
            var best = kotlin.math.abs(rows[column][column])
            for (row in column + 1 until n) {
                val value = kotlin.math.abs(rows[row][column])
                if (value > best) {
                    best = value
                    pivot = row
                }
            }
            if (best < 1e-8) return null
            if (pivot != column) {
                val swap = rows[column]
                rows[column] = rows[pivot]
                rows[pivot] = swap
            }
            val divisor = rows[column][column]
            for (columnIndex in column..n) rows[column][columnIndex] /= divisor
            for (row in 0 until n) {
                if (row == column) continue
                val factor = rows[row][column]
                if (factor == 0.0) continue
                for (columnIndex in column..n) {
                    rows[row][columnIndex] -= factor * rows[column][columnIndex]
                }
            }
        }
        return DoubleArray(n) { rows[it][n] }
    }
}
