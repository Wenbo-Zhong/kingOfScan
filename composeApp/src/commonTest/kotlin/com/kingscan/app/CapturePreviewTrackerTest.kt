package com.kingscan.app

import com.kingscan.app.imaging.AutoCaptureController
import com.kingscan.app.imaging.CaptureFrame
import com.kingscan.app.imaging.CapturePreviewTracker
import com.kingscan.app.model.Quad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturePreviewTrackerTest {
    private fun frame(time: Long, quad: Quad? = Quad.inset(0.2f), width: Int = 360, height: Int = 480) =
        CaptureFrame(quad, width, height, time, 180f, 100f, IntArray(768) { 180 })

    private fun acquire(tracker: CapturePreviewTracker): Quad {
        assertNull(tracker.update(frame(0)).quad)
        assertNull(tracker.update(frame(120)).quad)
        return assertNotNull(tracker.update(frame(240)).quad)
    }

    @Test fun shortMissesAndIsolatedWrongRegionsDoNotFlash() {
        val tracker = CapturePreviewTracker()
        val page = acquire(tracker)
        val miss = tracker.update(frame(360, null))
        assertTrue(miss.quad == page)
        assertFalse(miss.geometryFresh)
        val wrong = tracker.update(frame(480, Quad.inset(0.05f)))
        assertTrue(wrong.quad == page)
        assertFalse(wrong.geometryFresh)
        assertTrue(tracker.update(frame(600)).quad == page)
        assertTrue(tracker.update(frame(600, width = 480, height = 360)).quad == null)
    }

    @Test fun missingPageExpiresAndRequiresNewConfirmation() {
        val tracker = CapturePreviewTracker()
        acquire(tracker)
        assertNotNull(tracker.update(frame(480, null)).quad)
        assertNull(tracker.update(frame(720, null)).quad)
        assertNull(tracker.preferred(720, 360, 480))
        assertNull(tracker.update(frame(840)).quad)
        assertNull(tracker.update(frame(960)).quad)
        assertNotNull(tracker.update(frame(1080)).quad)
    }

    @Test fun steadyMotionFollowsWithoutFreezingAndSmallJitterIsReduced() {
        val tracker = CapturePreviewTracker()
        val page = acquire(tracker)
        val jitter = tracker.update(frame(360, page.translate(0.004f, 0f)))
        val jitterQuad = assertNotNull(jitter.quad)
        assertTrue(jitterQuad.topLeft.x - page.topLeft.x in 0f..0.003f)
        var latest = jitterQuad
        for (i in 1..8) {
            val target = page.translate(i * 0.018f, i * 0.006f)
            latest = assertNotNull(tracker.update(frame(480 + i * 120L, target)).quad)
            assertTrue(abs(latest.topLeft.x - target.topLeft.x) < 0.025f)
        }
        assertTrue(latest.topLeft.x > page.topLeft.x + 0.12f)
    }

    @Test fun consistentNewPositionReplacesOldOutline() {
        val tracker = CapturePreviewTracker()
        acquire(tracker)
        val replacement = Quad.inset(0.06f)
        assertFalse(tracker.update(frame(360, replacement)).geometryFresh)
        assertFalse(tracker.update(frame(480, replacement)).geometryFresh)
        val result = tracker.update(frame(600, replacement))
        assertTrue(result.geometryFresh)
        assertTrue(result.quad == replacement)
    }

    @Test fun heldOutlineCannotCompleteAutomaticCapture() {
        val tracker = CapturePreviewTracker()
        val controller = AutoCaptureController()
        acquire(tracker)
        for (time in 360L..1440L step 120) {
            val result = tracker.update(frame(time, if (time % 240 == 0L) null else Quad.inset(0.2f)))
            assertFalse(controller.update(result, enabled = true, busy = false).capture)
        }
    }
}
