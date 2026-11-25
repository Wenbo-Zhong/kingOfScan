package com.kingscan.app.imaging

import com.kingscan.app.model.Quad
import kotlin.math.abs
import kotlin.math.sqrt

/** Image dimensions are already rotated to match the portrait preview. */
data class CaptureFrame(
    val quad: Quad?,
    val width: Int,
    val height: Int,
    val timeMillis: Long,
    val luminance: Float,
    val sharpness: Float,
    val fingerprint: IntArray,
    val geometryFresh: Boolean = true,
)

object CaptureAnalysis {
    fun analyze(image: RgbaImage, timeMillis: Long, preferred: Quad? = null): CaptureFrame {
        if (image.width < 4 || image.height < 4) return CaptureFrame(null, image.width, image.height,
            timeMillis, 0f, 0f, IntArray(0))
        val quad = DocumentDetector.detectOrNull(image, preferred)
        val region = quad ?: Quad.inset(0.15f)
        val points = listOf(region.topLeft, region.topRight, region.bottomRight, region.bottomLeft)
        val left = (points.minOf { it.x } * image.width).toInt().coerceIn(1, image.width - 2)
        val right = (points.maxOf { it.x } * image.width).toInt().coerceIn(left + 1, image.width - 1)
        val top = (points.minOf { it.y } * image.height).toInt().coerceIn(1, image.height - 2)
        val bottom = (points.maxOf { it.y } * image.height).toInt().coerceIn(top + 1, image.height - 1)
        fun gray(x: Int, y: Int): Int {
            val pixel = image.pixel(x, y)
            return (pixel.red() * 77 + pixel.green() * 150 + pixel.blue() * 29) shr 8
        }
        var count = 0
        var light = 0.0
        var laplacian = 0.0
        var squared = 0.0
        for (y in top until bottom step 2) for (x in left until right step 2) {
            val value = gray(x, y)
            val lap = 4 * value - gray(x - 1, y) - gray(x + 1, y) - gray(x, y - 1) - gray(x, y + 1)
            count++
            light += value
            laplacian += lap
            squared += lap.toDouble() * lap
        }
        val n = count.coerceAtLeast(1)
        // Rectify the tiny fingerprint so camera jitter is less likely to look like a page turn.
        val page = if (quad == null) image else PerspectiveWarp.warp(image.scaledDown(160), quad)
        val signature = IntArray(24 * 32) { index ->
            val x = ((index % 24 + 0.5f) * page.width / 24).toInt().coerceAtMost(page.width - 1)
            val y = ((index / 24 + 0.5f) * page.height / 32).toInt().coerceAtMost(page.height - 1)
            val pixel = page.pixel(x, y)
            (pixel.red() * 77 + pixel.green() * 150 + pixel.blue() * 29) shr 8
        }
        return CaptureFrame(quad, image.width, image.height, timeMillis, (light / n).toFloat(),
            (squared / n - laplacian / n * (laplacian / n)).toFloat().coerceAtLeast(0f), signature)
    }
}

data class CaptureReadiness(val progress: Float, val hint: String, val capture: Boolean = false)

/** Automatic capture requires quality, stable geometry and a new page since the last shutter. */
class AutoCaptureController {
    private var previous: CaptureFrame? = null
    private var stableSince: Long? = null
    private var captured: IntArray? = null
    private var missingSince: Long? = null
    private var newPageFrames = 0

    fun reset() {
        previous = null; stableSince = null; captured = null; missingSince = null; newPageFrames = 0
    }

    fun markCaptured(frame: CaptureFrame?) {
        captured = frame?.fingerprint?.copyOf()
        stableSince = null
        newPageFrames = 0
    }

    fun update(frame: CaptureFrame, enabled: Boolean, busy: Boolean): CaptureReadiness {
        val old = previous
        previous = frame
        val quad = frame.quad
        if (quad == null) {
            stableSince = null
            val since = missingSince ?: frame.timeMillis.also { missingSince = it }
            if (frame.timeMillis - since >= 650) captured = null
            return CaptureReadiness(0f, "请把整页放入取景框")
        }
        missingSince = null
        if (!frame.geometryFresh) {
            stableSince = null
            return CaptureReadiness(0f, "请保持手机和纸张稳定")
        }
        if (frame.luminance < 55f) {
            stableSince = null
            return CaptureReadiness(0f, "光线偏暗，可打开补光灯")
        }
        if (frame.sharpness < 32f) {
            stableSince = null
            return CaptureReadiness(0f, "请靠近一些，等待对焦")
        }
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        if (points.any { it.x < 0.018f || it.x > 0.982f || it.y < 0.018f || it.y > 0.982f }) {
            stableSince = null
            return CaptureReadiness(0f, "请留出纸张边缘")
        }
        captured?.let { signature ->
            val change = difference(signature, frame.fingerprint)
            newPageFrames = if (change > 13f) newPageFrames + 1 else 0
            if (newPageFrames >= 3) { captured = null; stableSince = null }
            else return CaptureReadiness(0f, "已拍摄，请翻页或更换文档")
        }
        if (!enabled || busy) {
            stableSince = null
            return CaptureReadiness(0f, if (busy) "正在保存" else "对齐后点击拍摄")
        }
        val oldQuad = old?.quad
        val stable = oldQuad != null && frame.timeMillis - old.timeMillis in 1..450 &&
            quadDistance(quad, oldQuad) < 0.012f && difference(frame.fingerprint, old.fingerprint) < 8f
        if (!stable) {
            stableSince = null
            return CaptureReadiness(0f, "请保持手机和纸张稳定")
        }
        val start = stableSince ?: frame.timeMillis.also { stableSince = it }
        val progress = ((frame.timeMillis - start) / 900f).coerceIn(0f, 1f)
        if (progress >= 1f) {
            markCaptured(frame)
            return CaptureReadiness(1f, "正在拍摄", capture = true)
        }
        return CaptureReadiness(progress, "保持稳定，即将拍摄")
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 255f
        // Exposure drift alone is not a page turn.
        val offset = (a.sum().toFloat() - b.sum()) / a.size
        return a.indices.sumOf { abs(a[it] - b[it] - offset).toDouble() }.toFloat() / a.size
    }

    private fun quadDistance(a: Quad, b: Quad): Float {
        val first = listOf(a.topLeft, a.topRight, a.bottomRight, a.bottomLeft)
        val second = listOf(b.topLeft, b.topRight, b.bottomRight, b.bottomLeft)
        return first.indices.maxOf { i ->
            val dx = first[i].x - second[i].x; val dy = first[i].y - second[i].y
            sqrt(dx * dx + dy * dy)
        }
    }
}
