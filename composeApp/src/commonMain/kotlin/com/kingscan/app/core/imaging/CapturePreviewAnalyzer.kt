package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.PointF
import com.kingscan.app.core.document.Quad
import kotlin.math.exp
import kotlin.math.sqrt

/** One instance per camera session, called serially on the analysis queue. */
class CapturePreviewAnalyzer {
    private val tracker = CapturePreviewTracker()

    fun analyze(image: RgbaImage, timeMillis: Long): CaptureFrame =
        tracker.update(CaptureAnalysis.analyze(image, timeMillis,
            tracker.preferred(timeMillis, image.width, image.height)))
}

/**
 * Display the first candidate immediately, but confirm acquisition/replacement
 * before trusting it. Provisional and held outlines cannot advance auto capture.
 */
class CapturePreviewTracker {
    private var shown: Quad? = null
    private var pending: Quad? = null
    private var pendingCount = 0
    private var pendingSince = 0L
    private var lastFresh = 0L
    private var previousTime: Long? = null
    private var width = 0
    private var height = 0

    fun preferred(timeMillis: Long, width: Int, height: Int): Quad? =
        shown.takeIf { this.width == width && this.height == height &&
            timeMillis - lastFresh in 0..HOLD_MILLIS }

    fun reset() {
        shown = null
        pending = null
        pendingCount = 0
        previousTime = null
        lastFresh = 0
    }

    fun update(frame: CaptureFrame): CaptureFrame {
        val oldTime = previousTime
        if (frame.width != width || frame.height != height ||
            (oldTime != null && (frame.timeMillis <= oldTime || frame.timeMillis - oldTime > 700))
        ) reset()
        width = frame.width
        height = frame.height
        previousTime = frame.timeMillis
        val next = frame.quad
        val old = shown
        if (next != null && old != null && distance(old, next) <= 0.06f) {
            val motion = distance(old, next)
            val elapsed = (frame.timeMillis - (oldTime ?: frame.timeMillis - 120)).coerceIn(1, 250)
            val timeConstant = if (motion < 0.006f) 180.0 else 55.0
            val alpha = (1 - exp(-elapsed / timeConstant)).toFloat()
            shown = if (motion < 0.001f) old else blend(old, next, alpha)
            lastFresh = frame.timeMillis
            pending = null
            pendingCount = 0
            return frame.copy(quad = shown)
        }
        if (next != null) {
            val proposal = pending
            if (proposal == null || distance(proposal, next) > 0.035f) {
                pending = next
                pendingCount = 1
                pendingSince = frame.timeMillis
            } else {
                pending = blend(proposal, next, 0.5f)
                pendingCount++
            }
            if (pendingCount >= 3 && frame.timeMillis - pendingSince >= 180) {
                shown = pending
                lastFresh = frame.timeMillis
                pending = null
                pendingCount = 0
                return frame.copy(quad = shown)
            }
        } else {
            pending = null
            pendingCount = 0
        }
        if (frame.timeMillis - lastFresh > HOLD_MILLIS) shown = null
        // A provisional candidate is not stored in shown: it cannot become the
        // detector's preferred outline or survive a missing detection.
        return frame.copy(quad = shown ?: next, geometryFresh = false)
    }

    private fun distance(a: Quad, b: Quad): Float {
        val first = points(a)
        val second = points(b)
        return first.indices.maxOf { i ->
            val dx = first[i].x - second[i].x
            val dy = first[i].y - second[i].y
            sqrt(dx * dx + dy * dy)
        }
    }

    private fun blend(a: Quad, b: Quad, alpha: Float): Quad {
        fun point(p: PointF, q: PointF) = PointF(p.x + (q.x - p.x) * alpha, p.y + (q.y - p.y) * alpha)
        return Quad(point(a.topLeft, b.topLeft), point(a.topRight, b.topRight),
            point(a.bottomRight, b.bottomRight), point(a.bottomLeft, b.bottomLeft))
    }

    private fun points(quad: Quad) = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

    private companion object {
        const val HOLD_MILLIS = 360L
    }
}
